package com.peide.supsub.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 归档数据访问层（V2 唯一入口）。
 *
 * 取代了 V1 的三件套（FileExporter 写 JSON 文件群 + RoomLocalIndex + CachedFileIndex 内存索引）：
 * 正文与索引现在是**同一张表的同一行**，不存在「索引与文件不一致」这个 V1 必须靠 `rebuildFromJson`
 * 兜底的问题，`ArchiveStats.inconsistent` 也从「索引对不上文件」退化成单纯的字段完整性校验。
 *
 * 线程模型：所有方法都是 `suspend` 且切到 [Dispatchers.IO]；并发安全交给 SQLite 自身
 * （WAL 模式 + busy_timeout 5s，允许多读一写）。写路径**不开显式事务**——
 * 单次拉取只有百来行，逐行自动提交足够快，同时彻底规避 PKJ110 上的事务挂死历史问题。
 */
class ArchiveStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val helper = ArchiveDb.get(appContext)

    /** 库文件（供 UI 展示体积 / 备份） */
    fun dbFile(): File = ArchiveDb.dbFile(appContext)

    // ===================== 写入（拉取路径）=====================

    /**
     * 插入一篇文章；若 contentId 已存在则**原样保留旧行**（含其同步状态）并返回 false。
     *
     * 用 `INSERT OR IGNORE` 而不是 `REPLACE`：重复拉到同一篇时，绝不能把已经同步成功的
     * notion_page_id / synced_hash 冲掉，否则下次同步会误判为新文章、在 Notion 里建出重复页。
     */
    suspend fun insertIfAbsent(record: ArticleRecord): Boolean = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val rowId = db.insertWithOnConflict(
            ArchiveDb.TABLE_ARTICLES,
            null,
            record.toValues(),
            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
        )
        val inserted = rowId != -1L
        // 真正新增时才记一条 PULL 历史（重复拉取不重复记）
        if (inserted) {
            insertHistory(
                contentId = record.contentId,
                eventType = "PULL",
                eventTime = record.capturedAt,
                actor = "CLOUD",
                field = "*",
                oldValue = null,
                newValue = null,
                detail = "初始拉取（captured_at=${record.capturedAt}）",
            )
        }
        inserted
    }

    /** 批量插入（跳过已存在），返回实际新增条数 */
    suspend fun insertIfAbsentBatch(records: List<ArticleRecord>): Int = withContext(Dispatchers.IO) {
        var n = 0
        val db = helper.writableDatabase
        records.forEach { r ->
            val rowId = db.insertWithOnConflict(
                ArchiveDb.TABLE_ARTICLES,
                null,
                r.toValues(),
                android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
            )
            if (rowId != -1L) n++
        }
        n
    }

    // ===================== 去重 / 计数 =====================

    /**
     * 一次性取出全部 contentId 快照，供拉取过程在内存里做 O(1) 去重。
     *
     * 拉取时每篇都查一次库也能work，但一次拉取上千篇就是上千次查询；
     * 一次性拿全量 id（3767 条约 200KB）再在 HashSet 里判断要划算得多。
     */
    suspend fun allContentIds(): MutableSet<String> = withContext(Dispatchers.IO) {
        val out = HashSet<String>(4096)
        helper.readableDatabase.rawQuery(
            "SELECT content_id FROM ${ArchiveDb.TABLE_ARTICLES}", null,
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        out
    }

    /**
     * 一次性取出全部（已归一化的）url 快照，供拉取时按「原始链接」做跨通道去重：
     * 关注点文章若与已保留文章（订阅源 / 早先保留的关注点）链接相同，则视为重复。
     *
     * 归一化口径（[normalizeUrl]）必须与 [PullEngine] 完全一致，否则两侧同一篇对不上。
     */
    suspend fun allUrls(): MutableSet<String> = withContext(Dispatchers.IO) {
        val out = HashSet<String>(4096)
        helper.readableDatabase.rawQuery(
            "SELECT url FROM ${ArchiveDb.TABLE_ARTICLES} WHERE url IS NOT NULL AND url <> ''", null,
        ).use { c ->
            while (c.moveToNext()) out.add(normalizeUrl(c.getString(0)))
        }
        out
    }

    /** URL 归一化：去首尾空白、转小写、去结尾斜杠。去重两侧必须共用同一实现。 */
    fun normalizeUrl(url: String): String {
        val t = url.trim().lowercase()
        return if (t.endsWith("/")) t.substring(0, t.length - 1) else t
    }

    suspend fun contains(contentId: String): Boolean = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT 1 FROM ${ArchiveDb.TABLE_ARTICLES} WHERE content_id = ? LIMIT 1",
            arrayOf(contentId),
        ).use { it.moveToFirst() }
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) { queryInt("SELECT COUNT(*) FROM ${ArchiveDb.TABLE_ARTICLES}") }

    // ===================== 列表 / 统计 =====================

    /**
     * 分页列出文章概况（按发布时间倒序，content_id 兜底保证分页稳定不重不漏）。
     * 只 SELECT 列表要用的列，不带 summary / raw_json 这些大字段。
     */
    suspend fun listSummaries(offset: Int, limit: Int): List<ArticleSummary> = withContext(Dispatchers.IO) {
        doListSummaries(offset = offset, limit = limit)
    }

    /**
     * 一次性列出全部文章概况。
     * 「已拉取文章概况」弹窗需要按天快速跳转，分页会导致早期日期未加载、筛选条只显示最近一天；
     * 本地归档通常在数千篇以内，全量 SELECT 轻量列不会带来明显内存压力。
     */
    suspend fun listAllSummaries(): List<ArticleSummary> = withContext(Dispatchers.IO) {
        doListSummaries(offset = 0, limit = 0)
    }

    /**
     * 阅读页列表：返回 [ReadingItem] 轻量投影（tags/keywords 已解析为列表）。
     *
     * @param status 状态筛选：全部 / 已读 / 未读 / 高价值
     * @param bySource true 时按来源名升序（同来源内再按发布时间倒序），false 时按发布时间倒序
     * @param timeRange 按发布时间过滤（ALL/TODAY/WEEK/MONTH/OLDER）
     * @param sourceFilter 按公众号来源名精确过滤，null/blank 表示全部
     * @param syncFilter 同步状态筛选：全部 / 已同步(SYNCED) / 待同步(PENDING,SYNCING) / 待重推(PENDING_PROP)
     * @param search 关键字，模糊匹配 title 或 source_name（null/blank 表示不搜）
     */
    suspend fun listForReading(
        status: ReadingStatus,
        bySource: Boolean,
        timeRange: ReadingTimeRange = ReadingTimeRange.ALL,
        sourceFilter: String? = null,
        syncFilter: ReadingSyncFilter = ReadingSyncFilter.ALL,
        search: String? = null,
        /** 来源分类筛选：null=全部；"SUBSCRIPTION" / "FOCUS" / "WEBSET"（对应订阅源/关注点/网页集） */
        categoryFilter: String? = null,
        /** 关注点筛选：null=不限；非 null 时仅返回该关注点的文章（仅对 FOCUS 通道有意义） */
        focusFilter: Long? = null,
    ): List<ReadingItem> = withContext(Dispatchers.IO) {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<String>()
        // 已删除（本地待同步删 / 已同步删）的文章不进入阅读列表（真同步删除语义）
        conditions.add("sync_status NOT IN ('${SyncStatus.DELETED_LOCAL}','${SyncStatus.DELETED_SYNCED}')")
        when (status) {
            ReadingStatus.UNREAD -> conditions.add("is_user_read = 0")
            ReadingStatus.READ -> conditions.add("is_user_read = 1")
            ReadingStatus.HIGH_VALUE -> conditions.add("is_high_value = 1")
            ReadingStatus.ALL -> { /* no filter */ }
        }
        when (syncFilter) {
            ReadingSyncFilter.SYNCED -> conditions.add("sync_status = '${SyncStatus.SYNCED}'")
            ReadingSyncFilter.PENDING -> conditions.add("sync_status IN ('${SyncStatus.PENDING}','${SyncStatus.SYNCING}')")
            ReadingSyncFilter.PROP -> conditions.add("sync_status = '${SyncStatus.PROP_RESNC}'")
            ReadingSyncFilter.ALL -> { /* no filter */ }
        }
        if (!sourceFilter.isNullOrBlank()) {
            conditions.add("source_name = ?")
            args.add(sourceFilter)
        }
        if (!categoryFilter.isNullOrBlank()) {
            conditions.add("origin_category = ?")
            args.add(categoryFilter)
        }
        if (focusFilter != null) {
            conditions.add("focus_id = ?")
            args.add(focusFilter.toString())
        }
        if (!search.isNullOrBlank()) {
            conditions.add("(title LIKE ? OR source_name LIKE ?)")
            val like = "%${search.trim()}%"
            args.add(like)
            args.add(like)
        }
        if (timeRange != ReadingTimeRange.ALL) {
            val startOfTodaySec = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis / 1000L
            val oneDaySec = 24 * 60 * 60L
            val cutoff = when (timeRange) {
                ReadingTimeRange.TODAY -> startOfTodaySec
                ReadingTimeRange.WEEK -> startOfTodaySec - 7 * oneDaySec
                ReadingTimeRange.MONTH -> startOfTodaySec - 30 * oneDaySec
                ReadingTimeRange.OLDER -> startOfTodaySec - 30 * oneDaySec
                else -> 0L
            }
            if (timeRange == ReadingTimeRange.OLDER) {
                conditions.add("published_at < ?")
            } else {
                conditions.add("published_at >= ?")
            }
            args.add(cutoff.toString())
        }
        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        val order = if (bySource) {
            "ORDER BY source_name ASC, published_at DESC, content_id DESC"
        } else {
            "ORDER BY published_at DESC, content_id DESC"
        }
        val out = ArrayList<ReadingItem>()
        helper.readableDatabase.rawQuery(
            """
            SELECT content_id, title, source_name, source_type, origin_category, focus_id, focus_title,
                   published_at, is_user_read, is_high_value, cluster_id, cluster_label, summary, tags,
                   keywords, content_type, content_depth, tone, style, has_stance, stance_summary, url,
                   cover_image
            FROM ${ArchiveDb.TABLE_ARTICLES}
            $where
            $order
            """.trimIndent(),
            args.toTypedArray(),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ReadingItem(
                        contentId = c.getString(0),
                        title = c.getString(1) ?: "",
                        sourceName = c.getString(2) ?: "",
                        sourceType = c.getString(3) ?: "",
                        originCategory = c.getString(4) ?: "",
                        focusId = c.getLong(5),
                        focusTitle = c.getString(6) ?: "",
                        publishedAt = c.getLong(7),
                        isUserRead = c.getInt(8) != 0,
                        isHighValue = c.getInt(9) != 0,
                        clusterId = c.getString(10) ?: "",
                        clusterLabel = c.getString(11) ?: "",
                        summary = if (c.isNull(12)) null else c.getString(12),
                        tags = parseStrArray(c.getString(13)),
                        keywords = parseStrArray(c.getString(14)),
                        contentType = c.getString(15) ?: "",
                        contentDepth = c.getString(16) ?: "",
                        tone = c.getString(17) ?: "",
                        style = c.getString(18) ?: "",
                        hasStance = c.getInt(19) != 0,
                        stanceSummary = c.getString(20) ?: "",
                        url = c.getString(21) ?: "",
                        coverImage = if (c.isNull(22)) null else c.getString(22),
                    )
                )
            }
        }
        out
    }

    /**
     * 阅读页「按关注点」筛选项：返回本地所有 FOCUS 通道文章去重后的 (focus_id, focus_title)。
     * 只列「本地确实存在的关注点」，标题为空或 id=0 的行视为无效（订阅源/网页集借用同一张表）。
     * UI 在结果前拼一个「全部关注点」选项即可。
     */
    suspend fun listReadingFocuses(): List<ReadingFocus> = withContext(Dispatchers.IO) {
        val out = ArrayList<ReadingFocus>()
        helper.readableDatabase.rawQuery(
            """
            SELECT DISTINCT focus_id, focus_title FROM ${ArchiveDb.TABLE_ARTICLES}
            WHERE origin_category = 'FOCUS' AND focus_id IS NOT NULL AND focus_id <> 0
              AND focus_title IS NOT NULL AND focus_title <> ''
            ORDER BY focus_title ASC
            """.trimIndent(),
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out.add(ReadingFocus(id = c.getLong(0), title = c.getString(1) ?: ""))
            }
        }
        out
    }

    /** 阅读页「按公众号」筛选项：返回所有非空 source_name 去重升序。
     * 关注点（origin_category='FOCUS'）有独立的「来源 / 关注点」筛选器，这里不再列出，
     * 避免两个筛选器内容重复、造成来源下拉里出现「[关注点]xxx」的混淆项。
     */
    suspend fun listReadingSources(): List<String> = withContext(Dispatchers.IO) {
        val out = ArrayList<String>()
        helper.readableDatabase.rawQuery(
            """
            SELECT DISTINCT source_name FROM ${ArchiveDb.TABLE_ARTICLES}
            WHERE origin_category <> 'FOCUS'
              AND source_name IS NOT NULL AND source_name <> ''
            ORDER BY source_name ASC
            """.trimIndent(),
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out.add(c.getString(0) ?: "")
            }
        }
        out
    }

    private fun doListSummaries(offset: Int, limit: Int): List<ArticleSummary> {
        val out = ArrayList<ArticleSummary>(if (limit > 0) limit else 256)
        // 已删除文章不进入归档概况列表（真同步删除语义）
        val deletedFilter = "sync_status NOT IN ('${SyncStatus.DELETED_LOCAL}','${SyncStatus.DELETED_SYNCED}')"
        // SQLite 要求 LIMIT 在前；无 limit 时用 LIMIT -1 表示“不限”。
        val limitSql = when {
            limit > 0 && offset > 0 -> "LIMIT ? OFFSET ?"
            limit > 0 -> "LIMIT ?"
            offset > 0 -> "LIMIT -1 OFFSET ?"
            else -> ""
        }
        val args = when {
            limit > 0 && offset > 0 -> arrayOf(limit.toString(), offset.toString())
            limit > 0 -> arrayOf(limit.toString())
            offset > 0 -> arrayOf(offset.toString())
            else -> null
        }
        helper.readableDatabase.rawQuery(
            """
            SELECT content_id, source_type, origin_category, source_name, title,
                   published_at, is_read, sync_status, shard_key
            FROM ${ArchiveDb.TABLE_ARTICLES}
            WHERE $deletedFilter
            ORDER BY published_at DESC, content_id DESC
            $limitSql
            """.trimIndent(),
            args,
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ArticleSummary(
                        contentId = c.getString(0),
                        sourceType = c.getString(1),
                        originCategory = c.getString(2),
                        sourceName = c.getString(3),
                        title = c.getString(4),
                        publishedAt = c.getLong(5),
                        isRead = c.getInt(6) != 0,
                        syncStatus = c.getString(7),
                        shardKey = c.getString(8),
                    )
                )
            }
        }
        return out
    }

    /** 归档整体统计。总量/未读/待同步/失败用一条聚合 SQL 出，分布再各来一条 GROUP BY。 */
    suspend fun stats(): ArchiveStats = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        var total = 0; var unread = 0; var userUnread = 0
        var pending = 0; var prop = 0; var failed = 0; var bad = 0
        db.rawQuery(
            """
            SELECT COUNT(*),
                   SUM(CASE WHEN is_read = 0 THEN 1 ELSE 0 END),
                   SUM(CASE WHEN is_user_read = 0 THEN 1 ELSE 0 END),
                   SUM(CASE WHEN sync_status IN ('${SyncStatus.PENDING}','${SyncStatus.SYNCING}') THEN 1 ELSE 0 END),
                   SUM(CASE WHEN sync_status = '${SyncStatus.PROP_RESNC}' THEN 1 ELSE 0 END),
                   SUM(CASE WHEN sync_status = '${SyncStatus.FAILED}' THEN 1 ELSE 0 END),
                   SUM(CASE
                       WHEN shard_key = '' THEN 1
                       WHEN content_hash = '' AND (notion_page_id IS NULL OR notion_page_id = '') THEN 1
                       ELSE 0
                   END)
            FROM ${ArchiveDb.TABLE_ARTICLES}
            WHERE sync_status NOT IN ('${SyncStatus.DELETED_LOCAL}','${SyncStatus.DELETED_SYNCED}')
            """.trimIndent(),
            null,
        ).use { c ->
            if (c.moveToFirst()) {
                total = c.getInt(0); unread = c.getInt(1); userUnread = c.getInt(2)
                pending = c.getInt(3); prop = c.getInt(4); failed = c.getInt(5); bad = c.getInt(6)
            }
        }
        ArchiveStats(
            total = total,
            unread = unread,
            userUnread = userUnread,
            pendingSync = pending,
            pendingProp = prop,
            syncFailed = failed,
            bySourceType = groupCount("source_type"),
            // byOrigin 的 key 直接来自 DB 列原始字符串，可能混入中文 label / 大小写变体（早期同步污染）。
            // 这里按枚举归一化成英文 key 并聚合 count，统计与 UI 才能正确合并同类目。
            byOrigin = groupCount("origin_category").let { raw ->
                val merged = LinkedHashMap<String, Int>()
                raw.forEach { (k, c) -> merged.merge(OriginCategory.fromKey(k).key, c) { a, b -> a + b } }
                merged
            },
            inconsistent = bad,
            source = "SQLITE",
        )
    }

    private fun groupCount(column: String): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        helper.readableDatabase.rawQuery(
            "SELECT $column, COUNT(*) FROM ${ArchiveDb.TABLE_ARTICLES} " +
                "WHERE sync_status NOT IN ('${SyncStatus.DELETED_LOCAL}','${SyncStatus.DELETED_SYNCED}') " +
                "GROUP BY $column", null,
        ).use { c ->
            while (c.moveToNext()) out[c.getString(0) ?: ""] = c.getInt(1)
        }
        return out
    }

    // ===================== 已读标记 =====================

    suspend fun markRead(contentId: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.execSQL(
            "UPDATE ${ArchiveDb.TABLE_ARTICLES} SET is_read = 1, updated_at = ? WHERE content_id = ?",
            arrayOf(System.currentTimeMillis(), contentId),
        )
    }

    /** 批量标记已读（用于「只标记本次同步的条目」）。分批 500 个 id，避开 SQLite 变量数上限。 */
    suspend fun markRead(contentIds: Collection<String>): Int = withContext(Dispatchers.IO) {
        if (contentIds.isEmpty()) return@withContext 0
        val db = helper.writableDatabase
        var affected = 0
        contentIds.chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val stmt = db.compileStatement(
                "UPDATE ${ArchiveDb.TABLE_ARTICLES} SET is_read = 1, updated_at = ${System.currentTimeMillis()} " +
                    "WHERE content_id IN ($placeholders)"
            )
            chunk.forEachIndexed { i, id -> stmt.bindString(i + 1, id) }
            affected += stmt.executeUpdateDelete()
            stmt.close()
        }
        affected
    }

    // ===================== 阅读页本地标记（实际已读 / 高价值）=====================

    /**
     * 写「实际已读」标志。
     *
     * 与 [markRead]（supsub 服务端的 is_read，用于拉取去重）**完全独立**：
     * 这个标志只代表「用户在阅读页里实际读过了」，永不回传 supsub、永不参与拉取/去重判定。
     */
    suspend fun setUserRead(contentId: String, value: Boolean) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        helper.writableDatabase.execSQL(
            "UPDATE ${ArchiveDb.TABLE_ARTICLES} SET is_user_read = ?, user_read_dirty = 1, local_edited_at = ?, updated_at = ? WHERE content_id = ?",
            arrayOf(if (value) 1 else 0, now, now, contentId),
        )
        insertHistory(
            contentId, "LOCAL_EDIT", now, "LOCAL", "is_user_read",
            if (value) "0" else "1", if (value) "1" else "0", null,
        )
    }

    /** 写「高价值」标志。若该条已同步到 Notion，调用方应再调 [markPropResyncIfSynced] 标为属性级重推，下次同步 PATCH 该 checkbox */
    suspend fun setHighValue(contentId: String, value: Boolean) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        helper.writableDatabase.execSQL(
            "UPDATE ${ArchiveDb.TABLE_ARTICLES} SET is_high_value = ?, high_value_dirty = 1, local_edited_at = ?, updated_at = ? WHERE content_id = ?",
            arrayOf(if (value) 1 else 0, now, now, contentId),
        )
        insertHistory(
            contentId, "LOCAL_EDIT", now, "LOCAL", "is_high_value",
            if (value) "0" else "1", if (value) "1" else "0", null,
        )
    }

    /**
     * 该条已同步到 Notion（有页面 id 且 sync_status=SYNCED）时，切「实际已读」后把它标为属性级重推
     * （[SyncStatus.PROP_RESNC]），下次同步只 PATCH「实际已读」这个 checkbox、不重建正文，幂等且请求量最小。
     * 若本条尚未同步（PENDING/FAILED）则不动——它下次正常同步时本就会带上新的 is_user_read。
     */
    suspend fun markPropResyncIfSynced(contentId: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.execSQL(
            """
            UPDATE ${ArchiveDb.TABLE_ARTICLES}
            SET sync_status = '${SyncStatus.PROP_RESNC}', updated_at = ?
            WHERE content_id = ? AND sync_status = '${SyncStatus.SYNCED}'
              AND notion_page_id IS NOT NULL AND notion_page_id <> ''
            """.trimIndent(),
            arrayOf(System.currentTimeMillis(), contentId),
        )
    }


    suspend fun markSourceRead(sourceType: String, sourceId: Long): Int = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val stmt = db.compileStatement(
            "UPDATE ${ArchiveDb.TABLE_ARTICLES} SET is_read = 1, updated_at = ? WHERE source_type = ? AND source_id = ?"
        )
        stmt.bindLong(1, System.currentTimeMillis())
        stmt.bindString(2, sourceType)
        stmt.bindLong(3, sourceId)
        val n = stmt.executeUpdateDelete()
        stmt.close()
        n
    }

    /**
     * 用「服务端未读集合」对账本地已读状态。
     *
     * [unreadIdsByType] 是各来源类型下**仍为未读**的 contentId 集合（来自 supsub 服务端 type=unread 查询）。
     * 以此为真相源做双向对账：
     *  - 本地某篇属于该类型、且 contentId 在集合内  → 服务端仍未读       → 本地置 `is_read = 0`
     *  - 本地某篇属于该类型、且 contentId 不在集合内 → 服务端已不计入未读（已读/已滚出）→ 本地置 `is_read = 1`
     *
     * 只处理 [unreadIdsByType] 里出现的来源类型，其余类型完全不动，避免误改。
     * 实现上对每个类型先整型置已读、再把未读子集置回未读，规避 `NOT IN` 超长占位符的问题，
     * 写路径延续本类「不显式开事务」的约定（单次拉取量小、逐句自动提交即可，且规避 PKJ110 事务挂死）。
     *
     * @return 实际发生变更的 is_read 行数（含置 0 与置 1）
     */
    suspend fun syncReadStateFromServerUnread(unreadIdsByType: Map<String, Set<String>>): Int = withContext(Dispatchers.IO) {
        if (unreadIdsByType.isEmpty()) return@withContext 0
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        var changed = 0
        for ((type, unreadIds) in unreadIdsByType) {
            if (type.isBlank()) continue
            // 1) 该类型默认全部置已读：服务端未读清单之外的都视为已读
            val markAllRead = db.compileStatement(
                "UPDATE ${ArchiveDb.TABLE_ARTICLES} SET is_read = 1, updated_at = ? " +
                    "WHERE source_type = ? AND is_read <> 1"
            ).also {
                it.bindLong(1, now)
                it.bindString(2, type)
            }
            changed += markAllRead.executeUpdateDelete()
            markAllRead.close()
            // 2) 未读子集（contentId 在服务端未读集合内）置回未读
            unreadIds.chunked(500).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                val stmt = db.compileStatement(
                    "UPDATE ${ArchiveDb.TABLE_ARTICLES} SET is_read = 0, updated_at = ? " +
                        "WHERE source_type = ? AND content_id IN ($placeholders) AND is_read <> 0"
                )
                stmt.bindLong(1, now)
                stmt.bindString(2, type)
                chunk.forEachIndexed { i, id -> stmt.bindString(3 + i, id) }
                changed += stmt.executeUpdateDelete()
                stmt.close()
            }
        }
        Log.i("ArchiveStore", "syncReadStateFromServerUnread: 对账类型=${unreadIdsByType.keys}，变更 is_read 行数=$changed")
        changed
    }

    // ===================== 同步状态回写 =====================

    /** 同步成功：记录页面 id、所属子库 id 与两个哈希（内容哈希用于跳过、正文哈希用于短路重写）。
     * @param remoteEditedAtMs Notion 页面被本次写入后的 last_edited_time（ms）；>0 时才写入
     *        notion_last_edited_at（避免把已探明的时间戳覆盖回 0）。用于双向同步的「上次已知远端时间」。 */
    suspend fun markSynced(
        contentId: String,
        pageId: String,
        databaseId: String,
        contentHash: String,
        bodyHash: String,
        remoteEditedAtMs: Long = 0,
    ) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        // remoteEditedAtMs>0 时才更新 notion_last_edited_at，否则保留上次探到的值（不回退成 0）。
        // 同时把 local_edited_at 对齐到推送时间：推送成功即代表「本地内容已在此时固化到远端」，
        // 让本地与远端时间线相等，随后的拉取阶段不会把刚推完的条目又拉回来（避免无意义写入与拉取计数虚高）。
        val sql = if (remoteEditedAtMs > 0) {
            """
            UPDATE ${ArchiveDb.TABLE_ARTICLES}
            SET sync_status = '${SyncStatus.SYNCED}', notion_page_id = ?, notion_db_id = ?,
                synced_hash = ?, synced_body_hash = ?, notion_last_edited_at = ?, local_edited_at = ?,
                user_read_dirty = 0, high_value_dirty = 0,
                retry_count = 0, last_error = NULL, updated_at = ?
            WHERE content_id = ?
            """.trimIndent()
        } else {
            """
            UPDATE ${ArchiveDb.TABLE_ARTICLES}
            SET sync_status = '${SyncStatus.SYNCED}', notion_page_id = ?, notion_db_id = ?,
                synced_hash = ?, synced_body_hash = ?, user_read_dirty = 0, high_value_dirty = 0,
                retry_count = 0, last_error = NULL, updated_at = ?
            WHERE content_id = ?
            """.trimIndent()
        }
        val args = if (remoteEditedAtMs > 0) {
            arrayOf(pageId, databaseId, contentHash, bodyHash, remoteEditedAtMs, remoteEditedAtMs, now, contentId)
        } else {
            arrayOf(pageId, databaseId, contentHash, bodyHash, now, contentId)
        }
        db.execSQL(sql, args)
    }

    /**
     * 双向同步「拉取」回写：把 Notion 端更新后的结构化属性写回本地行。
     *
     * 仅更新 [NotionRemotePatch] 中非 null 的字段（COALESCE 语义），远端没填的列不动本地；
     * 同时把 notion_last_edited_at 与 local_edited_at 都刷新为远端时间，
     * 使「两端时间线对齐」——下次同步时远端不再显得更新，避免无意义的反复拉取。
     * v1 不碰正文/摘要（它们不在 properties 里，pull 阶段根本取不到）。
     *
     * @return 本次是否真的改动了本地行。Notion 的 last_edited_time 只有分钟精度，
     * pull 侧门禁必须放宽到「远端时间 >= 本地已知远端版本」才不漏掉同一分钟内的对端改动，
     * 代价是每篇都会走到这里；因此这里先做一次全字段比对，**完全没差异就不写库也不计数**，
     * 否则全量同步会退化成每轮 1000+ 次无谓 UPDATE + 噪声日志。
     */
    suspend fun markPulled(
        contentId: String,
        patch: NotionRemotePatch,
        remoteEditedAtMs: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        // 1) 回写前先快照可能被改的列（用于逐字段记历史）
        val old = HashMap<String, String?>()
        db.rawQuery(
            "SELECT title,source_name,source_type,origin_category,focus_title,published_at,tags,url," +
                "keywords,content_type,content_depth,tone,style,has_action,has_stance,stance_summary," +
                "cluster_label,cluster_size,is_cluster_rep,is_user_read,is_high_value," +
                "user_read_dirty,high_value_dirty,local_edited_at,notion_last_edited_at,summary,cover_image " +
                "FROM ${ArchiveDb.TABLE_ARTICLES} WHERE content_id = ?",
            arrayOf(contentId),
        ).use { c ->
            if (c.moveToFirst()) {
                for (col in arrayOf(
                    "title", "source_name", "source_type", "origin_category", "focus_title", "published_at",
                    "tags", "url", "keywords", "content_type", "content_depth", "tone", "style",
                    "has_action", "has_stance", "stance_summary", "cluster_label", "cluster_size",
                    "is_cluster_rep", "is_user_read", "is_high_value",
                    "user_read_dirty", "high_value_dirty", "local_edited_at", "notion_last_edited_at",
                    "summary", "cover_image",
                )) {
                    old[col] = c.getString(c.getColumnIndexOrThrow(col))
                }
            }
        }
        // ── v15：字段级合并（解决 ⑤-B 并发改不同字段不收敛）──
        // is_user_read / is_high_value 是用户可在两端独立改的受管列。拉取回写时按「本地脏标记」逐字段判定：
        //  · 本地该字段无未同步改动（不脏）→ 采用远端值（云端覆盖本地，正常传播）；
        //  · 本地该字段脏（用户刚改、尚未推送）→ 保留本地值，不被远端陈旧值覆盖
        //    （远端该字段并未被对端改动，只是本设备上次同步后没再推过；等下次推送即上行本地值）。
        // 注意不能用「远端页面 last_edited_time 是否更晚」来判定单字段胜负：该时间是页面级、混了其它字段，
        // 否则手机会用自己刚推的 read 的时间戳把平板未推送的 high 误判为「对端更新」而覆盖掉。
        // 因此跨字段并发改（手机 read + 平板 high）时两端各自保留本地改动，再各自推送，最终收敛为 (high=1,read=1)。
        val localReadDirty = old["user_read_dirty"] == "1"
        val localHighDirty = old["high_value_dirty"] == "1"
        val localReadBit = old["is_user_read"].takeIf { it == "1" || it == "0" } ?: "0"
        val localHighBit = old["is_high_value"].takeIf { it == "1" || it == "0" } ?: "0"
        val remoteReadBit = if (patch.isUserRead == true) "1" else "0"
        val remoteHighBit = if (patch.isHighValue == true) "1" else "0"
        fun decide(localDirty: Boolean, localBit: String, remoteBit: String): Pair<Boolean, String> {
            if (!localDirty) return true to remoteBit          // 本地无未同步改动 → 采用远端
            if (localBit == remoteBit) return true to remoteBit // 两端一致 → 采用并清脏
            return false to localBit                            // 本地脏且不一致 → 保留本地，等下次推送
        }
        val (adoptRead, readBit) = decide(localReadDirty, localReadBit, remoteReadBit)
        val (adoptHigh, highBit) = decide(localHighDirty, localHighBit, remoteHighBit)
        // 2) 先做全字段比对：远端与本地完全一致（门禁放宽后的绝大多数情况）就直接返回，
        //    既不写库也不计数，避免每轮同步对上千行做无意义 UPDATE。
        val specs = listOf(
            "title" to patch.title,
            "source_name" to patch.sourceName,
            "source_type" to patch.sourceType,
            "origin_category" to patch.originCategory,
            "focus_title" to patch.focusTitle,
            "published_at" to patch.publishedAt?.toString(),
            "tags" to patch.tags,
            "url" to patch.url,
            "keywords" to patch.keywords,
            "content_type" to patch.contentType,
            "content_depth" to patch.contentDepth,
            "tone" to patch.tone,
            "style" to patch.style,
            "has_action" to patch.hasAction?.let { if (it) "1" else "0" },
            "has_stance" to patch.hasStance?.let { if (it) "1" else "0" },
            "stance_summary" to patch.stanceSummary,
            "cluster_label" to patch.clusterLabel,
            "cluster_size" to patch.clusterSize?.toString(),
            "is_cluster_rep" to patch.isClusterRep?.let { if (it) "1" else "0" },
            "is_user_read" to readBit,
            "is_high_value" to highBit,
        )
        val diffs = specs.filter { (col, newVal) -> newVal != null && old[col] != newVal }
        val bodyDiff = (patch.summary != null && old["summary"] != patch.summary) ||
            (patch.coverImage != null && old["cover_image"] != patch.coverImage)
        val nextReadDirty = if (adoptRead) "0" else "1"
        val nextHighDirty = if (adoptHigh) "0" else "1"
        val dirtyChanged = old["user_read_dirty"] != nextReadDirty || old["high_value_dirty"] != nextHighDirty
        val timeChanged = (old["notion_last_edited_at"]?.toLongOrNull() ?: 0L) != remoteEditedAtMs
        if (diffs.isEmpty() && !bodyDiff && !dirtyChanged && !timeChanged) return@withContext false
        // 本地该字段脏（有未推送改动）时不要把 local_edited_at 往回拨到远端时间，
        // 否则会抹掉「本地比远端新」的事实，影响冲突展示。
        val keepLocalEdit = !adoptRead || !adoptHigh
        val oldLocalEdit = old["local_edited_at"]?.toLongOrNull() ?: 0L
        // 3) 照旧 COALESCE 回写（仅非 null 字段覆盖本地）
        val cv = ContentValues().apply {
            patch.title?.let { put("title", it) }
            patch.sourceName?.let { put("source_name", it) }
            patch.sourceType?.let { put("source_type", it) }
            patch.originCategory?.let { put("origin_category", it) }
            patch.focusTitle?.let { put("focus_title", it) }
            patch.publishedAt?.let { put("published_at", it) }
            patch.tags?.let { put("tags", it) }
            patch.url?.let { put("url", it) }
            patch.keywords?.let { put("keywords", it) }
            patch.contentType?.let { put("content_type", it) }
            patch.contentDepth?.let { put("content_depth", it) }
            patch.tone?.let { put("tone", it) }
            patch.style?.let { put("style", it) }
            patch.hasAction?.let { put("has_action", if (it) 1 else 0) }
            patch.hasStance?.let { put("has_stance", if (it) 1 else 0) }
            patch.stanceSummary?.let { put("stance_summary", it) }
            patch.clusterLabel?.let { put("cluster_label", it) }
            patch.clusterSize?.let { put("cluster_size", it) }
            patch.isClusterRep?.let { put("is_cluster_rep", if (it) 1 else 0) }
            // v15 字段级合并结果：adoptRead/adoptHigh 决定是否采用远端，否则保留本地未同步改动；同时维护脏标记
            put("is_user_read", if (readBit == "1") 1 else 0)
            put("user_read_dirty", if (adoptRead) 0 else 1)
            put("is_high_value", if (highBit == "1") 1 else 0)
            put("high_value_dirty", if (adoptHigh) 0 else 1)
            // 摘要/封面：属性级无损重建，让冷启动平板能与手机做到「内容字段完全一致」
            patch.summary?.let { put("summary", it) }
            patch.coverImage?.let { put("cover_image", it) }
            // 两端时间线对齐：拉取后本地「编辑时间」等于远端时间，下次不再被判定为远端更新
            put("notion_last_edited_at", remoteEditedAtMs)
            put("local_edited_at", if (keepLocalEdit) maxOf(oldLocalEdit, remoteEditedAtMs) else remoteEditedAtMs)
            put("updated_at", System.currentTimeMillis())
        }
        db.update(ArchiveDb.TABLE_ARTICLES, cv, "content_id = ?", arrayOf(contentId))
        // 4) 真正变化的字段各记一条 PULL_OVERRIDE（云端覆盖本地）
        for ((col, newVal) in diffs) {
            insertHistory(contentId, "PULL_OVERRIDE", remoteEditedAtMs, "CLOUD", col, old[col], newVal, null)
        }
        // 只有业务字段真的变了才算「拉回一篇」；仅时间戳/脏标记对齐不计入
        diffs.isNotEmpty() || bodyDiff
    }

    // ===================== 文章历史流水 =====================

    /** 写入一条历史事件（内部使用；各业务方法在状态变更时调用） */
    private suspend fun insertHistory(
        contentId: String,
        eventType: String,
        eventTime: Long,
        actor: String,
        field: String?,
        oldValue: String?,
        newValue: String?,
        detail: String?,
    ) = withContext(Dispatchers.IO) {
        helper.writableDatabase.execSQL(
            "INSERT INTO ${ArchiveDb.TABLE_HISTORY} " +
                "(content_id, event_type, event_time, actor, field, old_value, new_value, detail) " +
                "VALUES (?,?,?,?,?,?,?,?)",
            arrayOf(contentId, eventType, eventTime, actor, field, oldValue, newValue, detail),
        )
    }

    /** 引擎在「真正推送成功（Updated）」时调用：记录一次本地覆盖云端。skip 不计。 */
    suspend fun recordPush(contentId: String, pageId: String) = withContext(Dispatchers.IO) {
        insertHistory(
            contentId, "PUSH", System.currentTimeMillis(), "LOCAL", "*", null, null,
            "本地推送到 Notion（page=$pageId）",
        )
    }

    /**
     * 评论生命周期事件（新增/编辑/删除/推送/拉回）写入历史流水。
     * 与 insertHistory 同表（article_history），仅多带 comment_id 关联列。
     */
    private suspend fun insertCommentHistory(
        contentId: String,
        commentId: Long,
        eventType: String,
        eventTime: Long,
        actor: String,
        field: String?,
        oldValue: String?,
        newValue: String?,
        detail: String?,
    ) = withContext(Dispatchers.IO) {
        helper.writableDatabase.execSQL(
            "INSERT INTO ${ArchiveDb.TABLE_HISTORY} " +
                "(content_id, comment_id, event_type, event_time, actor, field, old_value, new_value, detail) " +
                "VALUES (?,?,?,?,?,?,?,?,?)",
            arrayOf(
                contentId, commentId.toString(), eventType, eventTime, actor,
                field, oldValue, newValue, detail,
            ),
        )
    }

    /** 读取某篇文章的历史流水（时间倒序） */
    suspend fun listHistory(contentId: String): List<HistoryEntry> = withContext(Dispatchers.IO) {
        val out = ArrayList<HistoryEntry>()
        helper.readableDatabase.rawQuery(
            "SELECT id, content_id, event_type, event_time, actor, field, old_value, new_value, detail " +
                "FROM ${ArchiveDb.TABLE_HISTORY} WHERE content_id = ? ORDER BY event_time DESC, id DESC",
            arrayOf(contentId),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    HistoryEntry(
                        id = c.getLong(0),
                        contentId = c.getString(1),
                        eventType = c.getString(2),
                        eventTime = c.getLong(3),
                        actor = c.getString(4),
                        field = c.getString(5),
                        oldValue = c.getString(6),
                        newValue = c.getString(7),
                        detail = c.getString(8),
                    ),
                )
            }
        }
        out
    }

    // ===================== 本地评论（与 Notion 双向同步）=====================

    /** 新增一条本地评论（LOCAL_NEW），author 默认「土豆」。返回新行 id。 */
    suspend fun addComment(contentId: String, body: String, author: String = "土豆"): Long =
        withContext(Dispatchers.IO) {
            val id = helper.writableDatabase.insert(
                ArchiveDb.TABLE_COMMENTS,
                null,
                ContentValues().apply {
                    put("content_id", contentId)
                    put("body", body)
                    put("author", author)
                    put("created_at", System.currentTimeMillis())
                    put("sync_state", CommentSyncState.LOCAL_NEW)
                    put("actor", "LOCAL")
                    put("deleted", 0)
                },
            )
            if (id > 0) {
                insertCommentHistory(
                    contentId, id, "COMMENT_ADD", System.currentTimeMillis(), "LOCAL",
                    null, null, body, "新增评论（作者=$author）",
                )
            }
            id
        }

    /** 编辑本地评论正文：LOCAL_NEW 保持原态；SYNCED 翻成 EDIT_PENDING（下次同步删旧+重建）。 */
    suspend fun editCommentLocal(id: Long, body: String) = withContext(Dispatchers.IO) {
        val old = helper.readableDatabase.rawQuery(
            "SELECT content_id, body FROM ${ArchiveDb.TABLE_COMMENTS} WHERE id = ? AND deleted = 0",
            arrayOf(id.toString()),
        ).use { c -> if (c.moveToFirst()) c.getString(0) to (c.getString(1) ?: "") else null }
        helper.writableDatabase.execSQL(
            "UPDATE ${ArchiveDb.TABLE_COMMENTS} SET body = ?, edited_at = ?, " +
                "sync_state = CASE WHEN sync_state = '${CommentSyncState.SYNCED}' " +
                "THEN '${CommentSyncState.EDIT_PENDING}' ELSE sync_state END " +
                "WHERE id = ? AND deleted = 0",
            arrayOf(body, System.currentTimeMillis(), id),
        )
        old?.let { (cid, oldBody) ->
            insertCommentHistory(
                cid, id, "COMMENT_EDIT", System.currentTimeMillis(), "LOCAL",
                null, oldBody, body, "本地编辑评论",
            )
        }
    }

    /** 本地删除：置 deleted=1 + DELETED_LOCAL（待同步删云端或仅本地清理）。 */
    suspend fun deleteCommentLocal(id: Long) = withContext(Dispatchers.IO) {
        val cid = helper.readableDatabase.rawQuery(
            "SELECT content_id FROM ${ArchiveDb.TABLE_COMMENTS} WHERE id = ?",
            arrayOf(id.toString()),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        helper.writableDatabase.execSQL(
            "UPDATE ${ArchiveDb.TABLE_COMMENTS} SET deleted = 1, sync_state = '${CommentSyncState.DELETED_LOCAL}' WHERE id = ?",
            arrayOf(id),
        )
        cid?.let {
            insertCommentHistory(it, id, "COMMENT_DELETE", System.currentTimeMillis(), "LOCAL", null, null, null, "本地删除评论")
        }
    }

    /** 推送成功后回填 notion_comment_id + notion_page_id，状态置 SYNCED，并记录同步时间。 */
    suspend fun markCommentSynced(id: Long, notionCommentId: String, pageId: String) = withContext(Dispatchers.IO) {
        val cid = helper.readableDatabase.rawQuery(
            "SELECT content_id FROM ${ArchiveDb.TABLE_COMMENTS} WHERE id = ?",
            arrayOf(id.toString()),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        helper.writableDatabase.execSQL(
            "UPDATE ${ArchiveDb.TABLE_COMMENTS} SET notion_comment_id = ?, notion_page_id = ?, " +
                "sync_state = '${CommentSyncState.SYNCED}', synced_at = ? WHERE id = ?",
            arrayOf(notionCommentId, pageId, System.currentTimeMillis(), id),
        )
        cid?.let {
            insertCommentHistory(it, id, "COMMENT_PUSH", System.currentTimeMillis(), "LOCAL", null, null, null, "推送成功（notion_comment_id=$notionCommentId）")
        }
    }

    /** 本地标记评论已在两端删除（墓碑）。 */
    suspend fun markCommentDeletedSynced(id: Long) = withContext(Dispatchers.IO) {
        val cid = helper.readableDatabase.rawQuery(
            "SELECT content_id FROM ${ArchiveDb.TABLE_COMMENTS} WHERE id = ?",
            arrayOf(id.toString()),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        helper.writableDatabase.execSQL(
            "UPDATE ${ArchiveDb.TABLE_COMMENTS} SET sync_state = '${CommentSyncState.DELETED_SYNCED}', deleted = 1, synced_at = ? WHERE id = ?",
            arrayOf(System.currentTimeMillis(), id),
        )
        cid?.let {
            insertCommentHistory(it, id, "COMMENT_DELETE_SYNC", System.currentTimeMillis(), "LOCAL", null, null, null, "本地删除已同步到云端")
        }
    }

    /** 云端评论被删（pull 对账发现远端消失）：本地标 tombstone，并记录同步时间。 */
    suspend fun markCommentDeletedLocal(id: Long) = withContext(Dispatchers.IO) {
        val cid = helper.readableDatabase.rawQuery(
            "SELECT content_id FROM ${ArchiveDb.TABLE_COMMENTS} WHERE id = ?",
            arrayOf(id.toString()),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        helper.writableDatabase.execSQL(
            "UPDATE ${ArchiveDb.TABLE_COMMENTS} SET deleted = 1, sync_state = '${CommentSyncState.DELETED_LOCAL}', synced_at = ? WHERE id = ?",
            arrayOf(System.currentTimeMillis(), id),
        )
        cid?.let {
            insertCommentHistory(it, id, "COMMENT_PULL_DELETE", System.currentTimeMillis(), "CLOUD", null, null, null, "云端评论已删除，本地标记删除")
        }
    }

    /** 列出某篇文章的可见评论（deleted=0，按创建时间升序），供 UI 展示。 */
    suspend fun listComments(contentId: String): List<CommentEntry> = withContext(Dispatchers.IO) {
        cursorComments(
            "SELECT id, content_id, notion_page_id, notion_comment_id, body, author, created_at, " +
                "edited_at, sync_state, deleted, actor, synced_at FROM ${ArchiveDb.TABLE_COMMENTS} " +
                "WHERE content_id = ? AND deleted = 0 ORDER BY created_at ASC, id ASC",
            arrayOf(contentId),
        )
    }

    /** 拉回的云端评论入库（actor=CLOUD，本地只读）。 */
    suspend fun insertCloudComment(
        contentId: String,
        pageId: String,
        notionCommentId: String,
        body: String,
        createdAt: Long = System.currentTimeMillis(),
    ): Long = withContext(Dispatchers.IO) {
        val id = helper.writableDatabase.insert(
            ArchiveDb.TABLE_COMMENTS,
            null,
            ContentValues().apply {
                put("content_id", contentId)
                put("notion_page_id", pageId)
                put("notion_comment_id", notionCommentId)
                put("body", body)
                put("author", "Notion")
                put("created_at", createdAt)
                put("synced_at", System.currentTimeMillis())
                put("sync_state", CommentSyncState.SYNCED)
                put("actor", "CLOUD")
                put("deleted", 0)
            },
        )
        if (id > 0) {
            insertCommentHistory(contentId, id, "COMMENT_PULL_ADD", System.currentTimeMillis(), "CLOUD", null, null, body, "从 Notion 拉回评论")
        }
        id
    }

    /**
     * 拉回的云端评论：upsert（而非只插不更新）。
     * - 本地无该 notion_comment_id → 插入（actor=CLOUD）。
     * - 本地已有（同 id，即同一云端评论被用户编辑过）且文本不同 → 仅更新 body，
     *   让「在 Notion 网页端编辑评论」也能同步回本地。保留原 actor 不变。
     * 返回：0=无变化 / 1=新增 / 2=更新。
     * 仅对 sync_state=SYNCED 的行做更新，避免覆盖本地待推送(LOCAL_NEW/EDIT_PENDING)
     * 或已删(DELETED_LOCAL)的行——那些交由 push / tombstone 流程处理。
     */
    suspend fun upsertCloudComment(
        contentId: String,
        pageId: String,
        notionCommentId: String,
        body: String,
    ): Int = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.rawQuery(
            "SELECT id, body FROM ${ArchiveDb.TABLE_COMMENTS} " +
                "WHERE content_id = ? AND notion_comment_id = ? AND sync_state = '${CommentSyncState.SYNCED}' LIMIT 1",
            arrayOf(contentId, notionCommentId),
        ).use { c ->
            if (c.moveToFirst()) {
                val localBody = c.getString(1) ?: ""
                if (localBody != body) {
                    db.execSQL(
                        "UPDATE ${ArchiveDb.TABLE_COMMENTS} SET body = ?, notion_page_id = ?, deleted = 0, synced_at = ? " +
                            "WHERE id = ?",
                        arrayOf(body, pageId, System.currentTimeMillis(), c.getLong(0)),
                    )
                    insertCommentHistory(
                        contentId, c.getLong(0), "COMMENT_PULL_EDIT",
                        System.currentTimeMillis(), "CLOUD", null, localBody, body, "云端编辑后拉回（本地更新）",
                    )
                    return@withContext 2
                }
                return@withContext 0
            }
        }
        insertCloudComment(contentId, pageId, notionCommentId, body)
        return@withContext 1
    }

    private suspend fun cursorComments(sql: String, args: Array<String>): List<CommentEntry> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<CommentEntry>()
            helper.readableDatabase.rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        CommentEntry(
                            id = c.getLong(0),
                            contentId = c.getString(1),
                            notionPageId = c.getString(2),
                            notionCommentId = c.getString(3),
                            body = c.getString(4) ?: "",
                            author = c.getString(5) ?: "土豆",
                            createdAt = c.getLong(6),
                            editedAt = if (c.isNull(7)) null else c.getLong(7),
                            syncedAt = if (c.isNull(11)) 0 else c.getLong(11),
                            syncState = c.getString(8) ?: CommentSyncState.LOCAL_NEW,
                            deleted = c.getInt(9) != 0,
                            actor = c.getString(10) ?: "LOCAL",
                        ),
                    )
                }
            }
            out
        }

    /** 待推送：本地新建未同步 */
    suspend fun localNewComments(contentId: String): List<CommentEntry> = withContext(Dispatchers.IO) {
        cursorComments(
            "SELECT id,content_id,notion_page_id,notion_comment_id,body,author,created_at,edited_at," +
                "sync_state,deleted,actor,synced_at FROM ${ArchiveDb.TABLE_COMMENTS} " +
                "WHERE content_id = ? AND sync_state = '${CommentSyncState.LOCAL_NEW}' AND deleted = 0",
            arrayOf(contentId),
        )
    }

    /** 待推送：已同步后被本地编辑（删旧+重建） */
    suspend fun editPendingComments(contentId: String): List<CommentEntry> = withContext(Dispatchers.IO) {
        cursorComments(
            "SELECT id,content_id,notion_page_id,notion_comment_id,body,author,created_at,edited_at," +
                "sync_state,deleted,actor,synced_at FROM ${ArchiveDb.TABLE_COMMENTS} " +
                "WHERE content_id = ? AND sync_state = '${CommentSyncState.EDIT_PENDING}'",
            arrayOf(contentId),
        )
    }

    /** 待推送：本地已删除（需删云端；无 notion_comment_id 的仅本地清理） */
    suspend fun deletedLocalComments(contentId: String): List<CommentEntry> = withContext(Dispatchers.IO) {
        cursorComments(
            "SELECT id,content_id,notion_page_id,notion_comment_id,body,author,created_at,edited_at," +
                "sync_state,deleted,actor,synced_at FROM ${ArchiveDb.TABLE_COMMENTS} " +
                "WHERE content_id = ? AND sync_state = '${CommentSyncState.DELETED_LOCAL}' AND deleted = 1",
            arrayOf(contentId),
        )
    }

    /** 已同步（SYNCED）评论，用于 pull 对账时按 notion_comment_id 找本地行 */
    suspend fun syncedComments(contentId: String): List<CommentEntry> = withContext(Dispatchers.IO) {
        cursorComments(
            "SELECT id,content_id,notion_page_id,notion_comment_id,body,author,created_at,edited_at," +
                "sync_state,deleted,actor,synced_at FROM ${ArchiveDb.TABLE_COMMENTS} " +
                "WHERE content_id = ? AND sync_state = '${CommentSyncState.SYNCED}' AND deleted = 0",
            arrayOf(contentId),
        )
    }

    /** 按 notion_comment_id 判断该云端评论本地是否已存在（pull 去重） */
    suspend fun hasComment(contentId: String, notionCommentId: String): Boolean = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT 1 FROM ${ArchiveDb.TABLE_COMMENTS} WHERE content_id = ? AND notion_comment_id = ? LIMIT 1",
            arrayOf(contentId, notionCommentId),
        ).use { it.moveToFirst() }
    }

    /** 该文章是否在本地有任何评论行（无论状态/actor）。用于评论同步早退：无任何评论则跳过网络请求，避免全量 listComments 风暴。 */
    suspend fun hasAnyComments(contentId: String): Boolean = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT 1 FROM ${ArchiveDb.TABLE_COMMENTS} WHERE content_id = ? LIMIT 1",
            arrayOf(contentId),
        ).use { it.moveToFirst() }
    }

    /**
     * 取出所有已映射到 Notion 页面的本地行（轻量投影，仅带 LWW 比较所需时间字段）。
     * 双向同步拉取阶段按 notion_db_id 分组后，每个子库一次 queryDatabase 拉全量远端状态。
     */
    suspend fun loadNotionMapped(): List<NotionMappedRow> = withContext(Dispatchers.IO) {
        val out = ArrayList<NotionMappedRow>()
        helper.readableDatabase.rawQuery(
            """
            SELECT content_id, title, source_name, notion_page_id, notion_db_id, local_edited_at, notion_last_edited_at
            FROM ${ArchiveDb.TABLE_ARTICLES}
            WHERE notion_page_id IS NOT NULL AND notion_page_id <> ''
              AND notion_db_id IS NOT NULL AND notion_db_id <> ''
            """.trimIndent(),
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    NotionMappedRow(
                        contentId = c.getString(0),
                        title = c.getString(1) ?: "",
                        sourceName = c.getString(2) ?: "",
                        notionPageId = c.getString(3),
                        notionDatabaseId = c.getString(4),
                        localEditedAt = c.getLong(5),
                        notionLastEditedAt = c.getLong(6),
                    )
                )
            }
        }
        out
    }

    /**
     * 冷启动 / 跨设备合并：把 Notion 远端页（含 PROP_CONTENT_ID）还原为一条本地 articles 行。
     * 内容字段来自 [NotionRemotePatch]（与 [markPulled] 镜像），同步态置 SYNCED、映射指向远端页，
     * 时间线与远端对齐（local_edited_at = notion_last_edited_at = remoteTime），保证「DB 内容字段完全一致」。
     * 命中墓碑（防复活）则跳过，返回 false。
     */
    suspend fun insertFromRemote(
        contentId: String, pageId: String, dbId: String,
        patch: NotionRemotePatch, remoteTime: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        if (isTombstoned(contentId)) return@withContext false
        val shardKey = ShardKeys.of(patch.publishedAt ?: 0L, patch.publishedAt ?: 0L)
        val record = ArticleRecord(
            contentId = contentId,
            sourceType = patch.sourceType ?: "",
            originCategory = patch.originCategory ?: OriginCategory.SUBSCRIPTION.key,
            sourceName = patch.sourceName ?: "",
            focusTitle = patch.focusTitle ?: "",
            title = patch.title ?: "",
            summary = patch.summary,
            tags = patch.tags ?: "[]",
            keywords = patch.keywords ?: "[]",
            url = patch.url ?: "",
            coverImage = patch.coverImage,
            // patch.publishedAt 已是分钟精度（Notion date 属性还原），这里再向下取整一次确保与本地存储口径一致
            publishedAt = patch.publishedAt?.let { if (it > 0) (it / 60) * 60 else it } ?: 0L,
            // patch.publishedAt 是「秒」级时间戳（Notion date 属性经 dateSecOf 还原），
            // 而 articles.captured_at 全表约定「毫秒」级（手机源库即毫秒）。必须 *1000 升到毫秒，
            // 否则冷启动重建的行会被当成 1970 年、按时间排序直接错乱。
            // 无 publishedAt 时退回拉取时刻（毫秒），保证单位始终为毫秒。
            capturedAt = patch.publishedAt?.let { it * 1000 } ?: System.currentTimeMillis(),
            contentType = patch.contentType ?: "",
            contentDepth = patch.contentDepth ?: "",
            tone = patch.tone ?: "",
            style = patch.style ?: "",
            hasAction = patch.hasAction ?: false,
            hasStance = patch.hasStance ?: false,
            stanceSummary = patch.stanceSummary ?: "",
            contentHash = "",
            shardKey = shardKey,
            syncStatus = SyncStatus.SYNCED,
            notionPageId = pageId,
            notionDatabaseId = dbId,
            syncedHash = "",
            syncedBodyHash = "",
            clusterLabel = patch.clusterLabel ?: "",
            clusterSize = patch.clusterSize ?: 0,
            isClusterRep = patch.isClusterRep ?: false,
            isUserRead = patch.isUserRead ?: false,
            isHighValue = patch.isHighValue ?: false,
            localEditedAt = remoteTime,
            notionLastEditedAt = remoteTime,
            updatedAt = System.currentTimeMillis(),
        )
        insertIfAbsent(record)
    }

    /** 本地物理删除一篇文章及其评论（删除同步最终态 / 墓碑防复活后清理） */
    suspend fun deleteLocal(contentId: String) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.execSQL("DELETE FROM ${ArchiveDb.TABLE_COMMENTS} WHERE content_id = ?", arrayOf(contentId))
        db.execSQL("DELETE FROM ${ArchiveDb.TABLE_ARTICLES} WHERE content_id = ?", arrayOf(contentId))
    }

    /** 待同步删除的本地行（sync_status = DELETED_LOCAL）：交给「删除 pass」推到云端 */
    suspend fun pendingDeleted(): List<DeletedLocalRow> = withContext(Dispatchers.IO) {
        val out = ArrayList<DeletedLocalRow>()
        helper.readableDatabase.rawQuery(
            "SELECT content_id, notion_page_id, notion_db_id FROM ${ArchiveDb.TABLE_ARTICLES} WHERE sync_status = '${SyncStatus.DELETED_LOCAL}'",
            null,
        ).use { c ->
            while (c.moveToNext()) out.add(DeletedLocalRow(c.getString(0), c.getString(1), c.getString(2)))
        }
        out
    }

    /** 删除已成功同步到云端：物理删本地行 + 写墓碑（防另一端陈旧 push 复活） */
    suspend fun markDeletedSynced(contentId: String) = withContext(Dispatchers.IO) {
        deleteLocal(contentId)
        insertTombstone(contentId, "synced_delete")
        insertHistory(contentId, "DELETE_SYNCED", System.currentTimeMillis(), "LOCAL", "*", null, null, null)
    }

    /** 删除 pass 用轻量投影：本地 contentId + 其远端页 id + 所在子库 id（为空表示从未上云，markDeletedLocal 已物理删） */
    data class DeletedLocalRow(val contentId: String, val notionPageId: String?, val notionDatabaseId: String?)

    suspend fun markSyncFailed(contentId: String, error: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.execSQL(
            """
            UPDATE ${ArchiveDb.TABLE_ARTICLES}
            SET sync_status = '${SyncStatus.FAILED}', retry_count = retry_count + 1,
                last_error = ?, updated_at = ?
            WHERE content_id = ?
            """.trimIndent(),
            arrayOf(error.take(300), System.currentTimeMillis(), contentId),
        )
    }

    // ===================== 删除同步（真同步删除，P0/P4）=====================

    /**
     * 写墓碑：记录该 contentId 已「真删除」，防止被另一端陈旧的 push 复活。
     * 删除是覆盖原「本地独有保留不删」的语义——任一台删除→远端标记 _del=true→另一台删本地行+写墓碑。
     */
    suspend fun insertTombstone(contentId: String, reason: String = "") = withContext(Dispatchers.IO) {
        helper.writableDatabase.insertWithOnConflict(
            ArchiveDb.TABLE_DELETED, null,
            ContentValues().apply {
                put("content_id", contentId)
                put("deleted_at", System.currentTimeMillis())
                put("reason", reason)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** 该 contentId 是否已在墓碑中（pull 跳过重建 / push 守门不重建的依据） */
    suspend fun isTombstoned(contentId: String): Boolean = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT 1 FROM ${ArchiveDb.TABLE_DELETED} WHERE content_id = ? LIMIT 1", arrayOf(contentId),
        ).use { it.moveToFirst() }
    }

    /** 本地删除：置 DELETED_LOCAL（待同步删云端）。若从未同步过（无 notion_page_id），直接物理删除更干净。 */
    suspend fun markDeletedLocal(contentId: String) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val hasPage = db.rawQuery(
            "SELECT 1 FROM ${ArchiveDb.TABLE_ARTICLES} WHERE content_id = ? AND notion_page_id IS NOT NULL AND notion_page_id <> '' LIMIT 1",
            arrayOf(contentId),
        ).use { it.moveToFirst() }
        if (hasPage) {
            db.execSQL(
                "UPDATE ${ArchiveDb.TABLE_ARTICLES} SET sync_status = '${SyncStatus.DELETED_LOCAL}', updated_at = ? WHERE content_id = ?",
                arrayOf(System.currentTimeMillis(), contentId),
            )
        } else {
            // 从未上云：物理删除即可，并写墓碑（防后续意外 push 又把同 contentId 拉回）
            db.execSQL("DELETE FROM ${ArchiveDb.TABLE_ARTICLES} WHERE content_id = ?", arrayOf(contentId))
            insertTombstone(contentId, "never_synced")
        }
        insertHistory(contentId, "LOCAL_DELETE", System.currentTimeMillis(), "LOCAL", "*", null, null, null)
    }

    /** 撤销本地删除（用户后悔/撤销）：把 DELETED_LOCAL 复原为 SYNCED，等待下次正常同步。 */
    suspend fun dropDeletedLocal(contentId: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.execSQL(
            "UPDATE ${ArchiveDb.TABLE_ARTICLES} SET sync_status = '${SyncStatus.SYNCED}', updated_at = ? WHERE content_id = ?",
            arrayOf(System.currentTimeMillis(), contentId),
        )
    }

    /** 列出所有墓碑 contentId（调试 / 清理用） */
    suspend fun allTombstones(): MutableSet<String> = withContext(Dispatchers.IO) {
        val out = HashSet<String>(64)
        helper.readableDatabase.rawQuery("SELECT content_id FROM ${ArchiveDb.TABLE_DELETED}", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        out
    }

    /**
     * 取出待同步的完整记录。
     *
     * 这是 V2 相对 V1 最大的提速点：V1 要把**全部** JSON 文件逐个读出来解析，
     * 才知道哪些需要同步（3767 篇就是 3767 次 SAF 单文档读）；
     * 现在一条带索引的 WHERE 就筛完，没变动的条目连行都不会被读出来。
     *
     * @param forceResync true 时忽略同步状态，全量重推
     * @param limit       <=0 表示不限
     */
    suspend fun pendingForSync(
        forceResync: Boolean,
        limit: Int = 0,
        singleContentId: String? = null,
    ): List<ArticleRecord> =
        withContext(Dispatchers.IO) {
        // content_hash 仅在入库时写一次、之后永不变，故 SYNCED 行的 synced_hash 必然等于 content_hash；
        // 原先 `OR synced_hash <> content_hash` 永远筛不出额外行，反而让 WHERE 失去索引（退化成全表扫描）。
        // 直接用 `sync_status NOT IN (...)` 即可走 idx_articles_sync_status 索引，只触达少量待同步行。
        // 关键（三方同步 P0/P4）：已删除态(DELETED_LOCAL/DELETED_SYNCED)必须排除，否则已删文章会被重新
        // 推送、把 Notion 端已被标记删除的页「复活」。删除由独立的「删除 pass」处理，不走普通推送通道。
        val where = buildString {
            append(
                if (forceResync) {
                    "WHERE sync_status NOT IN ('${SyncStatus.DELETED_LOCAL}','${SyncStatus.DELETED_SYNCED}') "
                } else {
                    "WHERE sync_status NOT IN ('${SyncStatus.SYNCED}','${SyncStatus.DELETED_LOCAL}','${SyncStatus.DELETED_SYNCED}') "
                },
            )
            // 调试用：临时只同步单篇（加速单篇回归，如 ⑤-B）。生产永不传此参数。
            if (!singleContentId.isNullOrBlank()) {
                append("AND content_id = ? ")
            }
        }
        val limitSql = if (limit > 0) "LIMIT $limit" else ""
        val out = ArrayList<ArticleRecord>()
        helper.readableDatabase.rawQuery(
            "SELECT * FROM ${ArchiveDb.TABLE_ARTICLES} $where ORDER BY published_at DESC, content_id DESC $limitSql",
            if (!singleContentId.isNullOrBlank()) arrayOf(singleContentId) else null,
        ).use { c ->
            val idx = ColumnIndex(c)
            while (c.moveToNext()) out.add(idx.read(c))
        }
        out
    }

    /**
     * 一次性「属性级重推」标记：把所有**已同步且记录了 Notion 页面 id** 的条目标为 [SyncStatus.PROP_RESNC]，
     * 让同步引擎在下一次同步时仅 PATCH 结构化属性（含新增列），不重建正文。
     *
     * 用途：当 Notion 端新增/变更了某个结构化列（例如新增「是否代表」），历史已同步页面因当时该列不存在而缺值；
     * 把这批页面一次性标记为属性级重推，下次同步即补齐，且幂等、请求量最小。
     * 未同步（无 notion_page_id）的条目无需标记——它们首次同步时本就会带上新列。
     */
    suspend fun markSyncedForPropBackfill() = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val stmt = db.compileStatement(
            "UPDATE ${ArchiveDb.TABLE_ARTICLES} SET sync_status = '${SyncStatus.PROP_RESNC}', updated_at = ? " +
                "WHERE sync_status = '${SyncStatus.SYNCED}' " +
                "AND notion_page_id IS NOT NULL AND notion_page_id <> ''"
        )
        stmt.bindLong(1, System.currentTimeMillis())
        val n = stmt.executeUpdateDelete()
        stmt.close()
        Log.i(TAG, "markSyncedForPropBackfill: 标记 $n 条待属性级重推")
    }

    /**
     * 调试用（仅本地调试，生产永不触发）：读取 `files/debug_single_sync.txt` 第一行作为 contentId，
     * 让本次同步只处理这一篇，加速单篇回归（如 ⑤-B 并发改字段验证）。文件不存在/为空/空白返回 null。
     * 通过 `adb shell run-as com.peide.supsub.notion sh -c 'echo <contentId> > files/debug_single_sync.txt'`
     * 设置，删文件即恢复全量同步。
     */
    fun readDebugSingleSyncId(): String? = runCatching {
        val f = File(appContext.filesDir, "debug_single_sync.txt")
        if (f.exists()) f.readText().trim().takeIf { it.isNotBlank() } else null
    }.getOrNull()

    /**
     * 按 contentId 反查所在的按天分片 key（单篇同步调试用）。
     *
     * 单篇调试模式下 pull 阶段本可只查目标文章所在的那一个子库，
     * 不必枚举全部已登记分库（34 个子库 × 一次 queryDatabase ≈ 100s，纯属浪费）。
     * shard_key 为空（老数据）时用发布/采集时间现算，与推送侧 [ShardKeys.of] 口径一致。
     */
    suspend fun shardKeyForContent(contentId: String): String? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT shard_key, published_at, captured_at FROM ${ArchiveDb.TABLE_ARTICLES} " +
                "WHERE content_id = ? LIMIT 1",
            arrayOf(contentId),
        ).use { c ->
            if (!c.moveToFirst()) return@withContext null
            val k = c.getString(0) ?: ""
            if (k.isNotBlank()) k else ShardKeys.of(c.getLong(1), c.getLong(2))
        }
    }

    /** 按 contentId 取单条完整记录（调试 / 详情用） */
    suspend fun findById(contentId: String): ArticleRecord? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT * FROM ${ArchiveDb.TABLE_ARTICLES} WHERE content_id = ? LIMIT 1",
            arrayOf(contentId),
        ).use { c ->
            if (!c.moveToFirst()) null else ColumnIndex(c).read(c)
        }
    }

    // ===================== 聚类（相似文章归并）=====================

    /** 取时间窗内文章的聚类输入投影（轻量，用于聚类引擎，不含 rawJson） */
    suspend fun loadForClustering(fromSec: Long, toSec: Long): List<ClusteringRow> = withContext(Dispatchers.IO) {
        val out = ArrayList<ClusteringRow>()
        helper.readableDatabase.rawQuery(
            "SELECT content_id, title, summary, keywords, url, published_at, cluster_id, cluster_label " +
                "FROM ${ArchiveDb.TABLE_ARTICLES} WHERE published_at >= ? AND published_at < ?",
            arrayOf(fromSec.toString(), toSec.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ClusteringRow(
                        contentId = c.getString(0),
                        title = c.getString(1) ?: "",
                        summary = if (c.isNull(2)) null else c.getString(2),
                        keywords = parseStrArray(c.getString(3)),
                        url = c.getString(4) ?: "",
                        publishedAt = c.getLong(5),
                        clusterId = c.getString(6) ?: "",
                        clusterLabel = c.getString(7) ?: "",
                    )
                )
            }
        }
        out
    }

    /** 取时间窗内「尚未聚类」的文章（cluster_id 为空），供 clusterNew 作增量种子 */
    suspend fun loadUnclustered(fromSec: Long, toSec: Long): List<ClusteringRow> = withContext(Dispatchers.IO) {
        val out = ArrayList<ClusteringRow>()
        helper.readableDatabase.rawQuery(
            "SELECT content_id, title, summary, keywords, url, published_at, cluster_id, cluster_label " +
                "FROM ${ArchiveDb.TABLE_ARTICLES} " +
                "WHERE published_at >= ? AND published_at < ? AND (cluster_id IS NULL OR cluster_id = '')",
            arrayOf(fromSec.toString(), toSec.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ClusteringRow(
                        contentId = c.getString(0),
                        title = c.getString(1) ?: "",
                        summary = if (c.isNull(2)) null else c.getString(2),
                        keywords = parseStrArray(c.getString(3)),
                        url = c.getString(4) ?: "",
                        publishedAt = c.getLong(5),
                        clusterId = c.getString(6) ?: "",
                        clusterLabel = c.getString(7) ?: "",
                    )
                )
            }
        }
        out
    }

    /** 批量取 contentId → 现有聚类子字段，供 markClustered 判断「聚类结果是否变化」 */
    private data class StoredCluster(
        val clusterId: String,
        val clusterLabel: String,
        val clusterSize: Int,
        val isClusterRep: Boolean,
    )

    private suspend fun loadClusterMeta(ids: Set<String>): Map<String, StoredCluster> = withContext(Dispatchers.IO) {
        val out = HashMap<String, StoredCluster>(ids.size)
        ids.chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            helper.readableDatabase.rawQuery(
                "SELECT content_id, cluster_id, cluster_label, cluster_size, is_cluster_rep " +
                    "FROM ${ArchiveDb.TABLE_ARTICLES} WHERE content_id IN ($placeholders)",
                chunk.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) out[c.getString(0)] = StoredCluster(
                    clusterId = c.getString(1) ?: "",
                    clusterLabel = c.getString(2) ?: "",
                    clusterSize = c.getInt(3),
                    isClusterRep = c.getInt(4) != 0,
                )
            }
        }
        out
    }

    /**
     * 回写聚类结果。
     *
     * 变更判定：只有「簇分配 + 全部聚类子字段」都和库里一致才跳过；否则回写并把 sync_status 置回 PENDING，
     * 让 Notion 同步引擎（notionPageId 仍在）走原地 PATCH 补写「聚类主题 / 相似篇数 / 是否代表」等属性，而不重建页面。
     *
     * 旧实现只比 cluster_id：当代表文章早已聚类（cluster_id 不变）而簇规模因新成员并入变大时，
     * 代表的 cluster_size 会停在过期值、且不会被回写——这就是「代表相似篇数=1」的 bug。
     * 现在比对全字段，配合 [ClusterEngine] 把「被新种子触碰的整簇」纳入回写，代表也会被刷新为真实大小。
     */
    suspend fun markClustered(rows: List<ClusterAssignment>) = withContext(Dispatchers.IO) {
        if (rows.isEmpty()) return@withContext
        val oldMap = loadClusterMeta(rows.map { it.contentId }.toSet())
        val db = helper.writableDatabase
        rows.forEach { a ->
            val old = oldMap[a.contentId]
            val unchanged = old != null &&
                old.clusterId == a.clusterId &&
                old.clusterLabel == a.clusterLabel &&
                old.clusterSize == a.clusterSize &&
                old.isClusterRep == a.isClusterRep
            if (unchanged) return@forEach // 簇分配与聚类子字段都未变，跳过（不触发重同步）
            val stmt = db.compileStatement(
                "UPDATE ${ArchiveDb.TABLE_ARTICLES} SET cluster_id=?, cluster_label=?, cluster_size=?, " +
                    "is_cluster_rep=?, sync_status='${SyncStatus.PENDING}', updated_at=? " +
                    "WHERE content_id=?"
            )
            stmt.bindString(1, a.clusterId)
            stmt.bindString(2, a.clusterLabel)
            stmt.bindLong(3, a.clusterSize.toLong())
            stmt.bindLong(4, if (a.isClusterRep) 1L else 0L)
            stmt.bindLong(5, System.currentTimeMillis())
            stmt.bindString(6, a.contentId)
            stmt.executeUpdateDelete()
            stmt.close()
        }
        Unit
    }

    /** 解析库里存的 JSON 数组串（如 `["AI","模型"]`）为字符串列表；脏数据一律当空列表 */
    private fun parseStrArray(raw: String?): List<String> {
        if (raw.isNullOrBlank() || raw == "[]") return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.getOrDefault(emptyList())
    }

    // ===================== Notion 按天分库登记 =====================

    /** 查本地登记的子库 id（命中即零网络） */
    suspend fun shardDatabaseId(shardKey: String): String? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT database_id FROM ${ArchiveDb.TABLE_SHARDS} WHERE shard_key = ? LIMIT 1",
            arrayOf(shardKey),
        ).use { if (it.moveToFirst()) it.getString(0) else null }
    }

    /**
     * 查本地登记的完整分片信息。
     *
     * 比 [shardDatabaseId] 多回一个 parentPageId，路由靠它识别「换过容器页」的陈旧登记：
     * 用户把容器页换成另一个页面后，旧登记指向的子库还在老页面下，
     * 继续用就会把新文章写回老地方，必须当作未命中重新发现/创建。
     */
    suspend fun shard(shardKey: String): NotionShard? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT shard_key, database_id, title, parent_page_id, created_at FROM ${ArchiveDb.TABLE_SHARDS} " +
                "WHERE shard_key = ? LIMIT 1",
            arrayOf(shardKey),
        ).use { c ->
            if (!c.moveToFirst()) null else NotionShard(
                shardKey = c.getString(0),
                databaseId = c.getString(1) ?: "",
                title = c.getString(2) ?: "",
                parentPageId = c.getString(3) ?: "",
                createdAt = c.getLong(4),
            )
        }
    }

    /** 删除单个分片登记（子库在 Notion 侧已被删除时用，下次同步会重新发现或新建） */
    suspend fun deleteShard(shardKey: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.execSQL(
            "DELETE FROM ${ArchiveDb.TABLE_SHARDS} WHERE shard_key = ?",
            arrayOf(shardKey),
        )
    }

    suspend fun registerShard(shard: NotionShard) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("shard_key", shard.shardKey)
            put("database_id", shard.databaseId)
            put("title", shard.title)
            put("parent_page_id", shard.parentPageId)
            put("created_at", shard.createdAt)
        }
        helper.writableDatabase.insertWithOnConflict(
            ArchiveDb.TABLE_SHARDS, null, cv,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
        Unit
    }

    /** 列出全部分片（含各分片本地条目数），供「分库概览」展示 */
    suspend fun listShards(): List<NotionShard> = withContext(Dispatchers.IO) {
        val counts = HashMap<String, Int>()
        helper.readableDatabase.rawQuery(
            "SELECT shard_key, COUNT(*) FROM ${ArchiveDb.TABLE_ARTICLES} GROUP BY shard_key", null,
        ).use { c -> while (c.moveToNext()) counts[c.getString(0) ?: ""] = c.getInt(1) }

        val out = ArrayList<NotionShard>()
        helper.readableDatabase.rawQuery(
            "SELECT shard_key, database_id, title, parent_page_id, created_at FROM ${ArchiveDb.TABLE_SHARDS} " +
                "ORDER BY shard_key DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0)
                out.add(
                    NotionShard(
                        shardKey = key,
                        databaseId = c.getString(1),
                        title = c.getString(2) ?: "",
                        parentPageId = c.getString(3) ?: "",
                        createdAt = c.getLong(4),
                        itemCount = counts[key] ?: 0,
                    )
                )
            }
        }
        // 已有数据但还没建库的分片也列出来（databaseId 空 = 待创建），让用户看到「下次同步会新建哪些库」
        counts.keys.filter { it.isNotBlank() && out.none { s -> s.shardKey == it } }
            .sortedDescending()
            .forEach { out.add(NotionShard(shardKey = it, databaseId = "", itemCount = counts[it] ?: 0)) }
        out
    }

    /** 清空分库登记（容器页换了之后必须清，否则会往旧容器页下的库里继续写） */
    suspend fun clearShards() = withContext(Dispatchers.IO) {
        helper.writableDatabase.execSQL("DELETE FROM ${ArchiveDb.TABLE_SHARDS}")
    }

    // ===================== meta =====================

    suspend fun meta(key: String): String? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT v FROM ${ArchiveDb.TABLE_META} WHERE k = ? LIMIT 1", arrayOf(key),
        ).use { if (it.moveToFirst()) it.getString(0) else null }
    }

    suspend fun setMeta(key: String, value: String?) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply { put("k", key); put("v", value) }
        helper.writableDatabase.insertWithOnConflict(
            ArchiveDb.TABLE_META, null, cv,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
        Unit
    }

    // ===================== 备份导出 =====================

    /**
     * 一键备份：把整库复制到用户选择的 SAF 目录下的 `backup/` 子目录。
     *
     * 关键一步是导出前先 `wal_checkpoint(TRUNCATE)`：WAL 模式下最近的写入可能还只在 -wal 文件里，
     * 直接复制 .db 会得到一个**缺最新数据**的副本。checkpoint 把 WAL 全部回灌主库并清空，
     * 之后单独一个 .db 文件就是完整可用的库。
     *
     * 全程按可预测 docId 直接构造 URI，不做目录枚举（PKJ110 上 SAF 枚举会静默返回 0 条）。
     *
     * @return 成功时返回备份文件名，失败返回 null
     */
    suspend fun backupTo(treeUri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            // Android 16 上 execSQL 禁止执行返回结果的语句，PRAGMA 用 rawQuery 消费掉结果
            helper.writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            val src = dbFile()
            if (!src.exists()) {
                Log.w(TAG, "backupTo: 库文件不存在 ${src.absolutePath}")
                return@withContext null
            }
            val resolver = appContext.contentResolver
            val baseDocId = DocumentsContract.getDocumentId(treeUri)
            val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, baseDocId)

            // backup/ 子目录：先按可预测 docId 探测是否已存在，不存在才创建
            val backupDocId = "$baseDocId/backup"
            val backupUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, backupDocId)
            val backupExists = runCatching {
                resolver.query(
                    backupUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null,
                )?.use { it.moveToFirst() } ?: false
            }.getOrDefault(false)
            if (!backupExists) {
                runCatching {
                    DocumentsContract.createDocument(
                        resolver, rootUri, DocumentsContract.Document.MIME_TYPE_DIR, "backup",
                    )
                }
            }
            val parent = if (backupExists ||
                runCatching {
                    resolver.query(
                        backupUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null,
                    )?.use { it.moveToFirst() } ?: false
                }.getOrDefault(false)
            ) backupUri else rootUri // 子目录建不出来就退回根目录，别让备份直接失败

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val name = "supsub_archive_$stamp.db"
            val target = DocumentsContract.createDocument(
                resolver, parent, "application/octet-stream", name,
            ) ?: return@withContext null
            resolver.openOutputStream(target)?.use { out ->
                src.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
            } ?: return@withContext null
            setMeta(META_LAST_BACKUP, System.currentTimeMillis().toString())
            Log.i(TAG, "backupTo: 备份完成 $name (${src.length() / 1024} KB)")
            name
        } catch (e: Exception) {
            Log.e(TAG, "backupTo: 备份失败", e)
            null
        }
    }

    // ===================== 内部工具 =====================

    private fun queryInt(sql: String, args: Array<String>? = null): Int =
        helper.readableDatabase.rawQuery(sql, args).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun ArticleRecord.toValues() = ContentValues().apply {
        put("content_id", contentId)
        put("source_id", sourceId)
        put("source_type", sourceType)
        put("origin_category", originCategory)
        put("source_name", sourceName)
        put("focus_id", focusId)
        put("focus_title", focusTitle)
        put("title", title)
        put("summary", summary)
        put("tags", tags)
        put("keywords", keywords)
        put("url", url)
        put("cover_image", coverImage)
        put("published_at", publishedAt)
        put("captured_at", capturedAt)
        put("is_read", if (isRead) 1 else 0)
        put("content_type", contentType)
        put("content_depth", contentDepth)
        put("tone", tone)
        put("style", style)
        put("has_action", if (hasAction) 1 else 0)
        put("has_stance", if (hasStance) 1 else 0)
        put("stance_summary", stanceSummary)
        put("content_hash", contentHash)
        put("shard_key", shardKey)
        put("raw_json", rawJson)
        put("sync_status", syncStatus)
        put("notion_page_id", notionPageId)
        put("notion_db_id", notionDatabaseId)
        put("synced_hash", syncedHash)
        put("synced_body_hash", syncedBodyHash)
        put("retry_count", retryCount)
        put("last_error", lastError)
        put("cluster_id", clusterId)
        put("cluster_label", clusterLabel)
        put("cluster_size", clusterSize)
        put("is_cluster_rep", if (isClusterRep) 1 else 0)
        put("is_user_read", if (isUserRead) 1 else 0)
        put("is_high_value", if (isHighValue) 1 else 0)
        put("local_edited_at", localEditedAt)
        put("notion_last_edited_at", notionLastEditedAt)
        put("updated_at", updatedAt)
    }

    /** 列下标缓存：`SELECT *` 后按列名取一次下标，避免每行都做一次字符串查找 */
    private class ColumnIndex(c: Cursor) {
        private val map = HashMap<String, Int>(c.columnCount * 2)

        init {
            c.columnNames.forEachIndexed { i, n -> map[n] = i }
        }

        private fun str(c: Cursor, n: String): String = map[n]?.let { if (c.isNull(it)) null else c.getString(it) } ?: ""
        private fun strOrNull(c: Cursor, n: String): String? = map[n]?.let { if (c.isNull(it)) null else c.getString(it) }
        private fun long(c: Cursor, n: String): Long = map[n]?.let { if (c.isNull(it)) 0L else c.getLong(it) } ?: 0L
        private fun int(c: Cursor, n: String): Int = map[n]?.let { if (c.isNull(it)) 0 else c.getInt(it) } ?: 0
        private fun bool(c: Cursor, n: String): Boolean = int(c, n) != 0

        fun read(c: Cursor) = ArticleRecord(
            contentId = str(c, "content_id"),
            sourceId = long(c, "source_id"),
            sourceType = str(c, "source_type"),
            originCategory = str(c, "origin_category"),
            sourceName = str(c, "source_name"),
            focusId = long(c, "focus_id"),
            focusTitle = str(c, "focus_title"),
            title = str(c, "title"),
            summary = strOrNull(c, "summary"),
            tags = str(c, "tags").ifBlank { "[]" },
            keywords = str(c, "keywords").ifBlank { "[]" },
            url = str(c, "url"),
            coverImage = strOrNull(c, "cover_image"),
            publishedAt = long(c, "published_at"),
            capturedAt = long(c, "captured_at"),
            isRead = bool(c, "is_read"),
            contentType = str(c, "content_type"),
            contentDepth = str(c, "content_depth"),
            tone = str(c, "tone"),
            style = str(c, "style"),
            hasAction = bool(c, "has_action"),
            hasStance = bool(c, "has_stance"),
            stanceSummary = str(c, "stance_summary"),
            contentHash = str(c, "content_hash"),
            shardKey = str(c, "shard_key"),
            rawJson = strOrNull(c, "raw_json"),
            syncStatus = str(c, "sync_status").ifBlank { SyncStatus.PENDING },
            notionPageId = strOrNull(c, "notion_page_id"),
            notionDatabaseId = strOrNull(c, "notion_db_id"),
            syncedHash = strOrNull(c, "synced_hash"),
            syncedBodyHash = strOrNull(c, "synced_body_hash"),
            retryCount = int(c, "retry_count"),
            lastError = strOrNull(c, "last_error"),
            clusterId = str(c, "cluster_id"),
            clusterLabel = str(c, "cluster_label"),
            clusterSize = int(c, "cluster_size"),
            isClusterRep = bool(c, "is_cluster_rep"),
            isUserRead = bool(c, "is_user_read"),
            isHighValue = bool(c, "is_high_value"),
            userReadDirty = bool(c, "user_read_dirty"),
            highValueDirty = bool(c, "high_value_dirty"),
            localEditedAt = long(c, "local_edited_at"),
            notionLastEditedAt = long(c, "notion_last_edited_at"),
            updatedAt = long(c, "updated_at"),
        )
    }

    companion object {
        private const val TAG = "ArchiveStore"
        const val META_LAST_BACKUP = "last_backup_at"

        @Volatile
        private var instance: ArchiveStore? = null

        fun get(context: Context): ArchiveStore = instance ?: synchronized(this) {
            instance ?: ArchiveStore(context).also { instance = it }
        }
    }
}
