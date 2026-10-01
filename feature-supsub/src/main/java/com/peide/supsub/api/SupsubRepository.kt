package com.peide.supsub.api

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import retrofit2.HttpException
import java.io.IOException

/**
 * 一条内容 + 它的**服务端原始 JSON**。
 *
 * 保留 raw 的原因：`Article`/`FocusContent` 开了 ignoreUnknownKeys，
 * 只留解析后的模型会让服务端新增字段被静默丢弃且无从追溯。
 */
data class RawArticle(val article: Article, val raw: JsonObject)

data class RawFocusContent(val content: FocusContent, val raw: JsonObject)

/**
 * 对上层（ViewModel）暴露的唯一入口。
 * 负责：线程切换、异常归一化、分页拉全。
 */
class SupsubRepository(context: Context) {

    private val client = SupsubClient.get(context)

    val tokenStore: TokenStore get() = client.tokenStore
    val deviceFlow: DeviceFlowAuth by lazy { DeviceFlowAuth(client.authApi, client.tokenStore) }

    val isLoggedIn: Boolean get() = client.tokenStore.isLoggedIn

    suspend fun getUserInfo(): UserInfo = call { client.api.getUserInfo() }

    suspend fun listSubscriptions(sourceType: String? = null): List<Subscription> =
        call { client.api.listSubscriptions(sourceType) }

    /**
     * 拉取某个订阅源的文章。
     *
     * @param unreadOnly true 则只拉未读
     * @param maxPages 安全上限，防止服务端异常时无限翻页
     * @param onItem 每拿到一篇就回调（返回 false 可中断后续翻页，用于单源上限）
     */
    suspend fun fetchAllContents(
        sourceType: String,
        sourceId: Long,
        unreadOnly: Boolean = false,
        pageSize: Int = 100,
        maxPages: Int = 20,
        onItem: suspend (RawArticle) -> Boolean = { true },
    ): List<RawArticle> {
        val size = pageSize.coerceIn(1, 100) // 服务端上限 100
        val type = if (unreadOnly) "unread" else "all"
        val all = mutableListOf<RawArticle>()
        val seen = mutableSetOf<String>()

        var page = 1
        while (page <= maxPages) {
            currentCoroutineContext().ensureActive()
            // 仅网络调用在 IO；onItem 在调用方上下文（主线程）执行，保证 Flow emit 上下文一致
            val batch = callWithRetry { client.api.getContents(sourceType, sourceId, type, page, size) }
            if (batch.isEmpty()) break
            // 服务端分页异常时可能重复返回同一页，用 contentId 去重并据此判停
            val fresh = batch.mapNotNull { it.toRawArticle() }
                .filter { it.article.contentId.isNotBlank() && seen.add(it.article.contentId) }
            var stop = false
            for (a in fresh) {
                all += a
                // 回调返回 false（如已达单源上限）即中断翻页
                if (!onItem(a)) { stop = true; break }
            }
            if (stop) break
            if (fresh.isEmpty() || batch.size < size) break
            page++
        }
        return all
    }

    /** 原始 JSON → Article + raw；单条解析失败不影响整页 */
    private fun JsonObject.toRawArticle(): RawArticle? = runCatching {
        RawArticle(SupsubClient.json.decodeFromJsonElement(Article.serializer(), this), this)
    }.getOrNull()

    private fun JsonObject.toRawFocusContent(): RawFocusContent? = runCatching {
        RawFocusContent(SupsubClient.json.decodeFromJsonElement(FocusContent.serializer(), this), this)
    }.getOrNull()

    /** 只取 contentId，用于计数场景，避免整条反序列化 */
    private fun JsonObject.contentIdOrNull(): String? =
        runCatching { this["contentId"]?.jsonPrimitive?.content }.getOrNull()

    /** 只取 publishedAt（Unix 秒），用于分页顺序的验证日志 */
    private fun JsonObject.publishedAtOrNull(): Long? =
        runCatching { this["publishedAt"]?.jsonPrimitive?.longOrNull }.getOrNull()

