package com.peide.supsub.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.File

/**
 * 归档单库（V2）的建库/升级器。
 *
 * ## 为什么是**裸 SQLiteOpenHelper** 而不是 Room
 *
 * PKJ110（OPPO/一加, Android 16）上 Room 的写事务会挂死——`beginTransaction()` 永不返回。
 * 根因在 Room 侧：它把事务派发到自己的单线程 transaction executor，
 * 在协程环境下容易和调用线程互相等待形成死锁；这不是 SQLite 本身的问题。
 * 既然只需要一张主表 + 两张附表，Room 的编译期校验和 DAO 生成收益有限，
 * 直接用平台 SQLite API 反而绕开了这个雷，也省掉了 ksp 这一轮编译。
 *
 * ## 为什么放**应用私有目录**
 *
 * 库文件路径固定为 `filesDir/archive-v2/supsub_archive.db`：
 *  - SQLite 需要**真实文件路径**，SAF 的 `content://` URI 根本无法作为库文件；
 *  - `/sdcard` 是 FUSE 模拟存储，文件锁语义不完整，WAL 下容易损坏；
 *  - 应用私有目录读写最快、无权限申请、无 PKJ110 的 SAF 枚举坑。
 *
 * 代价是用户无法直接在文件管理器里看到它，因此 [ArchiveStore.backupTo] 提供「一键导出备份」，
 * 把整库（含 WAL 中未落盘的内容）复制到用户选择的 SAF 目录。
 */
