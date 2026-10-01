package com.peide.supsub.notionsync

import android.util.Log
import com.peide.supsub.data.ArchiveStore
import com.peide.supsub.data.NotionShard
import com.peide.supsub.data.ShardKeys
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Notion 请求重试出口。
 *
 * 路由要复用同步引擎那套 429/5xx 退避与全局并发限流，但引擎的 `withRetry` 是私有泛型方法、
 * 没法当参数传（Kotlin 的函数类型表达不了「泛型 suspend 函数」）。
 * 用一个带泛型方法的接口就能把它原样交出来，路由与引擎共用同一个限流闸门。
 */
interface NotionRetry {
    suspend fun <T> call(block: suspend () -> T): T
}

/**
 * 按天分库路由：`shardKey` → Notion 子数据库 id。
 *
 * 云端结构从「一个大库装全部」改成「容器页 + 每·天·一个子库」：
 *
 * ```
 * 订阅归档（容器页 · parentPageId）
 *   ├── 订阅归档 2026-08-03   ← child_database
 *   ├── 订阅归档 2026-08-04   ← child_database
 *   └── 订阅归档 2026-08-05   ← child_database
 * ```
 *
 * 查找顺序刻意分三级，越靠前越省请求：
 *  1. **内存缓存** —— 同一次同步里几百篇文章通常只落在两三个分片，除首次外全部命中，零网络；
 *  2. **本地登记表** `notion_shards` —— 跨进程/跨次同步命中，仍是零网络；
 *  3. **容器页发现 → 建库** —— 只在真正遇到新的一天时发生，一个分片一辈子最多一次。
 *
 * 第 3 步先「发现」再「创建」很关键：如果直接建，App 重装或本地库被清掉后就会在容器页里
 * 建出第二个「订阅归档 2026-08-05」，同一篇文章可能被写进两个库。
 * 按**标题**匹配已有子库（[ShardKeys.titleOf] 的输出保证稳定）能杜绝这种重复。
 *
 * 并发：同步引擎会并发处理多篇文章，同一分片的首次解析必须串行，
 * 否则两个协程会同时发现「没有这个库」并各建一个。这里按 shardKey 加锁，不同分片互不阻塞。
 */