    /**
     * 该条是否「服务端未读」（isRead=false）。
     * 字段缺失时保守视为未读（不消费/不遗漏，统计场景宁多勿漏）。
     */
    private fun JsonObject.isUnreadFlag(): Boolean =
        runCatching { this["isRead"]?.jsonPrimitive?.booleanOrNull == false }.getOrDefault(true)

    /** 整源标记已读 —— 不可逆，调用方必须先二次确认 */
    suspend fun markSourceAsRead(sourceType: String, sourceId: Long) =
        call { client.api.markAsRead(MarkAsReadRequest(sourceType, sourceId)) }

    /** 关注点整点标记已读（不传 contentId 即整点，不可逆）—— 与 [markSourceAsRead] 对应 */
    suspend fun markFocusAsRead(focusId: Long, request: FocusMarkAsReadRequest = FocusMarkAsReadRequest()) =
        call { client.api.markFocusAsRead(focusId, request) }

    // ─── 关注点（focus）────────────────────────────────────────

    /** 关注点列表 */
    suspend fun listFocuses(): List<Focus> = call { client.api.listFocuses() }

    /**
     * 将某个关注点下的单篇内容在云端标记为已读（不传 contentId 则整点已读）。
     * 单篇标记按 focus 下的 contentId 作用域生效，不会影响同名订阅源文章（其 contentId 不同）。
     */
    suspend fun markFocusContentAsRead(focusId: Long, contentId: String, sourceType: String) =
        call { client.api.markFocusAsRead(focusId, FocusMarkAsReadRequest(contentId = contentId, sourceType = sourceType)) }

    /**
     * 拉取单个关注点的聚合内容。分页与去重逻辑同 fetchAllContents。
     *
     * 增量早停（P1 减负）：服务端按收录时间倒序返回（新→旧），本地已归档的文章集中在尾部。
     * 传入 [existingContentIds] 后，连续 [earlyStopHitPages] 页的 contentId **全部命中本地**即停止翻页——
     * 旧文章都拉完了再翻全是浪费请求。首拉（本地为空）时几乎不可能连续全命中，自然全量翻。
     *
     * @param onItem 每拿到一篇就回调（返回 false 可中断后续翻页）
     * @param collectList false 时不在内存中收集返回列表（拉全量场景省内存，仅靠回调消费）
     */
    suspend fun fetchAllFocusContents(
        focusId: Long,
        unreadOnly: Boolean = false,
        pageSize: Int = 100,
        maxPages: Int = 20,
        /** 本地已归档 contentId 快照；非空时启用增量早停 */
        existingContentIds: Set<String>? = null,
        /** 连续多少页 contentId 全命中本地即停止翻页（仅当 existingContentIds 非空时生效） */
        earlyStopHitPages: Int = 3,
        /** 是否收集返回列表；false 时仅回调（省内存） */
        collectList: Boolean = true,
        onItem: suspend (RawFocusContent) -> Boolean = { true },
    ): List<RawFocusContent> {
        val size = pageSize.coerceIn(1, 100) // 服务端上限 100
        val type = if (unreadOnly) "unread" else "all"
        val all = if (collectList) mutableListOf<RawFocusContent>() else null
        val seen = mutableSetOf<String>()
        var consecutiveHits = 0

        var page = 1
        while (page <= maxPages) {
            currentCoroutineContext().ensureActive()
            // 仅网络调用在 IO；onItem 在调用方上下文执行，保证 Flow emit 上下文一致
            val batch = callWithRetry { client.api.getFocusContents(focusId, type, page, size) }
            if (batch.isEmpty()) break
            val fresh = batch.mapNotNull { it.toRawFocusContent() }
                .filter { it.content.contentId.isNotBlank() && seen.add(it.content.contentId) }

            // 增量早停：整页 contentId 全命中本地 → 连续计数 +1；出现未命中 → 清零。
            // 服务端若按收录时间倒序返回，命中页出现后翻下去只会越来越旧，连续 3 页全命中即可安全停翻。
            if (existingContentIds != null && batch.isNotEmpty()) {
                val hitCount = batch.count { it.contentIdOrNull() in existingContentIds }
                val allHit = hitCount == batch.size
                consecutiveHits = if (allHit) consecutiveHits + 1 else 0
                Log.d(
                    "FocusPage",
                    "focus=$focusId page=$page batch=${batch.size} hit=$hitCount/${batch.size} consecutiveHits=$consecutiveHits" +
                        (if (batch.isNotEmpty()) {
                            val first = batch.first().publishedAtOrNull()
                            val last = batch.last().publishedAtOrNull()
                            " first=$first last=$last"
                        } else ""),
                )
                if (consecutiveHits >= earlyStopHitPages) {
                    Log.i("FocusPage", "focus=$focusId 连续 $earlyStopHitPages 页全命中本地，增量早停（page=$page）")
                    break
                }
            }

            var stop = false
            for (a in fresh) {
                all?.add(a)
                if (!onItem(a)) { stop = true; break }
            }
            if (stop) break
            if (fresh.isEmpty() || batch.size < size) break
            page++
        }
        return all ?: emptyList()
    }

