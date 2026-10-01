package com.peide.supsub.notionsync

import android.util.Log
import com.peide.supsub.data.ArchiveStore
import com.peide.supsub.data.ArticleRecord
import com.peide.supsub.data.SyncStatus
import com.peide.supsub.data.OriginCategory
import com.peide.supsub.data.NotionRemotePatch
import com.peide.supsub.data.NotionMappedRow
import com.peide.supsub.data.ShardKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.HttpException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 单条同步概览的动作类型，供「本次同步概览」展示与配色 */
enum class SyncAction {
    /** 推送到 Notion：新建页面 */
    PUSH_CREATE,
    /** 推送到 Notion：原地覆盖更新已有页面 */
    PUSH_UPDATE,
    /** 推送到 Notion：仅属性级重推（PATCH 属性，不重建正文） */
    PUSH_PROP_BACKFILL,
    /** 从 Notion 拉回本地：远端更新覆盖本地 */
    PULL,
    /** 从 Notion 拉回本地：两端各自改过的真冲突（按 LWW 取较新者覆盖） */
    PULL_CONFLICT,
    /** 从 Notion 拉回本地：冷启动 / 跨设备合并时新建本地行（本地原本没有这篇） */
    PULL_CREATE,
    /** 从 Notion 拉回本地：远端标记已删除，本地同步删除并写墓碑防复活 */
    PULL_DELETE,
    /** 推送到 Notion：把本地删除同步到云端（PATCH _del=true） */
    PUSH_DELETE,
    /** 从 Notion 拉回本地：某分库拉取多次失败，整库跳过（不中断其余分库，避免冷启动重建静默丢数据） */
    PULL_SKIPPED,
}

/** 单次同步中单篇文章的概览条目（推送或拉取），供「本次同步概览」列表展示 */
data class SyncItemReport(
    val contentId: String,
    val title: String,
    val sourceName: String,
    val action: SyncAction,
)

/** 推送阶段结束时的计数快照，供拉取阶段在进度文案里继续展示推送结果 */
private data class PushCounts(
    val total: Int,
    val synced: Int,
    val skipped: Int,
    val failed: Int,
)

/** 同步进度（与 ViewModel 的 notionSyncState 对应） */
sealed interface SyncProgress {
    data object Idle : SyncProgress
    data class Running(
        val currentTitle: String,
        val index: Int,
        val total: Int,
        val synced: Int,
        val skipped: Int,
        val failed: Int,
    ) : SyncProgress
    data class Done(
        val synced: Int,
        val skipped: Int,
        val failed: Int,
        /** 本次同步新建的按天子库标签，如 `2026-08-05` */
        val createdShards: List<String> = emptyList(),
        /** 双向同步：从 Notion 拉回本地的篇数（远端比本地新） */
        val pulled: Int = 0,
        /** 双向同步：两端各自改过的「真冲突」篇数（LWW 取较新者覆盖，仍计入 pulled） */
        val conflicts: Int = 0,
        /** 本次推送概览（新建/更新/属性补齐的文章清单） */
        val pushOverview: List<SyncItemReport> = emptyList(),
        /** 本次拉取概览（从 Notion 拉回本地的文章清单） */
        val pullOverview: List<SyncItemReport> = emptyList(),
    ) : SyncProgress
    data class Error(val message: String) : SyncProgress
}

sealed interface SyncResult {
    /**
     * @param syncedContentIds 本次真正写入/更新 Notion 的条目 contentId 集合，
     *        供「只标记本次同步的条目」使用（本地标记已读，云端整源接口用不到）
     * @param createdShards 本次新建的按天子库标签
     */
    data class Success(
        val synced: Int,
        val skipped: Int,
        val syncedContentIds: Set<String> = emptySet(),
        val createdShards: List<String> = emptyList(),
        val pulled: Int = 0,
        val conflicts: Int = 0,
        val pushOverview: List<SyncItemReport> = emptyList(),
        val pullOverview: List<SyncItemReport> = emptyList(),
    ) : SyncResult
    data class PartialFailure(
        val synced: Int,
        val failed: Int,
        val syncedContentIds: Set<String> = emptySet(),
        val createdShards: List<String> = emptyList(),
        val pulled: Int = 0,
        val conflicts: Int = 0,
        val pushOverview: List<SyncItemReport> = emptyList(),
        val pullOverview: List<SyncItemReport> = emptyList(),
    ) : SyncResult
    data class Error(val message: String) : SyncResult
}

/**
 * 把本地 SQLite 归档同步到 Notion 的**按天分库**。
 *
 * ## V2 相对 V1 改了什么
 *
 * 1. **数据源**：V1 要把 SAF 目录里的 JSON 文件逐个读出来解析，才知道哪些需要同步
 *    （3767 篇就是 3767 次单文档读，绝大多数读完发现「没变、跳过」）；
 *    V2 一句 [ArchiveStore.pendingForSync] 带索引的 WHERE 就筛完，没变动的条目连行都不读。
 * 2. **写入目标**：V1 全部写进一个不断膨胀的大库；V2 按 `shardKey`（按天）路由到对应子库，
 *    由 [NotionShardRouter] 在容器页下按需发现/创建。
 * 3. **状态回写**：V1 把 sync{} 写回每个 item JSON 文件；V2 直接 UPDATE 一行。
 *
 * ## 模板
 *
 * 结构化属性列（列名见 [NotionSchema]，命中同名同类型列才写，缺失则跳过、不影响正文）：
 *   Name(title) / 来源 / 来源类型 / 来源分类 / 采集时间 / 标签 / 原文链接 /
 *   关键词 / 内容类型 / 内容深度 / 语气 / 体裁 / 含行动建议 / 含观点立场 / 立场摘要
 *
 * body 块顺序：
 *   1) 标签行 2) 关键词行 3) 信息行 ℹ️ 4) 内容分析行 🔍
 *   5) 「💡 摘要」+ summary（Markdown→Notion 块；接口只给摘要，无全文）
 *   6) 「🗣 立场摘要」 7) 原文链接 8) 封面图（external URL，置底）
 *
 * ## 幂等与去重
 *
 * - 待同步集本身已由 SQL 过滤（SYNCED 且 synced_hash == content_hash 的不会被取出）；
 * - 已记录 notionPageId 时：普通重推 → 原地 PATCH（pageId 不变）；强制重推 → 归档旧页 + 新建（每篇仅 2 请求）；
 * - notionPageId 缺失或旧页 404 → 退化为新建以自愈；
 * - 正文 children 哈希（syncedBodyHash）短路：正文没变就跳过整篇重写，仅计 skipped。
 *
 * ## 限流与并发
 *
 * 所有 Notion 请求经统一出口 [withRetry]，受全局 [reqSem]（4 并发）限流，429(Retry-After)/5xx/409 退避重试最多 4 次；
 * 文章任务并发 [taskSem]（16 篇），单篇内旧块删除再并发 8。
 * 分片之间**串行**处理：每个分片开头要探一次 schema，串行能让同一分片的 schema 只探一次，
 * 且新的一天的建库动作不会被多个分片同时触发。
 */
