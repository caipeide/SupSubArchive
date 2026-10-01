package com.peide.supsub.api

import android.content.Context
import android.net.Uri
import android.util.Log
import com.peide.supsub.data.ArchiveStore
import com.peide.supsub.data.ArticleRecord
import com.peide.supsub.data.ClusterEngine
import com.peide.supsub.data.ExportSettings
import com.peide.supsub.data.OriginCategory
import com.peide.supsub.data.ShardKeys
import com.peide.supsub.data.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 拉取方式。
 *
 * - [UNREAD_ONLY] 只拉未读（默认，当前 UI 主路径）
 * - [ALL]         全部历史（含已读），供底层能力
 * - [CUSTOM]      自定义拉取：来源（可指定若干订阅源）+ 发布时间范围组合。
 *                服务端 /api/subscriptions/contents 不支持时间区间参数，时间过滤在**客户端**按 publishedAt 做。
 */
enum class PullMode(val apiType: String) {
    UNREAD_ONLY("unread"),
    ALL("all"),
    CUSTOM("unread"),
}

/**
 * 自定义拉取过滤条件（[PullMode.CUSTOM] 使用）。
 *
 * @param sourceTypes 限定的来源类型（MP/WEBSITE/X）；null 表示沿用启用的来源类型
 * @param sources     限定的具体订阅源（(类型, id) 复合键，避免不同类型下 sourceId 撞号）；空表示不限
 * @param fromTimeSec 发布时间下界（Unix 秒，含）；null 不限
 * @param toTimeSec   发布时间上界（Unix 秒，含）；null 不限
 * @param unreadOnly  是否只拉未读。拉「时间范围」时通常设 false，以包含范围内的已读历史
 */
data class PullFilter(
    val sourceTypes: Set<String>? = null,
    val sources: Set<SourceRef> = emptySet(),
    val fromTimeSec: Long? = null,
    val toTimeSec: Long? = null,
    val unreadOnly: Boolean = true,
)

/**
 * 拉取策略：订阅源与关注点各自独立的过滤条件。
 *
 * - [sub]   订阅源过滤（来源多选 + 时间范围 + 是否只拉未读）。null = 订阅源走默认（仅未读、全来源、不限时间）。
 * - [focus] 关注点过滤（时间范围 + 是否只拉未读；关注点是聚合流，无逐源多选）。null = 关注点走默认（仅未读、不限时间）。
 *
 * 两者相互独立：例如可让「订阅源只拉近 30 天」，同时「关注点拉全部时间」。
 * 注意：关注点底层恒为 type=all 全量拉取（不消费云端未读），[focus].unreadOnly 仅控制本地是否跳过已读项。
 */
data class PullStrategy(
    val sub: PullFilter? = null,
    val focus: PullFilter? = null,
)

/**
 * 订阅源引用：(来源类型, sourceId) 复合键。
 * 必须成对使用——不同 sourceType 下的 sourceId 可能重号，单用 id 会误命中。
 */
data class SourceRef(val sourceType: String, val sourceId: Long)

/** 一次拉取中实际拉取过的订阅源（用于拉取后「标记已读」），与 [SourceRef] 同构 */
typealias PulledSource = SourceRef

/**
 * 本次拉取中实际写入（新增）的文章简要概况。
 *
 * 供「拉取与归档」卡片在拉取完成后展示本轮新增文章的概况：
 * 订阅源、标题、发布时间、所属按天分片、是否同步、是否刚刚新增。
 * 列表即「本轮新增集」——[isNew] 恒为 true；新文落库时同步状态恒为 [SyncStatus.PENDING]（未同步）。
 */
data class PulledArticle(
    val contentId: String,
    /** 订阅源 / 关注点名称（关注点已带「[关注点]」前缀） */
    val sourceName: String,
    /** 来源类型：MP / WEBSITE / X / FOCUS */
    val sourceType: String,
    val title: String,
    /** 发布时间（Unix 秒，0 表示未知） */
    val publishedAt: Long,
    /** 是否本次拉取新增（列表即本轮新增集，恒为 true） */
    val isNew: Boolean,
    /** 落库时的同步状态，新文恒为 [SyncStatus.PENDING]（未同步） */
    val syncStatus: String,
    /** 该条目归属的按天分片键，如 `2026-08-05`（决定它将同步进哪个 Notion 子库） */
    val shardKey: String = "",
)

/** 拉取目标类别，用于把订阅源与关注点的「拉取进度」分开展示 */
enum class PullTargetKind { SUB, FOCUS }

/**
 * 拉取进度。字段与 ViewModel 的 PullUiState 一一对应。
 */