    /**
     * 真实未读计数结果。
     * @param perType       各来源类型（MP/WEBSITE/X）服务器未读条数
     * @param perTypeNew    各来源类型下去重后的「待拉取」条数（= 未读 - 本地已存在）
     * @param subscriptionCountByType 各来源类型下的订阅源数量
     * @param focusUnread   关注点未读合计（仅当 includeFocus 时统计）
     * @param focusUnreadNew 关注点去重后的「待拉取」合计
     * @param unreadIdsByType 各来源类型下服务器未读的 contentId 集合
     * @param focusUnreadIds  关注点未读的 contentId 集合
     *
     * 保留 id 集合而不只是数字的原因：拉取完成后要立刻把「待拉取」降下来，
     * 有了这份快照就能在**本地**做一次集合差运算（快照 - 本地已存在）得到新数字，
     * 无需再打一轮网络；随后再跑精确统计与服务端对齐即可。
     */
    data class UnreadCountResult(
        val perType: Map<String, Int>,
        val perTypeNew: Map<String, Int>,
        val subscriptionCountByType: Map<String, Int>,
        val focusUnread: Int,
        val focusUnreadNew: Int,
        val unreadIdsByType: Map<String, Set<String>> = emptyMap(),
        val focusUnreadIds: Set<String> = emptySet(),
    )

