package com.peide.supsub.data

/**
 * 归档数据模型（V2）。
 *
 * V2 与 V1 的根本差别：
 *  - V1：正文写 SAF 目录下的 JSON 文件群（一篇一个文件），Room 只存「索引」；
 *        每天近百篇 → 文件数无上限增长，SAF 单文档读写成为瓶颈，且 PKJ110 上 SAF 枚举不可用。
 *  - V2：**单一 SQLite 库**（[ArchiveDb] / [ArchiveStore]）承载全部字段，放应用私有目录，
 *        无 SAF 依赖、无枚举坑、去重/计数/筛选全部走 SQL 索引。
 *
 * 因此这里不再有任何 Room 注解——表结构由 [ArchiveDb] 用原生 SQL 定义
 * （刻意不用 Room：PKJ110 上 Room 的事务执行器会挂死，见 [ArchiveDb] 注释）。
 */

/** 同步状态取值（articles.sync_status） */
object SyncStatus {
    const val PENDING = "PENDING"
    const val SYNCING = "SYNCING"
    const val SYNCED = "SYNCED"
    const val FAILED = "FAILED"
    /**
     * 属性级重推（PATCH 重推）：正文未变，仅因 Notion 端新增/变更了结构化列（如「是否代表」），
     * 而需要把历史已同步页面的属性补写一遍。同步引擎对这种条目**只 PATCH 属性、不重建正文**，
     * 请求量最小且幂等。区别于 PENDING——PENDING 通常表示内容变了需要重写正文。
     */
    const val PROP_RESNC = "PENDING_PROP"
    /**
     * 本地删除待同步：用户在某端删除了文章，待下次同步把 Notion 页标记删除（真同步删除）。
     * 与 article_comments 的 [CommentSyncState.DELETED_LOCAL] 同名但**不同对象**，概念分离（文章 vs 评论）。
     * 处于此态的行**不参与普通推送**（[ArchiveStore.pendingForSync] 会排除它），由独立的「删除 pass」处理。
     */
    const val DELETED_LOCAL = "DELETED_LOCAL"
    /** 删除已同步到 Notion（远端 _del=true 已置位 + 本地写墓碑），列表过滤掉、不再推送、pull 也不重建。 */
    const val DELETED_SYNCED = "DELETED_SYNCED"
}

/**
 * 一篇归档文章的**完整**记录，与 articles 表一一对应。
 *
 * 相比 V1 的 ArticleEntity + JSON 模板双份存储，这里把原先散落在 JSON 模板顶层的
 * keywords / analysis.* 等 AI 维度全部提升为**一等列**，同步引擎不再需要解析 JSON。
 *
 * @param tags            JSON 数组字符串，如 `["AI","模型"]`
 * @param keywords        JSON 数组字符串（实体词，比 tags 更细）
 * @param publishedAt     发布时间（Unix **秒**，0 表示未知）
 * @param capturedAt      入库时间（Unix **毫秒**）
 * @param shardKey        按天分片键，如 `2026-08-05`（见 [ShardKeys]）
 * @param rawJson         服务端原始报文，用于追溯字段变更/保留未知字段
 * @param notionDatabaseId 该条目实际写入的 Notion 子数据库 id（分库后每条各归其位）
 */