sealed class PullProgress {
    data object Idle : PullProgress()
    data class Running(
        /** 当前正在处理的目标类别：订阅源 or 关注点（用于 UI 分开展示进度） */
        val kind: PullTargetKind,
        val currentSource: String,
        /** 订阅源维度进度（与 focus* 独立展示） */
        val subIndex: Int,
        val subTotal: Int,
        /** 关注点维度进度 */
        val focusIndex: Int,
        val focusTotal: Int,
        val fetchedArticles: Int,
        /** 预计拉取总量（按未读合计估算），用于进度条与「总量」提示 */
        val totalArticlesEstimate: Int,
        /** 当前正在写入的文章标题（可选，逐篇更新） */
        val currentTitle: String,
        /** 本次拉取中实际新增/写入的条数（去重后） */
        val newArticles: Int = 0,
    ) : PullProgress()
    data class Done(
        val subscriptions: Int,
        val articles: Int,
        val newOrUpdated: Int,
        val unchanged: Int,
        /** 订阅源：本轮原始抓取篇数（去重前，含时间/未读过滤、URL 重复拦截前的全部扫描项） */
        val subRaw: Int = 0,
        /** 订阅源：本轮去重后实际新增篇数 */
        val subNew: Int = 0,
        /** 关注点：本轮拉取的未读原始篇数（去重前；时间范围内、未读项，URL 重复拦截/contentId 去重之前） */
        val focusRaw: Int = 0,
        /** 关注点：本轮去重后实际新增篇数 */
        val focusNew: Int = 0,
        /** 备份目录（SAF）。V2 正文不再写这里，仅「导出备份 .db」用；未选择时为 null，不影响拉取 */
        val exportDir: Uri?,
        /** 本次实际拉取过的订阅源（关注点不计入，云端无其 mark-as-read 端点） */
        val pulledSources: List<PulledSource> = emptyList(),
        /** 本次实际新增/写入的文章简要概况（供「拉取与归档」卡片展示） */
        val pulledArticles: List<PulledArticle> = emptyList(),
        /** 归档存储来源标识，V2 恒为 SQLITE */
        val indexSource: String = "SQLITE",
        /**
         * 优化：拉取过程中已逐篇拿到未读 contentId，可直接在本地算「待拉取 / 未读」，
         * 省掉拉完再跑一轮 countUnreadByType 的服务端重查。仅 [PullMode.UNREAD_ONLY] 主路径为 true；
         * ALL / 自定义非未读等场景无可靠未读快照，置 false 让 ViewModel 回退到服务端精确统计。
         */
        val countProvided: Boolean = false,
        /** 各来源类型下本次拉到的未读 contentId 集合（已按类型去重，口径同 countUnreadByType） */
        val unreadIdsByType: Map<String, Set<String>> = emptyMap(),
        /** 各来源类型下的订阅源数量（用于卡片「源数」列） */
        val subCountByType: Map<String, Int> = emptyMap(),
        /** 关注点下本次拉到的未读 contentId 集合 */
        val focusUnreadIds: Set<String> = emptySet(),
        /** 本次拉取实际涉及到的关注点 id（供「标记已读」按钮逐个整点标读） */
        val pulledFocusIds: List<Long> = emptyList(),
        /** 本轮实际拉到未读文章的订阅源（供「标记已读」范围优化：0 未读的源可跳过云端标读） */
        val subSourcesWithUnread: List<PulledSource> = emptyList(),
        /** 本轮实际拉到未读文章的关注点 id（供「标记已读」范围优化） */
        val focusIdsWithUnread: List<Long> = emptyList(),
        /** 本轮新增条目落到的按天分片键集合（同步时会按需在容器页下建这些子库） */
        val shardKeys: Set<String> = emptySet(),
        /** 逐关注点拉取汇总（原始/新增/去重丢弃），供完成页逐关注点列出 */
        val focusSummaries: List<FocusPullSummary> = emptyList(),
        /** 去重丢弃明细（判重那一刻仍持有归属关注点身份），供完成页「去重报告」展开查看 */
        val dedupReport: List<DroppedItem> = emptyList(),
        /**
         * 部分成功告警：个别目标（订阅源 / 关注点）拉取失败时的降级提示。
         * 非空 = 本轮有目标失败，但其余成功 → 仍算 Done（部分成功）；
         * 为空 = 全部成功。全部目标都失败时走 [PullProgress.Error]。
         */
        val warnings: List<String> = emptyList(),
    ) : PullProgress()
    data class Error(val message: String) : PullProgress()
}

/**
 * 被去重丢弃的文章明细。
 * 关键：判重（URL 重复 / 内容重复）的那一刻仍持有其归属关注点身份，故能精确归因到具体关注点，
 * 这也是「去重报告」能逐关注点展示的依据。
 *
 * @param reason "URL 重复" / "内容重复"
 */
data class DroppedItem(
    val focusId: Long,
    val focusTitle: String,
    val title: String,
    val url: String,
    val contentId: String,
    val reason: String,
)

/** 单个关注点的本轮拉取汇总（供完成页逐关注点列出）。 */
data class FocusPullSummary(
    val focusId: Long,
    val focusTitle: String,
    /** 时间范围内、未读、URL/内容去重之前扫描到的篇数 */
    val raw: Int,
    /** 实际新增落库篇数 */
    val newCount: Int,
    /** 被去重丢弃（URL 重复 + 内容重复）篇数 */
    val dropped: Int,
)

/**
 * 拉取引擎（V2：直写 SQLite 单库）。
 *
 * 与 V1 的差别集中在「落地」这一步：
 *  - V1：正文写 SAF 目录下的 JSON 文件群（一篇一个文件）+ Room 存索引。
 *        去重要先列目录拿全部文件名（PKJ110 上 SAF 枚举会静默返回 0 条，只能靠索引兜底），
 *        每写一篇是一次 SAF createDocument + openOutputStream，每天近百篇后文件数无上限增长。
 *  - V2：正文与索引是**同一张表的同一行**，写库走应用私有目录的真实文件路径，
 *        完全不碰 SAF；去重用开跑前的一次性 contentId 快照（HashSet，O(1)）。
 *
 * 落库时同时算好 [ShardKeys] 按天分片键——分片以**发布时间**为准，
 * 因此补拉历史文章会自动归入它本该在的那一档，同步阶段不需要再算一次。
 *
 * 仍然刻意不用 Room：PKJ110（Android 16）上 Room 的写事务会挂死（beginTransaction 永不返回），
 * [ArchiveStore] 用裸 SQLiteOpenHelper + 无显式事务的逐行提交规避。
 */
/**
 * 关注点全量拉取的翻页上限。关注点内容可能上千篇(已验证单点 625 篇)，
 * 全量拉取(type=all)需足够翻页才能覆盖，不能用订阅源默认的 3 页。
 */
private const val FOCUS_MAX_PAGES = 50

/**
 * 关注点增量早停阈值：连续多少页 contentId 全命中本地即停止翻页。
 * 依赖服务端按收录时间倒序返回（新→旧），命中页出现后翻下去只会越来越旧。
 * 首拉（本地为空）时几乎不可能连续全命中，自然全量翻；日常增量拉取通常 1~3 页收敛。
 */
private const val FOCUS_EARLY_STOP_HIT_PAGES = 3

class PullEngine(context: Context) {