    /**
     * 真实未读计数：不信任订阅列表里那个容易偏大的 unreadCount 字段，
     * 而是对每种启用的来源真正请求 type=unread，把返回的 contentId 按类型汇总成集合。
     * 再与本地已存在的内容做差集，给出「待拉取（新增）」数量。
     *
     * @param sourceTypes   启用的来源类型集合（如 MP/WEBSITE）
     * @param includeFocus  是否把关注点未读也统计进来
     * @param localExisting 本地 items/ 下已存在的 contentId 集合（用于去重）
     * @param maxPages      单个来源最多翻几页（防止异常时无限翻页），默认 3
     */
    suspend fun countUnreadByType(
        sourceTypes: Set<String>,
        includeFocus: Boolean,
        localExisting: Set<String> = emptySet(),
        maxPages: Int = 3,
        pageSize: Int = 100,
    ): UnreadCountResult = coroutineScope {
        val subs = listSubscriptions().filter { it.sourceType in sourceTypes }
        val subCount = mutableMapOf<String, Int>()
        // 按类型汇总未读 contentId。用集合而不是累加计数：
        // 同一篇文章可能同时挂在同类型的多个订阅源下，按条累加会把它重复计成两篇，
        // 而导出是按 contentId 落文件的，重复的那篇只会写一次——集合口径才对得上真实待拉取量。
        val idsByType = mutableMapOf<String, MutableSet<String>>()

        // 并发拉取各订阅源的未读 contentId，缩短「统计中…」时长。
        // 真正的全局限速仍在 HTTP 层令牌桶（2 个/秒）；Semaphore 只用来约束同时在飞的连接数，
        // 避免一次性对几十个源各开一个请求把连接池打满。
        val sem = Semaphore(8)
        val subJobs = subs.map { sub ->
            subCount[sub.sourceType] = (subCount[sub.sourceType] ?: 0) + 1
            sub to async {
                // 快路径：服务端下发的 unreadCount 已知只会偏大（见 Subscription 注释），
                // 因此 <=0 时必为真实的「无未读」，无需再打网络，直接省掉这次请求。
                if (sub.unreadCount <= 0) {
                    emptySet<String>()
                } else {
                    sem.withPermit {
                        fetchUnreadIds(maxPages, pageSize) { page, size ->
                            client.api.getContents(sub.sourceType, sub.sourceId, "unread", page, size)
                        }
                    }
                }
            }
        }
        for ((sub, job) in subJobs) {
            idsByType.getOrPut(sub.sourceType) { mutableSetOf() } += job.await()
        }

        val perType = idsByType.mapValues { (_, ids) -> ids.size }
        val perTypeNew = idsByType.mapValues { (_, ids) -> ids.count { it !in localExisting } }

        val focusIds = mutableSetOf<String>()
        if (includeFocus) {
            val focusJobs = listFocuses().map { f ->
                f to async {
                    // 同订阅源：unreadCount<=0 即视为无未读，跳过网络请求。
                    if (f.unreadCount <= 0) emptySet()
                    else sem.withPermit {
                        // ⚠️ 不能用 type=unread：服务端对关注点 unread 是「拉即标读」，统计本身就会消费云端未读。
                        //    改用 type=all + 本地筛未读（isRead=false），统计不产生副作用；
                        //    配合「连续全已读页早停」，未读集中在头部（新内容），翻 1~3 页即可收敛。
                        fetchUnreadIds(
                            maxPages,
                            pageSize,
                            onlyUnread = true,
                            stopAfterConsecutiveAllRead = 3,
                        ) { page, size ->
                            client.api.getFocusContents(f.id, "all", page, size)
                        }
                    }
                }
            }
            for ((_, job) in focusJobs) focusIds += job.await()
        }
        UnreadCountResult(
            perType = perType,
            perTypeNew = perTypeNew,
            subscriptionCountByType = subCount,
            focusUnread = focusIds.size,
            focusUnreadNew = focusIds.count { it !in localExisting },
            unreadIdsByType = idsByType,
            focusUnreadIds = focusIds,
        )
    }

    /**
     * 翻页拉取某个来源的未读 contentId 集合。
     * 服务端分页异常时可能重复返回同一页，用 contentId 去重并据此判停。
     *
     * @param onlyUnread true 时仅保留 isRead=false 的条目（用于关注点统计：type=all 拉取后本地筛未读，避免「拉即标读」副作用）
     * @param stopAfterConsecutiveAllRead 连续多少页「全部已读」即停止翻页（0=不启用）。
     *        依赖服务端按收录时间倒序返回：已读集中在尾部，翻到连续全已读说明未读区已扫完。
     */
    private suspend fun fetchUnreadIds(
        maxPages: Int,
        pageSize: Int,
        onlyUnread: Boolean = false,
        stopAfterConsecutiveAllRead: Int = 0,
        loadPage: suspend (page: Int, size: Int) -> List<JsonObject>,
    ): Set<String> {
        val size = pageSize.coerceIn(1, 100)
        val seen = mutableSetOf<String>()
        var consecutiveRead = 0
        var page = 1
        while (page <= maxPages) {
            currentCoroutineContext().ensureActive()
            val batch = callWithRetry { loadPage(page, size) }
            if (batch.isEmpty()) break
            val fresh = batch.mapNotNull { it.contentIdOrNull() }
                .filter { it.isNotBlank() }
                .filter { if (onlyUnread) isUnreadOf(batch, it) else true }
                .filter { seen.add(it) }

            // 连续「全部已读」早停：整页非空条目均已是已读 → 已读区开始，之后只会更旧，停翻。
            if (stopAfterConsecutiveAllRead > 0 && batch.isNotEmpty()) {
                val meaningful = batch.filter { !it.contentIdOrNull().isNullOrBlank() }
                val allRead = meaningful.isNotEmpty() && meaningful.all { !it.isUnreadFlag() }
                consecutiveRead = if (allRead) consecutiveRead + 1 else 0
                if (consecutiveRead >= stopAfterConsecutiveAllRead) {
                    Log.i("FocusPage", "统计未读：连续 $stopAfterConsecutiveAllRead 页全部已读，早停（page=$page）")
                    break
                }
            }
            if (fresh.isEmpty() || batch.size < size) break
            page++
        }
        return seen
    }

