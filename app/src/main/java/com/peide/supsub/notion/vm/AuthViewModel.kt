package com.peide.supsub.notion.vm
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peide.supsub.api.DeviceCodeResponse
import com.peide.supsub.api.DeviceFlowAuth
import com.peide.supsub.api.Focus
import com.peide.supsub.api.FocusMarkAsReadRequest
import com.peide.supsub.api.PullEngine
import com.peide.supsub.api.PullFilter
import com.peide.supsub.api.PullMode
import com.peide.supsub.api.PullStrategy
import com.peide.supsub.api.PullProgress
import com.peide.supsub.api.PullTargetKind
import com.peide.supsub.api.PulledSource
import com.peide.supsub.api.Subscription
import com.peide.supsub.api.SupsubException
import com.peide.supsub.api.SupsubRepository
import com.peide.supsub.api.UserInfo
import com.peide.supsub.data.ArchiveStats
import com.peide.supsub.data.ArchiveStore
import com.peide.supsub.data.ReadingItem
import com.peide.supsub.data.ReadingTimeRange
import com.peide.supsub.data.ReadingStatus
import com.peide.supsub.data.ReadingSyncFilter
import com.peide.supsub.data.ArticleSummary
import com.peide.supsub.data.ExportSettings
import com.peide.supsub.data.OriginCategory
import com.peide.supsub.data.ReadingFocus
import com.peide.supsub.data.NotionShard
import com.peide.supsub.data.ClusterEngine
import com.peide.supsub.data.HistoryEntry
import com.peide.supsub.data.CommentEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.peide.supsub.notionsync.ConnectionCheck
import com.peide.supsub.notionsync.NotionConfig
import com.peide.supsub.notionsync.SyncProgress
import com.peide.supsub.notionsync.SyncResult
import com.peide.supsub.notionsync.SupSubSyncEngine
import com.peide.supsub.notionsync.buildNotionApi
import com.peide.supsub.notionsync.checkNotionParentPage
import com.peide.supsub.notionsync.queryDatabaseCount
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 登录/账号页的 UI 状态 */
sealed interface AuthUiState {
    /** 未登录，等待用户点「登录」 */
    data object LoggedOut : AuthUiState

    /** 正在申请设备码 / 校验登录态 */
    data object Loading : AuthUiState

    /** 已拿到设备码，等待用户在网页完成授权 */
    data class AwaitingAuthorization(
        val deviceCode: DeviceCodeResponse,
        val remainingSeconds: Int,
    ) : AuthUiState

    /** 登录成功（offline=true 表示本地有令牌但服务端不可达，仅允许阅读/标记本地内容） */
    data class LoggedIn(val user: UserInfo, val offline: Boolean = false) : AuthUiState

    /** 出错（可重试） */
    data class Error(val message: String, val needsRelogin: Boolean = false) : AuthUiState
}

sealed interface PullUiState {
    data object Idle : PullUiState
    data object RequestingDir : PullUiState
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
        val totalArticlesEstimate: Int,
        val currentTitle: String,
        val newArticles: Int = 0,
    ) : PullUiState
    data class Done(
        val subscriptions: Int,
        val articles: Int,
        val newOrUpdated: Int,
        val unchanged: Int,
        /** 订阅源：本轮原始抓取篇数（去重前） */
        val subRaw: Int = 0,
        /** 订阅源：本轮去重后实际新增篇数 */
        val subNew: Int = 0,
        /** 关注点：本轮拉取的未读原始篇数（去重前；默认只拉未读） */
        val focusRaw: Int = 0,
        /** 关注点：本轮去重后实际新增篇数 */
        val focusNew: Int = 0,
        val exportDir: Uri?,
        /** 本次实际拉取过的订阅源（云端无关注点 mark-as-read 端点，不计入） */
        val pulledSources: List<PulledSource> = emptyList(),
        /** 本次实际新增/写入的文章简要概况（供「拉取与归档」卡片展示） */
        val pulledArticles: List<com.peide.supsub.api.PulledArticle> = emptyList(),
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
        /** 本轮实际拉取过的关注点 id 列表（size = 本次涉及关注点数） */
        val pulledFocusIds: List<Long> = emptyList(),
        /** 本轮实际拉到未读文章的订阅源（供「标记已读」范围优化：0 未读的源可跳过云端标读） */
        val subSourcesWithUnread: List<PulledSource> = emptyList(),
        /** 本轮实际拉到未读文章的关注点 id（供「标记已读」范围优化） */
        val focusIdsWithUnread: List<Long> = emptyList(),
        /** 本轮新增条目落到的按天分片键集合（同步时会按需在容器页下建这些子库） */
        val shardKeys: Set<String> = emptySet(),
        /** 逐关注点拉取汇总（原始/新增/去重丢弃），供完成页逐关注点列出 */
        val focusSummaries: List<com.peide.supsub.api.FocusPullSummary> = emptyList(),
        /** 去重丢弃明细（判重那一刻仍持有归属关注点身份），供完成页「去重报告」展开查看 */
        val dedupReport: List<com.peide.supsub.api.DroppedItem> = emptyList(),
        /** 部分成功告警：个别目标拉取失败时的降级提示（非空 = 本轮有失败但有成功） */
        val warnings: List<String> = emptyList(),
    ) : PullUiState
    data class Error(val message: String) : PullUiState
}

/**
 * 「标记已读」按钮触发的**关注点整点标读**进度（独立标识，不阻塞拉取进度条）。
 *
 * 用户点「标记已读」后，对本次拉取涉及到的关注点逐个整点标读（POST /api/focuses/{id}/mark-as-read）。
 * 有自己的进度提示，让用户知道云端标记已读跑到哪了、成功/失败多少。
 */
sealed interface FocusMarkUiState {
    data object Idle : FocusMarkUiState
    data class Running(val marked: Int, val failed: Int, val total: Int) : FocusMarkUiState
    data class Done(val marked: Int, val failed: Int, val total: Int) : FocusMarkUiState
    data class Error(val message: String) : FocusMarkUiState
}

/**
 * 「标记已读」按钮触发的**订阅源整源标读**进度（独立标识，与 [FocusMarkUiState] 并列展示）。
 *
 * 对本次拉取涉及到的订阅源逐个整源标读（POST /api/subscriptions/contents/mark-as-read，不可逆）。
 */
sealed interface SourceMarkUiState {
    data object Idle : SourceMarkUiState
    data class Running(val marked: Int, val failed: Int, val total: Int) : SourceMarkUiState
    data class Done(val marked: Int, val failed: Int, val total: Int) : SourceMarkUiState
    data class Error(val message: String) : SourceMarkUiState
}

/** 一键备份（SQLite 库 → SAF 备份目录）的交互状态 */
sealed interface BackupUiState {
    data object Idle : BackupUiState
    data object Running : BackupUiState
    data class Done(val fileName: String) : BackupUiState
    data class Error(val message: String) : BackupUiState
}

/** 相似文章聚类（本地）的交互状态 */
sealed interface ClusterUiState {
    data object Idle : ClusterUiState
    data object Running : ClusterUiState
    data class Done(val clusters: Int, val reassigned: Int) : ClusterUiState
    data class Error(val message: String) : ClusterUiState
}

/** 阅读页列表排序维度 */
enum class ReadingFilter {
    BY_TIME,
    BY_SOURCE,
}

class AuthViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SupsubRepository(app)
    private val pullEngine = PullEngine(app)
    private val exportSettings = ExportSettings(app)
    /** V2 单一 SQLite 归档库（替代 V1 的 FileExporter + Room 索引三件套） */
    private val store = ArchiveStore.get(app)

    /** 阅读页筛选偏好（记忆上次选择），用普通 SharedPreferences 落盘（非敏感信息） */
    private val readingPrefs: SharedPreferences =
        app.getSharedPreferences("reading_filters", Context.MODE_PRIVATE)

    /** 账号信息缓存（在线成功时写入，离线模式读取，避免账号卡空白）。明文 JSON，非敏感 */
    private val userPrefs: SharedPreferences =
        app.getSharedPreferences("cached_user", Context.MODE_PRIVATE)

    private object ReadingPrefKeys {
        const val TIME_RANGE = "time_range"
        const val SOURCE_FILTER = "source_filter"
        const val STATUS = "status"
        const val SYNC_FILTER = "sync_filter"
        const val CATEGORY = "category_filter"
        const val FOCUS_FILTER = "focus_filter"
    }


    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.LoggedOut)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    /** 是否处于离线模式（本地有令牌但服务端不可达）。离线时仅允许阅读/标记本地内容，联网操作被禁用 */
    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    private val _pullState = MutableStateFlow<PullUiState>(PullUiState.Idle)
    val pullState: StateFlow<PullUiState> = _pullState.asStateFlow()

    /** 启用的订阅源类型（MP / WEBSITE / X），默认 MP + WEBSITE */
    val enabledSourceTypes: StateFlow<Set<String>> = exportSettings.enabledSourceTypesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, ExportSettings.DEFAULT_ENABLED_SOURCE_TYPES)

    /** 是否纳入「关注点」内容，默认 false */
    val includeFocus: StateFlow<Boolean> = exportSettings.includeFocusFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 是否开启双向同步（推送后把 Notion 端较新的改动回拉本地），默认 true */
    val bidirectionalSync: StateFlow<Boolean> = exportSettings.bidirectionalSyncFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** 全部订阅源（用于统计未读、展示各来源条目） */
    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    /** 关注点列表（用于统计未读） */
    private val _focuses = MutableStateFlow<List<Focus>>(emptyList())
    val focuses: StateFlow<List<Focus>> = _focuses.asStateFlow()

    /** 各订阅源类型未读合计（优先用真实拉取计数，初始用 API 上报值兜底） */
    private val _unreadByType = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadByType: StateFlow<Map<String, Int>> = _unreadByType.asStateFlow()

    /** 各订阅源类型下去重后的「待拉取（新增）」条数（= 未读 - 本地已存在） */
    private val _newUnreadByType = MutableStateFlow<Map<String, Int>>(emptyMap())
    val newUnreadByType: StateFlow<Map<String, Int>> = _newUnreadByType.asStateFlow()

    /** 各订阅源类型下的订阅源数量（key 为 MP / WEBSITE / X） */
    private val _subscriptionCountByType = MutableStateFlow<Map<String, Int>>(emptyMap())
    val subscriptionCountByType: StateFlow<Map<String, Int>> = _subscriptionCountByType.asStateFlow()

    /** 关注点未读合计（开启关注点时参考，来自 API 上报值，可能偏大） */
    val totalFocusUnread: StateFlow<Int> = _focuses
        .map { it.sumOf { f -> f.unreadCount } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** 关注点精确云端未读合计（来自 countUnreadByType 的逐源真实统计，比 API 的 unreadCount 准确） */
    private val _focusCloudUnread = MutableStateFlow(0)
    val focusCloudUnread: StateFlow<Int> = _focusCloudUnread.asStateFlow()

    /** 令牌是否降级为明文存储（Keystore 异常时），用于在 UI 上提示 */
    val usingPlainStorage: Boolean get() = repo.tokenStore.usingFallback

    private var pollJob: Job? = null
    private var pullJob: Job? = null
    private var markReadJob: Job? = null
    private var notionJob: Job? = null

    /** 拉取冷却：两次拉取之间的最小间隔（毫秒），避免 UI 连点触发服务端限流 */
    private val MIN_PULL_INTERVAL_MS = 2_000L

    /** 上次拉取结束（完成/失败）的毫秒时间戳，用于冷却判断；0 表示尚无记录 */
    @Volatile
    private var lastPullEndedAt = 0L

    private val _notionSyncState = MutableStateFlow<SyncProgress>(SyncProgress.Idle)
    val notionSyncState: StateFlow<SyncProgress> = _notionSyncState.asStateFlow()

    private val _notionVerify = MutableStateFlow<ConnectionCheck?>(null)
    val notionVerify: StateFlow<ConnectionCheck?> = _notionVerify.asStateFlow()

    /** 已保存的 Notion Token / 容器页 ID（DataStore），用于初始化输入框与自动填充 */
    val notionConfig: StateFlow<Pair<String, String>> =
        exportSettings.notionTokenFlow.combine(exportSettings.notionParentPageIdFlow) { t, d -> (t ?: "") to (d ?: "") }
            .stateIn(viewModelScope, SharingStarted.Eagerly, "" to "")

    /** 当前导出（备份）目录（SAF），仅「导出备份 .db」时用；V2 正文不写这里 */
    val exportDir: StateFlow<Uri?> =
        exportSettings.exportDirFlow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 最近一次精确未读统计结果（供拉取进度估算复用，避免重复网络请求） */
    private var lastUnreadResult: SupsubRepository.UnreadCountResult? = null

    // ── 本地归档校验 / 统计（需求 2 / 5）──
    private val _archiveStats = MutableStateFlow<ArchiveStats?>(null)
    val archiveStats: StateFlow<ArchiveStats?> = _archiveStats.asStateFlow()

    private val _archiveHint = MutableStateFlow<String?>(null)
    val archiveHint: StateFlow<String?> = _archiveHint.asStateFlow()

    /** 本地归档校验是否正在进行（主卡进度提示用） */
    private val _archiveValidating = MutableStateFlow(false)
    val archiveValidating: StateFlow<Boolean> = _archiveValidating.asStateFlow()

    /** 进度提示最短可见时长：热缓存下校验为亚毫秒级，若立刻收起肉眼不可见 */
    private val MIN_VALIDATE_VISIBLE_MS = 400L

    /** 拉取策略：订阅源与关注点各自独立的过滤条件；null 表示两者都走默认「只拉未读」 */
    private val _pullStrategy = MutableStateFlow<PullStrategy?>(null)
    val pullStrategy: StateFlow<PullStrategy?> = _pullStrategy.asStateFlow()

    /** 拉取完成后「关注点整点标读」的后台进度（独立标识，由 startMarkRead 驱动） */
    private val _focusMarkState = MutableStateFlow<FocusMarkUiState>(FocusMarkUiState.Idle)
    val focusMarkState: StateFlow<FocusMarkUiState> = _focusMarkState.asStateFlow()

    /** 「标记已读」按钮触发的订阅源整源标读进度（与 _focusMarkState 并列展示） */
    private val _sourceMarkState = MutableStateFlow<SourceMarkUiState>(SourceMarkUiState.Idle)
    val sourceMarkState: StateFlow<SourceMarkUiState> = _sourceMarkState.asStateFlow()

    /** 最近一次拉取实际涉及到的关注点 id（供「标记已读」逐个整点标读） */
    private val _lastPulledFocusIds = MutableStateFlow<List<Long>>(emptyList())

    /** 最近一次拉取实际拉过的订阅源（供「标记已读」使用） */
    private val _lastPulledSources = MutableStateFlow<List<PulledSource>>(emptyList())
    val lastPulledSources: StateFlow<List<PulledSource>> = _lastPulledSources.asStateFlow()

    /** 最近一次拉取中「实际拉到未读文章」的订阅源（供「标记已读」范围优化：0 未读的源跳过云端标读） */
    private val _lastPulledSubSourcesWithUnread = MutableStateFlow<Set<PulledSource>>(emptySet())

    /** 最近一次拉取中「实际拉到未读文章」的关注点 id（供「标记已读」范围优化） */
    private val _lastPulledFocusIdsWithUnread = MutableStateFlow<Set<Long>>(emptySet())

    /**
     * 「标记已读（云端）」按钮即将作用的范围提示：
     * - 若本会话已拉取过 → "将对本次拉取的 X 个订阅源、Y 个关注点标读"；
     * - 若未拉取过 / App 重启后 → "将对全部已启用订阅源、关注点标读"。
     */
    val markReadScopeHint: StateFlow<String> = combine(_lastPulledSources, _lastPulledFocusIds) { sources, focusIds ->
        when {
            sources.isNotEmpty() || focusIds.isNotEmpty() -> {
                val parts = buildList {
                    if (sources.isNotEmpty()) add("${sources.size} 个订阅源")
                    if (focusIds.isNotEmpty()) add("${focusIds.size} 个关注点")
                }
                "将对本次拉取的 ${parts.joinToString("、")} 标读"
            }
            else -> "将对全部已启用订阅源、关注点标读"
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "将对全部已启用订阅源、关注点标读")

    /** 测试拉取订阅源是否正在进行（按钮进度提示用） */
    private val _probeRunning = MutableStateFlow(false)
    val probeRunning: StateFlow<Boolean> = _probeRunning.asStateFlow()

    /** 「待拉取 / 未读 / 源数」这组计数是否正在重新统计（卡片上给出「统计中…」提示） */
    private val _countsRefreshing = MutableStateFlow(false)
    val countsRefreshing: StateFlow<Boolean> = _countsRefreshing.asStateFlow()

    /** 精确未读统计任务。持有引用是为了后来者取消前一个，避免并发写同一批计数导致数字回跳 */
    private var unreadCountJob: Job? = null

    /** 最近一次拉取新增的文章 contentId 集合，用于在「已拉取文章概况」里标记「新增」 */
    private val _lastPulledContentIds = MutableStateFlow<Set<String>>(emptySet())
    val lastPulledContentIds: StateFlow<Set<String>> = _lastPulledContentIds.asStateFlow()

    /** 「已拉取文章概况」分页状态（常驻展示本地归档，与拉取瞬时状态解耦） */
    private val ARCHIVE_PAGE_SIZE = 40
    private val _archivedArticles = MutableStateFlow<List<ArticleSummary>>(emptyList())
    val archivedArticles: StateFlow<List<ArticleSummary>> = _archivedArticles.asStateFlow()
    private val _archivedHasMore = MutableStateFlow(false)
    val archivedHasMore: StateFlow<Boolean> = _archivedHasMore.asStateFlow()
    private val _archivedLoading = MutableStateFlow(false)
    val archivedLoading: StateFlow<Boolean> = _archivedLoading.asStateFlow()

    // ── 备份导出 / 分库概览（V2 新增入口）──
    private val _backupState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val backupState: StateFlow<BackupUiState> = _backupState.asStateFlow()

    private val _shards = MutableStateFlow<List<NotionShard>>(emptyList())
    val shards: StateFlow<List<NotionShard>> = _shards.asStateFlow()

    /** 分库概览里每个已建库子库的 Notion 端活跃页面数（点「刷新」时实时联网统计） */
    private val _notionCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val notionCounts: StateFlow<Map<String, Int>> = _notionCounts.asStateFlow()
    /** 是否正在联网统计 Notion 端篇数（控制 UI 进度圈） */
    private val _notionCounting = MutableStateFlow(false)
    val notionCounting: StateFlow<Boolean> = _notionCounting.asStateFlow()

    /** 相似文章聚类状态（手动「全量重聚类」用） */
    private val _clusterState = MutableStateFlow<ClusterUiState>(ClusterUiState.Idle)
    val clusterState: StateFlow<ClusterUiState> = _clusterState.asStateFlow()

    init {
        // 冷启动：本地有令牌就直接验一下是否还有效
        if (repo.isLoggedIn) refreshUserInfo()
        // 注册网络变化监听：联网恢复时若处于离线模式，自动重新校验登录态恢复在线
        observeConnectivity()
    }

    /** 缓存账号信息到本地（明文 key-value，非敏感；避免引入序列化依赖） */
    private fun saveCachedUser(user: UserInfo) {
        userPrefs.edit().apply {
            putString("name", user.name)
            putString("email", user.email)
            putLong("end_at", user.endAt)
            putBoolean("expired", user.expired)
        }
    }

    /** 读取缓存的账号信息；无缓存返回 null */
    private fun loadCachedUser(): UserInfo? {
        if (!userPrefs.contains("name") && !userPrefs.contains("email")) return null
        return UserInfo(
            name = userPrefs.getString("name", "") ?: "",
            email = userPrefs.getString("email", "") ?: "",
            endAt = userPrefs.getLong("end_at", 0L),
            expired = userPrefs.getBoolean("expired", false),
        )
    }

    /** 网络变化回调：联网恢复且当前处于离线模式时自动恢复在线。仅注册一次 */
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null

    private fun observeConnectivity() {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // 仅在当前处于离线模式时才尝试恢复在线，避免对正常在线态反复打扰
                if (_isOffline.value) {
                    Log.i("AuthViewModel", "网络恢复，尝试从离线模式恢复在线")
                    refreshUserInfo()
                }
            }
        }
        connectivityCallback = cb
        runCatching { cm.registerNetworkCallback(request, cb) }
    }

    /** 步骤 1+3：申请设备码并开始轮询 */
    fun startLogin() {
        pollJob?.cancel()
        _state.value = AuthUiState.Loading

        pollJob = viewModelScope.launch {
            val code = try {
                repo.deviceFlow.requestDeviceCode()
            } catch (e: SupsubException) {
                _state.value = AuthUiState.Error(e.message)
                return@launch
            } catch (e: Throwable) {
                _state.value = AuthUiState.Error(e.message ?: "申请设备码失败")
                return@launch
            }

            _state.value = AuthUiState.AwaitingAuthorization(code, code.expiresIn)

            val result = repo.deviceFlow.awaitAuthorization(
                deviceCode = code.deviceCode,
                intervalSeconds = code.interval,
                expiresInSeconds = code.expiresIn,
                onTick = { remaining ->
                    val cur = _state.value
                    if (cur is AuthUiState.AwaitingAuthorization) {
                        _state.value = cur.copy(remainingSeconds = remaining)
                    }
                },
            )

            when (result) {
                is DeviceFlowAuth.Result.Authorized -> refreshUserInfo()
                is DeviceFlowAuth.Result.Expired ->
                    _state.value = AuthUiState.Error("授权码已过期，请重新登录")
                is DeviceFlowAuth.Result.Failed ->
                    _state.value = AuthUiState.Error(result.message)
            }
        }
    }

    fun cancelLogin() {
        pollJob?.cancel()
        pollJob = null
        _state.value = AuthUiState.LoggedOut
    }

    fun refreshUserInfo() {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading
            try {
                // 在线成功：缓存账号信息、清离线标记，再加载订阅源/关注点（联网）
                val user = repo.getUserInfo()
                saveCachedUser(user)
                _isOffline.value = false
                _state.value = AuthUiState.LoggedIn(user, offline = false)
                loadSources()
            } catch (e: SupsubException) {
                if (e.needsRelogin) {
                    _state.value = AuthUiState.LoggedOut
                } else {
                    // 网络/服务端抖动：本地有令牌就进离线模式（可阅读/标记本地内容），不要登出
                    enterOffline(e.message ?: "网络不可用")
                }
            } catch (e: Throwable) {
                enterOffline(e.message ?: "网络不可用")
            }
        }
    }

    /**
     * 进入离线模式：本地有令牌但服务端不可达时，不强制登出，
     * 让用户继续阅读 / 标记本地已归档内容；联网操作（拉取/云端标读/同步）由各自护栏拦截。
     * 只加载本地数据，不触网。
     */
    private fun enterOffline(reason: String) {
        if (!repo.isLoggedIn) {
            // 真没令牌：仍是报错/未登录，不走离线模式
            _state.value = AuthUiState.Error(reason)
            return
        }
        _isOffline.value = true
        val cached = loadCachedUser()
        _state.value = AuthUiState.LoggedIn(cached ?: UserInfo(), offline = true)
        Log.w("AuthViewModel", "进入离线模式（原因：$reason），仅开放本地阅读/标记")
        viewModelScope.launch {
            validateArchive()
            refreshArchivedArticles()
            loadReading()
        }
    }

    /** 拉取订阅源与关注点列表，供「内容来源」卡片展示未读数量 */
    private fun loadSources() {
        viewModelScope.launch {
            try {
                _subscriptions.value = repo.listSubscriptions()
                refreshApiCounts()
            } catch (e: Throwable) {
                Log.w("AuthViewModel", "加载订阅源失败", e)
            }
            try {
                _focuses.value = repo.listFocuses()
            } catch (e: Throwable) {
                Log.w("AuthViewModel", "加载关注点失败", e)
            }
            // 后台用真实拉取重新统计未读（覆盖 API 那个偏大的 unreadCount）
            refreshAccurateUnread()
            // 启动即校验本地归档并刷新计数（需求 2）
            validateArchive()
            // 进入主界面即加载「已拉取文章概况」第一页（常驻展示本地归档）
            refreshArchivedArticles()
        }
    }

    /** 用 API 上报的 unreadCount 立即给出兜底数值（无需额外网络） */
    private fun refreshApiCounts() {
        val subs = _subscriptions.value
        _unreadByType.value = subs.groupingBy { it.sourceType }
            .fold(0) { acc, s -> acc + s.unreadCount }
        _subscriptionCountByType.value = subs.groupingBy { it.sourceType }.eachCount()
    }

    /**
     * 真实未读统计：对每种启用的来源真的去拉 type=unread 并计数，同时与本地已存在内容去重。
     * API 列表里的 unreadCount 字段容易偏大/失准，逐源实际拉取得到的才是真值；
     * 再扣掉已经导出到本地的，得到真正「待拉取（新增）」的数量。
     */
    private fun refreshAccurateUnread() {
        // 后来者取消前一个：拉取完成、勾选变化、手动测试可能连着触发，
        // 若并发跑完顺序不定，慢的那个会把新数字覆盖回旧值。
        unreadCountJob?.cancel()
        val job = viewModelScope.launch {
            _countsRefreshing.value = true
            try {
                val enabled = exportSettings.enabledSourceTypesFlow.first()
                val includeFocus = exportSettings.includeFocusFlow.first()
                // V2：本地已存在内容直接从 SQLite 一次性取全量 id 快照（替代 V1 的 SAF 目录枚举）
                val localExisting = withContext(Dispatchers.IO) { store.allContentIds() }
                val result = repo.countUnreadByType(enabled, includeFocus, localExisting)
                _unreadByType.value = result.perType
                _newUnreadByType.value = result.perTypeNew
                _subscriptionCountByType.value = result.subscriptionCountByType
                // 关注点精确云端未读：用于「内容来源」卡片红框区域的「云端未读 X 篇」
                _focusCloudUnread.value = result.focusUnread
                lastUnreadResult = result
                Log.i(
                    "AuthViewModel",
                    "精确未读统计完成：未读=${result.perType}，待拉取=${result.perTypeNew}，订阅源数=${result.subscriptionCountByType}",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w("AuthViewModel", "精确未读统计失败，沿用上一次的值", e)
            }
        }
        unreadCountJob = job
        // 只有「自己仍是最新任务」时才收起进度：被后来者取消的旧任务不该清掉新任务的进度标志
        job.invokeOnCompletion { if (unreadCountJob === job) _countsRefreshing.value = false }
    }

    /**
     * 归档内容变化后刷新「待拉取 / 未读 / 源数」。拉取完成、标记已读之后都要调。
     *
     * 分两段是因为精确统计要逐源打网络（几十个订阅源就是几十个请求），
     * 拉完立刻等它跑完，用户会盯着旧数字发呆好几秒：
     *  1. 本地即时重算：用上一次的未读 id 快照减去本地已存在，纯集合运算，数字立刻掉下来；
     *  2. 服务端精确对齐：兜住拉取期间新发布的内容、以及标记已读带来的未读变化。
     *
     * @param localFirst 是否先做本地即时重算。标记已读场景下服务端未读会变，
     *                   本地快照算不出来，直接走精确统计即可。
     */
    private fun refreshCountsAfterArchiveChange(localFirst: Boolean = true) {
        viewModelScope.launch {
            _countsRefreshing.value = true
            if (localFirst) {
                runCatching { recomputeNewUnreadLocally() }
                    .onFailure { Log.w("AuthViewModel", "本地即时重算「待拉取」失败，等精确统计兜底", it) }
            }
            refreshAccurateUnread()
        }
    }

    /**
     * 用「上一次未读快照 - 本地已存在」在本地重算「待拉取」，0 网络请求。
     *
     * 只改「待拉取」不改「未读」：拉取本身不会让服务端的未读状态发生变化
     * （标记已读是另一个显式操作），此时把未读数一起改掉反而是错的。
     */
    private suspend fun recomputeNewUnreadLocally() {
        val snapshot = lastUnreadResult ?: return
        if (snapshot.unreadIdsByType.isEmpty()) return
        // V2：本地已存在内容直接从 SQLite 一次性取全量 id 快照
        val localExisting = withContext(Dispatchers.IO) { store.allContentIds() }
        val newByType = snapshot.unreadIdsByType.mapValues { (_, ids) -> ids.count { it !in localExisting } }
        _newUnreadByType.value = newByType
        // 快照同步更新，否则下次拉取的进度预估还是按旧的「待拉取」算
        lastUnreadResult = snapshot.copy(
            perTypeNew = newByType,
            focusUnreadNew = snapshot.focusUnreadIds.count { it !in localExisting },
        )
        Log.i("AuthViewModel", "本地即时重算「待拉取」：$newByType（本地已存在 ${localExisting.size} 篇）")
    }

    /**
     * 用 [PullProgress.Done] 直接回传的未读集合算计数（优化路径）。
     *
     * 拉取时 PullEngine 已逐篇拿到未读 contentId，按类型汇总成集合带回；这里只要拿这份集合
     * 与「本地磁盘已存在」做一次差集，就能给出最终「待拉取 / 未读 / 源数」，**完全不打服务端**，
     * 省掉拉完再跑一轮 countUnreadByType 的网络重查。
     *
     * 仅当 [PullProgress.Done.countProvided] 为 true（纯 UNREAD_ONLY 主路径）才走此路；
     * 自定义 / ALL 等场景该字段为 false，回退到 [refreshCountsAfterArchiveChange] 走服务端精确统计。
     */
    private fun applyCountsFromPull(done: PullProgress.Done) {
        if (!done.countProvided) {
            refreshCountsAfterArchiveChange()
            return
        }
        // 取消可能仍在进行的历史精确统计任务：它的结果会以旧口径覆盖我们刚算好的值
        unreadCountJob?.cancel()
        viewModelScope.launch {
            // V2：本地已存在内容直接从 SQLite 一次性取全量 id 快照
            val localExisting = withContext(Dispatchers.IO) { store.allContentIds() }
            // 覆盖所有启用类型，缺未读的类型补 0，与老路径 perType 口径一致，避免卡片缺行
            val perType = done.subCountByType.keys.associateWith { done.unreadIdsByType[it]?.size ?: 0 }
            val perTypeNew = done.subCountByType.keys.associateWith { (done.unreadIdsByType[it] ?: emptySet()).count { id -> id !in localExisting } }
            val focusUnread = done.focusUnreadIds.size
            val focusUnreadNew = done.focusUnreadIds.count { it !in localExisting }
            _unreadByType.value = perType
            _newUnreadByType.value = perTypeNew
            _subscriptionCountByType.value = done.subCountByType
            // 关注点精确云端未读：拉取直接回传的集合规模，无需再打服务端
            _focusCloudUnread.value = focusUnread
            // 同步快照，供后续本地即时重算 / 进度预估使用
            lastUnreadResult = SupsubRepository.UnreadCountResult(
                perType = perType,
                perTypeNew = perTypeNew,
                subscriptionCountByType = done.subCountByType,
                focusUnread = focusUnread,
                focusUnreadNew = focusUnreadNew,
                unreadIdsByType = done.unreadIdsByType,
                focusUnreadIds = done.focusUnreadIds,
            )
            // 本地即时算完，没有后台网络统计，收起「统计中…」
            _countsRefreshing.value = false
            Log.i("AuthViewModel", "拉取直接回传计数：未读=$perType，待拉取=$perTypeNew，源数=${done.subCountByType}")
        }
    }

    fun logout() {
        pollJob?.cancel()
        pullJob?.cancel()
        // 统计任务会继续打带鉴权的接口，退登后必须停掉，否则会拿已失效的令牌反复 401
        unreadCountJob?.cancel()
        unreadCountJob = null
        _countsRefreshing.value = false
        repo.logout()
        _state.value = AuthUiState.LoggedOut
    }

    /**
     * 用户从 SAF 选择器返回的 URI，持久化保存。
     * V2 起该目录**仅用于「导出备份 .db」**，正文已写入应用私有目录的 SQLite 单库，不再触发拉取。
     */
    fun onExportDirSelected(uri: Uri) {
        viewModelScope.launch {
            exportSettings.setExportDir(uri)
            // V2：目录仅作备份出口，不再像 V1 那样触发一次拉取
        }
    }

    /**
     * 开始拉取。V2 正文落库在应用私有目录，不再依赖用户选目录，因此直接拉。
     *
     * 频率保护（与 HTTP 层账号级限速互为补充）：
     *  - 已在拉取中 → 忽略重复点击，避免「取消+重开」把请求再次打爆；
     *  - 距上次拉完不足冷却期 → 直接提示，避免短时间内反复拉取触发服务端限流。
     * 真正的请求节奏由 [com.peide.supsub.api.RateLimitInterceptor] 在出口卡住，这里只挡住 UI 层的连点。
     */
    fun requestPull() {
        if (_isOffline.value) {
            _pullState.value = PullUiState.Error("离线模式不可用，联网后重试")
            Log.i("AuthViewModel", "离线模式，忽略拉取请求")
            return
        }
        if (_pullState.value is PullUiState.Running) {
            Log.i("AuthViewModel", "拉取进行中，忽略重复点击")
            return
        }
        if (lastPullEndedAt != 0L) {
            val since = System.currentTimeMillis() - lastPullEndedAt
            if (since < MIN_PULL_INTERVAL_MS) {
                val waitSec = (MIN_PULL_INTERVAL_MS - since) / 1000 + 1
                _pullState.value = PullUiState.Error("拉取过于频繁，请 ${waitSec} 秒后再试")
                Log.i("AuthViewModel", "拉取冷却中（约剩 ${waitSec}s），忽略本次请求")
                return
            }
        }
        pullJob?.cancel()
        viewModelScope.launch { startPull() }
    }

    /** 重新选择备份（导出）目录（用于修正路径/换位置），不清除登录态 */
    fun changeExportDir(onRequestDir: (Uri?) -> Unit) {
        onRequestDir(null)
    }

    private fun startPull() {
        pullJob = viewModelScope.launch {
            _pullState.value = PullUiState.Running(
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
            // 清掉上一轮「标记已读」两段后台进度提示
            _focusMarkState.value = FocusMarkUiState.Idle
            _sourceMarkState.value = SourceMarkUiState.Idle
            val enabledSourceTypes = exportSettings.enabledSourceTypesFlow.first()
            val includeFocus = exportSettings.includeFocusFlow.first()
            val strategy = _pullStrategy.value
            val subFilter = strategy?.sub
            val focusFilter = strategy?.focus
            // mode 恒为 UNREAD_ONLY：是否「只拉未读」完全由 subFilter/focusFilter 各自的 unreadOnly 驱动，
            // 不再用 CUSTOM 模式去反推默认（否则只设关注点过滤会让订阅源误拉全量历史）。
            val mode = PullMode.UNREAD_ONLY
            // 用「去重后的待拉取」数量作为进度估算（已扣除本地已存在的内容）
            val estimate = (lastUnreadResult?.perTypeNew?.values?.sum() ?: 0) +
                if (includeFocus) (lastUnreadResult?.focusUnreadNew ?: 0) else 0
            Log.i("AuthViewModel", "开始拉取：启用来源=$enabledSourceTypes，包含关注点=$includeFocus，订阅源策略=$subFilter，关注点策略=$focusFilter，预计待拉取=$estimate")
            pullEngine.pull(
                mode = mode,
                enabledSourceTypes = enabledSourceTypes,
                includeFocus = includeFocus,
                subFilter = subFilter,
                focusFilter = focusFilter,
                estimatedTotal = estimate,
            ).collect { progress ->
                _pullState.value = when (progress) {
                    is PullProgress.Running -> PullUiState.Running(
                        kind = progress.kind,
                        currentSource = progress.currentSource,
                        subIndex = progress.subIndex,
                        subTotal = progress.subTotal,
                        focusIndex = progress.focusIndex,
                        focusTotal = progress.focusTotal,
                        fetchedArticles = progress.fetchedArticles,
                        totalArticlesEstimate = progress.totalArticlesEstimate,
                        currentTitle = progress.currentTitle,
                        newArticles = progress.newArticles,
                    )
                    is PullProgress.Done -> {
                        _lastPulledSources.value = progress.pulledSources
                        // 记录本轮涉及到的关注点 id，供「标记已读」逐个整点标读
                        _lastPulledFocusIds.value = progress.pulledFocusIds
                        // 记录本轮「有未读」的订阅源/关注点，供「标记已读」范围优化（0 未读的源/关注点跳过云端标读）
                        _lastPulledSubSourcesWithUnread.value = progress.subSourcesWithUnread.toSet()
                        _lastPulledFocusIdsWithUnread.value = progress.focusIdsWithUnread.toSet()
                        // 记录本轮新增的 contentId，供「已拉取文章概况」标记「新增」
                        _lastPulledContentIds.value = progress.pulledArticles.map { it.contentId }.toSet()
                        // 拉取完成后立即校验本地归档并刷新计数（需求 2）
                        validateArchive()
                        // 刷新常驻的「已拉取文章概况」列表，让刚拉到的文章立即出现在概况里
                        refreshArchivedArticles()
                        PullUiState.Done(
                            progress.subscriptions,
                            progress.articles,
                            progress.newOrUpdated,
                            progress.unchanged,
                            progress.subRaw,
                            progress.subNew,
                            progress.focusRaw,
                            progress.focusNew,
                            progress.exportDir,
                            progress.pulledSources,
                            progress.pulledArticles,
                            progress.indexSource,
                            pulledFocusIds = progress.pulledFocusIds,
                            subSourcesWithUnread = progress.subSourcesWithUnread,
                            focusIdsWithUnread = progress.focusIdsWithUnread,
                            focusSummaries = progress.focusSummaries,
                            dedupReport = progress.dedupReport,
                            warnings = progress.warnings,
                        )
                    }
                    is PullProgress.Error -> PullUiState.Error(progress.message)
                    else -> PullUiState.Idle
                }
            }
            // 流结束（完成/失败）：记录时间戳，供下次拉取的冷却判断
            lastPullEndedAt = System.currentTimeMillis()
            // 拉取流整体结束后 DB 已落盘，再补一次校验与列表刷新：
            // Done 回调里的 validateArchive()/refreshArchivedArticles() 可能在文章尚未提交时就
            // 读到了旧总数，导致「拉取与归档」计数与「文章概况」卡在拉取前的快照（如 579），
            // 非得重启才纠正。这里在流彻底结束（写入已提交）后重算，保证计数反映最终落盘值。
            validateArchive()
            refreshArchivedArticles()
            // 拉取 + 云端标记已读后，服务端关注点未读已变化，重新拉取 focuses
            // 让设置页「云端未读」跟进真实状态（否则仍是登录时的陈旧偏大快照）
            try {
                _focuses.value = repo.listFocuses()
            } catch (e: Throwable) {
                Log.w("AuthViewModel", "拉取后刷新关注点失败", e)
            }
        }
    }

    /**
     * 「标记已读」按钮：对订阅源整源标读 + 关注点整点标读。
     *
     * 与拉取解耦、独立可用：
     * - 若本会话拉取过，则只标读本次拉取涉及的订阅源/关注点；
     * - 若本会话尚未拉取（或 App 重启过），自动退化为「全部已启用」订阅源 + 关注点，
     *   直接调用云端整源/整点标读接口（订阅源整源、关注点整点均清掉该来源/关注点全部云端未读）。
     * 全程 best-effort、令牌桶限速（[SupsubRepository] 已封装），不阻塞 UI。
     */
    fun startMarkRead() {
        if (_isOffline.value) {
            // 云端整源/整点标读是联网操作，离线时禁用；单篇本地标读（setUserRead）不受影响
            _sourceMarkState.value = SourceMarkUiState.Error("离线模式不可用，联网后重试")
            Log.i("AuthViewModel", "离线模式，忽略云端标记已读")
            return
        }
        if (markReadJob?.isActive == true) return
        markReadJob = viewModelScope.launch {
            // 确定标读范围：本会话拉取过的优先；否则退化为「全部已启用」订阅源 + 关注点
            val sources = if (_lastPulledSources.value.isNotEmpty()) {
                _lastPulledSources.value
            } else {
                val enabledTypes = withContext(Dispatchers.IO) {
                    runCatching { exportSettings.enabledSourceTypesFlow.first() }
                        .getOrElse { ExportSettings.DEFAULT_ENABLED_SOURCE_TYPES }
                }
                runCatching { repo.listSubscriptions() }.getOrElse { emptyList() }
                    .filter { it.sourceType in enabledTypes }
                    .map { PulledSource(it.sourceType, it.sourceId) }
            }
            val focusIds = if (_lastPulledFocusIds.value.isNotEmpty()) {
                _lastPulledFocusIds.value
            } else {
                val includeFocus = withContext(Dispatchers.IO) {
                    runCatching { exportSettings.includeFocusFlow.first() }.getOrElse { false }
                }
                if (includeFocus) {
                    runCatching { repo.listFocuses() }.getOrElse { emptyList() }.map { it.id }
                } else {
                    emptyList()
                }
            }
            // 范围优化：本会话拉取过的前提下，只标读「本轮实际拉到未读」的源/关注点；
            // 0 未读的源/关注点标读是空操作却占一次云端调用，且关注点有服务端限流，跳过可提速。
            // 退化路径（未拉取直接标读）没有本轮未读信息，保持全标，行为不变。
            val markSources = if (_lastPulledSources.value.isNotEmpty()) {
                sources.filter { it in _lastPulledSubSourcesWithUnread.value }
            } else sources
            val markFocusIds = if (_lastPulledFocusIds.value.isNotEmpty()) {
                focusIds.filter { it in _lastPulledFocusIdsWithUnread.value }
            } else focusIds
            if (sources.isEmpty() && focusIds.isEmpty()) {
                // 没有可标内容（例如所有来源都关了），给出明确反馈，避免静默无反应
                _sourceMarkState.value =
                    SourceMarkUiState.Error("没有可标记的订阅源或关注点（来源可能都未启用）")
                return@launch
            }
            _sourceMarkState.value = SourceMarkUiState.Running(0, 0, markSources.size)
            _focusMarkState.value = FocusMarkUiState.Running(0, 0, markFocusIds.size)
            // 1) 订阅源整源标读（仅本轮有未读的源）
            var sOk = 0
            var sFail = 0
            markSources.forEach { src ->
                runCatching { repo.markSourceAsRead(src.sourceType, src.sourceId) }
                    .onSuccess { sOk++ }
                    .onFailure { sFail++; Log.w("AuthViewModel", "订阅源整源标读失败", it) }
                _sourceMarkState.value = SourceMarkUiState.Running(sOk, sFail, markSources.size)
            }
            _sourceMarkState.value = SourceMarkUiState.Done(sOk, sFail, markSources.size)
            // 2) 关注点整点标读（仅本轮有未读的关注点，空 body）
            var fOk = 0
            var fFail = 0
            markFocusIds.forEach { fid ->
                runCatching { repo.markFocusAsRead(fid, FocusMarkAsReadRequest()) }
                    .onSuccess { fOk++ }
                    .onFailure { fFail++; Log.w("AuthViewModel", "关注点整点标读失败", it) }
                _focusMarkState.value = FocusMarkUiState.Running(fOk, fFail, markFocusIds.size)
            }
            _focusMarkState.value = FocusMarkUiState.Done(fOk, fFail, markFocusIds.size)
            // 3) 本地：本轮已拉内容置 is_read=1（与云端口径对齐，便于后续排查）
            runCatching { withContext(Dispatchers.IO) { store.markRead(_lastPulledContentIds.value) } }
                .onFailure { Log.w("AuthViewModel", "本地标记已读失败", it) }
            // 4) 标记已读完成：重新统计云端未读，刷新「内容来源」卡片红框区域的关注点 / 订阅源类型数字
            refreshAccurateUnread()
        }
    }

    /** 开启/关闭某个订阅源类型（MP / WEBSITE / X） */
    fun setSourceTypeEnabled(type: String, enabled: Boolean) {
        viewModelScope.launch {
            exportSettings.setSourceTypeEnabled(type, enabled)
            // 统计只覆盖已勾选的类型，勾选变化后必须重算，
            // 否则新勾上的类型会一直显示 0，得手动点「测试拉取订阅源」才出数
            refreshAccurateUnread()
        }
    }

    /** 开启/关闭关注点内容拉取 */
    fun setIncludeFocus(enabled: Boolean) {
        viewModelScope.launch {
            exportSettings.setIncludeFocus(enabled)
            refreshAccurateUnread()
        }
    }

    /** 取消正在进行的拉取 */
    fun cancelPull() {
        pullJob?.cancel()
        pullJob = null
        _pullState.value = PullUiState.Idle
        Log.i("AuthViewModel", "用户取消了拉取")
    }

    // ─── 已拉取文章概况（常驻展示本地归档，分页）───

    /** 重新加载「已拉取文章概况」第一页（拉取/同步完成后、进入页面时调用） */
    fun refreshArchivedArticles() {
        viewModelScope.launch { loadArchivedPage(reset = true) }
    }

    /** 加载下一页（「加载更多」） */
    fun loadMoreArchivedArticles() {
        if (_archivedLoading.value || !_archivedHasMore.value) return
        viewModelScope.launch { loadArchivedPage(reset = false) }
    }

    /**
     * 一次性加载全部本地归档概况，供「已拉取文章概况」弹窗按天筛选/跳转。
     * 本地归档数量通常在数千以内，全量加载比分页 + 按日期定位的代码更简单，
     * 也能避免用户只看到最近一页的日期、误以为更早的文章丢失。
     */
    fun loadAllArchivedArticles() {
        if (_archivedLoading.value) return
        viewModelScope.launch {
            _archivedLoading.value = true
            try {
                val all = withContext(Dispatchers.IO) { store.listAllSummaries() }
                _archivedArticles.value = all
                _archivedHasMore.value = false
                runCatching { _shards.value = withContext(Dispatchers.IO) { store.listShards() } }
                Log.i("AuthViewModel", "已全量加载归档概况：${all.size} 篇")
            } catch (e: Throwable) {
                Log.w("AuthViewModel", "全量加载归档概况失败", e)
            } finally {
                _archivedLoading.value = false
            }
        }
    }

    private suspend fun loadArchivedPage(reset: Boolean) {
        _archivedLoading.value = true
        try {
            // V2：直接从 SQLite 分页读概况，不再做 SAF 目录枚举
            val offset = if (reset) 0 else _archivedArticles.value.size
            val page = withContext(Dispatchers.IO) { store.listSummaries(offset, ARCHIVE_PAGE_SIZE) }
            _archivedArticles.value = if (reset) page else (_archivedArticles.value + page)
            _archivedHasMore.value = page.size == ARCHIVE_PAGE_SIZE
            // 同步刷新按天分片真实条数，供概况按天分组时显示准确篇数
            // （否则标题只会统计「已加载分页」里的条数，与实际不符）
            runCatching { _shards.value = withContext(Dispatchers.IO) { store.listShards() } }
        } catch (e: Throwable) {
            Log.w("AuthViewModel", "加载归档概况失败", e)
        } finally {
            _archivedLoading.value = false
        }
    }

    // ─── 自定义拉取 / 标记已读 / 归档校验（需求 1 / 2 / 3 / 5）───

    /** 设置拉取策略（订阅源 + 关注点各自独立的过滤条件）；传 null 恢复默认「两者都只拉未读」 */
    fun setPullStrategy(strategy: PullStrategy?) {
        _pullStrategy.value = strategy
    }

    /**
     * 校验本地归档并刷新计数（需求 2）。
     * V2 起数据在应用私有目录的 SQLite 单库，无「目录选择」前置条件，直接聚合全表。
     */
    fun validateArchive() {
        viewModelScope.launch {
            _archiveValidating.value = true
            val start = System.currentTimeMillis()
            try {
                // V2：SQLite 单库，无目录依赖；stats() 直接聚合全表
                val stats = runCatching { store.stats() }.getOrNull()
                _archiveStats.value = stats
                _archiveHint.value = buildArchiveHint(stats)
            } finally {
                // 热缓存下校验是纯内存操作（亚毫秒级），若立刻收起提示肉眼不可见。
                // 强制让进度提示至少可见 MIN_VALIDATE_VISIBLE_MS 毫秒，保证用户能看到反馈。
                val elapsed = System.currentTimeMillis() - start
                val minVisible = MIN_VALIDATE_VISIBLE_MS
                if (elapsed < minVisible) {
                    delay(minVisible - elapsed)
                }
                _archiveValidating.value = false
            }
        }
    }

    private fun buildArchiveHint(stats: ArchiveStats?): String? {
        if (stats == null) return null
        val parts = mutableListOf<String>()
        parts.add("本地共 ${stats.total} 篇")
        parts.add("未读 ${stats.unread}")
        parts.add("未同步 ${stats.pendingSync}")
        if (stats.syncFailed > 0) parts.add("同步失败 ${stats.syncFailed}")
        val origin = stats.byOrigin.entries.joinToString(" / ") { (k, v) ->
            val label = com.peide.supsub.data.OriginCategory.fromKey(k).label
            "$label $v"
        }
        if (origin.isNotBlank()) parts.add("（来源分类：$origin）")
        if (stats.inconsistent > 0) parts.add("⚠️ ${stats.inconsistent} 条缺内容指纹或分片键，建议重新拉取")
        return parts.joinToString("，")
    }

    // ─── 一键备份 / 分库概览（V2 新增）───

    /** 一键备份：把应用私有目录的 SQLite 库复制到用户选择的 SAF 备份目录（落一份 .db 副本） */
    fun exportBackup() {
        viewModelScope.launch {
            val treeUri = exportSettings.exportDirUri()
            if (treeUri == null) {
                _backupState.value = BackupUiState.Error("未设置备份目录，请先点「设置备份目录」")
                return@launch
            }
            _backupState.value = BackupUiState.Running
            val name = runCatching { withContext(Dispatchers.IO) { store.backupTo(treeUri) } }.getOrNull()
            _backupState.value = if (name != null) BackupUiState.Done(name) else BackupUiState.Error("备份失败，请查看日志")
        }
    }

    /** 加载按天分库概览（本地：Notion 侧子库登记 + 各分片本地条目数）。打开弹窗时调用，不触网 */
    fun loadShardOverview() {
        viewModelScope.launch {
            _shards.value = runCatching { withContext(Dispatchers.IO) { store.listShards() } }.getOrDefault(emptyList())
        }
    }

    /**
     * 「刷新」触发：先本地刷新分库列表，再联网逐个统计各子库 Notion 端活跃页面数。
     * 已在进行中则忽略（避免重复并发统计）。
     */
    fun refreshShardOverview() {
        if (_notionCounting.value) return
        viewModelScope.launch {
            _shards.value = runCatching { withContext(Dispatchers.IO) { store.listShards() } }.getOrDefault(emptyList())
            loadNotionCounts()
        }
    }

    /**
     * 联网统计每个已建库子库（databaseId 非空）的 Notion 端活跃页面数。
     * 顺序请求 + 350ms 限速，单个库失败兜底为 0；结果随统计进度增量更新到 [_notionCounts]。
     */
    private suspend fun loadNotionCounts() {
        val token = notionConfig.value.first
        if (token.isBlank()) { _notionCounts.value = emptyMap(); return }
        val api = buildNotionApi(token)
        val built = _shards.value.filter { it.databaseId.isNotBlank() }
        _notionCounting.value = true
        val map = LinkedHashMap<String, Int>()
        try {
            for (s in built) {
                val n = runCatching { queryDatabaseCount(api, s.databaseId) }.getOrDefault(0)
                map[s.shardKey] = n
                _notionCounts.value = map.toMap()
            }
        } finally {
            _notionCounting.value = false
        }
    }

    // ─── 相似文章聚类（本地）───

    /**
     * 手动全量重聚类：重算整个归档的相似文章簇，并把「簇分配发生变化」的文章置回待同步，
     * 让 Notion 在下次同步时补写「聚类主题」属性。
     */
    fun recluster() {
        if (_clusterState.value is ClusterUiState.Running) return
        viewModelScope.launch {
            _clusterState.value = ClusterUiState.Running
            try {
                val result = withContext(Dispatchers.IO) { ClusterEngine(store).reclusterAll() }
                _clusterState.value = ClusterUiState.Done(result.clusters, result.reassigned)
                // 簇字段变了，刷新归档统计与概况列表
                validateArchive()
                refreshArchivedArticles()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _clusterState.value = ClusterUiState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** 重置聚类交互状态（UI 关闭结果提示时调用） */
    fun resetClusterState() {
        _clusterState.value = ClusterUiState.Idle
    }

    // ─── 阅读页（本地预览 / 标记 实际已读 / 高价值）───

    private val _readingItems = MutableStateFlow<List<ReadingItem>>(emptyList())
    val readingItems: StateFlow<List<ReadingItem>> = _readingItems.asStateFlow()

    private val _readingFilter = MutableStateFlow(ReadingFilter.BY_TIME)
    val readingFilter: StateFlow<ReadingFilter> = _readingFilter.asStateFlow()

    private val _readingTimeRange = MutableStateFlow(ReadingTimeRange.ALL)
    val readingTimeRange: StateFlow<ReadingTimeRange> = _readingTimeRange.asStateFlow()

    private val _readingSourceFilter = MutableStateFlow<String?>(null)
    val readingSourceFilter: StateFlow<String?> = _readingSourceFilter.asStateFlow()

    private val _readingSources = MutableStateFlow<List<String>>(emptyList())
    val readingSources: StateFlow<List<String>> = _readingSources.asStateFlow()

    private val _readingStatus = MutableStateFlow(ReadingStatus.ALL)
    val readingStatus: StateFlow<ReadingStatus> = _readingStatus.asStateFlow()

    private val _readingSyncFilter = MutableStateFlow(ReadingSyncFilter.ALL)
    val readingSyncFilter: StateFlow<ReadingSyncFilter> = _readingSyncFilter.asStateFlow()

    private val _readingSearch = MutableStateFlow("")
    val readingSearch: StateFlow<String> = _readingSearch.asStateFlow()

    /**
     * 阅读页「来源分类」筛选：null=全部；"SUBSCRIPTION"=订阅源；"FOCUS"=关注点；"WEBSET"=网页集。
     * 与「公众号」下拉（按 source_name 精确过滤）是不同维度：这里是「从哪个通道拉进来」的粗筛。
     */
    private val _readingCategory = MutableStateFlow<String?>(null)
    val readingCategory: StateFlow<String?> = _readingCategory.asStateFlow()

    /**
     * 阅读页「关注点」筛选：null=全部关注点；非 null 时仅返回该关注点的文章（仅对 FOCUS 通道有意义）。
     * 与「来源分类=FOCUS」互补：来源分类粗筛通道，关注点细筛具体关注点。
     */
    private val _readingFocusFilter = MutableStateFlow<Long?>(null)
    val readingFocusFilter: StateFlow<Long?> = _readingFocusFilter.asStateFlow()

    /** 阅读页「关注点」下拉选项：本地确实存在的关注点（id + 标题） */
    private val _readingFocuses = MutableStateFlow<List<ReadingFocus>>(emptyList())
    val readingFocuses: StateFlow<List<ReadingFocus>> = _readingFocuses.asStateFlow()

    private val _readingLoading = MutableStateFlow(false)
    val readingLoading: StateFlow<Boolean> = _readingLoading.asStateFlow()

    /** 启动时恢复上次选择的筛选条件（放在阅读状态流声明之后，确保属性已初始化） */
    init {
        _readingTimeRange.value = ReadingTimeRange.fromKey(
            readingPrefs.getString(ReadingPrefKeys.TIME_RANGE, "") ?: "",
        ) ?: ReadingTimeRange.ALL
        _readingSourceFilter.value = readingPrefs.getString(ReadingPrefKeys.SOURCE_FILTER, null)
        _readingStatus.value = ReadingStatus.fromKey(
            readingPrefs.getString(ReadingPrefKeys.STATUS, "") ?: "",
        ) ?: ReadingStatus.ALL
        _readingSyncFilter.value = ReadingSyncFilter.fromKey(
            readingPrefs.getString(ReadingPrefKeys.SYNC_FILTER, "") ?: "",
        ) ?: ReadingSyncFilter.ALL
        _readingCategory.value = readingPrefs.getString(ReadingPrefKeys.CATEGORY, null)
        _readingFocusFilter.value = readingPrefs.getString(ReadingPrefKeys.FOCUS_FILTER, null)?.toLongOrNull()
    }

    /** 重新加载阅读页列表（按当前各维度筛选 + 搜索），同时刷新公众号/关注点筛选项列表 */
    fun loadReading() {
        viewModelScope.launch {
            _readingLoading.value = true
            try {
                // 关注点已独立到「来源」筛选器；若旧版 prefs 里还存着「[关注点]xxx」的公众号筛选，
                // 这里自动清空，避免来源下拉里出现无法选中/不存在的焦点项。
                if (_readingSourceFilter.value?.startsWith("[关注点]") == true) {
                    _readingSourceFilter.value = null
                    saveReadingPrefs()
                }
                val (items, sources, focuses) = withContext(Dispatchers.IO) {
                    Triple(
                        store.listForReading(
                            status = _readingStatus.value,
                            bySource = _readingFilter.value == ReadingFilter.BY_SOURCE,
                            timeRange = _readingTimeRange.value,
                            sourceFilter = _readingSourceFilter.value,
                            syncFilter = _readingSyncFilter.value,
                            search = _readingSearch.value,
                            categoryFilter = _readingCategory.value,
                            focusFilter = _readingFocusFilter.value,
                        ),
                        store.listReadingSources(),
                        store.listReadingFocuses(),
                    )
                }
                _readingItems.value = items
                _readingSources.value = sources
                _readingFocuses.value = focuses
            } finally {
                _readingLoading.value = false
            }
        }
    }

    /** 切换「来源分类」筛选（订阅源 / 关注点） */
    fun setReadingCategory(category: String?) {
        _readingCategory.value = category
        saveReadingPrefs()
        loadReading()
    }

    /** 切换「关注点」筛选（null=全部关注点；非 null=仅该关注点） */
    fun setReadingFocus(focusId: Long?) {
        _readingFocusFilter.value = focusId
        saveReadingPrefs()
        loadReading()
    }

    /**
     * 阅读页「来源」合并筛选：把「来源分类」(订阅源/关注点/网页集) 与「具体关注点」两个维度合并到一次操作里，
     * 同时设置 [category] 与 [focusId] 并只重载列表一次（避免分别调用触发两次刷新）。
     * 用于把原独立的「关注点」下拉合并进「来源」下拉。
     */
    fun setReadingSourceCategory(category: String?, focusId: Long?) {
        _readingCategory.value = category
        _readingFocusFilter.value = focusId
        saveReadingPrefs()
        loadReading()
    }

    /** 把当前筛选选择写入 SharedPreferences，实现「记忆上次选择」 */
    private fun saveReadingPrefs() {
        readingPrefs.edit().apply {
            putString(ReadingPrefKeys.TIME_RANGE, _readingTimeRange.value.name)
            putString(ReadingPrefKeys.SOURCE_FILTER, _readingSourceFilter.value)
            putString(ReadingPrefKeys.STATUS, _readingStatus.value.name)
            putString(ReadingPrefKeys.SYNC_FILTER, _readingSyncFilter.value.name)
            putString(ReadingPrefKeys.CATEGORY, _readingCategory.value)
            putString(ReadingPrefKeys.FOCUS_FILTER, _readingFocusFilter.value?.toString())
        }.apply()
    }

    fun setReadingFilter(filter: ReadingFilter) {
        _readingFilter.value = filter
        // 切换维度时重置二级筛选，避免来源/时间过滤交叉导致空结果
        _readingTimeRange.value = ReadingTimeRange.ALL
        _readingSourceFilter.value = null
        saveReadingPrefs()
        loadReading()
    }

    fun setReadingTimeRange(range: ReadingTimeRange) {
        _readingTimeRange.value = range
        saveReadingPrefs()
        loadReading()
    }

    fun setReadingSourceFilter(source: String?) {
        _readingSourceFilter.value = source
        saveReadingPrefs()
        loadReading()
    }

    fun setReadingStatus(status: ReadingStatus) {
        _readingStatus.value = status
        saveReadingPrefs()
        loadReading()
    }

    fun setReadingSyncFilter(sync: ReadingSyncFilter) {
        _readingSyncFilter.value = sync
        saveReadingPrefs()
        loadReading()
    }

    /** 搜索关键字：仅更新状态，真正的重载由防抖协程触发，避免逐字符重建列表 */
    fun setReadingSearch(query: String) {
        _readingSearch.value = query
        debounceSearchReload()
    }

    private var searchReloadJob: Job? = null
    private fun debounceSearchReload() {
        searchReloadJob?.cancel()
        searchReloadJob = viewModelScope.launch {
            delay(300)
            loadReading()
        }
    }

    /** 切换「实际已读」：写本地列；若该条已同步到 Notion，标为属性级重推以便下次同步 PATCH 该 checkbox */
    fun setUserRead(contentId: String, value: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                store.setUserRead(contentId, value)
                store.markPropResyncIfSynced(contentId)
            }
            _readingItems.value = _readingItems.value.map {
                if (it.contentId == contentId) it.copy(isUserRead = value) else it
            }
            // 归档统计里的「实际未读」要跟着变
            validateArchive()
        }
    }

    /** 切换「高价值」标记：写本地列；若该条已同步到 Notion，标为属性级重推以便下次同步 PATCH 该 checkbox */
    fun setHighValue(contentId: String, value: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                store.setHighValue(contentId, value)
                store.markPropResyncIfSynced(contentId)
            }
            _readingItems.value = _readingItems.value.map {
                if (it.contentId == contentId) it.copy(isHighValue = value) else it
            }
        }
    }

    // ─── 文章历史 / 评论（本地，评论随双向同步上行 Notion）───

    /** 读取某篇文章的历史流水 */
    suspend fun loadHistory(contentId: String): List<HistoryEntry> =
        withContext(Dispatchers.IO) { store.listHistory(contentId) }

    /** 读取某篇文章的本地评论 */
    suspend fun loadComments(contentId: String): List<CommentEntry> =
        withContext(Dispatchers.IO) { store.listComments(contentId) }

    /** 新增评论（LOCAL_NEW，随下次双向同步推送 Notion） */
    suspend fun addComment(contentId: String, body: String) {
        withContext(Dispatchers.IO) { store.addComment(contentId, body) }
    }

    /** 编辑评论正文（LOCAL_NEW 直接改；SYNCED 翻 EDIT_PENDING，下次同步删旧+重建） */
    suspend fun editComment(id: Long, body: String) {
        withContext(Dispatchers.IO) { store.editCommentLocal(id, body) }
    }

    /** 删除评论（LOCAL_NEW 仅本地；SYNCED 翻 DELETED_LOCAL，下次同步删云端） */
    suspend fun deleteComment(id: Long) {
        withContext(Dispatchers.IO) { store.deleteCommentLocal(id) }
    }

    // ─── Notion 同步 ───────────────────────────────────────

    /** 验证 Notion Token + 容器页 ID 是否可用（不写盘，仅探测） */
    fun verifyNotionConnection(token: String, parentPageId: String) {
        if (token.isBlank() || parentPageId.isBlank()) {
            _notionVerify.value = ConnectionCheck(false, "请先填写 Token 和容器页 ID")
            return
        }
        viewModelScope.launch {
            _notionVerify.value = checkNotionParentPage(token.trim(), parentPageId.trim())
        }
    }

    /**
     * 开始把本地归档同步到 Notion 的**按天分库**。
     * 会先把 Token / 容器页 ID 落盘（DataStore），再按 shardKey 路由写各子库。
     * 增量：sync 状态为 SYNCED 且 hash 未变则跳过；forceResync 时无视跳过。
     * @param bidirectional 双向同步：推送完成后把 Notion 端较新的改动回拉本地（LWW），默认开
     */
    fun startNotionSync(token: String, parentPageId: String, forceResync: Boolean, bidirectional: Boolean = true) {
        Log.i("SupSubSync", "startNotionSync 被调用 tokenLen=${token.length} parentPageIdLen=${parentPageId.length} bidirectional=$bidirectional")
        notionJob?.cancel()
        if (_isOffline.value) {
            Log.e("SupSubSync", "离线模式，拒绝 Notion 同步")
            _notionSyncState.value = SyncProgress.Error("离线模式不可用，请联网后重试")
            return
        }
        if (token.isBlank() || parentPageId.isBlank()) {
            Log.e("SupSubSync", "startNotionSync: token/parentPageId 为空")
            _notionSyncState.value = SyncProgress.Error("请先填写并保存 Notion Token / 容器页 ID")
            return
        }
        notionJob = viewModelScope.launch {
            exportSettings.setNotionConfig(token.trim(), parentPageId.trim())
            exportSettings.setBidirectionalSync(bidirectional)
            _notionSyncState.value = SyncProgress.Running("", 0, 0, 0, 0, 0)
            try {
                // V2：直接注入 ArchiveStore；同步目标由 NotionShardRouter 按容器页下的按天子库路由
                val engine = SupSubSyncEngine(
                    store = store,
                    api = buildNotionApi(token.trim()),
                    config = NotionConfig(token.trim(), parentPageId.trim()),
                )
                val result = withContext(Dispatchers.IO) {
                    engine.sync(
                        forceResync = forceResync,
                        onProgress = { progress -> _notionSyncState.value = progress },
                        bidirectional = bidirectional,
                    )
                }
                _notionSyncState.value = when (result) {
                    is SyncResult.Success -> SyncProgress.Done(
                        result.synced, result.skipped, 0, result.createdShards, result.pulled, result.conflicts,
                        result.pushOverview, result.pullOverview,
                    )
                    is SyncResult.PartialFailure -> SyncProgress.Done(
                        result.synced, 0, result.failed, result.createdShards, result.pulled, result.conflicts,
                        result.pushOverview, result.pullOverview,
                    )
                    is SyncResult.Error -> SyncProgress.Error(result.message)
                }
                // 同步完成后刷新本地归档统计（「待同步」数应下降，需求 2）
                validateArchive()
                // 同步状态已变化，刷新概况列表里的「是否已同步」标记
                refreshArchivedArticles()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SupSubSync", "startNotionSync 外层异常", e)
                _notionSyncState.value = SyncProgress.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** 设置双向同步开关（持久化到 DataStore；默认开启） */
    fun setBidirectionalSync(enabled: Boolean) {
        viewModelScope.launch { exportSettings.setBidirectionalSync(enabled) }
    }

    /** 取消正在进行的 Notion 同步 */
    fun cancelNotionSync() {
        notionJob?.cancel()
        notionJob = null
        _notionSyncState.value = SyncProgress.Idle
        Log.i("AuthViewModel", "用户取消了 Notion 同步")
    }

    /** M2 预检：只统计「勾选的来源」，确认拉取链路可用；同时用真实拉取刷新卡片上的未读数 */
    fun probeSubscriptions(onResult: (String) -> Unit) {
        // 与后台的精确统计写同一批计数，让后台任务先退场，避免它慢一步把探测结果覆盖回去
        unreadCountJob?.cancel()
        if (_isOffline.value) {
            onResult("离线模式不可用，请联网后重试")
            return
        }
        viewModelScope.launch {
            _probeRunning.value = true
            try {
                val enabled = exportSettings.enabledSourceTypesFlow.first()
                val includeFocus = exportSettings.includeFocusFlow.first()
                val subs = repo.listSubscriptions().filter { it.sourceType in enabled }
                val subCountByType = subs.groupingBy { it.sourceType }.eachCount()
                // 真实未读计数（逐源拉 type=unread 统计），并与本地已存在内容去重，同步刷新卡片
                // V2：本地已存在内容直接从 SQLite 一次性取全量 id 快照
                val localExisting = withContext(Dispatchers.IO) { store.allContentIds() }
                val accurate = repo.countUnreadByType(enabled, includeFocus, localExisting)
                _unreadByType.value = accurate.perType
                _newUnreadByType.value = accurate.perTypeNew
                _subscriptionCountByType.value = accurate.subscriptionCountByType
                // 关注点精确云端未读：测试拉取订阅源后同步刷新红框数字
                _focusCloudUnread.value = accurate.focusUnread
                lastUnreadResult = accurate
                val unread = accurate.perType.values.sum()
                val newUnread = accurate.perTypeNew.values.sum()
                val msg = if (subs.isEmpty())
                    "已勾选来源（${enabled.joinToString(" / ")}）下没有订阅源"
                else
                    "已勾选来源（${enabled.joinToString(" / ")}）：订阅源 ${subs.size} 个（MP ${subCountByType["MP"] ?: 0} / 网站 ${subCountByType["WEBSITE"] ?: 0} / X ${subCountByType["X"] ?: 0}），未读合计 $unread 篇，待拉取（去重后）$newUnread 篇"
                Log.i("AuthViewModel", "probe: $msg")
                onResult(msg)
            } catch (e: Throwable) {
                onResult("测试失败：${e.message}")
            } finally {
                _probeRunning.value = false
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        pullJob?.cancel()
        notionJob?.cancel()
        unreadCountJob?.cancel()
        connectivityCallback?.let { cb ->
            runCatching {
                (getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                    ?.unregisterNetworkCallback(cb)
            }
        }
        connectivityCallback = null
        super.onCleared()
    }
}