    private val repo = SupsubRepository(context)
    private val store = ArchiveStore.get(context)
    private val settings = ExportSettings(context)

    /**
     * 拉取并归档到本地 SQLite。
     *
     * @param mode           拉取方式，默认 [PullMode.UNREAD_ONLY]
     * @param enabledSourceTypes 启用的订阅源类型，默认 MP + WEBSITE
     * @param includeFocus   是否把「关注点（focus）」聚合内容也纳入
     * @param subFilter      订阅源自定义拉取条件（来源 + 时间范围 + 是否只拉未读），null = 订阅源走默认
     * @param focusFilter    关注点自定义拉取条件（时间范围 + 是否只拉未读），null = 关注点走默认；
     *                       关注点底层恒 type=all 全量拉取，unreadOnly 仅作本地「跳过已读」用
     * @param perSourceMaxPages 单源最多翻页数（防止把历史全量翻完），默认 3
     * @param perSourceMaxArticles 单源最多写入条数（硬上限），默认 50
     * @param estimatedTotal 由 ViewModel 传入的真实未读估算（已按 unread 实际计数），<0 时内部用 unreadCount 兜底
     * @param concurrency    同时拉取的订阅源数量，默认 4
     */
    fun pull(
        mode: PullMode = PullMode.UNREAD_ONLY,
        enabledSourceTypes: Set<String> = setOf("MP", "WEBSITE"),
        includeFocus: Boolean = false,
        subFilter: PullFilter? = null,
        focusFilter: PullFilter? = null,
        perSourceMaxPages: Int = 3,
        perSourceMaxArticles: Int = 50,
        /** 由 ViewModel 传入的真实未读估算（已按 unread 实际计数），<0 时内部用 unreadCount 兜底 */
        estimatedTotal: Int = -1,
        /** 同时拉取的订阅源数量，默认 4；网络/IO 允许时可适当调大 */
        concurrency: Int = 4,
    ): Flow<PullProgress> = channelFlow {
        val isCustom = subFilter != null || focusFilter != null
        // 订阅源与关注点各自独立的「只拉未读」开关：未设各自过滤条件时回退到 mode 默认值（默认 UNREAD_ONLY=true）。
        val defaultUnreadOnly = (mode == PullMode.UNREAD_ONLY)
        val subUnreadOnly = subFilter?.unreadOnly ?: defaultUnreadOnly
        // 关注点永远 type=all 全量拉取（不消费云端未读），unreadOnly 仅作本地「跳过已读」用，故取 focusFilter 的开关。
        val focusUnreadOnly = focusFilter?.unreadOnly ?: defaultUnreadOnly
        // 仅「纯 UNREAD_ONLY 主路径（订阅源无自定义过滤）」在拉取时逐篇拿到未读 contentId，可直接回传算计数；
        // 任一类别自定义（带时间过滤）场景口径不一致，回退到服务端精确统计。
        val collectUnread = subUnreadOnly && !isCustom
        Log.i(TAG, "拉取方式=$mode 订阅源过滤=$subFilter 关注点过滤=$focusFilter subUnreadOnly=$subUnreadOnly focusUnreadOnly=$focusUnreadOnly collectUnread=$collectUnread")
        send(
            PullProgress.Running(
                kind = PullTargetKind.SUB,
                currentSource = "准备中…",
                subIndex = 0,
                subTotal = 0,
                focusIndex = 0,
                focusTotal = 0,
                fetchedArticles = 0,
                totalArticlesEstimate = 0,
                currentTitle = "",
                newArticles = 0,
            )
        )

        // V2：归档落在应用私有目录的 SQLite，不再依赖用户选目录。
        // 这里仍读一次备份目录，只是为了在 Done 里回传给 UI 展示，为空也照常拉取。
        val backupDir = runCatching { settings.exportDirUri() }.getOrNull()

        try {
            // 0) 已归档 contentId 快照，用于本轮 O(1) 去重。
            //    一次性取全量（3767 条约 200KB）远比每篇查一次库划算。
            val existingIds = store.allContentIds()
            Log.i(TAG, "本地已归档 ${existingIds.size} 篇，库=${store.dbFile().absolutePath}")

            // 0.5) 已归档 url 快照（归一化），供「关注点 vs 订阅源」按原始链接去重。
            //      关注点文章若与已保留文章（订阅源 / 早先保留的关注点）链接相同，则视为重复舍弃。
            val existingUrls = store.allUrls()

            // 1) 订阅源：只保留「启用类型」+ 自定义 filter 的来源/时间约束
            val allSubs = repo.listSubscriptions()
            val targetSourceTypes = subFilter?.sourceTypes ?: enabledSourceTypes
            val subs = allSubs.filter { it.sourceType in targetSourceTypes }
                .filter {
                    subFilter == null || subFilter.sources.isEmpty() ||
                        SourceRef(it.sourceType, it.sourceId) in subFilter.sources
                }
            Log.i(TAG, "订阅源总数=${allSubs.size}，命中类型=$targetSourceTypes -> ${subs.size} 个")

            // 2) 关注点（可选）
            val focuses = if (includeFocus) {
                val f = repo.listFocuses()
                Log.i(TAG, "关注点总数=${f.size}，已开启纳入")
                f
            } else {
                Log.i(TAG, "关注点未开启，跳过")
                emptyList()
            }

            // 订阅源 / 关注点各自的总数，供进度按类别独立展示
            val subTotalVal = subs.size
            val focusTotalVal = focuses.size

            // 3) 统一成「拉取目标」列表，订阅源 + 关注点顺序处理
            val targets = buildList {
                subs.forEach { add(Target.Sub(it)) }
                focuses.forEach { add(Target.FocusItem(it)) }
            }

            // 4) 预计总量
            val subUnread = subs.sumOf { it.unreadCount }
            val focusUnread = focuses.sumOf { it.unreadCount }
            val totalEstimate = if (estimatedTotal >= 0) estimatedTotal
            else subUnread + (if (includeFocus) focusUnread else 0)
            Log.i(TAG, "未读合计：订阅源=$subUnread，关注点=$focusUnread，预计总量=$totalEstimate")
            send(
                PullProgress.Running(
                    kind = PullTargetKind.SUB,
                    currentSource = "开始拉取",
                    subIndex = 0,
                    subTotal = subTotalVal,
                    focusIndex = 0,
                    focusTotal = focusTotalVal,
                    fetchedArticles = 0,
                    totalArticlesEstimate = totalEstimate,
                    currentTitle = "",
                )
            )

            // 并发拉取状态（线程安全）
            val totalArticles = AtomicInteger(0)
            val written = AtomicInteger(0)
            // 订阅源 / 关注点各自完成计数（进度按类别独立展示）
            val subDone = AtomicInteger(0)
            val focusDone = AtomicInteger(0)
            // 订阅源 / 关注点各自「原始抓取数」与「去重后新增数」（供完成页按通道拆分展示）。
            // - *Fetched：每篇 onArticle 回调都 +1（时间/未读过滤、URL 重复拦截之前），即去重前原始扫描量；
            // - *Written：仅 store.insertIfAbsent 返回 true（实际落库）时 +1，即去重后新增量。
            val subFetched = AtomicInteger(0)
            val focusFetched = AtomicInteger(0)
            // 关注点：本轮「拉取的未读」原始篇数（去重前）。关注点默认只拉未读，这里仅累计未读项，
            // 即 URL 重复拦截 / contentId 去重之前的未读扫描量；与「关注点去重后新增」对照看去重效果。
            val focusUnreadRaw = AtomicInteger(0)
            val subWritten = AtomicInteger(0)
            val focusWritten = AtomicInteger(0)
            val currentTitle = AtomicReference("")
            // 本轮内去重（同一篇可能同时出现在订阅源和关注点下）
            val seen = Collections.newSetFromMap<String>(ConcurrentHashMap())
            // URL 去重集合：初始化为「本地已存在 url」+ 本轮已保留文章的 url。
            // 关注点文章若命中集合则视为重复（与订阅源/早先关注点链接相同），直接舍弃。
            val seenUrls = Collections.synchronizedSet(mutableSetOf<String>()).apply { addAll(existingUrls) }
            val pulledSources = Collections.synchronizedList(mutableListOf<PulledSource>())
            // 部分成功语义：个别目标（订阅源/关注点）失败时记入告警，其余照常完成；全部失败才整轮 Error。
            val failures = Collections.synchronizedList(mutableListOf<String>())
            // 本轮涉及到的关注点 id（供「标记已读」按钮逐个整点标读）
            val pulledFocusIds = Collections.newSetFromMap<Long>(ConcurrentHashMap())
            // 本轮实际拉到「未读文章」的订阅源/关注点（供「标记已读」范围优化：0 未读的源/关注点无需发云端标读）
            val subSourcesWithUnread = Collections.newSetFromMap<PulledSource>(ConcurrentHashMap())
            val focusIdsWithUnread = Collections.newSetFromMap<Long>(ConcurrentHashMap())
            // 本轮新增文章的简要概况（线程安全：并发拉取多个来源时都会追加）
            val pulledArticles = Collections.synchronizedList(mutableListOf<PulledArticle>())
            // 本轮触达的按天分片（同步阶段据此按需建子库）
            val shardKeys = Collections.newSetFromMap<String>(ConcurrentHashMap())
            // 逐关注点计数 / 去重报告收集：判重那一刻仍持有 focus 身份，故可精确归因到具体关注点。
            // - focusRawByFocus：时间范围内、未读、URL/内容去重之前，按关注点累计扫描到的篇数
            // - focusNewByFocus：实际新增落库，按关注点累计
            // - focusDroppedByFocus：被去重丢弃（URL 重复 / 内容重复），按关注点累计
            // - focusTitleById：focusId → 展示标题（去重报告/汇总展示用，避免只存 id）
            // - droppedReport：被丢弃项的明细列表
            val focusRawByFocus = ConcurrentHashMap<Long, AtomicInteger>()
            val focusNewByFocus = ConcurrentHashMap<Long, AtomicInteger>()
            val focusDroppedByFocus = ConcurrentHashMap<Long, AtomicInteger>()
            val focusTitleById = ConcurrentHashMap<Long, String>()
            val droppedReport = Collections.synchronizedList(mutableListOf<DroppedItem>())
            // 未读集合收集（仅 collectUnread 主路径用）：拉取时已逐篇拿到未读 contentId，
            // 按来源类型汇总，拉完直接回传，省掉一轮 countUnreadByType 的服务端重查。
            val unreadIdsByType = ConcurrentHashMap<String, MutableSet<String>>()
            val focusUnreadIds = Collections.newSetFromMap<String>(ConcurrentHashMap())

            // 并发拉取统一收口在 coroutineScope 里：任一子任务抛出未捕获异常时，
            // 所有兄弟任务立即取消（不再有「awaitAll 已报错、孤儿任务还在后台拉写」的泄漏）。
            // 单个目标的失败由 async 内部 catch 降级为部分成功（见 failures 收集），不会走到这里。
            coroutineScope {
                val semaphore = Semaphore(concurrency.coerceAtLeast(1))

                val jobs = targets.mapIndexed { index, target ->
                    async {
                        try {
                            semaphore.withPermit {
                            currentCoroutineContext().ensureActive()
                            val label = when (target) {
                                is Target.Sub -> "订阅源 ${target.sub.name}"
                                is Target.FocusItem -> "关注点 ${target.focus.title}"
                            }
                            var writtenThisTarget = 0
                            val kind = if (target is Target.Sub) PullTargetKind.SUB else PullTargetKind.FOCUS

                            trySend(
                                PullProgress.Running(
                                    currentSource = "$label (${index + 1}/${targets.size})",
                                    kind = kind,
                                    subIndex = subDone.get(),
                                    subTotal = subTotalVal,
                                    focusIndex = focusDone.get(),
                                    focusTotal = focusTotalVal,
                                    fetchedArticles = totalArticles.get(),
                                    totalArticlesEstimate = totalEstimate,
                                    currentTitle = currentTitle.get(),
                                    newArticles = written.get(),
                                )
                            )

                            val onArticle: suspend (ArticleRecord, String) -> Boolean = onArticle@{ record, title ->
                                currentCoroutineContext().ensureActive()
                                val seq = totalArticles.incrementAndGet()
                                // 按通道累加「原始抓取数」（去重前：含时间/未读过滤、URL 重复拦截前的全部扫描项）
                                if (kind == PullTargetKind.SUB) subFetched.incrementAndGet() else focusFetched.incrementAndGet()

                                // 时间范围过滤（客户端，服务端无该参数）：订阅源与关注点各自取自己的过滤条件
                                val timeFilter = when (target) {
                                    is Target.FocusItem -> focusFilter
                                    is Target.Sub -> subFilter
                                }
                                if (!inTimeRange(record.publishedAt, timeFilter)) {
                                    currentTitle.set(title)
                                    trySend(
                                        PullProgress.Running(
                                            currentSource = "$label (${index + 1}/${targets.size})",
                                            kind = kind,
                                            subIndex = subDone.get(),
                                            subTotal = subTotalVal,
                                            focusIndex = focusDone.get(),
                                            focusTotal = focusTotalVal,
                                            fetchedArticles = seq,
                                            totalArticlesEstimate = totalEstimate,
                                            currentTitle = title,
                                            newArticles = written.get(),
                                        )
                                    )
                                    return@onArticle true // 时间范围外，跳过写入但不中断翻页
                                }

                                // 关注点未读原始量：时间范围内、且本篇未读（默认只拉未读）的关注点文章，
                                // 在 URL 重复拦截 / contentId 去重之前累计，作为「关注点原始拉取」展示值。
                                if (target is Target.FocusItem && !record.isRead) {
                                    focusUnreadRaw.incrementAndGet()
                                    if (record.focusId != 0L) {
                                        focusRawByFocus.computeIfAbsent(record.focusId) { AtomicInteger(0) }.incrementAndGet()
                                        if (record.focusTitle.isNotBlank()) focusTitleById[record.focusId] = record.focusTitle
                                    }
                                }

                                // 收集「本轮实际拉到未读文章」的源/关注点：供「标记已读」范围优化，
                                // 0 未读的源/关注点无需发起云端标读（标读是空操作但占一次云端调用，且关注点有服务端限流）。
                                if (!record.isRead) {
                                    when (target) {
                                        is Target.Sub -> subSourcesWithUnread.add(
                                            PulledSource(target.sub.sourceType, target.sub.sourceId),
                                        )
                                        is Target.FocusItem -> focusIdsWithUnread.add(target.focus.id)
                                    }
                                }

                                // 关注点：全量拉取(type=all，不触发服务端「拉即标读」)后本地筛选。
                                // 默认只保留未读(与订阅源 unreadOnly 语义对齐)；已读项直接跳过——
                                // 不写库、不计未读、也不触发云端标读；用户开「包含已读」(focusFilter.unreadOnly=false) 才纳入。
                                if (target is Target.FocusItem && focusUnreadOnly && record.isRead) {
                                    currentTitle.set(title)
                                    trySend(
                                        PullProgress.Running(
                                            currentSource = "$label (${index + 1}/${targets.size})",
                                            kind = kind,
                                            subIndex = subDone.get(),
                                            subTotal = subTotalVal,
                                            focusIndex = focusDone.get(),
                                            focusTotal = focusTotalVal,
                                            fetchedArticles = seq,
                                            totalArticlesEstimate = totalEstimate,
                                            currentTitle = title,
                                            newArticles = written.get(),
                                        )
                                    )
                                    return@onArticle true
                                }

                                // 关注点文章按原始链接去重：若其 url 已出现在已保留文章（订阅源或早先关注点）中，
                                // 视为与订阅源重复，舍弃该关注点条目；云端未读由「标记已读」按钮对整个关注点整点标读时统一清除。
                                val urlKey = record.url.takeIf { it.isNotBlank() }?.let { normalizeUrl(it) }.orEmpty()
                                if (target is Target.FocusItem && urlKey.isNotBlank() && seenUrls.contains(urlKey)) {
                                    Log.d(TAG, "关注点 URL 重复，舍弃：${record.contentId} (url=$urlKey)")
                                    if (record.focusId != 0L) {
                                        focusDroppedByFocus.computeIfAbsent(record.focusId) { AtomicInteger(0) }.incrementAndGet()
                                        if (record.focusTitle.isNotBlank()) focusTitleById[record.focusId] = record.focusTitle
                                        droppedReport.add(
                                            DroppedItem(
                                                focusId = record.focusId,
                                                focusTitle = record.focusTitle,
                                                title = title,
                                                url = record.url,
                                                contentId = record.contentId,
                                                reason = "URL 重复",
                                            ),
                                        )
                                    }
                                    currentTitle.set(title)
                                    trySend(
                                        PullProgress.Running(
                                            currentSource = "$label (${index + 1}/${targets.size})",
                                            kind = kind,
                                            subIndex = subDone.get(),
                                            subTotal = subTotalVal,
                                            focusIndex = focusDone.get(),
                                            focusTotal = focusTotalVal,
                                            fetchedArticles = seq,
                                            totalArticlesEstimate = totalEstimate,
                                            currentTitle = title,
                                            newArticles = written.get(),
                                        )
                                    )
                                    return@onArticle true // 重复项，跳过写入且不计入未读
                                }

                                val firstSeenThisRun = seen.add(record.contentId)
                                val shouldWrite = firstSeenThisRun && record.contentId !in existingIds
                                // 收集未读 contentId（仅主路径）：订阅源按 sourceType 汇总，关注点单独成集，
                                // 供拉完直接在本地算「待拉取 / 未读」，无需再打服务端。重复项已在上一步被拦截，不会进来。
                                if (collectUnread) {
                                    if (target is Target.FocusItem) {
                                        focusUnreadIds.add(record.contentId)
                                    } else {
                                        unreadIdsByType
                                            .computeIfAbsent(record.sourceType) { Collections.newSetFromMap(ConcurrentHashMap()) }
                                            .add(record.contentId)
                                    }
                                }
                                if (shouldWrite) {
                                    // INSERT OR IGNORE：并发下若另一目标刚好抢先写入同一篇，这里静默跳过，
                                    // 绝不会覆盖已有行的同步状态。
                                    val inserted = store.insertIfAbsent(record)
                                    if (inserted) {
                                        if (urlKey.isNotBlank()) seenUrls.add(urlKey)
                                        shardKeys.add(record.shardKey)
                                        // 收集本轮新增文章的概况（发布时间、来源、标题、分片、同步状态）供 UI 展示
                                        pulledArticles.add(
                                            PulledArticle(
                                                contentId = record.contentId,
                                                sourceName = record.sourceName,
                                                sourceType = record.sourceType,
                                                title = record.title,
                                                publishedAt = record.publishedAt,
                                                isNew = true,
                                                syncStatus = SyncStatus.PENDING,
                                                shardKey = record.shardKey,
                                            ),
                                        )
                                        written.incrementAndGet()
                                        writtenThisTarget++
                                        // 按通道累加「去重后新增数」（实际落库 insert 成功）
                                        if (kind == PullTargetKind.SUB) {
                                            subWritten.incrementAndGet()
                                        } else {
                                            focusWritten.incrementAndGet()
                                            if (target is Target.FocusItem && record.focusId != 0L) {
                                                focusNewByFocus.computeIfAbsent(record.focusId) { AtomicInteger(0) }.incrementAndGet()
                                                if (record.focusTitle.isNotBlank()) focusTitleById[record.focusId] = record.focusTitle
                                            }
                                        }
                                    }
                                } else if (target is Target.FocusItem && record.focusId != 0L) {
                                    // 内容重复（本轮已扫描到 或 本地已存在）：计入该关注点的去重丢弃
                                    focusDroppedByFocus.computeIfAbsent(record.focusId) { AtomicInteger(0) }.incrementAndGet()
                                    if (record.focusTitle.isNotBlank()) focusTitleById[record.focusId] = record.focusTitle
                                    droppedReport.add(
                                        DroppedItem(
                                            focusId = record.focusId,
                                            focusTitle = record.focusTitle,
                                            title = title,
                                            url = record.url,
                                            contentId = record.contentId,
                                            reason = "内容重复",
                                        ),
                                    )
                                }
                                currentTitle.set(title)
                                trySend(
                                    PullProgress.Running(
                                        currentSource = "$label (${index + 1}/${targets.size})",
                                        kind = kind,
                                        subIndex = subDone.get(),
                                        subTotal = subTotalVal,
                                        focusIndex = focusDone.get(),
                                        focusTotal = focusTotalVal,
                                        fetchedArticles = seq,
                                        totalArticlesEstimate = totalEstimate,
                                        currentTitle = title,
                                        newArticles = written.get(),
                                    )
                                )
                                // 主路径（collectUnread）要拿到完整未读集合用于计数：继续翻页（最多到 maxPages），
                                // 写入仍受 perSourceMaxArticles 限制；其余模式保持「写到上限即停翻页」原行为。
                                // 关注点全量拉取：必须翻完所有页（本地再按时间/未读筛选），故也继续翻页。
                                if (collectUnread || target is Target.FocusItem) true
                                else !(shouldWrite && writtenThisTarget >= perSourceMaxArticles)
                            }

                            when (target) {
                                is Target.Sub -> {
                                    val sub = target.sub
                                    pulledSources.add(PulledSource(sub.sourceType, sub.sourceId))
                                    repo.fetchAllContents(
                                        sourceType = sub.sourceType,
                                        sourceId = sub.sourceId,
                                        unreadOnly = subUnreadOnly,
                                        pageSize = 100,
                                        maxPages = perSourceMaxPages,
                                    ) { item ->
                                        onArticle(
                                            item.article.toRecord(
                                                sourceId = sub.sourceId,
                                                sourceType = sub.sourceType,
                                                sourceName = sub.name,
                                                originCategory = OriginCategory.SUBSCRIPTION,
                                                raw = item.raw,
                                            ),
                                            item.article.title,
                                        )
                                    }
                                }
                                is Target.FocusItem -> {
                                    val focus = target.focus
                                    pulledFocusIds.add(focus.id)
                                    // 关注点永远全量拉取(type=all)：服务端对 type=unread 是「拉即标读」，
                                    // 会破坏「非重复关注点云端保持未读」的约定。改为全量拉取后再本地按时间/未读筛选。
                                    // 增量早停：已归档 contentId 连续 3 页全命中本地即停翻（日常增量 1~3 页收敛）；
                                    // collectList=false：不再在内存中收集全关注点列表（降 OOM 峰值）。
                                    repo.fetchAllFocusContents(
                                        focusId = focus.id,
                                        unreadOnly = false,
                                        pageSize = 100,
                                        maxPages = FOCUS_MAX_PAGES,
                                        existingContentIds = existingIds,
                                        earlyStopHitPages = FOCUS_EARLY_STOP_HIT_PAGES,
                                        collectList = false,
                                    ) { item ->
                                        onArticle(
                                            item.content.toRecord(focus, item.raw),
                                            item.content.title,
                                        )
                                    }
                                }
                            }

                            val doneCount = if (target is Target.Sub) subDone.incrementAndGet() else focusDone.incrementAndGet()
                            Log.i(
                                TAG,
                                "目标 ${index + 1}/${targets.size} 完成：$label，本目标写入 $writtenThisTarget，累计完成 $doneCount",
                            )
                            trySend(
                                PullProgress.Running(
                                    kind = kind,
                                    currentSource = "$label 完成",
                                    subIndex = subDone.get(),
                                    subTotal = subTotalVal,
                                    focusIndex = focusDone.get(),
                                    focusTotal = focusTotalVal,
                                    fetchedArticles = totalArticles.get(),
                                    totalArticlesEstimate = totalEstimate,
                                    currentTitle = currentTitle.get(),
                                    newArticles = written.get(),
                                )
                            )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            // 部分成功语义：单个目标（订阅源 / 关注点）失败不整轮失败，
                            // 记入告警并推进完成计数（否则进度条卡在未完成态），其余目标照常拉取。
                            val label = when (target) {
                                is Target.Sub -> "订阅源 ${target.sub.name}"
                                is Target.FocusItem -> "关注点 ${target.focus.title}"
                            }
                            Log.w(TAG, "目标 $label 拉取失败，已降级为部分成功", e)
                            failures += "$label：${e.message ?: e.javaClass.simpleName}"
                            if (target is Target.Sub) subDone.incrementAndGet() else focusDone.incrementAndGet()
                        }
                    }
                }

                jobs.awaitAll()

                // 拉取完成后自动增量聚类（best-effort，失败不影响拉取主流程）
                runCatching { ClusterEngine(store).clusterNew() }
                    .onFailure { Log.w(TAG, "拉取后自动聚类失败（已忽略）", it) }
            }

            val finalTotal = totalArticles.get()
            val finalWritten = written.get()
            val finalShards = shardKeys.filter { it.isNotBlank() }.toSortedSet().toSet()

            // 汇总逐关注点拉取数据：合并 raw/new/dropped 三个维度涉及的关注点 id，按标题排序展示。
            val focusSummaries = mutableListOf<FocusPullSummary>().apply {
                (focusRawByFocus.keys + focusNewByFocus.keys + focusDroppedByFocus.keys)
                    .distinct()
                    .sortedBy { focusTitleById[it] ?: it.toString() }
                    .forEach { fid ->
                        add(
                            FocusPullSummary(
                                focusId = fid,
                                focusTitle = focusTitleById[fid] ?: "",
                                raw = focusRawByFocus[fid]?.get() ?: 0,
                                newCount = focusNewByFocus[fid]?.get() ?: 0,
                                dropped = focusDroppedByFocus[fid]?.get() ?: 0,
                            ),
                        )
                    }
            }

            store.setMeta(META_LAST_PULL, System.currentTimeMillis().toString())
            val stats = runCatching { store.stats() }.getOrNull()
            Log.i(
                TAG,
                "拉取完成：订阅源=${subs.size}，文章=$finalTotal，新增=$finalWritten，" +
                    "重复=${finalTotal - finalWritten}，库内合计=${stats?.total}，本轮分片=$finalShards",
            )
            Log.i(
                TAG,
                "DONE_MARKER subscriptions=${subs.size} articles=$finalTotal newOrUpdated=$finalWritten " +
                    "unchanged=${finalTotal - finalWritten} shards=${finalShards.joinToString(",")}",
            )

            // 全部目标都失败 → 整轮失败（部分成功只发生在「有成功有失败」时）
            if (targets.isNotEmpty() && failures.size >= targets.size) {
                val msg = failures.joinToString("；")
                Log.e(TAG, "全部 $targets.size 个目标拉取失败：$msg")
                send(PullProgress.Error(msg))
                return@channelFlow
            }

            val done = PullProgress.Done(
                subscriptions = subs.size,
                articles = finalTotal,
                newOrUpdated = finalWritten,
                unchanged = finalTotal - finalWritten, // 已归档过 / 本轮重复（同时出现在订阅源与关注点）
                subRaw = subFetched.get(),
                subNew = subWritten.get(),
                focusRaw = focusUnreadRaw.get(),
                focusNew = focusWritten.get(),
                exportDir = backupDir,
                pulledSources = pulledSources.toList(),
                pulledArticles = pulledArticles.sortedByDescending { it.publishedAt }.toList(),
                indexSource = stats?.source ?: "SQLITE",
                countProvided = collectUnread,
                unreadIdsByType = unreadIdsByType.mapValues { it.value.toSet() },
                subCountByType = subs.groupingBy { it.sourceType }.eachCount(),
                focusUnreadIds = focusUnreadIds.toSet(),
                pulledFocusIds = pulledFocusIds.toList(),
                subSourcesWithUnread = subSourcesWithUnread.toList(),
                focusIdsWithUnread = focusIdsWithUnread.toList(),
                shardKeys = finalShards,
                focusSummaries = focusSummaries,
                dedupReport = droppedReport.toList(),
                warnings = failures.toList(),
            )
            if (failures.isNotEmpty()) {
                Log.w(TAG, "本轮拉取部分成功（${failures.size}/${targets.size} 个目标失败）：${failures.joinToString("；")}")
            }
            send(done)
            // 标记已读不再随拉取自动执行，改由 UI「标记已读」按钮触发（见 AuthViewModel.startMarkRead）。
        } catch (e: Throwable) {
            // 取消（CancellationException）必须向上传播，否则会被当成「拉取失败」误报
            if (e is CancellationException) throw e
            // catch Throwable：OOM 等 Error 也兜住，转成可感知的 Error 状态而不是让进程直接崩。
            Log.e(TAG, "拉取失败", e)
            val msg = e.message ?: e.javaClass.simpleName
            send(PullProgress.Error(msg))
        }
    }
        // CONFLATED：进度只保留最新一条，避免上千次 emit 把主线程刷爆；
        // 终态（Done/Error）是最后一条，不会被丢弃。
        .buffer(Channel.CONFLATED)
        .flowOn(Dispatchers.IO)

    /** 关注点重复项在云端标记已读（best-effort，后台执行，不阻塞拉取主流程） */
    /** 客户端时间范围过滤（服务端无时间区间参数） */
    private fun inTimeRange(publishedAt: Long?, filter: PullFilter?): Boolean {
        if (filter == null) return true
        val t = publishedAt?.takeIf { it > 0 } ?: return false // 无时间字段的文章在时间范围筛选下跳过
        val from = filter.fromTimeSec
        val to = filter.toTimeSec
        if (from != null && t < from) return false
        if (to != null && t > to) return false
        return true
    }

    /** 拉取目标：订阅源 or 关注点 */
    private sealed interface Target {
        data class Sub(val sub: Subscription) : Target
        data class FocusItem(val focus: Focus) : Target
    }

    /** URL 归一化：去首尾空白、转小写、去结尾斜杠。须与 [ArchiveStore.normalizeUrl] 保持一致。 */
    private fun normalizeUrl(url: String): String {
        val t = url.trim().lowercase()
        return if (t.endsWith("/")) t.substring(0, t.length - 1) else t
    }

    /**
     * 订阅源文章 → 归档记录。
     *
     * V1 这里只映射「结构化基础字段」，keywords / analysis 那批 AI 维度靠 JSON 模板另存一份，
     * 同步时再把 JSON 解析回来。V2 直接把它们摊平成表列，同步引擎读一行就够，不用碰 JSON。
     *
     * @param raw 服务端返回的**原始 JSON**（不是模型再序列化），
     *            存进 raw_json，用于追溯字段变更 / 保留未知字段。
     */
    private fun Article.toRecord(
        sourceId: Long,
        sourceType: String,
        sourceName: String,
        originCategory: OriginCategory,
        raw: JsonObject,
    ): ArticleRecord {
        // published_at 按分钟精度存储（与 Notion date 属性对齐），秒向下取整到分钟
        val published = publishedAt?.let { if (it > 0) (it / 60) * 60 else it } ?: 0L
        val captured = System.currentTimeMillis()
        return ArticleRecord(
            contentId = contentId,
            sourceId = sourceId,
            sourceType = sourceType,
            originCategory = originCategory.key,
            sourceName = sourceName,
            title = title,
            summary = summary.takeIf { it.isNotBlank() },
            tags = Json.encodeToString(tags),
            keywords = Json.encodeToString(keywords),
            url = url,
            coverImage = coverImage.takeIf { it.isNotBlank() },
            publishedAt = published,
            capturedAt = captured,
            // 服务端已读状态，之前漏映射导致归档里恒为 false
            isRead = isRead,
            contentType = contentType,
            contentDepth = contentDepth,
            tone = tone,
            style = style,
            hasAction = hasAction,
            hasStance = hasStance,
            stanceSummary = stanceSummary,
            contentHash = contentHashOf(
                title, summary, tags, keywords, url,
                contentType, contentDepth, tone, style, stanceSummary,
            ),
            shardKey = ShardKeys.of(published, captured),
            rawJson = raw.toString(),
            syncStatus = SyncStatus.PENDING,
        )
    }

    /**
     * 关注点内容 → 归档记录。
     * 真实来源类型（MP/WEBSITE/X）保留在 sourceType；
     * sourceName 用服务端返回的真实来源名（公众号/网站名），不再带「[关注点]」前缀，
     * 避免阅读详情里把「关注点名字」错当成「公众号标题」。
     * 关注点身份由 originCategory='FOCUS' 与 focusId/focusTitle 单独标识。
     */
    private fun FocusContent.toRecord(focus: Focus, raw: JsonObject): ArticleRecord {
        // published_at 按分钟精度存储（与 Notion date 属性对齐），秒向下取整到分钟
        val published = publishedAt?.let { if (it > 0) (it / 60) * 60 else it } ?: 0L
        val captured = System.currentTimeMillis()
        return ArticleRecord(
            contentId = contentId,
            sourceId = focus.id,
            sourceType = sourceType.ifBlank { "FOCUS" },
            originCategory = OriginCategory.FOCUS.key,
            sourceName = sourceName.ifBlank { "[关注点]${focus.title}" },
            focusId = focus.id,
            focusTitle = focus.title,
            title = title,
            summary = summary.takeIf { it.isNotBlank() },
            tags = Json.encodeToString(tags),
            keywords = Json.encodeToString(keywords),
            url = url,
            coverImage = coverImage.takeIf { it.isNotBlank() },
            publishedAt = published,
            capturedAt = captured,
            isRead = isRead,
            contentType = contentType,
            contentDepth = contentDepth,
            tone = tone,
            style = style,
            hasAction = hasAction,
            hasStance = hasStance,
            stanceSummary = stanceSummary,
            contentHash = contentHashOf(
                title, summary, tags, keywords, url,
                contentType, contentDepth, tone, style, stanceSummary,
            ),
            shardKey = ShardKeys.of(published, captured),
            rawJson = raw.toString(),
            syncStatus = SyncStatus.PENDING,
        )
    }

    companion object {
        private const val TAG = "PullEngine"

        /** meta 表键：上次拉取完成时间（毫秒） */
        const val META_LAST_PULL = "last_pull_at"

        /**
         * 内容指纹：同步引擎靠它判断「这条要不要重推」。
         *
         * 相比 V1 只算 title+summary+tags+url，这里把 keywords 与 AI 分析维度也纳入——
         * 服务端经常是先给基础字段、隔一会儿才补全 analysis，
         * 不纳入的话补全后指纹不变，Notion 里就永远停留在缺分析维度的旧版本。
         */
        fun contentHashOf(
            title: String,
            summary: String,
            tags: List<String>,
            keywords: List<String>,
            url: String,
            contentType: String,
            contentDepth: String,
            tone: String,
            style: String,
            stanceSummary: String,
        ): String = sha256(
            buildString {
                append(title).append('\u0001')
                append(summary).append('\u0001')
                append(tags.joinToString(",")).append('\u0001')
                append(keywords.joinToString(",")).append('\u0001')
                append(url).append('\u0001')
                append(contentType).append('\u0001')
                append(contentDepth).append('\u0001')
                append(tone).append('\u0001')
                append(style).append('\u0001')
                append(stanceSummary)
            }
        )

        fun sha256(input: String): String {
            val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