internal class ArchiveDb private constructor(context: Context, dbPath: String) :
    SQLiteOpenHelper(context, dbPath, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        // WAL：读写不互斥，拉取（写）与列表刷新（读）可以并行，不会互相阻塞
        db.enableWriteAheadLogging()
        // 本库不依赖外键约束（articles / notion_shards / meta 之间无 FK），显式关闭
        db.setForeignKeyConstraintsEnabled(false)
    }

    override fun onCreate(db: SQLiteDatabase) {
        Log.i(TAG, "onCreate: 建库 v$VERSION")
        createSchema(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // 并发写入时最多等 5s 再报 SQLITE_BUSY，避免瞬时争用直接抛异常。
        // 注意：Android 的 execSQL 会拒绝 PRAGMA（视作查询），故用 rawQuery 设置。
        db.rawQuery("PRAGMA busy_timeout=5000", null).close()
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "onUpgrade: $oldVersion -> $newVersion")
        // 先补齐聚类列，再建表/索引——否则 createSchema 里的
        // `CREATE INDEX idx_articles_cluster ON articles (cluster_id)` 会因 cluster_id
        // 尚不存在而编译失败（CREATE INDEX 引用了还不存在的列），导致整段迁移抛异常回滚。
        // 这个库是唯一真相源，破坏性迁移会真丢数据。
        migrateArticlesColumns(db)
        // v6：关注点身份从临时塞进 source_id/source_name 的写法，正式落地为独立列。
        // 现有 FOCUS 行的 focus.id 已躺在 source_id 里、focus.title 在 source_name("[关注点]"前缀) 里，
        // 一次性回填到新列；订阅源/网页集行的 focus_id 保持 0。
        if (oldVersion < 6) backfillFocusColumns(db)
        // v7：双向同步需要的两条时间线列。
        //  - local_edited_at：本地「真实内容编辑」时间（仅 setUserRead/setHighValue 这类用户操作写入），
        //    用于与 Notion 的 last_edited_time 做 LWW 比较；不能与 updated_at 混用（markSynced 每次都刷它）。
        //  - notion_last_edited_at：上次从 Notion 端探到的页面 last_edited_time，作为「上次已知远端时间」，
        //    用于判断本次两端是否都改过（冲突计数）。
        if (oldVersion < 7) backfillLocalEditedAt(db)
        // v4：cluster_title 与代表文章 title 冗余，已废弃，安全删列（低版本 SQLite 不支持 DROP COLUMN 时容错跳过）。
        dropClusterTitleColumn(db)
        // 加法式迁移：低版本库若缺表，直接补建（IF NOT EXISTS 幂等，绝不 DROP）。
        createSchema(db)
        // v9：评论时间戳(synced_at) + 历史流水关联(comment_id)
        migrateCommentsColumns(db)
        if (oldVersion < 9) backfillCommentSyncedAt(db)
        // v8：存量文章补历史基线（PULL + 可能的 PULL_OVERRIDE），WHERE NOT EXISTS 幂等，绝不重复。
        if (oldVersion < 8) backfillHistory(db)
        // v10：归一化 origin_category 存量脏值（早期双向同步把 Notion select 中文 label 写回了该列，
        // 导致同一「订阅源」类目在统计时被切成「SUBSCRIPTION」与中文 label 两个分组）。
        if (oldVersion < 10) normalizeOriginCategory(db)
        // v11：关注点文章的 source_name 回填为真实来源名。
        // 早期 source_name 被写成「[关注点]{focus.title}」，阅读页 badge2 因此错把关注点名称当公众号名；
        // 服务端 raw_json 里的 sourceName 才是文章的真实公众号/网站名，这里一次性回填。
        if (oldVersion < 11) backfillFocusSourceName(db)
        // v12：v11 把 source_name 改成真实公众号名后，部分老数据 focus_title 原本为空，
        // 导致关注点 badge 回退到 source_name 而显示成「关注点·DASOU」。这里从同 focus_id 的
        // 其他行（或残留的旧 source_name）回填 focus_title。
        if (oldVersion < 12) backfillFocusTitleFromPeers(db)
        // v13：三方同步（手机/平板/Notion）需要「真同步删除」，新增 synced_deleted 墓碑表：
        // 任一台删除→远端标记 _del=true→另一台同步删本地行+写墓碑，墓碑防该 contentId 被陈旧 push 复活。
        if (oldVersion < 13) createDeletedTable(db)
        // v14：published_at 统一按「分钟精度」存储，与 Notion date 属性的分钟精度对齐，
        // 消除冷启动从 Notion 重建时因 Notion 把秒截断到分钟而产生的 ≤59s 差异。
        // 存量值向下取整到分钟；0/负数（未知时间）保持不变，避免把未知误写成某个具体分钟。
        if (oldVersion < 14) normalizePublishedAtToMinute(db)
        // v15：三方同步「字段级脏标记」。is_user_read / is_high_value 是用户可在两端独立改的受管列，
        // 用于实现「增量推送 + 拉取按字段合并」：某端只把自己改过的字段推上 Notion（不抹掉对端独立改的另一字段），
        // 拉取时仅当本地该字段无未同步改动才采用远端值。缺列则 ALTER 补齐（加法式，绝不 DROP）。
        if (oldVersion < 15) addSyncDirtyColumns(db)
    }

    /**
     * v15 新增两列脏标记：user_read_dirty / high_value_dirty（INTEGER，NOT NULL DEFAULT 0）。
     * 用 PRAGMA 探测，缺哪个补哪个，幂等安全。
     */
    private fun addSyncDirtyColumns(db: SQLiteDatabase) {
        val existing = HashSet<String>(16)
        db.rawQuery("PRAGMA table_info($TABLE_ARTICLES)", null).use { c ->
            while (c.moveToNext()) existing.add(c.getString(1).lowercase())
        }
        for (col in listOf("user_read_dirty", "high_value_dirty")) {
            if (!existing.contains(col)) {
                db.execSQL("ALTER TABLE $TABLE_ARTICLES ADD COLUMN $col INTEGER NOT NULL DEFAULT 0")
                Log.i(TAG, "addSyncDirtyColumns: 已加列 $col")
            }
        }
    }

    /**
     * v14 一次性迁移：published_at 列单位仍是 Unix 秒，但精度从「秒」收敛为「分钟」。
     * 本地与 Notion 都按分钟存储后，两端不会出现秒级抖动，冷启动重建结果可逐字段一致。
     */
    private fun normalizePublishedAtToMinute(db: SQLiteDatabase) {
        try {
            db.execSQL(
                "UPDATE $TABLE_ARTICLES SET published_at = (published_at / 60) * 60 " +
                    "WHERE published_at > 0"
            )
            Log.i(TAG, "normalizePublishedAtToMinute: 已把 published_at 规整到分钟精度")
        } catch (e: Exception) {
            Log.e(TAG, "normalizePublishedAtToMinute: 迁移失败（不影响启动）", e)
        }
    }

    /**
     * v13 建表：已删除文章的 contentId 墓碑。
     * 删除是「真同步删除」（覆盖原「本地独有保留不删」）：某端删除后，若该 contentId 仍存在于 Notion，
     * 另一端的陈旧 push 可能把它重新建回来——墓碑让 pull 阶段看到它直接跳过重建、push 守门也不重建。
     */
    private fun createDeletedTable(db: SQLiteDatabase) {
        val existing = HashSet<String>(8)
        db.rawQuery("PRAGMA table_info($TABLE_DELETED)", null).use { c ->
            while (c.moveToNext()) existing.add(c.getString(1).lowercase())
        }
        if (existing.isEmpty()) {
            db.execSQL(SQL_CREATE_DELETED)
            Log.i(TAG, "createDeletedTable: 已建立墓碑表 synced_deleted")
        } else {
            Log.i(TAG, "createDeletedTable: 墓碑表已存在，跳过")
        }
    }

    /**
     * v7 一次性回填：老库升级时 `local_edited_at` 全为 0，若不填会让任何一次 Notion 探到的
     * last_edited_time（哪怕只是首推时间）都「比本地新」，从而把远端版本覆盖回本地，
     * 冲掉用户从未改动过的本地真实数据。
     * 用 `COALESCE(captured_at, updated_at, 0)` 给一个合理的「本地基线时间」：
     * 采集时间通常早于首推，于是首推后的 Notion 编辑会正确地被识别为「远端更新」并被拉回；
     * 用户从未改动的条目则保持本地胜出，稳定不翻车。
     */
    private fun backfillLocalEditedAt(db: SQLiteDatabase) {
        try {
            db.execSQL(
                "UPDATE $TABLE_ARTICLES SET local_edited_at = " +
                    "CASE WHEN captured_at > 0 THEN captured_at " +
                    "WHEN updated_at > 0 THEN updated_at ELSE 0 END " +
                    "WHERE local_edited_at = 0"
            )
            Log.i(TAG, "backfillLocalEditedAt: 已回填本地编辑基线时间")
        } catch (e: Exception) {
            Log.e(TAG, "backfillLocalEditedAt: 回填失败（不影响启动）", e)
        }
    }

    /**
     * v6 一次性回填：把历史 FOCUS 行的关注点身份从 source_id / source_name 搬到独立列。
     * - focus_id ← source_id（关注点文章入库时就是这么放的）
     * - focus_title ← source_name 去掉 "[关注点]" 前缀（保留真实关注点名，丢弃临时前缀）
     * 幂等：只处理 focus_id 仍为 0 的 FOCUS 行，已填过的不动。
     */
    private fun backfillFocusColumns(db: SQLiteDatabase) {
        try {
            db.execSQL(
                "UPDATE $TABLE_ARTICLES SET focus_id = source_id, " +
                    "focus_title = REPLACE(source_name, '[关注点]', '') " +
                    "WHERE origin_category = 'FOCUS' AND (focus_id IS NULL OR focus_id = 0)"
            )
            Log.i(TAG, "backfillFocusColumns: 已回填历史关注点身份列")
        } catch (e: Exception) {
            Log.e(TAG, "backfillFocusColumns: 回填失败（不影响启动）", e)
        }
    }

    /**
     * 给 articles 表补齐聚类相关列。CREATE TABLE IF NOT EXISTS 不会给已存在的表加列，
     * 所以这里用 `PRAGMA table_info` 探测现有列，缺哪个 ALTER 哪个。绝不 DROP / 改类型。
     */
    private fun migrateArticlesColumns(db: SQLiteDatabase) {
        val existing = HashSet<String>(16)
        db.rawQuery("PRAGMA table_info($TABLE_ARTICLES)", null).use { c ->
            while (c.moveToNext()) existing.add(c.getString(1).lowercase())
        }
        fun need(col: String) = !existing.contains(col.lowercase())
        if (need("cluster_id")) db.execSQL("ALTER TABLE $TABLE_ARTICLES ADD COLUMN cluster_id TEXT NOT NULL DEFAULT ''")
        if (need("cluster_label")) db.execSQL("ALTER TABLE $TABLE_ARTICLES ADD COLUMN cluster_label TEXT NOT NULL DEFAULT ''")
        if (need("cluster_size")) db.execSQL("ALTER TABLE $TABLE_ARTICLES ADD COLUMN cluster_size INTEGER NOT NULL DEFAULT 0")
        if (need("is_cluster_rep")) db.execSQL("ALTER TABLE $TABLE_ARTICLES ADD COLUMN is_cluster_rep INTEGER NOT NULL DEFAULT 0")
        if (need("is_user_read")) db.execSQL("ALTER TABLE $TABLE_ARTICLES ADD COLUMN is_user_read INTEGER NOT NULL DEFAULT 0")
        if (need("is_high_value")) db.execSQL("ALTER TABLE $TABLE_ARTICLES ADD COLUMN is_high_value INTEGER NOT NULL DEFAULT 0")
        if (need("cluster_id")) db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_articles_cluster ON $TABLE_ARTICLES (cluster_id)"
        )
        if (need("is_user_read")) db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_articles_user_read ON $TABLE_ARTICLES (is_user_read)"
        )
        if (need("focus_id")) db.execSQL("ALTER TABLE $TABLE_ARTICLES ADD COLUMN focus_id INTEGER NOT NULL DEFAULT 0")
        if (need("focus_title")) db.execSQL("ALTER TABLE $TABLE_ARTICLES ADD COLUMN focus_title TEXT NOT NULL DEFAULT ''")
        if (need("focus_id")) db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_articles_focus ON $TABLE_ARTICLES (focus_id)"
        )
        // v7：双向同步时间线列
        if (need("local_edited_at")) db.execSQL("ALTER TABLE $TABLE_ARTICLES ADD COLUMN local_edited_at INTEGER NOT NULL DEFAULT 0")
        if (need("notion_last_edited_at")) db.execSQL("ALTER TABLE $TABLE_ARTICLES ADD COLUMN notion_last_edited_at INTEGER NOT NULL DEFAULT 0")
    }

    /**
     * 安全删除 cluster_title 列（v4 起冗余废弃）。
     * 仅当列存在且当前 SQLite 支持 ALTER TABLE DROP COLUMN 时删除；
     * 否则（极老 SQLite）保留该列——代码已不再读写它，不影响功能。
     */
    private fun dropClusterTitleColumn(db: SQLiteDatabase) {
        val existing = HashSet<String>(16)
        db.rawQuery("PRAGMA table_info($TABLE_ARTICLES)", null).use { c ->
            while (c.moveToNext()) existing.add(c.getString(1).lowercase())
        }
        if (!existing.contains("cluster_title")) return
        try {
            db.execSQL("ALTER TABLE $TABLE_ARTICLES DROP COLUMN cluster_title")
            Log.i(TAG, "dropClusterTitleColumn: 已删除冗余列 cluster_title")
        } catch (e: Exception) {
            Log.w(TAG, "dropClusterTitleColumn: 当前 SQLite 不支持 DROP COLUMN，保留 cluster_title（无害）：${e.message}")
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 装了旧版 APK 时不要崩，容忍降级（新列对旧代码只是不可见）
        Log.w(TAG, "onDowngrade: $oldVersion -> $newVersion（已忽略）")
    }

    /**
     * v8 一次性回填：老库升级时 article_history 为空，逐篇补两条基线事件，
     * 让历史 Tab 不至于空白：
     *  - PULL：以 captured_at 为拉取时间（CLOUD 源 → actor=CLOUD）
     *  - PULL_OVERRIDE（仅当 notion_last_edited_at>0）：云端曾覆盖本地，时间取 notion_last_edited_at
     * 两条都带 `WHERE NOT EXISTS`：若已通过 insertIfAbsent 实时记过 PULL，则不重复插入。
     */
    private fun backfillHistory(db: SQLiteDatabase) {
        try {
            db.execSQL(
                "INSERT INTO $TABLE_HISTORY (content_id, event_type, event_time, actor, field, detail) " +
                    "SELECT content_id, 'PULL', captured_at, 'CLOUD', '*', '初始拉取' FROM $TABLE_ARTICLES " +
                    "WHERE captured_at > 0 AND NOT EXISTS (" +
                    "SELECT 1 FROM $TABLE_HISTORY h WHERE h.content_id = $TABLE_ARTICLES.content_id)",
            )
            db.execSQL(
                "INSERT INTO $TABLE_HISTORY (content_id, event_type, event_time, actor, field, detail) " +
                    "SELECT content_id, 'PULL_OVERRIDE', notion_last_edited_at, 'CLOUD', '*', '云端曾覆盖（升级回填）' FROM $TABLE_ARTICLES " +
                    "WHERE notion_last_edited_at > 0 AND NOT EXISTS (" +
                    "SELECT 1 FROM $TABLE_HISTORY h WHERE h.content_id = $TABLE_ARTICLES.content_id)",
            )
            Log.i(TAG, "backfillHistory: 已回填历史基线")
        } catch (e: Exception) {
            Log.e(TAG, "backfillHistory: 回填失败（不影响启动）", e)
        }
    }

    /**
     * v10 一次性清洗：早期双向同步把 Notion select 的中文 label（如「订阅源」「关注点」）
     * 直接写回了 origin_category 列，导致本应存英文枚举 key（SUBSCRIPTION/FOCUS/WEBSET）的列
     * 混入了中文值，统计时同一类目被切成两个分组。这里按「key 或 label 任一匹配」归一化回英文 key。
     */
    private fun normalizeOriginCategory(db: SQLiteDatabase) {
        try {
            // 先按 label 命中（中文 + 大小写变体）归位 FOCUS / WEBSET
            db.execSQL("UPDATE $TABLE_ARTICLES SET origin_category='FOCUS' WHERE origin_category IN ('FOCUS','关注点') COLLATE NOCASE")
            db.execSQL("UPDATE $TABLE_ARTICLES SET origin_category='WEBSET' WHERE origin_category IN ('WEBSET','网页集') COLLATE NOCASE")
            // 其余（含「订阅源」、'SUBSCRIPTION'、空串、未知串）统一归并到 SUBSCRIPTION
            db.execSQL("UPDATE $TABLE_ARTICLES SET origin_category='SUBSCRIPTION' WHERE origin_category NOT IN ('FOCUS','WEBSET') COLLATE NOCASE")
            Log.i(TAG, "normalizeOriginCategory: 已归一化 origin_category 存量脏值")
        } catch (e: Exception) {
            Log.e(TAG, "normalizeOriginCategory: 清洗失败（不影响启动）", e)
        }
    }

    /**
     * v11 一次性回填：关注点文章的 source_name 改存真实来源名。
     * 历史数据 source_name 被写成「[关注点]{focus.title}」，现在从 raw_json 里的 sourceName 字段
     * 提取真实公众号/网站名并写回 source_name；raw_json 缺失或解析失败则保留原值。
     */
    private fun backfillFocusSourceName(db: SQLiteDatabase) {
        try {
            var updated = 0
            db.rawQuery(
                "SELECT content_id, raw_json FROM $TABLE_ARTICLES " +
                    "WHERE origin_category = 'FOCUS' AND raw_json IS NOT NULL AND raw_json <> ''",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val contentId = c.getString(0)
                    val raw = c.getString(1) ?: continue
                    try {
                        val sourceName = org.json.JSONObject(raw).optString("sourceName").trim()
                        if (sourceName.isNotBlank()) {
                            db.execSQL(
                                "UPDATE $TABLE_ARTICLES SET source_name = ? WHERE content_id = ?",
                                arrayOf(sourceName, contentId),
                            )
                            updated++
                        }
                    } catch (e: Exception) {
                        // 单条解析失败不影响整体迁移。
                    }
                }
            }
            Log.i(TAG, "backfillFocusSourceName: 已回填 $updated 篇关注点文章的来源名")
        } catch (e: Exception) {
            Log.e(TAG, "backfillFocusSourceName: 回填失败（不影响启动）", e)
        }
    }

    /**
     * v12 一次性回填：v11 把关注点 source_name 改成真实公众号名后，focus_title 为空的行
     * 会把关注点 badge 错误地显示成「关注点·DASOU」。这里先从同 focus_id 的其他行复制
     * focus_title；若同关注点也没有，则尝试从当前行的旧 source_name 中的「[关注点]」前缀解析。
     */
    private fun backfillFocusTitleFromPeers(db: SQLiteDatabase) {
        try {
            // 1) 同类行回填
            db.execSQL(
                "UPDATE $TABLE_ARTICLES AS target " +
                    "SET focus_title = (" +
                    "  SELECT source.focus_title FROM $TABLE_ARTICLES AS source " +
                    "  WHERE source.focus_id = target.focus_id " +
                    "    AND source.focus_title IS NOT NULL AND source.focus_title <> '' " +
                    "  LIMIT 1" +
                    ") " +
                    "WHERE target.origin_category = 'FOCUS' " +
                    "  AND (target.focus_title IS NULL OR target.focus_title = '')",
            )
            // 2) 仍为空时，若 source_name 还残留旧格式「[关注点]xxx」，则解析出来兜底
            db.rawQuery(
                "SELECT content_id, source_name FROM $TABLE_ARTICLES " +
                    "WHERE origin_category = 'FOCUS' " +
                    "  AND (focus_title IS NULL OR focus_title = '') " +
                    "  AND source_name LIKE '[关注点]%'",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val contentId = c.getString(0)
                    val sourceName = c.getString(1) ?: continue
                    val focusTitle = sourceName.removePrefix("[关注点]").trim()
                    if (focusTitle.isNotBlank()) {
                        db.execSQL(
                            "UPDATE $TABLE_ARTICLES SET focus_title = ? WHERE content_id = ?",
                            arrayOf(focusTitle, contentId),
                        )
                    }
                }
            }
            Log.i(TAG, "backfillFocusTitleFromPeers: 已回填关注点标题")
        } catch (e: Exception) {
            Log.e(TAG, "backfillFocusTitleFromPeers: 回填失败（不影响启动）", e)
        }
    }

    /**
     * 给 article_comments / article_history 补齐评论相关列：",
     *  - article_comments.synced_at：评论上次同步（推送/拉取/删除同步）的时间戳
     *  - article_history.comment_id：评论事件流水关联到具体评论行
     * 用 PRAGMA table_info 探测现有列，缺哪个 ALTER 哪个，绝不 DROP / 改类型。
     */
    private fun migrateCommentsColumns(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA table_info($TABLE_COMMENTS)", null).use { c ->
            val existing = HashSet<String>(16)
            while (c.moveToNext()) existing.add(c.getString(1).lowercase())
            if (!existing.contains("synced_at")) {
                db.execSQL("ALTER TABLE $TABLE_COMMENTS ADD COLUMN synced_at INTEGER NOT NULL DEFAULT 0")
                Log.i(TAG, "migrateCommentsColumns: article_comments 已加 synced_at")
            }
        }
        db.rawQuery("PRAGMA table_info($TABLE_HISTORY)", null).use { c ->
            val existing = HashSet<String>(16)
            while (c.moveToNext()) existing.add(c.getString(1).lowercase())
            if (!existing.contains("comment_id")) {
                db.execSQL("ALTER TABLE $TABLE_HISTORY ADD COLUMN comment_id TEXT")
                Log.i(TAG, "migrateCommentsColumns: article_history 已加 comment_id")
            }
        }
    }

    /**
     * v9 回填：存量已同步(SYNCED)评论没有 synced_at，用 created_at 兜底，
     * 让历史 Tab / 评论行不至于显示空时间戳。
     */
    private fun backfillCommentSyncedAt(db: SQLiteDatabase) {
        try {
            db.execSQL(
                "UPDATE $TABLE_COMMENTS SET synced_at = created_at " +
                    "WHERE synced_at = 0 AND sync_state = 'SYNCED' AND deleted = 0",
            )
            Log.i(TAG, "backfillCommentSyncedAt: 已回填评论同步时间戳")
        } catch (e: Exception) {
            Log.e(TAG, "backfillCommentSyncedAt: 回填失败（不影响启动）", e)
        }
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_ARTICLES)
        SQL_CREATE_ARTICLE_INDICES.forEach(db::execSQL)
        db.execSQL(SQL_CREATE_SHARDS)
        db.execSQL(SQL_CREATE_META)
        db.execSQL(SQL_CREATE_HISTORY)
        db.execSQL(SQL_CREATE_COMMENTS)
        SQL_CREATE_HISTORY_INDICES.forEach(db::execSQL)
        SQL_CREATE_COMMENT_INDICES.forEach(db::execSQL)
        db.execSQL(SQL_CREATE_DELETED)
    }

    companion object {
        private const val TAG = "ArchiveDb"
        private const val VERSION = 15

        /** 新版缓存目录名（与 V1 的 SAF JSON 归档完全隔离，互不影响） */
        const val DIR_NAME = "archive-v2"
        const val DB_NAME = "supsub_archive.db"

        const val TABLE_ARTICLES = "articles"
        const val TABLE_SHARDS = "notion_shards"
        const val TABLE_META = "meta"
        const val TABLE_HISTORY = "article_history"
        const val TABLE_COMMENTS = "article_comments"
        const val TABLE_DELETED = "synced_deleted"

        private val SQL_CREATE_ARTICLES = """
            CREATE TABLE IF NOT EXISTS $TABLE_ARTICLES (
                content_id        TEXT PRIMARY KEY NOT NULL,
                source_id         INTEGER NOT NULL DEFAULT 0,
                source_type       TEXT    NOT NULL DEFAULT '',
                origin_category   TEXT    NOT NULL DEFAULT 'SUBSCRIPTION',
                source_name       TEXT    NOT NULL DEFAULT '',
                focus_id          INTEGER NOT NULL DEFAULT 0,
                focus_title       TEXT    NOT NULL DEFAULT '',
                title             TEXT    NOT NULL DEFAULT '',
                summary           TEXT,
                tags              TEXT    NOT NULL DEFAULT '[]',
                keywords          TEXT    NOT NULL DEFAULT '[]',
                url               TEXT    NOT NULL DEFAULT '',
                cover_image       TEXT,
                published_at      INTEGER NOT NULL DEFAULT 0,
                captured_at       INTEGER NOT NULL DEFAULT 0,
                is_read           INTEGER NOT NULL DEFAULT 0,
                content_type      TEXT    NOT NULL DEFAULT '',
                content_depth     TEXT    NOT NULL DEFAULT '',
                tone              TEXT    NOT NULL DEFAULT '',
                style             TEXT    NOT NULL DEFAULT '',
                has_action        INTEGER NOT NULL DEFAULT 0,
                has_stance        INTEGER NOT NULL DEFAULT 0,
                stance_summary    TEXT    NOT NULL DEFAULT '',
                content_hash      TEXT    NOT NULL DEFAULT '',
                shard_key         TEXT    NOT NULL DEFAULT '',
                raw_json          TEXT,
                sync_status       TEXT    NOT NULL DEFAULT 'PENDING',
                notion_page_id    TEXT,
                notion_db_id      TEXT,
                synced_hash       TEXT,
                synced_body_hash  TEXT,
                retry_count       INTEGER NOT NULL DEFAULT 0,
                last_error        TEXT,
                cluster_id        TEXT    NOT NULL DEFAULT '',
                cluster_label     TEXT    NOT NULL DEFAULT '',
                cluster_size      INTEGER NOT NULL DEFAULT 0,
                is_cluster_rep    INTEGER NOT NULL DEFAULT 0,
                is_user_read      INTEGER NOT NULL DEFAULT 0,
                is_high_value     INTEGER NOT NULL DEFAULT 0,
                updated_at        INTEGER NOT NULL DEFAULT 0,
                local_edited_at   INTEGER NOT NULL DEFAULT 0,
                notion_last_edited_at INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()

        /**
         * 索引取舍：只给「高频筛选/排序」的列建索引。
         * published_at DESC + content_id 复合索引直接支撑列表分页的 ORDER BY，
         * 避免上万行时每次翻页都走全表排序。
         */
        private val SQL_CREATE_ARTICLE_INDICES = listOf(
            "CREATE INDEX IF NOT EXISTS idx_articles_published ON $TABLE_ARTICLES (published_at DESC, content_id DESC)",
            "CREATE INDEX IF NOT EXISTS idx_articles_sync_status ON $TABLE_ARTICLES (sync_status)",
            "CREATE INDEX IF NOT EXISTS idx_articles_is_read ON $TABLE_ARTICLES (is_read)",
            "CREATE INDEX IF NOT EXISTS idx_articles_source ON $TABLE_ARTICLES (source_type, source_id)",
            "CREATE INDEX IF NOT EXISTS idx_articles_origin ON $TABLE_ARTICLES (origin_category)",
            "CREATE INDEX IF NOT EXISTS idx_articles_focus ON $TABLE_ARTICLES (focus_id)",
            "CREATE INDEX IF NOT EXISTS idx_articles_shard ON $TABLE_ARTICLES (shard_key)",
            "CREATE INDEX IF NOT EXISTS idx_articles_cluster ON $TABLE_ARTICLES (cluster_id)",
        )

        private val SQL_CREATE_SHARDS = """
            CREATE TABLE IF NOT EXISTS $TABLE_SHARDS (
                shard_key       TEXT PRIMARY KEY NOT NULL,
                database_id     TEXT NOT NULL,
                title           TEXT NOT NULL DEFAULT '',
                parent_page_id  TEXT NOT NULL DEFAULT '',
                created_at      INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()

        private val SQL_CREATE_META = """
            CREATE TABLE IF NOT EXISTS $TABLE_META (
                k TEXT PRIMARY KEY NOT NULL,
                v TEXT
            )
        """.trimIndent()

        /**
         * 文章事件流水（append-only）：拉取 / 本地编辑 / 本地覆盖云端 / 云端覆盖本地。
         * 与 articles 同期建表，绝不 DROP。
         */
        private val SQL_CREATE_HISTORY = """
            CREATE TABLE IF NOT EXISTS $TABLE_HISTORY (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                content_id  TEXT    NOT NULL,
                event_type  TEXT    NOT NULL,
                event_time  INTEGER NOT NULL,
                actor       TEXT    NOT NULL,
                field       TEXT,
                old_value   TEXT,
                new_value   TEXT,
                detail      TEXT,
                comment_id  TEXT
            )
        """.trimIndent()

        private val SQL_CREATE_HISTORY_INDICES = listOf(
            "CREATE INDEX IF NOT EXISTS idx_history_cid ON $TABLE_HISTORY (content_id, id DESC)",
        )

        /**
         * 本地评论 + 与 Notion 页面评论的对账。
         * actor 区分归属（LOCAL 可编辑/删除；CLOUD 从 Notion 拉回，本地只读）。
         * sync_state 见 [com.peide.supsub.data.CommentSyncState]。
         */
        private val SQL_CREATE_COMMENTS = """
            CREATE TABLE IF NOT EXISTS $TABLE_COMMENTS (
                id                INTEGER PRIMARY KEY AUTOINCREMENT,
                content_id       TEXT    NOT NULL,
                notion_page_id   TEXT,
                notion_comment_id TEXT,
                body             TEXT    NOT NULL,
                author           TEXT    NOT NULL DEFAULT '土豆',
                created_at       INTEGER NOT NULL,
                edited_at       INTEGER,
                sync_state       TEXT    NOT NULL DEFAULT 'LOCAL_NEW',
                deleted          INTEGER NOT NULL DEFAULT 0,
                actor            TEXT    NOT NULL DEFAULT 'LOCAL',
                synced_at        INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()

        private val SQL_CREATE_COMMENT_INDICES = listOf(
            "CREATE INDEX IF NOT EXISTS idx_comments_cid ON $TABLE_COMMENTS (content_id)",
            "CREATE INDEX IF NOT EXISTS idx_comments_ncid ON $TABLE_COMMENTS (notion_comment_id)",
        )

        /** 已删除文章墓碑表（v13）：contentId → 删除时间/原因。pull 看到墓碑即跳过重建，push 守门不重建。 */
        private val SQL_CREATE_DELETED = """
            CREATE TABLE IF NOT EXISTS $TABLE_DELETED (
                content_id  TEXT PRIMARY KEY NOT NULL,
                deleted_at  INTEGER NOT NULL DEFAULT 0,
                reason      TEXT    NOT NULL DEFAULT ''
            )
        """.trimIndent()

        /** 库文件位置：`filesDir/archive-v2/supsub_archive.db` */
        fun dbFile(context: Context): File {
            val dir = File(context.applicationContext.filesDir, DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            return File(dir, DB_NAME)
        }

        @Volatile
        private var instance: ArchiveDb? = null

        fun get(context: Context): ArchiveDb = instance ?: synchronized(this) {
            instance ?: run {
                val file = dbFile(context)
                // SQLiteOpenHelper 接受绝对路径作为 name（ContextImpl.getDatabasePath 对
                // 以 '/' 开头的 name 会直接当成完整路径并自动建父目录），因此无需自定义 ContextWrapper
                ArchiveDb(context.applicationContext, file.absolutePath).also {
                    it.setWriteAheadLoggingEnabled(true)
                    instance = it
                }
            }
        }
    }
}