class SupSubSyncEngine(
    private val store: ArchiveStore,
    private val api: NotionApi,
    private val config: NotionConfig,
) {

    // 全局并发控制——reqSem 限制同时在飞的 Notion 请求数（逼近限流，避免 429 风暴）；taskSem 限制同时处理的文章任务数
    private val reqSem = Semaphore(REQUEST_CONCURRENCY)
    private val taskSem = Semaphore(MAX_TASK_CONCURRENCY)

    /** 把私有的 withRetry 暴露给路由，让建库/发现请求也走同一个限流与退避闸门 */
    private val retryGate = object : NotionRetry {
        override suspend fun <T> call(block: suspend () -> T): T = withRetry(block)
    }

    private val router = NotionShardRouter(api, store, config.parentPageId, retryGate)

    /** dbId → schema，分片子库的 schema 只探一次 */
    private val schemaCache = ConcurrentHashMap<String, DbSchema>()
    private val schemaLock = Mutex()

    companion object {
        private const val TAG = "SupSubSync"
        private const val MAX_CHILDREN = 80 // createPage children 上限（留余量）
        private const val RICH_TEXT_LIMIT = 1900
        private const val MAX_RETRIES = 4
        private const val BASE_BACKOFF_MS = 1000L
        /**
         * 分库级拉取重试：单个分库 queryDatabase 在限流窗口内可能耗尽内部 4 次重试并抛错。
         * 若直接冒泡会中断整个 pullFromRemote，导致后续分库几百篇文章被静默丢弃（冷启动重建不完整）。
         * 这里在分库外层再兜底重试若干次，仍失败则仅跳过该分库并记入概览，绝不让个别分库拖垮整轮同步。
         */
        private const val SHARD_FETCH_MAX_RETRY = 3
        private const val SHARD_FETCH_BACKOFF_MS = 3000L
        private const val MIN_ITEM_INTERVAL_MS = 80L   // 去掉固定 350ms 空等，仅保留微小礼貌间隔
        private const val DELETE_CONCURRENCY = 8       // 每篇内并发删除旧块的信号量许可数
        private const val REQUEST_CONCURRENCY = 4      // 全局同时在飞的 Notion 请求上限（适度降低以减少 409 并发写冲突）
        private const val MAX_TASK_CONCURRENCY = 16    // 并发处理的文章任务数
        private const val MAX_QUERY_PAGES = 50         // queryDatabase 翻页上限（拉取远端状态兜底防死循环）
        private const val WATERMARK_BUFFER_MS = 120_000L // 拉取时间水印缓冲：多往前拉 2 分钟，规避 Notion 分钟精度导致的同分钟改动漏抓
        /**
         * 属性级重推版本号：每当 Notion 模板新增/变更了某个会写入的结构化列时 +1，
         * 触发一次「历史已同步页面补齐该列值」的属性级重推（PATCH 属性、不重建正文）。
         * - v1：新增「是否代表」列，补齐单簇代表的缺值。
         * - v2（三方同步 P0/P1）：新增 内容ID / 摘要 / 封面 / 已删除 四列，把存量已同步页的
         *   content_id / summary / cover 一次性 PATCH 补齐，使冷启动平板能从 Notion 无损重建整行。
         */
        private const val SCHEMA_BACKFILL_VERSION = 2
        private const val META_SCHEMA_BACKFILL = "notion_schema_backfill_v"
        /**
         * 一次性清理版本号：把历史按天子库里多余的「已读」列移除后写入，
         * 跑过一次即跳过，避免每次同步都打一遍 PATCH。
         */
        private const val META_PRUNE_READ_COL = "notion_prune_read_col_v"
        private const val META_LAST_PULL_TS = "notion_last_pull_ts" // 上次成功拉取的开始时间戳(ms)，作为增量拉取水印
    }

    /**
     * 执行同步。
     *
     * @param forceResync 忽略同步状态全量重推（旧页归档 + 新建）
     * @param bidirectional 双向同步：推送完成后，再按子库拉取 Notion 远端状态，
     *        对每篇比较本地编辑时间(local_edited_at)与远端 last_edited_time，远端更新则回拉本地（LWW）。
     * @param onSynced    每成功同步一篇即回调（contentId, pageId, contentHash）
     */
    suspend fun sync(
        forceResync: Boolean,
        onProgress: suspend (SyncProgress) -> Unit,
        onSynced: suspend (contentId: String, pageId: String, hash: String) -> Unit = { _, _, _ -> },
        bidirectional: Boolean = false,
    ): SyncResult {
        onProgress(SyncProgress.Running("读取待同步条目…", 0, 0, 0, 0, 0))

        // 属性级重推（PATCH 重推）：当 Notion 模板新增/变更了结构化列时，把历史已同步页面一次性标记补写。
        // 仅触发一次（由 meta 版本号控制），标记后这些条目走「只 PATCH 属性、不重建正文」的轻量路径。
        runCatching {
            val applied = store.meta(META_SCHEMA_BACKFILL)?.toIntOrNull() ?: 0
            if (applied < SCHEMA_BACKFILL_VERSION) {
                val n = store.markSyncedForPropBackfill()
                store.setMeta(META_SCHEMA_BACKFILL, SCHEMA_BACKFILL_VERSION.toString())
                Log.i(TAG, "触发属性级重推补齐：标记 $n 条（版本 $applied → $SCHEMA_BACKFILL_VERSION）")
            }
        }.onFailure { Log.w(TAG, "属性级重推标记失败（忽略，不影响正常同步）", it) }

        // 一次性清理：移除历史按天子库里多余的「已读」列（让「同步到 Notion 不再有『已读』」在存量库上也成立）
        pruneReadColumnIfNeeded()

        // 冷启动/跨设备：拉取前必须先去容器页发现并登记所有已存在的按天子库。
        // 不能只在「本地尚未登记任何分库」时才发现——否则二次及之后的同步会跳过发现，
        // 手机端在这之后新建的按天子库（含其余文章）永不被枚举，平板就拉不到那些文章
        // （实测：手机 1542 篇/35 库，平板只拉到 1450 篇/34 库，漏掉最新一天「订阅归档 2026-08-18」整库 92 篇）。
        // 每次都重新发现：discoverAllShards 内部强制重走容器页（清空 discovered 缓存），
        // 开销仅一次子库列表拉取，换来「任意时刻新建的子库都能被拉到」。发现失败时 runCatching 兜底，
        // 不会中断同步，本地已登记的分库仍照常枚举（仅可能漏掉新增分库）。
        // discoverAllShards 返回「本次新发现的分库 id」：新库从未被增量拉取水印覆盖过，
        // 必须对其全量拉取（since=0），否则库里 last_edited_time 早于水印的存量文章会被 on_or_after 过滤掉。
        val fullPullDbIds = if (bidirectional && config.parentPageId.isNotBlank()) {
            runCatching { router.discoverAllShards() }
                .onFailure { Log.w(TAG, "发现容器页已存在子库失败（拉取可能不完整）", it) }
                .getOrDefault(emptySet())
        } else emptySet()
        if (fullPullDbIds.isNotEmpty()) {
            Log.i(TAG, "本次发现新增分库 ${fullPullDbIds.size} 个，将对它们全量拉取（不受增量水印限制）：${fullPullDbIds.joinToString { it.take(8) }}")
        }

        // 调试用：若 files/debug_single_sync.txt 存在且非空，本次同步只处理该 contentId（加速单篇回归，如 ⑤-B）。
        val singleId = store.readDebugSingleSyncId()
        if (!singleId.isNullOrBlank()) {
            Log.i(TAG, "单篇同步调试模式：仅处理 contentId=$singleId")
        }
        val pending = store.pendingForSync(forceResync, singleContentId = singleId)
        val archived = store.count()
        // 「跳过」沿用旧口径：库里存在但本次无需推送的条目（已同步且内容未变）
        val skipped = AtomicInteger((archived - pending.size).coerceAtLeast(0))
        val total = pending.size
        Log.i(TAG, "待同步 $total 篇（库内合计 $archived，已同步未变 ${skipped.get()}），forceResync=$forceResync，bidirectional=$bidirectional")
        if (total == 0) {
            // 即使没有待推送，也可能存在「仅在 Notion 端改过」的条目需要拉回
            val earlyPush = Collections.synchronizedList(mutableListOf<SyncItemReport>())
            val earlyPull = Collections.synchronizedList(mutableListOf<SyncItemReport>())
            val (p, c) = if (bidirectional) runCatching {
                // 否则 pullFromRemote 内部约 2.3s 才报第一个进度，UI 会卡在「读取待同步条目…」
                onProgress(SyncProgress.Running("先从 Notion 拉取更新…", 0, 0, 0, skipped.get(), 0))
                pullFromRemote(PushCounts(0, 0, skipped.get(), 0), earlyPull, onProgress, singleContentId = singleId, fullPullDbIds = fullPullDbIds)
            }.getOrDefault(0 to 0) else (0 to 0)
            // 删除同步（P4 推送侧）：即使无待推送，本地也可能有 DELETED_LOCAL 待推到云端
            if (bidirectional) runCatching { pushDeleted(earlyPush) }
            onProgress(SyncProgress.Done(0, skipped.get(), 0, emptyList(), p, c, earlyPush, earlyPull))
            return SyncResult.Success(0, skipped.get(), emptySet(), emptyList(), p, c, earlyPush, earlyPull)
        }
        if (config.parentPageId.isBlank()) {
            return SyncResult.Error("未配置 Notion 容器页 ID")
        }

        // 按天分片归组。shardKey 为空是老数据/异常数据，用发布时间现算一个兜底。
        val groups: Map<String, List<ArticleRecord>> = pending.groupBy { r ->
            r.shardKey.ifBlank { ShardKeys.of(r.publishedAt, r.capturedAt) }
        }.toSortedMap(compareByDescending { it }) // 新的日期先同步，用户最关心最近的内容

        Log.i(
            TAG,
            "涉及 ${groups.size} 个按天分片：" +
                groups.entries.joinToString { "${it.key}(${it.value.size})" },
        )

        val synced = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val completed = AtomicInteger(0)
        val syncedIds = ConcurrentHashMap.newKeySet<String>()
        // 概览清单：推送（新建/更新/属性补齐）与拉取（回拉/冲突）的文章，供「本次同步概览」展示
        val pushReports = Collections.synchronizedList(mutableListOf<SyncItemReport>())
        val pullReports = Collections.synchronizedList(mutableListOf<SyncItemReport>())

        // 双向同步：先拉后推（P5）——拉取在推送之前，避免双设备各改不同字段互相覆盖。
        // 冷启动平板在此从 Notion 重建整表（新建本地行）；已同步设备则拉回云端更新。
        val pulled = AtomicInteger(0)
        val conflicts = AtomicInteger(0)
        if (bidirectional) {
            onProgress(
                SyncProgress.Running(
                    "先从 Notion 拉取更新…",
                    total, total, 0, skipped.get(), 0,
                ),
            )
            runCatching {
                val (p, c) = pullFromRemote(
                    PushCounts(total, 0, skipped.get(), 0),
                    pullReports,
                    onProgress,
                    singleContentId = singleId,
                    fullPullDbIds = fullPullDbIds,
                )
                pulled.set(p); conflicts.set(c)
            }.onFailure { Log.e(TAG, "双向同步拉取阶段异常（不影响后续推送）", it) }
        }

        for ((shardKey, records) in groups) {
            currentCoroutineContext().ensureActive()

            // 1) 路由到子库（命中缓存/本地登记则零网络；新的一天在这里建库）
            val resolved = runCatching { resolveShard(shardKey) }
            if (resolved.isFailure) {
                val msg = failureMessage(resolved.exceptionOrNull() as? Exception ?: Exception("未知错误"))
                Log.e(TAG, "分片 $shardKey 路由失败，本片 ${records.size} 篇全部计失败：$msg")
                records.forEach { r ->
                    runCatching { store.markSyncFailed(r.contentId, "分库路由失败：$msg") }
                    failed.incrementAndGet()
                    val done = completed.incrementAndGet()
                    onProgress(SyncProgress.Running(r.title, done, total, synced.get(), skipped.get(), failed.get()))
                }
                continue
            }
            val (dbId, schema) = resolved.getOrThrow()

            // 2) 该分片的 select / multi_select 选项统一上色（Notion 只能在 schema 层定义颜色）
            val items = records.map { it.toItemData(shardKey) }
            ensureOptionColors(schema, dbId, items)

            // 3) 并发写页面
            Log.i(TAG, "分片 $shardKey → db=$dbId，开始同步 ${items.size} 篇")
            coroutineScope {
                items.map { item ->
                    async {
                        taskSem.withPermit {
                            currentCoroutineContext().ensureActive()
                            try {
                                when (val outcome = syncItemPage(item, schema, dbId, forceResync)) {
                                    is SyncOutcome.Updated -> {
                                        store.markSynced(
                                            contentId = item.contentId,
                                            pageId = outcome.pageId,
                                            databaseId = dbId,
                                            contentHash = item.contentHash,
                                            bodyHash = outcome.bodyHash,
                                            remoteEditedAtMs = outcome.remoteEditedAt,
                                        )
                                        // 记录一次「本地覆盖云端」历史（仅真正推送成功时；Skipped 不计）
                                        runCatching { store.recordPush(item.contentId, outcome.pageId) }
                                        runCatching { onSynced(item.contentId, outcome.pageId, item.contentHash) }
                                        syncedIds.add(item.contentId)
                                        synced.incrementAndGet()
                                        pushReports.add(
                                            SyncItemReport(
                                                contentId = item.contentId,
                                                title = item.title.ifBlank { "（无标题）" },
                                                sourceName = item.sourceName,
                                                action = when (outcome.action) {
                                                    PushAction.CREATE -> SyncAction.PUSH_CREATE
                                                    PushAction.UPDATE -> SyncAction.PUSH_UPDATE
                                                    PushAction.PROP_BACKFILL -> SyncAction.PUSH_PROP_BACKFILL
                                                },
                                            ),
                                        )
                                    }
                                    is SyncOutcome.Skipped -> {
                                        // 正文未变，跳过整篇重写。但内容哈希已变（否则不会被选中），
                                        // 仍要写回 synced_hash，不然下次同步会把它再捞出来一遍。
                                        // 跳过 = 本次没写 Notion，remoteEditedAt 传 0 保留 notion_last_edited_at 原值。
                                        store.markSynced(
                                            contentId = item.contentId,
                                            pageId = outcome.pageId,
                                            databaseId = dbId,
                                            contentHash = item.contentHash,
                                            bodyHash = outcome.bodyHash,
                                            remoteEditedAtMs = outcome.remoteEditedAt,
                                        )
                                        skipped.incrementAndGet()
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e(TAG, "同步单条失败: ${item.title.take(40)}", e)
                                runCatching { store.markSyncFailed(item.contentId, failureMessage(e)) }
                                failed.incrementAndGet()
                            }
                            delay(MIN_ITEM_INTERVAL_MS)
                            val done = completed.incrementAndGet()
                            onProgress(
                                SyncProgress.Running(
                                    item.title.ifBlank { "（无标题）" },
                                    done,
                                    total,
                                    synced.get(),
                                    skipped.get(),
                                    failed.get(),
                                )
                            )
                        }
                    }
                }.awaitAll()
            }
        }

        val createdShards = router.newlyCreated.map { ShardKeys.labelOf(it) }.sorted()
        // 双向同步的拉取阶段已在推送之前完成（P5 先拉后推），此处不再重复拉取。
        // 删除同步（P4 推送侧）：把本地删除推到云端（在推送之后执行）
        if (bidirectional) runCatching { pushDeleted(pushReports) }
        Log.i(
            TAG,
            "同步结束：成功=${synced.get()} 跳过=${skipped.get()} 失败=${failed.get()}" +
                if (createdShards.isEmpty()) "" else "，新建子库=${createdShards.joinToString()}" +
                if (bidirectional) "，拉取=${pulled.get()} 冲突=${conflicts.get()}" else "",
        )
        onProgress(
            SyncProgress.Done(
                synced.get(), skipped.get(), failed.get(), createdShards,
                pulled.get(), conflicts.get(), pushReports, pullReports,
            ),
        )
        return if (failed.get() == 0) {
            SyncResult.Success(synced.get(), skipped.get(), syncedIds.toSet(), createdShards, pulled.get(), conflicts.get(), pushReports, pullReports)
        } else {
            SyncResult.PartialFailure(synced.get(), failed.get(), syncedIds.toSet(), createdShards, pulled.get(), conflicts.get(), pushReports, pullReports)
        }
    }

    /**
     * 解析分片 → (databaseId, schema)。
     *
     * 顺带自愈「子库在 Notion 侧被手动删掉」的情况：探 schema 时 404 说明库没了，
     * 作废本地登记后重新解析一次（会重新发现或新建）。这一步不额外花请求——
     * schema 本来就要探，正好当作存活探测。
     */
    private suspend fun resolveShard(shardKey: String): Pair<String, DbSchema> {
        val dbId = router.databaseIdFor(shardKey)
        return try {
            dbId to schemaFor(dbId)
        } catch (e: SyncHttpError) {
            if (e.code != 404) throw e
            Log.w(TAG, "分片 $shardKey 的子库 $dbId 已不存在，作废登记后重建")
            router.invalidate(shardKey)
            val fresh = router.databaseIdFor(shardKey)
            fresh to schemaFor(fresh)
        }
    }

    /** 探测并补全某个子库的 schema，结果缓存（失败不缓存） */
    private suspend fun schemaFor(dbId: String): DbSchema {
        schemaCache[dbId]?.let { return it }
        return schemaLock.withLock {
            schemaCache[dbId]?.let { return@withLock it }
            val s = ensureSchema(loadDatabaseSchema(dbId), dbId)
            schemaCache[dbId] = s
            s
        }
    }

    private fun failureMessage(e: Exception): String = when (e) {
        is SyncHttpError -> "HTTP ${e.code}: ${e.detail}".take(200)
        is HttpException -> {
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
                ?.replace(Regex("\\s+"), " ")?.take(200) ?: ""
            "HTTP ${e.code()}${if (body.isNotBlank()) ": $body" else ""}"
        }
        else -> (e.message ?: e.javaClass.simpleName).take(200)
    }

    /**
     * 一次性清理：把历史按天子库里多余的「已读」受管列移除。
     *
     * 早期版本把「已读」作为手动维护列预置进每个新建子库；后来「实际已读」改由受管列
     * [NotionSchema.PROP_USER_READ] 承担，「已读」成了重复概念，新建库模板已不再创建它（见 [NotionSchema]）。
     * 本方法把**存量**子库里的「已读」列删除（best-effort，逐库 PATCH properties 设为 null），
     * 让「同步到 Notion 不再有『已读』」在旧库上也成立。
     *
     * 一次性：由 meta 版本号控制，跑过一次就跳过，避免每次同步都打一遍 PATCH。
     */
    private suspend fun pruneReadColumnIfNeeded() {
        val applied = store.meta(META_PRUNE_READ_COL)?.toIntOrNull() ?: 0
        if (applied >= 1) return
        // 先置位，避免异常/限流导致反复重跑
        runCatching { store.setMeta(META_PRUNE_READ_COL, "1") }
        runCatching {
            val shards = store.listShards()
            var removed = 0
            var fail = 0
            for (s in shards) {
                val dbId = s.databaseId
                if (dbId.isBlank()) continue
                // 仅当该库确实还存在「已读」列时才打 PATCH，避免对无此列的库报错
                val hasRead = runCatching { loadDatabaseSchema(dbId) }.getOrNull()
                    ?.props?.has("已读") ?: false
                if (!hasRead) continue
                val res = runCatching {
                    withRetry {
                        api.updateDatabase(
                            dbId,
                            JSONObject().put("properties", JSONObject().put("已读", JSONObject.NULL))
                                .toString().toJsonBody(),
                        ).string()
                    }
                }
                if (res.isSuccess) removed++ else {
                    fail++
                    Log.w(TAG, "pruneReadColumn 失败 db=$dbId", res.exceptionOrNull())
                }
            }
            Log.i(TAG, "pruneReadColumn：移除存量子库『已读』列 成功=$removed 失败=$fail")
        }.onFailure { Log.w(TAG, "pruneReadColumn 异常（忽略，不影响同步）", it) }
    }

    // ===================== Schema =====================

    /** 探测目标数据库的属性结构：标题属性名 + 全量属性（用于结构化字段匹配） */
    private suspend fun loadDatabaseSchema(dbId: String): DbSchema = withRetry {
        val resp = api.getDatabase(dbId).string()
        val obj = JSONObject(resp)
        val props = obj.optJSONObject("properties") ?: JSONObject()
        var title = "Name"
        props.keys().forEach { k ->
            if (props.optJSONObject(k)?.optString("type") == "title") title = k
        }
        DbSchema(title, props)
    }

    /**
     * 最佳努力：若目标库缺少模板所需的结构化列，则自动补建（PATCH database）。
     *
     * 原则：只要候选名里已有任意同名列（哪怕类型不同）就完全不动，尊重用户已有结构；
     * 一个候选名都不存在时，才用首选中文名新建。补建失败不影响同步（正文照常写）。
     *
     * 由 [NotionShardRouter] 新建的子库开箱就是全列齐备，这里会直接零请求返回；
     * 需要补建的只有「用户手动建过、或早期版本建的」库。
     */
    private suspend fun ensureSchema(schema: DbSchema, dbId: String): DbSchema {
        val add = JSONObject()
        for ((candidates, type, spec) in NotionSchema.MANAGED) {
            if (candidates.any { schema.props.has(it) }) continue
            add.put(candidates.first(), JSONObject().put(type, spec()))
        }
        if (add.length() == 0) {
            Log.i(TAG, "ensureSchema: $dbId 结构化列已齐备，无需新建")
            return schema
        }
        Log.i(TAG, "ensureSchema: $dbId 准备新建列 -> ${add.keys().asSequence().joinToString()}")
        val result = runCatching {
            withRetry {
                // 必须消费响应体（与 createPage 一致），否则某些 OkHttp 配置下会异常
                api.updateDatabase(dbId, JSONObject().put("properties", add).toString().toJsonBody()).string()
            }
        }
        if (result.isFailure) {
            Log.e(TAG, "ensureSchema: 新建列失败（已忽略，正文照常同步）", result.exceptionOrNull())
            return schema
        }
        return runCatching { loadDatabaseSchema(dbId) }.getOrDefault(schema)
    }

    private data class DbSchema(val titleProp: String, val props: JSONObject) {
        /** 在数据库中查找给定候选名里第一个存在且类型匹配的属性名；找不到返回 null */
        fun find(candidates: List<String>, type: String): String? {
            for (name in candidates) {
                val p = runCatching { props.getJSONObject(name) }.getOrNull() ?: continue
                if (p.optString("type") == type) return name
            }
            return null
        }
    }

    /**
     * 最佳努力：为目标库的 select / multi_select 属性预置带颜色的选项。
     * Notion 选项配色只能在数据库 schema 层定义，故写页面前先 PATCH，让随后写入的值呈彩色。
     * 注意：PATCH options 是整体替换，必须带上全部已有选项（沿用其颜色），只对新选项上色。
     */
    private suspend fun ensureOptionColors(schema: DbSchema, dbId: String, items: List<ItemData>) {
        val props = JSONObject()
        // multi_select：标签 + 关键词
        schema.find(NotionSchema.PROP_TAGS, "multi_select")?.let { name ->
            buildColorOptions(schema, name, "multi_select", items.flatMapTo(mutableSetOf()) { it.tags })
                ?.let { props.put(name, it) }
        }
        schema.find(NotionSchema.PROP_KEYWORDS, "multi_select")?.let { name ->
            buildColorOptions(schema, name, "multi_select", items.flatMapTo(mutableSetOf()) { it.keywords })
                ?.let { props.put(name, it) }
        }
        // select：来源类型 / 来源分类 + 4 个 AI 分析维度 + 关注点
        listOf(
            NotionSchema.PROP_SOURCE_TYPE to items.mapTo(mutableSetOf()) { it.sourceType },
            NotionSchema.PROP_ORIGIN_CATEGORY to items.mapTo(mutableSetOf()) { it.originCategory },
            NotionSchema.PROP_FOCUS to items.mapTo(mutableSetOf()) { it.focusTitle }.filter { it.isNotBlank() }.toSet(),
            NotionSchema.PROP_CONTENT_TYPE to items.mapTo(mutableSetOf()) { it.contentType },
            NotionSchema.PROP_CONTENT_DEPTH to items.mapTo(mutableSetOf()) { it.contentDepth },
            NotionSchema.PROP_TONE to items.mapTo(mutableSetOf()) { it.tone },
            NotionSchema.PROP_STYLE to items.mapTo(mutableSetOf()) { it.style },
        ).forEach { (cands, desired) ->
            schema.find(cands, "select")?.let { name ->
                buildColorOptions(schema, name, "select", desired)?.let { props.put(name, it) }
            }
        }
        if (props.length() == 0) return
        val body = JSONObject().put("properties", props)
        runCatching { withRetry { api.updateDatabase(dbId, body.toString().toJsonBody()).string() } }
            .onFailure { Log.w(TAG, "ensureOptionColors: 上色失败（忽略）", it) }
    }

    private fun buildColorOptions(
        schema: DbSchema,
        propName: String,
        type: String,
        desired: Set<String>,
    ): JSONObject? {
        val existing = existingOptionList(schema, propName)
        val existingNames = existing.map { it.first }.toSet()
        val newOnes = desired.filter { it.isNotBlank() && it !in existingNames }
        if (newOnes.isEmpty()) return null
        val arr = JSONArray()
        existing.forEach { (n, c) -> arr.put(JSONObject().put("name", n).put("color", c)) }
        newOnes.forEach { n -> arr.put(JSONObject().put("name", n).put("color", NotionSchema.colorFor(n))) }
        return JSONObject().put(type, JSONObject().put("options", arr))
    }

    private fun existingOptionList(schema: DbSchema, propName: String): List<Pair<String, String>> {
        val p = runCatching { schema.props.getJSONObject(propName) }.getOrNull() ?: return emptyList()
        val type = p.optString("type")
        val arr = p.optJSONObject(type)?.optJSONArray("options") ?: return emptyList()
        val list = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optString("name")
            if (n.isNotBlank()) list.add(n to (o.optString("color").takeIf { it.isNotBlank() } ?: "default"))
        }
        return list
    }

    // ===================== 单条同步 =====================

    private suspend fun createPage(item: ItemData, schema: DbSchema, dbId: String): PageWrite {
        val body = JSONObject().apply {
            put("parent", JSONObject().put("database_id", dbId))
            put("properties", buildProperties(item, schema))
            put("children", buildChildren(item))
        }
        return try {
            val obj = withRetry { JSONObject(api.createPage(body.toString().toJsonBody()).string()) }
            PageWrite(obj.getString("id"), parseIsoMs(obj.optString("last_edited_time")))
        } catch (e: Exception) {
            Log.e(TAG, "createPage 调用失败 db=$dbId body前200=${body.toString().take(200)}", e)
            throw e
        }
    }

    /**
     * 新建页面遇 409 冲突时，按原文链接在目标库内找回已存在的页面。
     * 用于自愈「历史假失败」：页面其实已写入 Notion，但客户端因 409 异常未回写 notion_page_id，
     * 导致本地标记为 FAILED；下次同步又发「新建」请求而持续 409。找到已存在页即复用并原地 PATCH。
     * 命中返回 pageId，未命中 / 出错返回 null。
     */
    private suspend fun findExistingPageByUrl(dbId: String, schema: DbSchema, url: String): String? {
        if (url.isBlank()) return null
        return try {
            withRetry {
                val prop = schema.find(NotionSchema.PROP_DEEPLINK, "url") ?: return@withRetry null
                val filter = JSONObject().put("property", prop).put("url", JSONObject().put("equals", url))
                val body = JSONObject().put("filter", filter).put("page_size", 1)
                val resp = JSONObject(api.queryDatabase(dbId, body.toString().toJsonBody()).string())
                val arr = resp.optJSONArray("results")
                if (arr != null && arr.length() > 0) {
                    arr.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "findExistingPageByUrl 失败 db=$dbId url=$url", e)
            null
        }
    }

    /**
     * 建页前主动按 contentId 查重（三方同步 P2 的「去重建页」核心）。
     *
     * 背景：Notion 对「标题/正文相同」的页面**不会**返回 409，因此双设备各自同步同一 contentId 会
     * 静默建出两页，`findExistingPageByUrl` 的 409 兜底形同虚设。这里在决定「新建」**之前**先按
     * [NotionSchema.PROP_CONTENT_ID]（rich_text equals）过滤一趟 queryDatabase，命中即复用该页、原地更新，
     * 从根上杜绝重复页。两设备按 contentId 去重后都会解析到同一个 Notion 页，故 noton_page_id 天然一致。
     *
     * @return 已存在页的 id；未命中 / 出错 / 列不存在返回 null。
     */
    private suspend fun findExistingPageByContentId(dbId: String, schema: DbSchema, contentId: String): String? {
        if (contentId.isBlank()) return null
        return try {
            withRetry {
                val prop = schema.find(NotionSchema.PROP_CONTENT_ID, "rich_text") ?: return@withRetry null
                val filter = JSONObject().put("property", prop)
                    .put("rich_text", JSONObject().put("equals", contentId))
                val body = JSONObject().put("filter", filter).put("page_size", 1)
                val resp = JSONObject(api.queryDatabase(dbId, body.toString().toJsonBody()).string())
                val arr = resp.optJSONArray("results")
                if (arr != null && arr.length() > 0) {
                    arr.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "findExistingPageByContentId 失败 db=$dbId contentId=$contentId", e)
            null
        }
    }

    /**
     * 属性级重推：仅 PATCH 结构化属性（不含正文 children），用于补齐 Notion 新增/变更的列。
     * 请求量最小（每篇 1 次 PATCH），且幂等——重复跑也不会建重复对象或重复正文。
     */
    private suspend fun updatePageProps(item: ItemData, schema: DbSchema, pageId: String): Long {
        val obj = withRetry {
            JSONObject(
                api.updatePageProperties(
                    pageId,
                    JSONObject().put("properties", buildProperties(item, schema)).toString().toJsonBody(),
                ).string(),
            )
        }
        return parseIsoMs(obj.optString("last_edited_time"))
    }

    /**
     * 原地覆盖更新已存在的 Notion 页面（去重核心）：
     * 1) PATCH 结构化属性；
     * 2) 拉取并逐个删除旧 children；
     * 3) 追加新 children。
     * 返回原 pageId（保持不变）。
     */
    private suspend fun updatePage(item: ItemData, schema: DbSchema, pageId: String): PageWrite {
        // PATCH 属性时取回 last_edited_time，作为本次写入的远端时间线
        val obj = withRetry {
            JSONObject(
                api.updatePageProperties(
                    pageId,
                    JSONObject().put("properties", buildProperties(item, schema)).toString().toJsonBody(),
                ).string(),
            )
        }
        val t = parseIsoMs(obj.optString("last_edited_time"))
        // 列出旧子块并逐个删除（Notion 无批量删除接口）
        val oldIds = mutableListOf<String>()
        withRetry {
            val resp = JSONObject(api.getBlockChildren(pageId, 100).string())
            val arr = resp.optJSONArray("results") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val id = arr.optJSONObject(i)?.optString("id", "").orEmpty()
                if (id.isNotBlank()) oldIds.add(id)
            }
        }
        // 并发删除旧块（受信号量限流；DELETE 幂等，失败由 withRetry 重试）
        val deleteSem = Semaphore(DELETE_CONCURRENCY)
        coroutineScope {
            oldIds.map { id -> async { deleteSem.withPermit { withRetry { api.deleteBlock(id) } } } }.awaitAll()
        }
        withRetry {
            api.appendBlockChildren(
                pageId,
                JSONObject().put("children", buildChildren(item)).toString().toJsonBody(),
            )
        }
        return PageWrite(pageId, t)
    }

    /** 一次 Notion 页面写操作的返回值：pageId + 写入后的 last_edited_time(ms)，供双向同步记录远端时间线 */
    private data class PageWrite(val pageId: String, val lastEditedMs: Long)

    /** 单条同步结果——Updated 表示真正写入了 Notion，Skipped 表示正文未变已短路跳过重写 */
    private sealed interface SyncOutcome {
        val pageId: String
        val bodyHash: String
        /** 本次写入后 Notion 页面的 last_edited_time(ms)；Skipped 为 0（没写远端，保留原值） */
        val remoteEditedAt: Long
        data class Updated(
            override val pageId: String,
            override val bodyHash: String,
            override val remoteEditedAt: Long,
            val action: PushAction,
        ) : SyncOutcome
        data class Skipped(
            override val pageId: String,
            override val bodyHash: String,
            override val remoteEditedAt: Long,
        ) : SyncOutcome
    }

    /** 单条推送的具体动作，用于概览归类 */
    private enum class PushAction { CREATE, UPDATE, PROP_BACKFILL }

    /** 对正文 children 计算稳定哈希，用于判断「正文是否真的变了」从而短路跳过重写 */
    private fun computeBodyHash(item: ItemData): String =
        Integer.toHexString(buildChildren(item).toString().hashCode())

    /**
     * 单条同步的「建 or 更」决策：
     * - 普通同步且正文未变 → 跳过重写（短路）；
     * - 普通重推且正文变了 → 原地 PATCH 覆盖更新（去重，pageId 不变）；
     * - 强制全量重推 → 归档旧页 + 新建（每篇仅 2 请求），pageId 更新；
     * - 未记录 notionPageId / 旧页已 404 → 新建以自愈。
     *
     * 另有一种分库特有的情况：条目此前同步到的是**别的库**（比如旧的单一大库，
     * 或用户换过容器页）。这时不能原地更新——那样内容会留在旧库里，
     * 必须在正确的按天子库里重新建页，所以 dbId 不一致时一律走新建。
     */
    private suspend fun syncItemPage(
        item: ItemData,
        schema: DbSchema,
        dbId: String,
        forceResync: Boolean,
    ): SyncOutcome {
        val newBodyHash = computeBodyHash(item)
        val existing = item.notionPageId?.takeIf { it.isNotBlank() && it != "null" }
        val sameDb = item.notionDatabaseId.isNullOrBlank() || item.notionDatabaseId == dbId
        return try {
            // 属性级重推（PATCH 重推）：仅因 Notion 新增/变更列而补写属性，正文未变 → 只 PATCH 属性、不重建正文。
            // 必须在「正文未变短路跳过」分支之前拦截，否则会直接 Skipped 而不写新列（这正是「是否代表」缺值的根因）。
            if (item.syncStatus == SyncStatus.PROP_RESNC && existing != null && sameDb) {
                Log.i(TAG, "syncItemPage: 属性级重推（仅补写属性）contentId=${item.contentId} pageId=$existing")
                val bodyHash = item.syncedBodyHash ?: newBodyHash
                val t = updatePageProps(item, schema, existing)
                return SyncOutcome.Updated(existing, bodyHash, t, PushAction.PROP_BACKFILL)
            }
            when {
                existing == null -> {
                    // 建页前先按 contentId 主动查重：避免双设备各自同步同一 contentId 时 Notion 静默建两页
                    // （Notion 对相同标题/正文不返回 409，409 兜底不可靠）。
                    val dup = findExistingPageByContentId(dbId, schema, item.contentId)
                    if (dup != null) {
                        Log.i(TAG, "syncItemPage: 按 contentId 命中已存在页，原地更新 pageId=$dup contentId=${item.contentId}")
                        val pw = updatePage(item, schema, dup)
                        SyncOutcome.Updated(dup, newBodyHash, pw.lastEditedMs, PushAction.UPDATE)
                    } else {
                        Log.i(TAG, "syncItemPage: 新建页面 contentId=${item.contentId}")
                        val pw = createPage(item, schema, dbId)
                        SyncOutcome.Updated(pw.pageId, newBodyHash, pw.lastEditedMs, PushAction.CREATE)
                    }
                }
                !sameDb -> {
                    Log.i(
                        TAG,
                        "syncItemPage: 条目原属库 ${item.notionDatabaseId} 与目标分库 $dbId 不同，" +
                            "在目标分库重新建页 contentId=${item.contentId}",
                    )
                    // 同样先查重，避免换容器页/分库后重复建页
                    val dup = findExistingPageByContentId(dbId, schema, item.contentId)
                    if (dup != null) {
                        val pw = updatePage(item, schema, dup)
                        SyncOutcome.Updated(dup, newBodyHash, pw.lastEditedMs, PushAction.UPDATE)
                    } else {
                        val pw = createPage(item, schema, dbId)
                        SyncOutcome.Updated(pw.pageId, newBodyHash, pw.lastEditedMs, PushAction.CREATE)
                    }
                }
                !forceResync && item.syncedBodyHash == newBodyHash -> {
                    Log.i(TAG, "syncItemPage: 正文未变，短路跳过重写 contentId=${item.contentId} pageId=$existing")
                    SyncOutcome.Skipped(existing, newBodyHash, 0)
                }
                forceResync -> {
                    val pw = archiveAndRecreate(item, schema, existing, dbId)
                    SyncOutcome.Updated(pw.pageId, newBodyHash, pw.lastEditedMs, PushAction.CREATE)
                }
                else -> {
                    Log.i(TAG, "syncItemPage: 原地覆盖更新 contentId=${item.contentId} pageId=$existing")
                    val pw = updatePage(item, schema, existing)
                    SyncOutcome.Updated(pw.pageId, newBodyHash, pw.lastEditedMs, PushAction.UPDATE)
                }
            }
        } catch (e: SyncHttpError) {
            if (e.code == 409 && existing == null) {
                // 新建遇 409：页面已存在（历史假失败），按原文链接找回并复用，原地 PATCH 属性
                val reused = findExistingPageByUrl(dbId, schema, item.url)
                if (reused != null) {
                    Log.i(TAG, "syncItemPage: 新建 409，复用已存在页 pageId=$reused contentId=${item.contentId}")
                    val pw = updatePage(item, schema, reused)
                    return SyncOutcome.Updated(reused, newBodyHash, pw.lastEditedMs, PushAction.UPDATE)
                }
            }
            if (e.code == 404 && existing != null) {
                // 页面被手动删除 → 退化为新建（注意：库不存在的 404 已在 resolveShard 阶段拦掉）
                Log.w(TAG, "syncItemPage: 原页面 404，退化新建 contentId=${item.contentId} oldPageId=$existing")
                val pw = createPage(item, schema, dbId)
                SyncOutcome.Updated(pw.pageId, newBodyHash, pw.lastEditedMs, PushAction.CREATE)
            } else throw e
        }
    }

    /**
     * 强制全量重推时，先把旧页归档（archived=true，最佳努力，失败/404 忽略），再新建一页。
     * 每篇仅 2 次请求（archive + create），相比 updatePage 的 ~18 次请求是量级提速。
     * 代价：pageId 会变，旧页进入 Notion 回收站；故仅用于强制重推。
     */
    private suspend fun archiveAndRecreate(item: ItemData, schema: DbSchema, pageId: String, dbId: String): PageWrite {
        runCatching {
            withRetry {
                api.updatePageProperties(pageId, JSONObject().put("archived", true).toString().toJsonBody())
            }
        }.onFailure { e ->
            Log.w(TAG, "archiveAndRecreate: 归档旧页失败（忽略，直接新建） contentId=${item.contentId}", e)
        }
        return createPage(item, schema, dbId)
    }

    // ===================== 双向同步：拉取远端 =====================

    /**
     * 双向同步「拉取」阶段：推送完成后，把已映射到 Notion 的本地行按子库分组，
     * 每个子库一次 queryDatabase 拉全量页面（含 last_edited_time 与当前属性），
     * 对每篇比较本地编辑时间(local_edited_at)与远端 last_edited_time——远端更新则回拉本地（LWW）。
     *
     * 设计要点：
     * - 只拉结构化属性（properties），正文/摘要不在 properties 里，v1 刻意不回拉；
     * - 本地比远端新（或等于）的条目不动；仅「远端更新」才写回，保持幂等、请求量最小；
     * - 远端页面已删除的：v1 不自动重建（避免静默覆盖），留待「强制全量重推」自愈；
     * - 全部请求经 withRetry 限流（4 并发），不会触发 429 风暴。
     *
     * @return 拉回篇数 与 「两端各自改过的真冲突」篇数（LWW 取较新者覆盖，仍计入拉回）
     */
    /**
     * 双向同步「拉取」阶段（P3 改造：支持冷启动 / 跨设备合并重建本地 DB）。
     *
     * 与旧版最大区别：不再依赖 [ArchiveStore.loadNotionMapped]（仅已映射行）来枚举分库，
     * 而是直接枚举所有已登记分库（[ArchiveStore.listShards]），对每个远端页：
     *  - 解析远端 contentId（PROP_CONTENT_ID）：
     *    - 命中墓碑 → 跳过（防复活）；
     *    - 已映射到同 pageId → LWW（[ArchiveStore.markPulled]，同旧逻辑）；
     *    - 本地无此 contentId → 新建本地行（[ArchiveStore.insertFromRemote]，冷启动/跨设备合并）；
     *    - 同 contentId 不同 pageId → 归一到最新页（P7 合并，这里先更新映射 + LWW）；
     *  - 远端标记 _del（PROP_DELETED=true）→ 删本地行 + 写墓碑（P4 删除同步拉取侧）。
     * 评论双向同步挂载在每页（无论新建或已映射都扫）。
     *
     * @return 拉取覆盖篇数 to 冲突篇数（新建/删除篇数计入 [SyncItemReport] 概览，不在此返回）。
     */
    /** 拉取阶段单个分库的局部结果，供并行 coroutineScope 汇总（避免共享可变状态竞争）。 */
    private data class ShardPullResult(
        val pulled: Int,
        val conflicts: Int,
        val created: Int,
        val delPulled: Int,
        val reports: List<SyncItemReport>,
    )

    private suspend fun pullFromRemote(
        push: PushCounts,
        reports: MutableList<SyncItemReport>,
        onProgress: suspend (SyncProgress) -> Unit,
        singleContentId: String? = null,
        fullPullDbIds: Set<String> = emptySet(),
    ): Pair<Int, Int> {
        val shards = store.listShards()
        val allDbIds = shards.mapNotNull { it.databaseId.ifBlank { null } }.distinct()
        // 单篇调试模式：目标文章只可能在自己那一天的子库里，没必要把 34 个子库全 query 一遍
        // （全量枚举 ≈ 100s，定位到单库 ≈ 3s）。解析不出分库时退化为全量枚举，保证行为不变。
        val dbIds = if (singleContentId != null) {
            val key = store.shardKeyForContent(singleContentId)
            val only = key?.let { store.shardDatabaseId(it) }
            if (only.isNullOrBlank()) {
                Log.w(TAG, "单篇同步：contentId=$singleContentId 未解析到分库（shardKey=$key），退化为枚举全部分库")
                allDbIds
            } else {
                Log.i(TAG, "单篇同步：pull 仅覆盖分库 $only（shardKey=$key）")
                listOf(only)
            }
        } else {
            allDbIds
        }
        // 增量拉取水印：记下「上次成功拉取的开始时间」，本次只拉该时间之后变更过的页，
        // 把拉取请求量从 O(总篇数) 降到 O(本次变更数)——文章越多，日常同步越不会变慢。
        // 首次（无记录）或水印异常时 since=0，走全量拉取；其余走增量（上游留 2 分钟缓冲规避分钟精度漏抓）。
        val lastPullTs = store.meta(META_LAST_PULL_TS)?.toLongOrNull() ?: 0L
        val pullStart = System.currentTimeMillis()
        val since = if (lastPullTs > 0) (lastPullTs - WATERMARK_BUFFER_MS).coerceAtLeast(0L) else 0L
        val isFullPull = since <= 0L
        // 单篇调试模式：按 contentId 精确过滤即可，不叠加时间水印（否则可能漏掉目标页）
        val fetchSince = if (singleContentId != null) 0L else since
        if (dbIds.isEmpty()) return 0 to 0
        // 已映射行按 contentId 建索引，供 LWW 比较（冷启动平板上为空，但不影响新建分支）
        val mappedByContent = store.loadNotionMapped().associateBy { it.contentId }
        var pulled = 0
        var conflicts = 0
        var created = 0
        var delPulled = 0
        val dbTotal = dbIds.size
        val completed = AtomicInteger(0)
        if (dbTotal > 0) {
            // 立刻报 0 进度：否则第一批 4 个分库完成前约有 2.3s 静默，UI 看起来像在「读取待同步条目」卡住。
            onProgress(
                SyncProgress.Running(
                    "正在从 Notion 拉取（0/$dbTotal 个子库处理中）…",
                    0, dbTotal, push.synced, push.skipped, push.failed,
                ),
            )
        }
        // 增量拉取并行化：34 个子库原本串行（每库一次 queryDatabase 网络往返 ~2.3s），
        // 现用 coroutineScope + async 并发执行，每片网络请求仍受全局 reqSem(4) 限流，
        // 不会突破 Notion 限速（429 由 withRetry 兜底）。各片局部计数，awaitAll 后汇总，避免共享可变状态竞争。
        val shardResults = coroutineScope {
            dbIds.map { dbId ->
                async {
                    currentCoroutineContext().ensureActive()
                    var pPulled = 0
                    var pConflicts = 0
                    var pCreated = 0
                    var pDelPulled = 0
                    val pReports = mutableListOf<SyncItemReport>()
                    // 进度回报放到「分库处理完成时」（而非开始时）：并行下 34 个库分批完成，
                    // UI 的「X/34」才会随时间真实推进。若放在开始处，34 个 async 会瞬间同时触发，
                    // StateFlow 合并后 UI 直接跳到 34/34 然后卡住，表现为「进展文字一直不变」。
                    suspend fun reportProgress() {
                        val done = completed.incrementAndGet()
                        onProgress(
                            SyncProgress.Running(
                                "正在从 Notion 拉取（$done/$dbTotal 个子库处理中）…已拉回 $pPulled 篇、新建 $pCreated 篇、删 $pDelPulled 篇",
                                done, dbTotal, push.synced, push.skipped, push.failed,
                            ),
                        )
                    }
                    // 分库级容错：单个分库拉取失败（限流/瞬时 5xx 耗尽内部重试）绝不能中断整个冷启动重建，
                    // 否则后续分库的几百篇文章会被静默丢弃。外层再兜底重试若干次，仍失败则仅跳过本片并记入概览。
                    var remote: Map<String, Pair<Long, JSONObject>>? = null
                    var lastErr: Throwable? = null
                    repeat(SHARD_FETCH_MAX_RETRY) { attempt ->
                        try {
                            // 新发现的分库（fullPullDbIds）从没被增量水印覆盖过，对其全量拉（since=0），
                            // 否则库里 last_edited_time 早于水印的存量文章会被 on_or_after 过滤掉。
                            val shardSince = if (dbId in fullPullDbIds) 0L else fetchSince
                            remote = fetchRemotePages(dbId, singleContentId, shardSince)
                            lastErr = null
                            return@repeat
                        } catch (e: Exception) {
                            lastErr = e
                            Log.w(TAG, "pullFromRemote: 分库 $dbId 拉取失败（第 ${attempt + 1}/$SHARD_FETCH_MAX_RETRY 次，将重试）", e)
                            if (attempt < SHARD_FETCH_MAX_RETRY - 1) runCatching { delay(SHARD_FETCH_BACKOFF_MS) }
                        }
                    }
                    if (remote == null) {
                        Log.e(TAG, "pullFromRemote: 分库 $dbId 多次拉取仍失败，跳过本片（其余分库继续）", lastErr)
                        pReports.add(SyncItemReport(dbId, "", "分库拉取失败", SyncAction.PULL_SKIPPED))
                        reportProgress()
                        return@async ShardPullResult(0, 0, 0, 0, pReports)
                    }
                    if (remote!!.isEmpty()) {
                        Log.w(TAG, "pullFromRemote: 分库 $dbId 拉取远端状态为空，跳过本片")
                        reportProgress()
                        return@async ShardPullResult(0, 0, 0, 0, pReports)
                    }
                    for ((pageId, pair) in remote) {
                        val (remoteTime, props) = pair
                        val contentId = richTextOf(propObj(props, NotionSchema.PROP_CONTENT_ID, "rich_text"))
                        // 单篇同步调试模式：跳过非目标页（配合 fetchRemotePages 的 content_id 过滤，几乎不下载无关页）
                        if (singleContentId != null && contentId != singleContentId) continue
                        // —— P4 删除同步（拉取侧）：远端标记删除 → 删本地 + 墓碑防复活 ——
                        val remoteDeleted = boolOf(propObj(props, NotionSchema.PROP_DELETED, "checkbox")) == true
                        if (remoteDeleted && contentId != null) {
                            if (store.contains(contentId)) store.deleteLocal(contentId)
                            store.insertTombstone(contentId, "remote_del")
                            pDelPulled++
                            pReports.add(SyncItemReport(contentId, "", "", SyncAction.PULL_DELETE))
                            continue
                        }
                        if (contentId == null) {
                            Log.w(TAG, "pullFromRemote: 页 $pageId 无 content_id（旧存量页），跳过重建")
                            continue
                        }
                        // 墓碑守门：防止被另一端陈旧的 push 复活
                        if (store.isTombstoned(contentId)) continue
                        val patch = parseRemoteItem(props)
                        val local = mappedByContent[contentId]
                        if (local != null && local.notionPageId == pageId) {
                            // 门禁基准是 notionLastEditedAt（本地已知的远端版本），不是 localEditedAt：
                            //  · 用 localEditedAt 会让「本地编辑过的行」在本地时间戳追上之前永远拉不到远端更新；
                            //  · Notion 的 last_edited_time 只有分钟精度，两台设备在同一分钟内先后推送时，
                            //    远端时间戳与本地记录完全相等，用 > 会漏掉后一次改动（⑤-C 实测手机 high 卡在 0）。
                            // 故放宽为 >=，靠 markPulled 内部全字段比对兜底：无实际差异不写库、返回 false 不计数。
                            if (remoteTime < local.notionLastEditedAt) continue
                            val isConflict = local.notionLastEditedAt != 0L &&
                                local.localEditedAt > local.notionLastEditedAt && remoteTime > local.notionLastEditedAt
                            val changed = store.markPulled(contentId, patch, remoteTime)
                            if (changed) {
                                pPulled++
                                pReports.add(
                                    SyncItemReport(
                                        contentId = contentId,
                                        title = local.title.ifBlank { "（无标题）" },
                                        sourceName = local.sourceName,
                                        action = if (isConflict) SyncAction.PULL_CONFLICT else SyncAction.PULL,
                                    ),
                                )
                                if (isConflict) pConflicts++
                            }
                        } else if (local != null && local.notionPageId != pageId) {
                            // 同 contentId 但本地映射到旧 pageId：归一到最新页（P7 合并重复页，这里先收敛映射）
                            store.markSynced(contentId, pageId, dbId, "", "", remoteTime)
                            if (remoteTime >= local.notionLastEditedAt && store.markPulled(contentId, patch, remoteTime)) {
                                pPulled++
                            }
                        } else {
                            // 本地无此 contentId：冷启动 / 跨设备合并 → 新建本地行
                            store.insertFromRemote(contentId, pageId, dbId, patch, remoteTime)
                            pCreated++
                            pReports.add(
                                SyncItemReport(
                                    contentId = contentId,
                                    title = patch.title ?: "（无标题）",
                                    sourceName = patch.sourceName ?: "",
                                    action = SyncAction.PULL_CREATE,
                                ),
                            )
                        }
                        // 评论双向同步：仅对「评论活跃」页或首次全量拉取才扫，避免对无评论页做无谓的本地查询/网络往返。
                        // （syncComments 内部已有 hasAnyComments 早退；此处把意图显式提到调用处，配合增量水印后遍历页本就很少）
                        val needComments = singleContentId != null || isFullPull || store.hasAnyComments(contentId)
                        if (needComments) {
                            runCatching { syncComments(pageId, contentId) }
                                .onFailure { Log.w(TAG, "syncComments($contentId) 异常（忽略，不影响文章同步）", it) }
                        }
                    }
                    reportProgress()
                    ShardPullResult(pPulled, pConflicts, pCreated, pDelPulled, pReports)
                }
            }.awaitAll()
        }
        for (r in shardResults) {
            pulled += r.pulled
            conflicts += r.conflicts
            created += r.created
            delPulled += r.delPulled
            reports.addAll(r.reports)
        }
        Log.i(TAG, "pullFromRemote: 拉回 $pulled 篇，新建 $created 篇，远端删除 $delPulled 篇，冲突 $conflicts 篇（覆盖 $dbTotal 个子库），fetchSince=$fetchSince")
        // 记录拉取水印：下次同步只拉 fetchSince 之后变更的页。单篇调试模式不推进水印（只覆盖一个分库，推进会漏掉其余）。
        if (singleContentId == null) {
            runCatching { store.setMeta(META_LAST_PULL_TS, pullStart.toString()) }
                .onFailure { Log.w(TAG, "pullFromRemote: 记录拉取水印失败（下次全量拉取兜底）", it) }
        }
        return pulled to conflicts
    }

    /**
     * 评论双向同步（挂载在双向同步 pull 阶段的 per-page 循环里，每篇已映射文章都扫）：
     *  - Push 侧（本地 → 云端）：LOCAL_NEW 直接创建；EDIT_PENDING 删旧+重建（Notion 不支持改评论正文）；
     *    DELETED_LOCAL 删云端（无 notion_comment_id 的 LOCAL_NEW 删除仅本地清理）。
     *  - Pull 侧（云端 → 本地）：GET 页面评论，按 notion_comment_id 对账；远端新评论入库(actor=CLOUD，本地只读)，
     *    远端消失的本地 SYNCED 评论标 tombstone。
     * 云端他人创建的评论（actor=CLOUD）本地只读，不删不改。整段失败不影响文章同步。
     */
    /** 评论双向同步的单篇统计，供 pullFromRemote 汇总日志（便于排查「静默不同步」）。 */
    private data class CommentSyncStat(val pushed: Int, val pulled: Int, val updated: Int, val failed: Int)

    /**
     * 评论双向同步（挂载在双向同步 pull 阶段的 per-page 循环里，每篇已映射文章都扫，且不受正文变更门禁影响）：
     *  - Push 侧（本地 → 云端）：LOCAL_NEW 直接创建；EDIT_PENDING 删旧+重建（Notion 不支持改评论正文）；
     *    DELETED_LOCAL 删云端（无 notion_comment_id 的 LOCAL_NEW 删除仅本地清理）。
     *  - Pull 侧（云端 → 本地）：GET 页面评论，按 notion_comment_id 对账；远端新评论入库(actor=CLOUD，本地只读)，
     *    远端消失的本地 SYNCED 评论标 tombstone。
     * 云端他人创建的评论（actor=CLOUD）本地只读，不删不改。
     * @return 本次统计：推送成功数 / 拉取新增数 / 失败数（失败已就地 Log，不影响调用方）。
     */
    /**
     * 删除同步（P4 推送侧）：把本地 DELETED_LOCAL 行推到云端。
     * 对每行 PATCH 其 Notion 页的 _del（PROP_DELETED）checkbox=true，成功后物理删本地行 + 写墓碑防复活。
     * 失败的行保留 DELETED_LOCAL，下次同步重试（不会重复 PATCH，因为未 markDeletedSynced）。
     */
    private suspend fun pushDeleted(reports: MutableList<SyncItemReport>) {
        val rows = store.pendingDeleted()
        if (rows.isEmpty()) return
        var ok = 0
        var fail = 0
        for (row in rows) {
            val pageId = row.notionPageId
            val dbId = row.notionDatabaseId
            if (pageId.isNullOrBlank() || dbId.isNullOrBlank()) {
                // 理论上不会出现：DELETED_LOCAL 由 markDeletedLocal 对「已上云」行置位，notion_page_id/db_id 非空
                Log.w(TAG, "pushDeleted: 跳过无映射行 ${row.contentId}")
                continue
            }
            runCatching {
                val schema = schemaFor(dbId)
                val col = schema.find(NotionSchema.PROP_DELETED, "checkbox")
                if (col == null) {
                    Log.w(TAG, "pushDeleted: 分库 $dbId 无 ${NotionSchema.PROP_DELETED} 列，跳过 ${row.contentId}")
                    return@runCatching
                }
                withRetry {
                    api.updatePageProperties(
                        pageId,
                        JSONObject().put("properties", JSONObject().put(col, checkboxProp(true))).toString().toJsonBody(),
                    ).string()
                }
                store.markDeletedSynced(row.contentId)
                reports.add(SyncItemReport(contentId = row.contentId, title = "", sourceName = "", action = SyncAction.PUSH_DELETE))
                ok++
            }.onFailure {
                Log.e(TAG, "pushDeleted 失败 ${row.contentId}", it)
                fail++
            }
        }
        Log.i(TAG, "pushDeleted: 成功 $ok 失败 $fail")
    }

    private suspend fun syncComments(pageId: String, contentId: String): CommentSyncStat {
        if (pageId.isBlank()) return CommentSyncStat(0, 0, 0, 0)
        // 早退优化：该文章本地无任何评论行时，既不推送也无从发现云端评论，跳过整个网络往返，
        // 避免对全量已映射文章（上千篇）逐篇调用 listComments 触发 Notion 限速风暴。
        // 一旦用户在某篇加了本地评论或曾拉回云端评论，本文即进入「评论活跃」集合，后续正常双向同步。
        if (!store.hasAnyComments(contentId)) return CommentSyncStat(0, 0, 0, 0)
        var pushed = 0
        var failed = 0
        // —— Push 侧：本地 → 云端 ——
        store.localNewComments(contentId).forEach { c ->
            runCatching {
                val r = withRetry { api.createComment(commentBody(pageId, c.body)) }
                store.markCommentSynced(c.id, commentIdOf(r), pageId)
                pushed++
            }.onFailure { failed++; Log.w(TAG, "syncComments: 推送新评论失败($contentId)", it) }
        }
        store.editPendingComments(contentId).forEach { c ->
            runCatching {
                // 删旧（云端已无则 404，忽略）；再重建
                val oldId = c.notionCommentId
                if (!oldId.isNullOrBlank()) runCatching { withRetry { api.deleteComment(oldId) } }
                val r = withRetry { api.createComment(commentBody(pageId, c.body)) }
                store.markCommentSynced(c.id, commentIdOf(r), pageId)
                pushed++
            }.onFailure { failed++; Log.w(TAG, "syncComments: 编辑重建评论失败($contentId)", it) }
        }
        store.deletedLocalComments(contentId).forEach { c ->
            runCatching {
                val delId = c.notionCommentId
                if (!delId.isNullOrBlank()) runCatching { withRetry { api.deleteComment(delId) } }
                store.markCommentDeletedSynced(c.id)
            }.onFailure { failed++; Log.w(TAG, "syncComments: 删除云端评论失败($contentId)", it) }
        }
        // —— Pull 侧：云端 → 本地 ——
        val cloud = runCatching { parseComments(withRetry { api.listComments(pageId) }.string()) }
            .getOrElse { emptyList() }
        val cloudIds = cloud.map { it.first }.toSet()
        var pulled = 0
        var updated = 0
        for ((cid, text) in cloud) {
            Log.d(TAG, "syncComments pull: cid=$cid body=${text.take(24)}")
            when (val act = store.upsertCloudComment(contentId, pageId, cid, text)) {
                1 -> pulled++   // 云端新增评论 → 本地插入
                2 -> updated++  // 云端已有评论被编辑 → 本地更新文本
            }
        }
        store.syncedComments(contentId).forEach { lc ->
            val nid = lc.notionCommentId
            if (!nid.isNullOrBlank() && nid !in cloudIds) {
                store.markCommentDeletedLocal(lc.id)
            }
        }
        if (pushed > 0 || pulled > 0 || updated > 0 || failed > 0)
            Log.i(TAG, "syncComments($contentId): 推送=$pushed 拉取(新增)=$pulled 更新=$updated 失败=$failed")
        return CommentSyncStat(pushed, pulled, updated, failed)
    }

    /**
     * 拉取单个子库的全部页面（翻页），返回 pageId → (last_edited_time_ms, properties)。
     * 每个子库一次 queryDatabase（page_size=100）起步，has_more 才翻页，翻页上限复用 MAX_DISCOVERY_PAGES。
     */
    private suspend fun fetchRemotePages(
        dbId: String,
        filterContentId: String? = null,
        sinceMs: Long = 0L,
    ): Map<String, Pair<Long, JSONObject>> {
        val out = LinkedHashMap<String, Pair<Long, JSONObject>>()
        // 单篇同步调试：按 content_id 精确过滤，避免拉取 34 分库 × 上千页（加速 ⑤-B 等单篇回归）。
        val contentFilter = if (!filterContentId.isNullOrBlank()) {
            val propName = runCatching { schemaFor(dbId).find(NotionSchema.PROP_CONTENT_ID, "rich_text") }.getOrNull()
            if (propName != null) {
                JSONObject().apply {
                    put("property", propName)
                    put("rich_text", JSONObject().put("equals", filterContentId))
                }
            } else null
        } else null
        // 增量拉取水印：只拉 last_edited_time >= sinceMs 的页（规避 Notion 分钟精度，上游已留 2 分钟缓冲）。
        val tsFilter = if (sinceMs > 0) {
            JSONObject().apply {
                put("timestamp", "last_edited_time")
                put("last_edited_time", JSONObject().put("on_or_after", isoUtc(sinceMs)))
            }
        } else null
        // 同时有 contentId 与 时间 两个条件时用 and 组合，否则取其一（都无 → 不过滤 = 全量）
        val filter = when {
            contentFilter != null && tsFilter != null ->
                JSONObject().put("and", JSONArray().put(contentFilter).put(tsFilter))
            contentFilter != null -> contentFilter
            tsFilter != null -> tsFilter
            else -> null
        }
        var cursor: String? = null
        var page = 0
        do {
            val body = JSONObject().apply {
                put("page_size", 100)
                if (cursor != null) put("start_cursor", cursor)
                if (filter != null) put("filter", filter)
            }
            val resp = withRetry {
                JSONObject(api.queryDatabase(dbId, body.toString().toJsonBody()).string())
            }
            val arr = resp.optJSONArray("results") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val id = p.optString("id", "")
                if (id.isBlank()) continue
                val t = parseIsoMs(p.optString("last_edited_time", ""))
                val props = p.optJSONObject("properties") ?: JSONObject()
                out[id] = t to props
            }
            cursor = if (resp.optBoolean("has_more")) {
                resp.optString("next_cursor").takeIf { it.isNotBlank() && it != "null" }
            } else null
        } while (cursor != null && ++page < MAX_QUERY_PAGES)
        return out
    }

    /**
     * 把 Notion 页面 properties 反向解析为 [NotionRemotePatch]。
     * 与 [buildProperties] 严格镜像：按候选名 + 类型找列，缺失/类型不符的字段留 null（不覆盖本地）。
     */
    private fun parseRemoteItem(props: JSONObject): NotionRemotePatch {
        // 标题属性名：扫描 properties 找 type==title
        var titleProp: String? = null
        props.keys().forEach { k ->
            if (props.optJSONObject(k)?.optString("type") == "title") titleProp = k
        }
        // 注意：Notion 的 title 类型属性，正文数组挂在 "title" 键下（与普通 rich_text 的 "rich_text" 不同），
        // 因此不能用 richTextOf（只读 rich_text），必须用 titleTextOf 读 "title" 数组，否则冷启动重建的标题全为空。
        val title = titleProp?.let { titleTextOf(props.optJSONObject(it)) }

        val tagsJson = multiSelectNamesOf(propObj(props, NotionSchema.PROP_TAGS, "multi_select"))
            .takeIf { it.isNotEmpty() }?.let { JSONArray(it).toString() }
        val keywordsJson = multiSelectNamesOf(propObj(props, NotionSchema.PROP_KEYWORDS, "multi_select"))
            .takeIf { it.isNotEmpty() }?.let { JSONArray(it).toString() }

        return NotionRemotePatch(
            contentId = richTextOf(propObj(props, NotionSchema.PROP_CONTENT_ID, "rich_text")),
            title = title,
            sourceName = richTextOf(propObj(props, NotionSchema.PROP_SOURCE, "rich_text")),
            sourceType = selectNameOf(propObj(props, NotionSchema.PROP_SOURCE_TYPE, "select")),
            // 从 Notion select 读回的是中文 label（如「订阅源」），写回本地前必须先归一化成枚举 key，
            // 否则会再次污染 origin_category 列、制造第二个「订阅源」分组。
            originCategory = OriginCategory.fromKey(selectNameOf(propObj(props, NotionSchema.PROP_ORIGIN_CATEGORY, "select"))).key,
            focusTitle = selectNameOf(propObj(props, NotionSchema.PROP_FOCUS, "select")),
            publishedAt = dateSecOf(propObj(props, NotionSchema.PROP_CAPTURED_AT, "date")),
            tags = tagsJson,
            url = urlOf(propObj(props, NotionSchema.PROP_DEEPLINK, "url")),
            keywords = keywordsJson,
            contentType = selectNameOf(propObj(props, NotionSchema.PROP_CONTENT_TYPE, "select")),
            contentDepth = selectNameOf(propObj(props, NotionSchema.PROP_CONTENT_DEPTH, "select")),
            tone = selectNameOf(propObj(props, NotionSchema.PROP_TONE, "select")),
            style = selectNameOf(propObj(props, NotionSchema.PROP_STYLE, "select")),
            hasAction = boolOf(propObj(props, NotionSchema.PROP_HAS_ACTION, "checkbox")),
            hasStance = boolOf(propObj(props, NotionSchema.PROP_HAS_STANCE, "checkbox")),
            stanceSummary = richTextOf(propObj(props, NotionSchema.PROP_STANCE_SUMMARY, "rich_text")),
            clusterLabel = selectNameOf(propObj(props, NotionSchema.PROP_CLUSTER_TOPIC, "select")),
            clusterSize = numberOf(propObj(props, NotionSchema.PROP_CLUSTER_COUNT, "number")),
            isClusterRep = boolOf(propObj(props, NotionSchema.PROP_IS_CLUSTER_REP, "checkbox")),
            isUserRead = boolOf(propObj(props, NotionSchema.PROP_USER_READ, "checkbox")),
            // 「高价值」是受管列：拉取方向把用户在 Notion 勾的值带回本地（推送方向由 buildProperties 写回）
            isHighValue = boolOf(manualProp(props, "高价值")),
            // 摘要/封面：属性级无损还原（让冷启动平板重建的 articles 与手机内容字段完全一致）
            summary = richTextOf(propObj(props, NotionSchema.PROP_SUMMARY, "rich_text")),
            coverImage = urlOf(propObj(props, NotionSchema.PROP_COVER, "url")),
        )
    }

    /** 在 properties 中按候选名 + 类型定位属性对象（与 DbSchema.find 同语义，但作用于单页 properties） */
    private fun propObj(props: JSONObject, candidates: List<String>, type: String): JSONObject? {
        for (n in candidates) {
            val p = runCatching { props.getJSONObject(n) }.getOrNull() ?: continue
            if (p.optString("type") == type) return p
        }
        return null
    }

    /** 手动维护列（如「高价值」）：按确切列名取 checkbox 对象，类型不符返回 null */
    private fun manualProp(props: JSONObject, name: String): JSONObject? {
        val p = runCatching { props.getJSONObject(name) }.getOrNull() ?: return null
        return if (p.optString("type") == "checkbox") p else null
    }

    private fun richTextOf(p: JSONObject?): String? {
        val arr = p?.optJSONArray("rich_text") ?: return null
        val sb = StringBuilder()
        for (i in 0 until arr.length()) sb.append(arr.optJSONObject(i)?.optString("plain_text").orEmpty())
        return sb.toString().takeIf { it.isNotBlank() }
    }

    /** 读取 Notion title 类型属性的文本：正文数组挂在 "title" 键下（非普通 rich_text 的 "rich_text"）。 */
    private fun titleTextOf(p: JSONObject?): String? {
        val arr = p?.optJSONArray("title") ?: return null
        val sb = StringBuilder()
        for (i in 0 until arr.length()) sb.append(arr.optJSONObject(i)?.optString("plain_text").orEmpty())
        return sb.toString().takeIf { it.isNotBlank() }
    }

    private fun selectNameOf(p: JSONObject?): String? =
        p?.optJSONObject("select")?.optString("name")?.takeIf { it.isNotBlank() }

    private fun multiSelectNamesOf(p: JSONObject?): List<String> {
        val arr = p?.optJSONArray("multi_select") ?: return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { list.add(it) }
        }
        return list
    }

    private fun dateSecOf(p: JSONObject?): Long? {
        val start = p?.optJSONObject("date")?.optString("start") ?: return null
        if (start.isBlank()) return null
        return parseIsoMs(start).let { if (it > 0) it / 1000 else null }
    }

    /**
     * Notion 的 url 属性在「未设置」时返回 `{"url":null}`（JSON null，而非缺失键）。
     * 若用 `optString("url")`，null 会被序列化成字面量字符串 "null" 而非空白，
     * 导致 pull 方向把封面图写成字符串 "null"、与手机端 NULL 不一致。
     * 这里用 `opt` 区分类型：只有真正的非空字符串才保留。
     */
    private fun urlOf(p: JSONObject?): String? {
        val u = p?.opt("url")
        return if (u is String && u.isNotBlank()) u else null
    }

    /** checkbox：属性缺失返回 null（不覆盖本地），存在则返回布尔值 */
    private fun boolOf(p: JSONObject?): Boolean? = if (p == null) null else p.optBoolean("checkbox", false)

    private fun numberOf(p: JSONObject?): Int? {
        if (p == null) return null
        return runCatching { p.optInt("number", -1) }.getOrNull()?.takeIf { it >= 0 }
    }

    /**
     * 解析 Notion 的 ISO-8601 时间戳为毫秒；解析失败返回 0。
     * 兼容两种写法：
     *  - UTC 带 Z：2026-08-05T12:34:56.789Z / 2026-08-05T12:34:56Z（Notion 返回 last_edited_time 的标准格式）
     *  - 本地时区偏移：2026-08-05T20:34:56+08:00（早期 push 用 isoLocal 误写成的格式，冷启动拉取需能还原）
     */
    private fun parseIsoMs(iso: String): Long {
        if (iso.isBlank()) return 0L
        val candidates = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
        )
        for (f in candidates) {
            runCatching {
                val fmt = SimpleDateFormat(f, Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                fmt.parse(iso)?.time
            }.getOrNull()?.let { return it }
        }
        return 0L
    }


    // ===================== 属性 / 正文构造 =====================

    /** 构造页面结构化属性（新建与更新共用） */
    private fun buildProperties(item: ItemData, schema: DbSchema): JSONObject {
        val titleText = (item.title.ifBlank { "SupSub 文章" }).take(RICH_TEXT_LIMIT)
        val properties = JSONObject().apply { put(schema.titleProp, titleObject(titleText)) }
        schema.find(NotionSchema.PROP_SOURCE, "rich_text")?.let {
            properties.put(it, richTextProp(item.sourceName.take(RICH_TEXT_LIMIT)))
        }
        schema.find(NotionSchema.PROP_SOURCE_TYPE, "select")?.let { properties.put(it, selectProp(item.sourceType)) }
        schema.find(NotionSchema.PROP_ORIGIN_CATEGORY, "select")?.let { properties.put(it, selectProp(item.originCategory)) }
        // 关注点 select 列：仅 FOCUS 通道有值（focusTitle 非空）；订阅源/网页集留空，符合「最小方案只记首个关注点」。
        schema.find(NotionSchema.PROP_FOCUS, "select")?.let {
            if (item.focusTitle.isNotBlank()) properties.put(it, selectProp(item.focusTitle))
        }
        // published_at 推送前先截断到分钟，与 Notion date 属性的分钟精度对齐（Notion 自身也会截断，这里显式保证）
        schema.find(NotionSchema.PROP_CAPTURED_AT, "date")?.let {
            properties.put(it, dateProp((item.publishedAtSec * 1000 / 60000) * 60000))
        }
        schema.find(NotionSchema.PROP_TAGS, "multi_select")?.let { properties.put(it, multiSelectProp(item.tags)) }
        schema.find(NotionSchema.PROP_DEEPLINK, "url")?.let { properties.put(it, urlProp(item.url)) }
        schema.find(NotionSchema.PROP_KEYWORDS, "multi_select")?.let { properties.put(it, multiSelectProp(item.keywords)) }
        schema.find(NotionSchema.PROP_CONTENT_TYPE, "select")?.let { properties.put(it, selectProp(item.contentType)) }
        schema.find(NotionSchema.PROP_CONTENT_DEPTH, "select")?.let { properties.put(it, selectProp(item.contentDepth)) }
        schema.find(NotionSchema.PROP_TONE, "select")?.let { properties.put(it, selectProp(item.tone)) }
        schema.find(NotionSchema.PROP_STYLE, "select")?.let { properties.put(it, selectProp(item.style)) }
        schema.find(NotionSchema.PROP_HAS_ACTION, "checkbox")?.let { properties.put(it, checkboxProp(item.hasAction)) }
        schema.find(NotionSchema.PROP_HAS_STANCE, "checkbox")?.let { properties.put(it, checkboxProp(item.hasStance)) }
        schema.find(NotionSchema.PROP_STANCE_SUMMARY, "rich_text")?.let {
            properties.put(it, richTextProp(item.stanceSummary.take(RICH_TEXT_LIMIT)))
        }
        // 聚类主题（select）+ 相似篇数（number）：同簇文章共享，便于在 Notion 里分组 / 筛选
        schema.find(NotionSchema.PROP_CLUSTER_TOPIC, "select")?.let {
            val safeTopic = sanitizeClusterTopic(item.clusterTopic)
            if (safeTopic.isNotBlank()) properties.put(it, selectProp(safeTopic))
        }
        schema.find(NotionSchema.PROP_CLUSTER_COUNT, "number")?.let {
            if (item.clusterCount > 1) properties.put(it, numberProp(item.clusterCount))
        }
        // 是否代表（checkbox）：标记该页是否为本簇代表文章，便于在 Notion 里筛选「簇入口」
        schema.find(NotionSchema.PROP_IS_CLUSTER_REP, "checkbox")?.let {
            properties.put(it, checkboxProp(item.isClusterRep))
        }
        // 实际已读（checkbox）：用户在 App 阅读页手动勾选的「我实际读过」，由引擎写入，独立于 supsub 的 is_read
        // v15 字段级增量推送：仅当「本地脏（用户刚改过该字段）」或「本页尚未建过（首次推送须建立值）」才写该 checkbox，
        // 否则省略——避免用本设备陈旧的 false 把另一设备独立改过的 is_user_read 在 Notion 端抹掉（⑤-B 根因）。
        val isNewPage = item.notionPageId.isNullOrBlank()
        if (item.userReadDirty || isNewPage) {
            schema.find(NotionSchema.PROP_USER_READ, "checkbox")?.let {
                properties.put(it, checkboxProp(item.isUserRead))
            }
        }
        // 高价值（checkbox）：用户在 App 阅读页手动标记的「高价值」，由引擎写入 Notion（受管列，双向同步）
        if (item.highValueDirty || isNewPage) {
            schema.find(NotionSchema.PROP_HIGH_VALUE, "checkbox")?.let {
                properties.put(it, checkboxProp(item.isHighValue))
            }
        }
        // ─── 三方同步（手机/平板/Notion）新增隐藏列 ───
        // 跨设备主键：contentId，拉取按它映射/去重，两设备落到同一页。推送必带。
        schema.find(NotionSchema.PROP_CONTENT_ID, "rich_text")?.let {
            properties.put(it, richTextProp(item.contentId))
        }
        // 摘要：rich_text 无损存，拉取按属性直接重建本地 summary（不再依赖解析 blocks）
        schema.find(NotionSchema.PROP_SUMMARY, "rich_text")?.let {
            item.summary?.let { s -> properties.put(it, richTextProp(s.take(RICH_TEXT_LIMIT))) }
        }
        // 封面图：url
        schema.find(NotionSchema.PROP_COVER, "url")?.let {
            item.coverImage?.let { c -> properties.put(it, urlProp(c)) }
        }
        // 注意：PROP_DELETED（已删除 checkbox）**不在此写入**——普通同步会把它重置为 false，冲掉删除信号。
        // 它只由 P4 的独立「删除 pass」在需要删除时 PATCH 为 true。列已由 ensureSchema 建好（默认未勾选）。
        return properties
    }

    /** 构造页面正文 children 块（顺序：标签 → 关键词 → 信息行 → 分析行 → 摘要 → 立场摘要 → 原文链接 → 封面图） */
    private fun buildChildren(item: ItemData): JSONArray {
        val children = JSONArray()
        if (item.tags.isNotEmpty()) {
            addBlock(children, paragraphBlock("🏷 标签：" + item.tags.joinToString("   ·   ")))
        }
        if (item.keywords.isNotEmpty()) {
            addBlock(children, paragraphBlock("🔑 关键词：" + item.keywords.joinToString("   ·   ")))
        }
        appendInfoLine(children, item)
        appendClusterLine(children, item)
        val analysisParts = mutableListOf<String>()
        item.contentDepth.takeIf { it.isNotBlank() }?.let { analysisParts.add("内容深度（$it）") }
        item.contentType.takeIf { it.isNotBlank() }?.let { analysisParts.add("内容类型（$it）") }
        item.tone.takeIf { it.isNotBlank() }?.let { analysisParts.add("语气（$it）") }
        if (analysisParts.isNotEmpty()) {
            addBlock(children, paragraphBlock("🔍 " + analysisParts.joinToString("   ·   ")))
        }
        item.summary?.let { summary ->
            addBlock(children, headingBlock("💡 摘要", 2))
            val blocks = markdownToBlocks(summary)
            if (appendBlocks(children, blocks) < blocks.size) {
                addBlock(children, paragraphBlock("（摘要较长，已省略部分内容）"))
            }
        }
        item.stanceSummary.takeIf { it.isNotBlank() }?.let { stance ->
            addBlock(children, headingBlock("🗣 立场摘要", 2))
            val stanceBlocks = markdownToBlocks(stance)
            if (appendBlocks(children, stanceBlocks) < stanceBlocks.size) {
                addBlock(children, paragraphBlock("（立场摘要较长，已省略部分内容）"))
            }
        }
        item.url.takeIf { it.isNotBlank() }?.let { addBlock(children, paragraphWithLink("🔗 查看原文", it)) }
        item.coverImage?.let { addBlock(children, imageBlockExternal(it)) }
        return children
    }

    /** 同主题聚类提示行：放在正文靠前位置，便于在 Notion 页面一眼看到「还有 N 篇同主题」 */
    private fun appendClusterLine(children: JSONArray, item: ItemData) {
        if (item.clusterCount <= 1) return
        addBlock(children, paragraphBlock("🔗 同主题聚类：${item.clusterTopic}（共 ${item.clusterCount} 篇）"))
    }

    private fun appendInfoLine(children: JSONArray, item: ItemData) {
        val parts = mutableListOf<String>()
        item.sourceName.takeIf { it.isNotBlank() }?.let { parts.add("来源：$it") }
        item.sourceType.takeIf { it.isNotBlank() }?.let { parts.add("类型：$it") }
        if (item.publishedAtSec > 0) parts.add("时间：${formatLocal(item.publishedAtSec * 1000)}")
        if (parts.isEmpty()) return
        addBlock(children, paragraphBlock("ℹ️ " + parts.joinToString("   ·   ")))
    }

    /** 把块追加到 target，整体不超过 MAX_CHILDREN，返回实际添加数量 */
    private fun appendBlocks(target: JSONArray, blocks: List<JSONObject>): Int {
        var added = 0
        for (b in blocks) {
            if (target.length() >= MAX_CHILDREN) break
            target.put(b)
            added++
        }
        return added
    }

    private fun addBlock(target: JSONArray, block: JSONObject): Boolean {
        if (target.length() >= MAX_CHILDREN) return false
        target.put(block)
        return true
    }

    private fun headingBlock(text: String, level: Int): JSONObject {
        val type = "heading_$level"
        return JSONObject().put("object", "block").put("type", type)
            .put(type, JSONObject().put("rich_text", richTextInline(text)))
    }

    private fun paragraphBlock(text: String): JSONObject =
        JSONObject().put("object", "block").put("type", "paragraph")
            .put("paragraph", JSONObject().put("rich_text", richTextInline(text)))

    private fun paragraphWithLink(label: String, url: String): JSONObject =
        JSONObject().put("object", "block").put("type", "paragraph")
            .put(
                "paragraph",
                JSONObject().put(
                    "rich_text",
                    JSONArray().put(
                        JSONObject().put("type", "text").put(
                            "text",
                            JSONObject().put("content", "$label：").put("link", JSONObject().put("url", url))
                        )
                    )
                )
            )

    private fun imageBlockExternal(url: String): JSONObject =
        JSONObject().put("object", "block").put("type", "image").put(
            "image",
            JSONObject().put("type", "external").put("external", JSONObject().put("url", url))
        )

    /**
     * 轻量 Markdown → Notion 块转换（覆盖摘要常见格式）：
     * 标题(#/##/###)、无序/有序列表、引用(>)、代码围栏(```)、分隔线(---)、
     * 以及行内 **加粗** / *斜体* / `代码` / [文本](链接)。
     */
    private fun markdownToBlocks(md: String): List<JSONObject> {
        val blocks = mutableListOf<JSONObject>()
        val lines = md.replace("\r\n", "\n").split("\n")
        val paraBuf = StringBuilder()
        fun flushPara() {
            if (paraBuf.isNotEmpty()) {
                blocks.add(paragraphBlock(paraBuf.toString().trimEnd()))
                paraBuf.setLength(0)
            }
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.matches(Regex("""```.*""")) -> {
                    flushPara()
                    val lang = line.removePrefix("```").trim()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].matches(Regex("""```.*"""))) {
                        if (code.isNotEmpty()) code.append("\n")
                        code.append(lines[i]); i++
                    }
                    i++ // 跳过结束围栏
                    if (code.isNotEmpty()) blocks.add(codeBlock(code.toString(), lang))
                }
                line.matches(Regex("""#{1,3}\s+.*""")) -> {
                    flushPara()
                    val level = line.takeWhile { it == '#' }.length
                    blocks.add(headingBlock(line.substring(level).trim(), level))
                }
                line.matches(Regex("""\s*[-*]\s+.*""")) -> {
                    flushPara()
                    blocks.add(listItemBlock(line.trim().removePrefix("-").removePrefix("*").trim(), false))
                }
                line.matches(Regex("""\s*\d+\.\s+.*""")) -> {
                    flushPara()
                    blocks.add(listItemBlock(line.trim().substringAfter(".").trim(), true))
                }
                line.matches(Regex("""\s*>\s?.*""")) -> {
                    flushPara()
                    blocks.add(quoteBlock(line.trim().removePrefix(">").trim()))
                }
                line.matches(Regex("""\s*(---|\*\*\*|___)\s*""")) -> {
                    flushPara()
                    blocks.add(dividerBlock())
                }
                line.isBlank() -> flushPara()
                else -> {
                    if (paraBuf.isNotEmpty()) paraBuf.append("\n")
                    paraBuf.append(line)
                }
            }
            i++
        }
        flushPara()
        return blocks
    }

    /** 行内富文本：解析 **加粗** / *斜体* / `代码` / [文本](链接) */
    private fun richTextInline(text: String): JSONArray {
        val out = JSONArray()
        if (text.isEmpty()) { out.put(textSegment("")); return out }
        val re = Regex("""(\*\*(.+?)\*\*)|(`(.+?)`)|(\[([^\]]+)\]\(([^)]+)\))|(\*(.+?)\*)""")
        var last = 0
        re.findAll(text).forEach { m ->
            if (m.range.first > last) out.put(textSegment(text.substring(last, m.range.first)))
            when {
                m.groups[2] != null -> out.put(textSegment(m.groupValues[2], bold = true))
                m.groups[4] != null -> out.put(textSegment(m.groupValues[4], code = true))
                m.groups[6] != null -> out.put(textSegment(m.groupValues[6], link = m.groupValues[7]))
                m.groups[8] != null -> out.put(textSegment(m.groupValues[8], italic = true))
            }
            last = m.range.last + 1
        }
        if (last < text.length) out.put(textSegment(text.substring(last)))
        if (out.length() == 0) out.put(textSegment(text))
        return out
    }

    private fun textSegment(
        content: String,
        bold: Boolean = false,
        italic: Boolean = false,
        code: Boolean = false,
        link: String? = null,
    ): JSONObject {
        val t = JSONObject().put("content", content)
        if (link != null) t.put("link", JSONObject().put("url", link))
        val ann = JSONObject()
            .put("bold", bold).put("italic", italic).put("strikethrough", false)
            .put("underline", false).put("code", code).put("color", "default")
        return JSONObject().put("type", "text").put("text", t).put("annotations", ann)
    }

    private fun codeBlock(code: String, lang: String): JSONObject =
        JSONObject().put("object", "block").put("type", "code")
            .put(
                "code",
                JSONObject().put("rich_text", richText(code))
                    .put("language", if (lang.isBlank()) "plain text" else lang)
            )

    private fun quoteBlock(text: String): JSONObject =
        JSONObject().put("object", "block").put("type", "quote")
            .put("quote", JSONObject().put("rich_text", richTextInline(text)))

    private fun listItemBlock(text: String, ordered: Boolean): JSONObject {
        val type = if (ordered) "numbered_list_item" else "bulleted_list_item"
        return JSONObject().put("object", "block").put("type", type)
            .put(type, JSONObject().put("rich_text", richTextInline(text)))
    }

    private fun dividerBlock(): JSONObject =
        JSONObject().put("object", "block").put("type", "divider").put("divider", JSONObject())

    private fun richText(content: String): JSONArray =
        JSONArray().put(JSONObject().put("type", "text").put("text", JSONObject().put("content", content)))

    private fun titleObject(content: String): JSONObject =
        JSONObject().put("title", JSONArray().put(JSONObject().put("text", JSONObject().put("content", content))))

    private fun richTextProp(content: String): JSONObject =
        JSONObject().put("rich_text", JSONArray().put(JSONObject().put("text", JSONObject().put("content", content))))

    private fun selectProp(name: String?): JSONObject = JSONObject().put(
        "select",
        if (name.isNullOrBlank()) JSONObject.NULL else JSONObject().put("name", name)
    )

    private fun multiSelectProp(names: List<String>): JSONObject {
        val arr = JSONArray()
        names.forEach { if (it.isNotBlank()) arr.put(JSONObject().put("name", it)) }
        return JSONObject().put("multi_select", arr)
    }

    private fun checkboxProp(value: Boolean): JSONObject = JSONObject().put("checkbox", value)

    private fun urlProp(url: String): JSONObject =
        JSONObject().put("url", if (url.isBlank()) JSONObject.NULL else url)

    private fun numberProp(value: Int): JSONObject = JSONObject().put("number", value)

    private fun dateProp(epochMs: Long?): JSONObject = JSONObject().put(
        "date",
        if (epochMs == null || epochMs <= 0) JSONObject.NULL else JSONObject().put("start", isoUtc(epochMs)),
    )

    /**
     * 把毫秒时间戳格式化为 Notion 接受的 UTC 时间戳（带 Z、含毫秒）。
     * 必须用 UTC+'Z'，与 [parseIsoMs] 的 Z 解析严格对应，否则跨时区/往返会出现偏移或解析失败
     * （早期用本地偏移 +08:00 序列化，导致拉取端无法还原、published_at 全 0、shard_key 退化为 1970-01-01）。
     */
    private fun isoUtc(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(epochMs))
    }

    private fun formatLocal(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

    // ===================== 限流退避 =====================

    /**
     * 对 Notion 调用做限流退避：409(并发写冲突) / 429(Retry-After) / 5xx 自动重试，最多 MAX_RETRIES 次；
     * 其余 4xx（如 401/404）视为致命错误直接抛出，由上层写入同步状态。
     *
     * 关于 409 conflict_error：Notion 对同一对象（页面 / 数据库）并发写入会返回 409，
     * 表示本次保存因冲突未提交。官方建议「稍后重试」，且重发不会新建重复对象，故安全重试。
     */
    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return reqSem.withPermit { block() }
            } catch (e: HttpException) {
                val code = e.code()
                val retryable = code == 409 || code == 429 || code in 500..599
                if (!retryable || attempt >= MAX_RETRIES) {
                    val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
                        ?.replace(Regex("\\s+"), " ")?.take(200) ?: ""
                    throw SyncHttpError(code, if (body.isNotBlank()) body else (e.message ?: "HTTP $code"))
                }
                val retryAfterSec = e.response()?.headers()?.get("Retry-After")?.toLongOrNull()
                val delayMs = if (retryAfterSec != null && retryAfterSec > 0) {
                    retryAfterSec * 1000
                } else {
                    BASE_BACKOFF_MS * (1L shl attempt)
                }
                delay(delayMs)
                attempt++
            }
        }
    }

    private class SyncHttpError(val code: Int, val detail: String) : Exception("HTTP $code: $detail")

    // ===================== 记录 → 同步项 =====================

    /**
     * 一个待同步条目的内存表示。
     *
     * V1 这里要从 JSON 文件里一层层 optString 挖出来（keywords 在顶层、分析维度在 analysis{} 里）；
     * V2 全是表列，直接取字段，只有 tags / keywords 两个 JSON 数组串需要解一下。
     */
    private data class ItemData(
        val contentId: String,
        val shardKey: String,
        val title: String,
        val sourceName: String,
        val sourceType: String,
        val originCategory: String,
        val focusTitle: String = "",
        val publishedAtSec: Long,
        val tags: List<String>,
        val keywords: List<String>,
        val contentType: String,
        val contentDepth: String,
        val tone: String,
        val style: String,
        val hasAction: Boolean,
        val hasStance: Boolean,
        val stanceSummary: String,
        val url: String,
        val coverImage: String?,
        val summary: String?,
        val contentHash: String,
        val notionPageId: String?,
        val notionDatabaseId: String?,
        val syncedBodyHash: String?,
        val syncStatus: String = SyncStatus.PENDING,
        val clusterTopic: String = "",
        val clusterCount: Int = 0,
        val isClusterRep: Boolean = false,
        val isUserRead: Boolean = false,
        val isHighValue: Boolean = false,
        /** v15 字段级脏标记：本地自上次同步后是否改过该字段（用于增量推送，避免抹掉对端独立改动） */
        val userReadDirty: Boolean = false,
        val highValueDirty: Boolean = false,
    )

    private fun ArticleRecord.toItemData(shardKey: String) = ItemData(
        contentId = contentId,
        shardKey = shardKey,
        title = title,
        sourceName = sourceName,
        sourceType = sourceType,
        originCategory = OriginCategory.fromKey(originCategory).label,
        focusTitle = focusTitle,
        publishedAtSec = publishedAt,
        tags = parseJsonArray(tags),
        keywords = parseJsonArray(keywords),
        contentType = contentType,
        contentDepth = contentDepth,
        tone = tone,
        style = style,
        hasAction = hasAction,
        hasStance = hasStance,
        stanceSummary = stanceSummary,
        url = url,
        coverImage = coverImage?.takeIf { it.isNotBlank() },
        summary = summary?.takeIf { it.isNotBlank() },
        contentHash = contentHash,
        notionPageId = notionPageId,
        notionDatabaseId = notionDatabaseId,
        syncedBodyHash = syncedBodyHash,
        syncStatus = syncStatus,
        clusterTopic = clusterLabel,
        clusterCount = clusterSize,
        isClusterRep = isClusterRep,
        isUserRead = isUserRead,
        isHighValue = isHighValue,
        userReadDirty = userReadDirty,
        highValueDirty = highValueDirty,
    )

    /**
     * Notion select 选项名不能包含半角逗号，否则返回 400 validation_error。
     * 把半角逗号替换为全角逗号；同时限制长度避免 select 选项超长。
     */
    private fun sanitizeClusterTopic(topic: String): String {
        return topic.replace(',', '，').take(100).trim()
    }

    /** 解析库里存的 JSON 数组串（如 `["AI","模型"]`）；脏数据一律当空数组，不让同步整篇失败 */
    private fun parseJsonArray(raw: String?): List<String> {
        if (raw.isNullOrBlank() || raw == "[]") return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.getOrDefault(emptyList())
    }
}