data class ArticleRecord(
    val contentId: String,
    val sourceId: Long = 0,
    val sourceType: String = "",
    val originCategory: String = OriginCategory.SUBSCRIPTION.key,
    val sourceName: String = "",
    /** 关注点身份：仅 FOCUS 通道有意义。记录「首个」归属关注点（最小方案：重复跨关注点只留先到者）。订阅源/网页集恒为 0/空 */
    val focusId: Long = 0,
    val focusTitle: String = "",
    val title: String = "",
    val summary: String? = null,
    val tags: String = "[]",
    val keywords: String = "[]",
    val url: String = "",
    val coverImage: String? = null,
    val publishedAt: Long = 0,
    val capturedAt: Long = System.currentTimeMillis(),
    val isRead: Boolean = false,
    // ─── AI 分析维度（原 JSON 模板 analysis{}，V2 提升为一等列）───
    val contentType: String = "",
    val contentDepth: String = "",
    val tone: String = "",
    val style: String = "",
    val hasAction: Boolean = false,
    val hasStance: Boolean = false,
    val stanceSummary: String = "",
    // ─── 校验与分片 ───
    val contentHash: String = "",
    val shardKey: String = "",
    val rawJson: String? = null,
    // ─── Notion 同步状态 ───
    val syncStatus: String = SyncStatus.PENDING,
    val notionPageId: String? = null,
    val notionDatabaseId: String? = null,
    val syncedHash: String? = null,
    val syncedBodyHash: String? = null,
    val retryCount: Int = 0,
    val lastError: String? = null,
    // ─── 聚类（本地相似文章归并）───
    val clusterId: String = "",
    val clusterLabel: String = "",
    val clusterSize: Int = 0,
    val isClusterRep: Boolean = false,
    // ─── 阅读页本地标记（与 supsub 的 is_read 完全独立）───
    /** 实际已读：只由阅读页单篇开关写，永不回传 supsub、永不参与拉取/去重；可上行 Notion「实际已读」受管列 */
    val isUserRead: Boolean = false,
    /** 高价值：仅本地标记，本次不同步 Notion（Notion「高价值」为手动列，引擎不碰） */
    val isHighValue: Boolean = false,
    /**
     * 字段级脏标记（v15）：该布尔自上次成功同步后是否被本地用户改动过（setUserRead/setHighValue 置 1）。
     * 推送时只在脏时才把该字段 PATCH 上 Notion，避免全量状态覆盖掉对端独立改的另一字段；
     * 拉取时仅当本地该字段无脏改动才采用远端值（保留本地未同步改动）。
     */
    val userReadDirty: Boolean = false,
    val highValueDirty: Boolean = false,
    /** 本地「真实内容编辑」时间（ms）：仅由 setUserRead/setHighValue 这类用户操作写入，
     *  用于与 Notion 的 last_edited_time 做 LWW 比较。NOT markSynced/updated_at（同步记账时间，不是编辑时间）。 */
    val localEditedAt: Long = 0,
    /** 上次从 Notion 侧探到的页面 last_edited_time（ms），作为「上次已知远端时间」，用于冲突判定 */
    val notionLastEditedAt: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * 列表展示用的轻量投影（不含 summary / rawJson 等大字段）。
 * 「已拉取文章概况」分页读的就是它——同样一屏 20 条，读的字节数比整行小一到两个量级。
 */
data class ArticleSummary(
    val contentId: String,
    val sourceType: String = "",
    val originCategory: String = OriginCategory.SUBSCRIPTION.key,
    val sourceName: String = "",
    val title: String = "",
    val publishedAt: Long = 0,
    val isRead: Boolean = false,
    val syncStatus: String = SyncStatus.PENDING,
    val shardKey: String = "",
)

/**
 * 阅读页时间筛选维度。
 */
enum class ReadingTimeRange(val label: String) {
    ALL("全部时间"),
    TODAY("今天"),
    WEEK("近 7 天"),
    MONTH("近 30 天"),
    OLDER("更早"),
    ;

    companion object {
        /** 按枚举常量名反序列化（持久化存储用 name），未知/空返回 null */
        fun fromKey(key: String): ReadingTimeRange? = entries.firstOrNull { it.name == key }
    }
}

/**
 * 阅读页状态筛选维度（与 supsub 的 is_read 解耦，只看本地标记）。
 */
enum class ReadingStatus(val label: String) {
    ALL("全部"),
    UNREAD("未读"),
    READ("已读"),
    HIGH_VALUE("高价值"),
    ;

    companion object {
        fun fromKey(key: String): ReadingStatus? = entries.firstOrNull { it.name == key }
    }
}

/**
 * 阅读页同步状态筛选维度（看本地 articles.sync_status 列）。
 * 注意：PENDING_PROP（属性级重推）在本地也视为「未同步」，因为它尚未完全同步完成。
 */
enum class ReadingSyncFilter(val label: String) {
    ALL("全部"),
    SYNCED("已同步"),
    PENDING("待同步"),
    PROP("待重推"),
    ;

    companion object {
        fun fromKey(key: String?): ReadingSyncFilter? {
            if (key.isNullOrBlank()) return null
            // 升级前的旧值 UNSYNCED（=「未同步」合集）已无对应单选项，
            // 回落到「全部」，避免把待重推(PENDING_PROP)等文章误隐藏
            if (key == "UNSYNCED") return null
            return entries.firstOrNull { it.name == key }
        }
    }
}

/**
 * 阅读页用的轻量投影（含详情所需字段，tags/keywords 已解析为列表）。
 * 由 [ArchiveStore.listForReading] 直接返回，UI 不再做任何 JSON 解析。
 */
data class ReadingItem(
    val contentId: String,
    val title: String = "",
    val sourceName: String = "",
    val sourceType: String = "",
    val originCategory: String = "",
    /** 关注点筛选用：所属关注点 id / 标题（仅 FOCUS 通道非空） */
    val focusId: Long = 0,
    val focusTitle: String = "",
    val publishedAt: Long = 0,
    val isUserRead: Boolean = false,
    val isHighValue: Boolean = false,
    val clusterId: String = "",
    val clusterLabel: String = "",
    val summary: String? = null,
    val tags: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val contentType: String = "",
    val contentDepth: String = "",
    val tone: String = "",
    val style: String = "",
    val hasStance: Boolean = false,
    val stanceSummary: String = "",
    val url: String = "",
    val coverImage: String? = null,
)

/**
 * 阅读页「按关注点」筛选项：一个关注点对应一行（id + 展示标题）。
 * 由 [ArchiveStore.listReadingFocuses] 返回，供下拉只列「本地确实存在的关注点」。
 */
data class ReadingFocus(
    val id: Long,
    val title: String,
)

/**
 * 归档整体统计（供「归档状态」卡片展示）。
 *
 * @param inconsistent 数据一致性问题条数（shard_key 缺失，或 content_hash 缺失且非同步行）
 * @param source       统计来源标识，V2 恒为 `SQLITE`
 */
data class ArchiveStats(
    val total: Int = 0,
    val unread: Int = 0,
    val userUnread: Int = 0,
    val pendingSync: Int = 0,
    val pendingProp: Int = 0,
    val syncFailed: Int = 0,
    val bySourceType: Map<String, Int> = emptyMap(),
    val byOrigin: Map<String, Int> = emptyMap(),
    val inconsistent: Int = 0,
    val source: String = "SQLITE",
)

/**
 * 一个 Notion 按天子数据库的本地登记。
 *
 * 分库路由先查这张表（O(1)，零网络），未命中才去容器页里发现/创建，
 * 因此稳定运行后同步全程不会为「找库」多打一次 Notion 请求。
 */
data class NotionShard(
    val shardKey: String,
    val databaseId: String,
    val title: String = "",
    val parentPageId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** 该分片下本地条目数（listShards 时联表统计，登记时不写库） */
    val itemCount: Int = 0,
)

/**
 * 双向同步「拉取」阶段：从 Notion 页面 properties 反向解析出的本地字段补丁。
 *
 * 只有非 null 的字段会被写回本地（[ArchiveStore.markPulled] 用 COALESCE 语义逐列更新），
 * 因此 Notion 端没填/缺失的列不会把本地已有值冲掉。
 * 字段命名与 [ArticleRecord] 一一对应；tags/keywords 是 JSON 数组串（与库内存法一致），
 * publishedAt 是 Unix **秒**（与库内一致）。
 *
 * 注意：正文(body)不回拉（Notion 只存摘要级文本）；摘要(summary)/封面(cover)已升级为属性级存储，
 * 由 [PROP_SUMMARY]/[PROP_COVER] 承载，拉取按属性直接重建。
 */
data class NotionRemotePatch(
    /** 跨设备主键（Notion「内容ID」rich_text 属性 → 本地 content_id）；冷启动重建时用于本地查重 / 新建 */
    val contentId: String? = null,
    val title: String? = null,
    val sourceName: String? = null,
    val sourceType: String? = null,
    val originCategory: String? = null,
    val focusTitle: String? = null,
    val publishedAt: Long? = null,
    val tags: String? = null,
    val url: String? = null,
    val keywords: String? = null,
    val contentType: String? = null,
    val contentDepth: String? = null,
    val tone: String? = null,
    val style: String? = null,
    val hasAction: Boolean? = null,
    val hasStance: Boolean? = null,
    val stanceSummary: String? = null,
    val clusterLabel: String? = null,
    val clusterSize: Int? = null,
    val isClusterRep: Boolean? = null,
    /** 实际已读（Notion「实际已读」受管列 → 本地 is_user_read） */
    val isUserRead: Boolean? = null,
    /** 高价值（Notion「高价值」手动列 → 本地 is_high_value；拉取方向生效，推送方向引擎不写） */
    val isHighValue: Boolean? = null,
    /** 摘要（Notion「摘要」rich_text 属性 → 本地 summary），无损重建内容字段一致性 */
    val summary: String? = null,
    /** 封面图 url（Notion「封面」url 属性 → 本地 cover_image） */
    val coverImage: String? = null,
)

/**
 * 双向同步拉取阶段的轻量投影：已映射到 Notion 页面的本地行，
 * 只带比较 LWW 所需的几个时间字段，避免在 3767 篇规模上 SELECT *。
 */
data class NotionMappedRow(
    val contentId: String,
    /** 标题（概览展示用） */
    val title: String = "",
    /** 来源名（概览展示用） */
    val sourceName: String = "",
    val notionPageId: String,
    val notionDatabaseId: String,
    /** 本地真实编辑时间（ms），与远端 last_edited_time 比较 */
    val localEditedAt: Long,
    /** 上次已知远端编辑时间（ms），用于冲突判定（两端都比它新 = 真冲突） */
    val notionLastEditedAt: Long,
)

/**
 * 评论同步状态（article_comments.sync_state）。
 * - LOCAL_NEW：本地新建，尚未推到 Notion
 * - SYNCED：已与 Notion 页面评论对齐（notion_comment_id 非空）
 * - EDIT_PENDING：曾 SYNCED、本地改了正文 → 下次同步「删旧+重建」
 * - DELETED_LOCAL：本地删除，待同步删云端（无 notion_comment_id 的 LOCAL_NEW 删除也走此态，仅本地清理）
 * - DELETED_SYNCED：已与云端一致删除（墓碑，列表过滤掉）
 */
object CommentSyncState {
    const val LOCAL_NEW = "LOCAL_NEW"
    const val SYNCED = "SYNCED"
    const val EDIT_PENDING = "EDIT_PENDING"
    const val DELETED_LOCAL = "DELETED_LOCAL"
    const val DELETED_SYNCED = "DELETED_SYNCED"
}

/**
 * 本地评论（article_comments 一行）。
 * @param actor 归属：LOCAL=本 App 发起（可编辑/删除）；CLOUD=从 Notion 拉回（本地只读，避免误删他人评论）
 * @param notionCommentId 与 Notion 评论的映射 id；编辑走「删旧+重建」后此项落到新 id
 */
data class CommentEntry(
    val id: Long,
    val contentId: String,
    val notionPageId: String? = null,
    val notionCommentId: String? = null,
    val body: String,
    val author: String = "土豆",
    val createdAt: Long = 0,
    val editedAt: Long? = null,
    val syncedAt: Long = 0,
    val syncState: String = CommentSyncState.LOCAL_NEW,
    val deleted: Boolean = false,
    val actor: String = "LOCAL",
)

/**
 * 文章事件流水（article_history 一行），覆盖拉取 / 本地编辑 / 本地覆盖云端 / 云端覆盖本地。
 */
data class HistoryEntry(
    val id: Long,
    val contentId: String,
    val eventType: String,
    val eventTime: Long,
    val actor: String,
    val field: String? = null,
    val oldValue: String? = null,
    val newValue: String? = null,
    val detail: String? = null,
)