    /** 在整页 JSON 里按 contentId 找对应条目的 isRead（避免二次反序列化整条） */
    private fun isUnreadOf(batch: List<JsonObject>, contentId: String): Boolean {
        val obj = batch.firstOrNull { it.contentIdOrNull() == contentId } ?: return true
        return obj.isUnreadFlag()
    }

    fun logout() = client.tokenStore.clear()

    /**
     * 统一异常归一化：
     *  - SupsubException 直接透传（拦截器已抛出的）
     *  - HttpException  → 解析 {code,message,status} 错误体；套餐过期单独识别
     *  - IOException    → NETWORK_ERROR
     */
    private suspend fun <T> call(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: SupsubException) {
            throw e
        } catch (e: HttpException) {
            throw e.toSupsubException()
        } catch (e: IOException) {
            throw SupsubException.network(e.message)
        }
    }

    /**
     * 带指数退避的调用（P0 止血核心）。
     *
     * 之前拉取路径零重试：一次 429 / 网络抖动 / 超时就整轮失败。现在对
     * 限流（RATE_LIMITED）、网络抖动（NETWORK_ERROR）、服务端 5xx 自动重试：
     *  - 退避节奏：1s → 2s → 4s（[PULL_MAX_RETRIES] 次），上限 [PULL_MAX_BACKOFF_MS]；
     *  - 服务端带了 Retry-After → 以它为准（等待更久），否则指数退避；
     *  - 鉴权 / 套餐过期等业务性错误不重试，直接抛。
     * 重试期间协程可被取消（取消 → 立即停手，不阻塞 UI 取消）。
     */
    private suspend fun <T> callWithRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return call(block)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SupsubException) {
                if (!e.isRetryable || attempt >= PULL_MAX_RETRIES) throw e
                val backoffMs = (PULL_BASE_BACKOFF_MS shl attempt).coerceAtMost(PULL_MAX_BACKOFF_MS)
                val delayMs = e.retryAfterMs?.coerceAtLeast(backoffMs) ?: backoffMs
                Log.w(TAG, "请求失败（第 ${attempt + 1}/$PULL_MAX_RETRIES 次，${delayMs}ms 后重试）：${e.code} ${e.message}")
                delay(delayMs)
                attempt++
            } catch (e: Throwable) {
                // 未知异常不重试（如反序列化错误），透传由上层处理
                throw e
            }
        }
    }

    private fun HttpException.toSupsubException(): SupsubException {
        val status = code()
        val raw = try {
            response()?.errorBody()?.string().orEmpty()
        } catch (_: Throwable) {
            ""
        }
        val envelope = runCatching {
            SupsubClient.json.decodeFromString(ErrorEnvelope.serializer(), raw)
        }.getOrNull()

        val code = envelope?.code?.takeIf { it.isNotBlank() } ?: SupsubException.CODE_SERVER
        val message = envelope?.message?.takeIf { it.isNotBlank() }
            ?: raw.takeIf { it.isNotBlank() }
            ?: "服务异常（HTTP $status）"

        // 403 常见于套餐过期，给出更可读的提示
        if (status == 403) {
            return SupsubException(SupsubException.CODE_PLAN_EXPIRED, message, status)
        }
        return SupsubException(code, message, status)
    }

    companion object {
        private const val TAG = "SupsubRepository"

        /** 单请求最大重试次数（不含首次） */
        private const val PULL_MAX_RETRIES = 3

        /** 指数退避基数：1s → 2s → 4s */
        private const val PULL_BASE_BACKOFF_MS = 1_000L

        /** 单次退避上限：即使多次失败也不无限等，最多 5s */
        private const val PULL_MAX_BACKOFF_MS = 5_000L
    }
}