class NotionShardRouter(
    private val api: NotionApi,
    private val store: ArchiveStore,
    private val parentPageId: String,
    private val retry: NotionRetry,
) {

    /** shardKey → databaseId，本次同步内的一级缓存 */
    private val cache = ConcurrentHashMap<String, String>()

    /** 每个 shardKey 一把锁：同分片串行解析，跨分片并行 */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** 容器页里已存在的子库：title → databaseId（懒加载一次，本次同步内复用） */
    @Volatile
    private var discovered: MutableMap<String, String>? = null
    private val discoveryLock = Mutex()

    /** 本次同步真正新建的分片（供 UI 提示「新建了哪几个按天库」） */
    private val created = ConcurrentHashMap.newKeySet<String>()
    val newlyCreated: Set<String> get() = created.toSet()

    /**
     * 取（必要时创建）该分片对应的子数据库 id。
     *
     * @throws Exception 建库失败时抛出（容器页不可访问 / 填成了数据库 id 等），由调用方计入该篇失败
     */
    suspend fun databaseIdFor(shardKey: String): String {
        cache[shardKey]?.let { return it }
        val lock = locks.computeIfAbsent(shardKey) { Mutex() }
        return lock.withLock {
            // 双检：等锁期间可能已被同分片的另一个协程解析好
            cache[shardKey]?.let { return@withLock it }

            // 1) 本地登记表。parentPageId 对不上说明用户换过容器页，旧登记作废重新解析。
            val local = store.shard(shardKey)
            if (local != null && local.databaseId.isNotBlank() &&
                (local.parentPageId.isBlank() || local.parentPageId == parentPageId)
            ) {
                cache[shardKey] = local.databaseId
                return@withLock local.databaseId
            }

            val title = ShardKeys.titleOf(shardKey)

            // 2) 容器页里按标题发现已存在的子库（防重复建库）
            val known = loadDiscovered()
            known[title]?.let { found ->
                Log.i(TAG, "分片 $shardKey 命中容器页已有子库「$title」 db=$found")
                register(shardKey, found, title)
                return@withLock found
            }

            // 3) 建库
            val body = NotionSchema.createDatabaseBody(parentPageId, title)
            val id = retry.call {
                JSONObject(api.createDatabase(body.toString().toJsonBody()).string()).getString("id")
            }
            // 新一天的子库默认被 Notion 加到容器页底部；移到顶部，让最新的按天库显示在最上方。
            // 只在新建时发生，不碰已存在的子库（避免打乱用户手动调整过的顺序）。
            runCatching {
                retry.call { api.moveBlock(id, moveBlockToTopBody(parentPageId)) }
            }.onFailure {
                Log.w(TAG, "分片 $shardKey 新建子库「$title」后移动到顶部失败（库已建，仅未置顶）", it)
            }
            Log.i(TAG, "分片 $shardKey 新建子库「$title」 db=$id")
            known[title] = id
            created.add(shardKey)
            register(shardKey, id, title)
            id
        }
    }

    /**
     * 冷启动 / 跨设备：把容器页下所有已存在的子库一次性发现并登记到本地 [ArchiveStore]，
     * 供 [com.peide.supsub.notionsync.SupSubSyncEngine.pullFromRemote] 枚举拉取。
     *
     * @return 本次新发现的子库 databaseId 集合（之前本地从未登记过）。
     *         新库从未被增量拉取水印覆盖过，拉取侧须对其走全量（since=0），
     *         否则库里 last_edited_time 早于水印的存量文章会被 on_or_after 过滤掉
     *         （实测：手机先推 92 篇进「订阅归档 2026-08-18」库、平板后拉，增量水印在推送之后，
     *         该库若走增量将一篇都拉不到）。
     * 否则空白板设备（从未推送过本地文章、[com.peide.supsub.data.ArchiveDb.TABLE_SHARDS] 为空）
     * 在 pullFromRemote 里 `listShards()` 返回空，会直接拉 0 篇，冷启动重建整表失败。
     * 已同步设备调用也无害：registerShard 走 REPLACE，幂等。
     */
    suspend fun discoverAllShards(): Set<String> {
        // 强制重走容器页发现：router 是引擎持有的单例，discovered 缓存在实例生命周期内复用，
        // 若不在这里清空，第二次同步会直接复用上一次的旧缓存，漏掉这段时间内手机端新建的按天子库。
        discovered = null
        val map = loadDiscovered() // title -> databaseId
        // 快照发现前已登记的分片 key，用于识别「新出现的子库」（其内容从未被增量水印覆盖过）
        val knownBefore = runCatching { store.listShards().map { it.shardKey }.toSet() }.getOrDefault(emptySet())
        val newlyDbIds = mutableSetOf<String>()
        for ((title, dbId) in map) {
            val shardKey = ShardKeys.fromTitle(title)
            Log.i(TAG, "discoverAllShards：登记已存在子库「$title」 shardKey=$shardKey db=$dbId")
            register(shardKey, dbId, title)
            if (shardKey !in knownBefore) newlyDbIds.add(dbId)
        }
        Log.i(TAG, "discoverAllShards 完成：共登记 ${map.size} 个已存在子库，新增 ${newlyDbIds.size} 个（将对新增库全量拉取）")
        return newlyDbIds
    }

    /**
     * 作废某分片的路由结果。
     *
     * 子库在 Notion 侧被手动删除时，本地登记仍指向一个已不存在的 id，写页面会一直 404。
     * 引擎捕获 404 后调用这里清掉缓存与登记，下一次 [databaseIdFor] 会重新发现或新建，实现自愈。
     */
    suspend fun invalidate(shardKey: String) {
        cache.remove(shardKey)
        discovered?.remove(ShardKeys.titleOf(shardKey))
        runCatching { store.deleteShard(shardKey) }
        Log.w(TAG, "分片 $shardKey 路由已作废，下次解析将重新发现/新建")
    }

    /** 懒加载容器页下的全部 child_database（分页拉全，翻页上限 50 页兜底防死循环） */
    private suspend fun loadDiscovered(): MutableMap<String, String> {
        discovered?.let { return it }
        return discoveryLock.withLock {
            discovered?.let { return@withLock it }
            val map = LinkedHashMap<String, String>()
            var cursor: String? = null
            var page = 0
            do {
                val resp = retry.call {
                    JSONObject(api.getBlockChildren(parentPageId, 100, cursor).string())
                }
                val arr = resp.optJSONArray("results") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val b = arr.optJSONObject(i) ?: continue
                    if (b.optString("type") != "child_database") continue
                    // child_database 块的 block id 就是数据库 id
                    val id = b.optString("id", "")
                    val t = b.optJSONObject("child_database")?.optString("title").orEmpty()
                    // 同名多个时保留最早那个，避免每次同步在不同副本间摇摆
                    if (id.isNotBlank() && t.isNotBlank() && !map.containsKey(t)) map[t] = id
                }
                cursor = if (resp.optBoolean("has_more")) {
                    resp.optString("next_cursor").takeIf { it.isNotBlank() && it != "null" }
                } else {
                    null
                }
            } while (cursor != null && ++page < MAX_DISCOVERY_PAGES)
            Log.i(TAG, "容器页 $parentPageId 下发现 ${map.size} 个已存在子库：${map.keys.joinToString()}")
            discovered = map
            map
        }
    }

    private suspend fun register(shardKey: String, dbId: String, title: String) {
        cache[shardKey] = dbId
        runCatching {
            store.registerShard(
                NotionShard(
                    shardKey = shardKey,
                    databaseId = dbId,
                    title = title,
                    parentPageId = parentPageId,
                )
            )
        }.onFailure { Log.w(TAG, "登记分片失败（不影响本次同步） $shardKey", it) }
    }

    private companion object {
        const val TAG = "NotionShardRouter"
        const val MAX_DISCOVERY_PAGES = 50
    }
}
