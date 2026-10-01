package com.peide.supsub.notion.ui
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.State
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.peide.supsub.data.OriginCategory
import com.peide.supsub.data.ReadingFocus
import com.peide.supsub.data.ReadingItem
import com.peide.supsub.data.ReadingStatus
import com.peide.supsub.data.ReadingSyncFilter
import com.peide.supsub.data.ReadingTimeRange
import com.peide.supsub.data.HistoryEntry
import com.peide.supsub.data.CommentEntry
import com.peide.supsub.data.CommentSyncState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.peide.supsub.notion.vm.AuthViewModel

/**
 * 阅读分页：本地内容预览 + 标记（实际已读 / 高价值）+ 聚类堆叠展示。
 *
 * - 列表按 clusterId 分组：同簇文章折叠成「堆叠卡片」，展开后逐篇显示标题与单篇开关；
 *   无聚类的文章各自独立成卡。
 * - 顶部筛选：按时间 / 按公众号 + 「只看实际未读」（实际已读 = 独立的 is_user_read，与 supsub 的 is_read 无关）。
 * - 点开任意文章 → 底部抽屉详情（封面图 + 左下「查看原文」+ tags/keywords 底色框 + 摘要 + 立场摘要
 *   + AI 维度 + 聚类进度 + 底部操作栏：上一篇 / 下一篇 / 实际已读 checkbox / 高价值星标）。
 */

/** 列表里的一组：clusterId 为空表示单篇（独立卡） */
private data class ReadingGroup(
    val clusterId: String,
    val clusterLabel: String,
    val items: List<ReadingItem>,
)

/**
 * 阅读列表是否正在滚动：由 [ReadTab] 的 LazyListState 提供（一个稳定的 State<Boolean> 引用，
 * provider 节点不订阅它，避免列表滚动起止时重 compose 整棵子树）。
 * [MarqueeBadgeRow] 读取它，在列表滚动时暂停自动跑马灯（P0 优化）。
 * 详情抽屉（ReadingDetailSheet）不在 provider 范围内，回退默认值 false（不暂停）。
 */
private val LocalMarqueePaused = compositionLocalOf<State<Boolean>> { mutableStateOf(false) }

@Composable
fun ReadTab(vm: AuthViewModel) {
    val items by vm.readingItems.collectAsStateWithLifecycle()
    val timeRange by vm.readingTimeRange.collectAsStateWithLifecycle()
    val sourceFilter by vm.readingSourceFilter.collectAsStateWithLifecycle()
    val sources by vm.readingSources.collectAsStateWithLifecycle()
    val status by vm.readingStatus.collectAsStateWithLifecycle()
    val syncFilter by vm.readingSyncFilter.collectAsStateWithLifecycle()
    val category by vm.readingCategory.collectAsStateWithLifecycle()
    val searchQuery by vm.readingSearch.collectAsStateWithLifecycle()
    val loading by vm.readingLoading.collectAsStateWithLifecycle()
    val focusFilter by vm.readingFocusFilter.collectAsStateWithLifecycle()
    val focuses by vm.readingFocuses.collectAsStateWithLifecycle()

    var selectedContentId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedIdx = items.indexOfFirst { it.contentId == selectedContentId }

    val configuration = LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600 && configuration.screenWidthDp >= 720

    LaunchedEffect(Unit) { vm.loadReading() }

    val groups = remember(items) { buildGroups(items) }
    val totalCount = items.size
    val unreadCount = items.count { !it.isUserRead }
    val highValueCount = items.count { it.isHighValue }

    val listState = rememberLazyListState()
    // 列表滚动状态：下传给 badge 跑马灯，滚动时暂停自动跑马灯（P0 优化）。
    // 用 derivedStateOf 包成稳定 State 引用，provider 节点不订阅，仅 badge 自身订阅。
    val marqueePausedState = remember { derivedStateOf { listState.isScrollInProgress } }
    // 用 Box 把列表与右侧滚动条叠在一起（内容不足一屏时滚动条自动隐藏）
    if (isTablet) {
        ReadTabTablet(
            vm = vm, items = items, groups = groups,
            totalCount = totalCount, unreadCount = unreadCount, highValueCount = highValueCount,
            timeRange = timeRange, sourceFilter = sourceFilter, sources = sources,
            status = status, syncFilter = syncFilter, category = category,
            searchQuery = searchQuery, focusFilter = focusFilter, focuses = focuses,
            loading = loading,
            selectedContentId = selectedContentId, selectedIdx = selectedIdx,
            onSelect = { selectedContentId = it }, onClear = { selectedContentId = null },
            listState = listState, marqueePausedState = marqueePausedState,
        )
    } else {
    CompositionLocalProvider(LocalMarqueePaused provides marqueePausedState) {
        Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, top = 0.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        item {
            Spacer(Modifier.height(4.dp))
            ReadFilterBar(
                timeRange = timeRange,
                sourceFilter = sourceFilter,
                sources = sources,
                status = status,
                syncFilter = syncFilter,
                category = category,
                searchQuery = searchQuery,
                focusFilter = focusFilter,
                focuses = focuses,
                totalCount = totalCount,
                unreadCount = unreadCount,
                highValueCount = highValueCount,
                onTimeRange = vm::setReadingTimeRange,
                onSourceFilter = vm::setReadingSourceFilter,
                onStatus = vm::setReadingStatus,
                onSyncFilter = vm::setReadingSyncFilter,
                onSourceCategory = vm::setReadingSourceCategory,
                onSearch = vm::setReadingSearch,
            )
        }

        when {
            loading && items.isEmpty() -> item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            }
            items.isEmpty() -> item {
                Text(
                    when (status) {
                        ReadingStatus.UNREAD -> "当前没有「未读」的文章"
                        ReadingStatus.READ -> "当前没有「已读」的文章"
                        ReadingStatus.HIGH_VALUE -> "当前没有「高价值」的文章"
                        ReadingStatus.ALL -> "本地还没有文章，先去「拉取」页拉取吧"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
            else -> items(
                items = groups,
                key = { it.clusterId + ":" + it.items.firstOrNull()?.contentId.orEmpty() },
            ) { group ->
                val isSingle = group.clusterId.isBlank() || group.items.size == 1
                if (isSingle) {
                    ReadingArticleCard(
                        item = group.items.first(),
                        isSelected = selectedContentId == group.items.first().contentId,
                        onClick = { selectedContentId = group.items.first().contentId },
                        onUserRead = { vm.setUserRead(group.items.first().contentId, it) },
                        onHighValue = { vm.setHighValue(group.items.first().contentId, it) },
                    )
                } else {
                    ClusterReadingCard(
                        group = group,
                        isSelected = group.items.any { it.contentId == selectedContentId },
                        onOpenItem = { selectedContentId = it.contentId },
                        onUserRead = { id, v -> vm.setUserRead(id, v) },
                        onHighValue = { id, v -> vm.setHighValue(id, v) },
                    )
                }
            }
        }
        }   // 关闭 LazyColumn（Box 内）
        // 右侧滚动条：与「已拉取文章概况」弹窗共用同一实现，作为 LazyColumn 的兄弟节点（不可放进 LazyColumn 内容里）
        LazyColumnScrollbar(
            listState = listState,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 2.dp),
        )
        }       // 关闭 Box
    }   // 关闭 CompositionLocalProvider

    // 详情抽屉（手机：底部抽屉）
    if (selectedIdx in items.indices) {
        ReadingDetailSheet(
            item = items[selectedIdx],
            vm = vm,
            position = selectedIdx + 1,
            total = items.size,
            allItems = items,
            onDismiss = { selectedContentId = null },
            onPrev = { if (selectedIdx > 0) selectedContentId = items[selectedIdx - 1].contentId },
            onNext = { if (selectedIdx < items.size - 1) selectedContentId = items[selectedIdx + 1].contentId },
            onUserRead = { vm.setUserRead(items[selectedIdx].contentId, it) },
            onHighValue = { vm.setHighValue(items[selectedIdx].contentId, it) },
        )
    }
    }   // 关闭 else（手机单栏分支）
}

// ─── 平板双栏阅读 ───

/**
 * 平板双栏阅读：左列表 + 右详情，中间可拖拽分割条。
 * - 双轴判定（smallestScreenWidthDp>=600 && screenWidthDp>=720）已在 [ReadTab] 完成，这里只负责布局。
 * - 分割比例用 rememberSaveable 记忆（旋转 / 系统回收后保留，应用彻底冷重启不保证）。
 * - 比例随屏宽自适应：窄屏默认 44/56，宽屏(>=1000dp)默认 38/62；列表最小 300dp、详情最小 480dp（仅当屏宽足够时生效）。
 */
@Composable
private fun ReadTabTablet(
    vm: AuthViewModel,
    items: List<ReadingItem>,
    groups: List<ReadingGroup>,
    totalCount: Int,
    unreadCount: Int,
    highValueCount: Int,
    timeRange: ReadingTimeRange,
    sourceFilter: String?,
    sources: List<String>,
    status: ReadingStatus,
    syncFilter: ReadingSyncFilter,
    category: String?,
    searchQuery: String,
    focusFilter: Long?,
    focuses: List<ReadingFocus>,
    loading: Boolean,
    selectedContentId: String?,
    selectedIdx: Int,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
    listState: LazyListState,
    marqueePausedState: State<Boolean>,
) {
    CompositionLocalProvider(LocalMarqueePaused provides marqueePausedState) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val maxWidthPx = with(density) { maxWidth.toPx() }
            val splitterWidth = 12.dp
            val splitterPx = with(density) { splitterWidth.toPx() }
            val availablePx = (maxWidthPx - splitterPx).coerceAtLeast(1f)

            // 默认比例随屏宽自适应
            val wideThresholdPx = with(density) { 1000.dp.toPx() }
            val defaultRatio = if (maxWidthPx >= wideThresholdPx) 0.38f else 0.44f

            var listRatio by rememberSaveable { mutableStateOf<Float?>(null) }
            val ratio = listRatio ?: defaultRatio

            // 最小宽度约束（仅当屏宽足够时生效，否则回退到 0.30~0.70 安全区间）
            val minListPx = with(density) { 300.dp.toPx() }
            val minDetailPx = with(density) { 480.dp.toPx() }
            val canEnforceMins = availablePx >= minListPx + minDetailPx
            val effectiveMin = if (canEnforceMins) (minListPx / availablePx).coerceIn(0f, 1f) else 0.30f
            val effectiveMax = if (canEnforceMins) ((availablePx - minDetailPx) / availablePx).coerceIn(0f, 1f) else 0.70f
            val clampedRatio = ratio.coerceIn(effectiveMin, effectiveMax)

            Row(Modifier.fillMaxSize()) {
                // ── 左栏：列表 ──
                Box(Modifier.weight(clampedRatio).fillMaxHeight()) {
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 20.dp, top = 0.dp, end = 8.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            item {
                                Spacer(Modifier.height(4.dp))
                                ReadFilterBar(
                                    timeRange = timeRange, sourceFilter = sourceFilter, sources = sources,
                                    status = status, syncFilter = syncFilter, category = category,
                                    searchQuery = searchQuery, focusFilter = focusFilter, focuses = focuses,
                                    totalCount = totalCount, unreadCount = unreadCount, highValueCount = highValueCount,
                                    onTimeRange = vm::setReadingTimeRange, onSourceFilter = vm::setReadingSourceFilter,
                                    onStatus = vm::setReadingStatus, onSyncFilter = vm::setReadingSyncFilter,
                                    onSourceCategory = vm::setReadingSourceCategory, onSearch = vm::setReadingSearch,
                                )
                            }
                            when {
                                loading && items.isEmpty() -> item {
                                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                                        androidx.compose.material3.CircularProgressIndicator()
                                    }
                                }
                                items.isEmpty() -> item {
                                    Text(
                                        when (status) {
                                            ReadingStatus.UNREAD -> "当前没有「未读」的文章"
                                            ReadingStatus.READ -> "当前没有「已读」的文章"
                                            ReadingStatus.HIGH_VALUE -> "当前没有「高价值」的文章"
                                            ReadingStatus.ALL -> "本地还没有文章，先去「拉取」页拉取吧"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 24.dp),
                                    )
                                }
                                else -> items(
                                    items = groups,
                                    key = { it.clusterId + ":" + it.items.firstOrNull()?.contentId.orEmpty() },
                                ) { group ->
                                    val isSingle = group.clusterId.isBlank() || group.items.size == 1
                                    if (isSingle) {
                                        ReadingArticleCard(
                                            item = group.items.first(),
                                            isSelected = selectedContentId == group.items.first().contentId,
                                            onClick = { onSelect(group.items.first().contentId) },
                                            onUserRead = { vm.setUserRead(group.items.first().contentId, it) },
                                            onHighValue = { vm.setHighValue(group.items.first().contentId, it) },
                                        )
                                    } else {
                                        ClusterReadingCard(
                                            group = group,
                                            isSelected = group.items.any { it.contentId == selectedContentId },
                                            onOpenItem = { onSelect(it.contentId) },
                                            onUserRead = { id, v -> vm.setUserRead(id, v) },
                                            onHighValue = { id, v -> vm.setHighValue(id, v) },
                                        )
                                    }
                                }
                            }
                        }
                        LazyColumnScrollbar(
                            listState = listState,
                            modifier = Modifier.align(Alignment.TopEnd).padding(end = 2.dp),
                        )
                    }
                }

                // ── 分割条（可拖拽）──
                Box(
                    Modifier
                        .width(splitterWidth)
                        .fillMaxHeight()
                        .pointerInput(availablePx, effectiveMin, effectiveMax) {
                            detectHorizontalDragGestures { change, dragAmount ->
                                change.consume()
                                val deltaRatio = dragAmount / availablePx
                                val base = listRatio ?: defaultRatio
                                val next = (base + deltaRatio).coerceIn(effectiveMin, effectiveMax)
                                listRatio = next
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .fillMaxHeight(0.6f)
                            .background(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
                    )
                }

                // ── 右栏：详情 / 占位 ──
                Box(Modifier.weight(1f - clampedRatio).fillMaxHeight()) {
                    if (selectedIdx in items.indices) {
                        ReadingDetailContent(
                            item = items[selectedIdx],
                            vm = vm,
                            position = selectedIdx + 1,
                            total = items.size,
                            allItems = items,
                            onClose = onClear,
                            onPrev = { if (selectedIdx > 0) onSelect(items[selectedIdx - 1].contentId) },
                            onNext = { if (selectedIdx < items.size - 1) onSelect(items[selectedIdx + 1].contentId) },
                            onUserRead = { vm.setUserRead(items[selectedIdx].contentId, it) },
                            onHighValue = { vm.setHighValue(items[selectedIdx].contentId, it) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        TabletDetailPlaceholder(totalCount = totalCount, unreadCount = unreadCount, highValueCount = highValueCount)
                    }
                }
            }

            // 选中项联动：把对应分组滚入可见区域
            val groupIndex = groups.indexOfFirst { g -> g.items.any { it.contentId == selectedContentId } }
            LaunchedEffect(selectedContentId) {
                if (groupIndex >= 0) {
                    try { listState.animateScrollToItem(groupIndex) } catch (_: Exception) { /* 越界忽略 */ }
                }
            }
        }
    }
}

/** 平板右栏未选中时的占位提示（含统计回声） */
@Composable
private fun TabletDetailPlaceholder(totalCount: Int, unreadCount: Int, highValueCount: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "选择左侧文章查看详情",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "共 $totalCount 篇 · 未读 $unreadCount · 高价值 $highValueCount",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 把扁平列表按 clusterId 分组（保留首次出现顺序），无聚类的文章各自成组 */
private fun buildGroups(items: List<ReadingItem>): List<ReadingGroup> {
    val map = LinkedHashMap<String, MutableList<ReadingItem>>()
    for (it in items) {
        val key = it.clusterId.ifBlank { "single:${it.contentId}" }
        map.getOrPut(key) { mutableListOf() }.add(it)
    }
    return map.map { (key, list) ->
        val isSingle = key.startsWith("single:")
        ReadingGroup(
            clusterId = if (isSingle) "" else key,
            clusterLabel = if (isSingle) "" else list.first().clusterLabel,
            items = list,
        )
    }
}

// ─── 顶部筛选栏 ───

@Composable
private fun ReadFilterBar(
    timeRange: ReadingTimeRange,
    sourceFilter: String?,
    sources: List<String>,
    status: ReadingStatus,
    syncFilter: ReadingSyncFilter,
    category: String?,
    searchQuery: String,
    focusFilter: Long?,
    focuses: List<ReadingFocus>,
    totalCount: Int,
    unreadCount: Int,
    highValueCount: Int,
    onTimeRange: (ReadingTimeRange) -> Unit,
    onSourceFilter: (String?) -> Unit,
    onStatus: (ReadingStatus) -> Unit,
    onSyncFilter: (ReadingSyncFilter) -> Unit,
    onSourceCategory: (category: String?, focusId: Long?) -> Unit,
    onSearch: (String) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            // 搜索框：匹配标题或来源名（紧凑高度，规避默认 56dp 过高）
            BasicTextField(
                value = searchQuery,
                onValueChange = onSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                singleLine = true,
                decorationBox = { innerTextField ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = "搜索",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f)) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    "搜索标题或来源…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        }
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearch("") }, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    Icons.Filled.Clear,
                                    contentDescription = "清除",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                },
            )

            Spacer(Modifier.height(10.dp))

            // 五个维度下拉选择器（单行水平滚动，避免窄屏下文字截断）
            val filterScrollState = rememberScrollState()
            val showFilterLeftFade by remember { derivedStateOf { filterScrollState.canScrollBackward } }
            val showFilterRightFade by remember { derivedStateOf { filterScrollState.canScrollForward } }
            val filterFadeColor = MaterialTheme.colorScheme.surfaceContainerLow
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(filterScrollState),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterDropdownButton(
                        label = "时间",
                        value = timeRange.label,
                        items = ReadingTimeRange.entries.map { it.label },
                        selectedItem = timeRange.label,
                        // 锁 116dp：5 个按钮 outer 等宽 → 视觉间距一致；长 value 走 Column(weight=1f)+ellipsis 兜底。
                        modifier = Modifier.widthIn(min = 116.dp, max = 116.dp),
                        onSelect = { label ->
                            ReadingTimeRange.entries.firstOrNull { it.label == label }?.let { onTimeRange(it) }
                        },
                    )
                    FilterDropdownButton(
                        label = "公众号",
                        value = sourceFilter ?: "全部公众号",
                        items = listOf("全部公众号") + sources,
                        selectedItem = sourceFilter ?: "全部公众号",
                        // 与其他按钮等宽（116dp）。长公众号名走 Column(weight=1f)+ellipsis 截断，
                        // 完整名称仍可在 DropdownMenu 列表查看。
                        modifier = Modifier.widthIn(min = 116.dp, max = 116.dp),
                        onSelect = { label ->
                            onSourceFilter(if (label == "全部公众号") null else label)
                        },
                    )
                    FilterDropdownButton(
                        label = "状态",
                        value = status.label,
                        items = ReadingStatus.entries.map { it.label },
                        selectedItem = status.label,
                        modifier = Modifier.widthIn(min = 116.dp, max = 116.dp),
                        onSelect = { label ->
                            ReadingStatus.entries.firstOrNull { it.label == label }?.let { onStatus(it) }
                        },
                    )
                    FilterDropdownButton(
                        label = "同步",
                        value = syncFilter.label,
                        items = ReadingSyncFilter.entries.map { it.label },
                        selectedItem = syncFilter.label,
                        modifier = Modifier.widthIn(min = 116.dp, max = 116.dp),
                        onSelect = { label ->
                            ReadingSyncFilter.entries.firstOrNull { it.label == label }?.let { onSyncFilter(it) }
                        },
                    )
                    // 来源：合并原「来源(订阅源/网页集/关注点分类)」与「关注点(具体关注点)」两个下拉。
                    // 选项：全部 / 订阅源 / 网页集 / 关注点(=全部关注点) / 关注点 · {具体关注点}
                    val sourceCategoryItems = buildList {
                        add("全部")
                        add("订阅源")
                        add("网页集")
                        if (focuses.isNotEmpty()) {
                            add("关注点")
                            focuses.forEach { add("关注点 · ${it.title}") }
                        }
                    }
                    val sourceCategoryValue = when (category) {
                        "SUBSCRIPTION" -> "订阅源"
                        "WEBSET" -> "网页集"
                        "FOCUS" -> if (focusFilter == null) {
                            "关注点"
                        } else {
                            "关注点 · ${focuses.firstOrNull { it.id == focusFilter }?.title ?: ""}"
                        }
                        else -> "全部"
                    }
                    FilterDropdownButton(
                        label = "来源",
                        value = sourceCategoryValue,
                        items = sourceCategoryItems,
                        selectedItem = sourceCategoryValue,
                        modifier = Modifier.widthIn(min = 116.dp, max = 116.dp),
                        onSelect = { label ->
                            when {
                                label == "全部" -> onSourceCategory(null, null)
                                label == "订阅源" -> onSourceCategory("SUBSCRIPTION", null)
                                label == "网页集" -> onSourceCategory("WEBSET", null)
                                label == "关注点" -> onSourceCategory("FOCUS", null)
                                label.startsWith("关注点 · ") -> {
                                    val title = label.removePrefix("关注点 · ")
                                    onSourceCategory("FOCUS", focuses.firstOrNull { it.title == title }?.id)
                                }
                            }
                        },
                    )
                }
                if (showFilterLeftFade) {
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxHeight()
                            .width(24.dp)
                            .background(Brush.horizontalGradient(listOf(filterFadeColor, Color.Transparent))),
                    )
                }
                if (showFilterRightFade) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(24.dp)
                            .background(Brush.horizontalGradient(listOf(Color.Transparent, filterFadeColor))),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Text(
                "共 ${totalCount} 篇 · 未读 ${unreadCount} 篇 · 高价值 ${highValueCount} 篇",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FilterDropdownButton(
    label: String,
    value: String,
    items: List<String>,
    selectedItem: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        // 关键：fillMaxWidth() 让 OutlinedButton 读 Box 的 116dp 约束；
        // 之前用 wrapContentWidth() 会读 intrinsic content，绕过 Box.widthIn(max)。
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // weight(1f) 让 Column 占满剩余宽度（按 outer=116dp 算 → 74dp），
                // 长 value（如来源名过长时）走 Text(maxLines=1, overflow=Ellipsis) 截断。
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            items.forEach { item ->
                val selected = item == selectedItem
                DropdownMenuItem(
                    text = {
                        Text(
                            item,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        onSelect(item)
                        expanded = false
                    },
                    trailingIcon = if (selected) {
                        {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "已选",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else null,
                )
            }
        }
    }
}

// ─── 单篇卡（无聚类）───

/**
 * 阅读页展示用来源名。
 * 新拉取的关注点文章 sourceName 已改为真实来源名（公众号/网站名），直接展示；
 * 老数据仍可能带「[关注点]xxx」前缀，这里兼容性地去掉前缀，避免与左侧「关注点」徽章重复。
 * 其他通道（订阅源 / 网页集）原样返回。
 */
private fun displaySourceName(item: ReadingItem): String {
    val raw = item.sourceName.ifBlank { "未知来源" }
    return if (item.originCategory == OriginCategory.FOCUS.key && raw.startsWith("[关注点]")) {
        raw.removePrefix("[关注点]")
    } else raw
}

@Composable
private fun ReadingArticleCard(
    item: ReadingItem,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onUserRead: (Boolean) -> Unit,
    onHighValue: (Boolean) -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().then(
            if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
            else Modifier
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            // 上排：标题 + 摘要 (左, weight=1f) | 已读/高价值控件 (右)
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(item.title.ifBlank { "（无标题）" }, style = MaterialTheme.typography.titleSmall)
                    val summary = item.summary
                    if (!summary.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                ReadMarkControls(
                    isUserRead = item.isUserRead,
                    isHighValue = item.isHighValue,
                    onUserRead = onUserRead,
                    onHighValue = onHighValue,
                )
            }
            // 下排：两个 source badge（同行，超出可横向滚动）+ 发布时间同排右对齐。
            Spacer(Modifier.height(10.dp))
            SourceTypeBadge(item = item)
        }
    }
}

// ─── 聚类堆叠卡 ───

@Composable
private fun ClusterReadingCard(
    group: ReadingGroup,
    isSelected: Boolean = false,
    onOpenItem: (ReadingItem) -> Unit,
    onUserRead: (String, Boolean) -> Unit,
    onHighValue: (String, Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val total = group.items.size
    val readCount = group.items.count { it.isUserRead }
    val anyHigh = group.items.any { it.isHighValue }
    val allRead = readCount == total

    Card(
        Modifier.fillMaxWidth().then(
            if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
            else Modifier
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        // 收起态：标题 + 篇数 + 聚合高价值星标 + 聚合实际已读进度
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    group.clusterLabel.ifBlank { "未命名聚类" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "$total 篇",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 聚合实际已读徽章
            if (allRead) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Text("已读", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            } else {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text("$readCount/$total 已读", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
            // 聚合高价值星标（任意一篇高价值即亮）
            Icon(
                if (anyHigh) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = "高价值",
                tint = if (anyHigh) Color(0xFFBA7517) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                group.items.forEach { it2 ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenItem(it2) }
                            .padding(vertical = 8.dp),
                    ) {
                        // 上排：标题（左, weight=1f）| 已读/高价值控件（右）
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                it2.title.ifBlank { "（无标题）" },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            ReadMarkControls(
                                isUserRead = it2.isUserRead,
                                isHighValue = it2.isHighValue,
                                onUserRead = { onUserRead(it2.contentId, it) },
                                onHighValue = { onHighValue(it2.contentId, it) },
                            )
                        }
                        // 下排：source badge（同行横滚）+ 发布时间同排右对齐
                        Spacer(Modifier.height(6.dp))
                        SourceTypeBadge(item = it2)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** 实际已读 checkbox + 高价值星标 的紧凑控件组 */
@Composable
private fun ReadMarkControls(
    isUserRead: Boolean,
    isHighValue: Boolean,
    onUserRead: (Boolean) -> Unit,
    onHighValue: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = isUserRead, onCheckedChange = onUserRead)
        IconButton(onClick = { onHighValue(!isHighValue) }, modifier = Modifier.size(36.dp)) {
            Icon(
                if (isHighValue) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = "高价值",
                tint = if (isHighValue) Color(0xFFBA7517) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ─── 详情底部抽屉 ───

@Composable
private fun ReadingDetailSheet(
    item: ReadingItem,
    vm: AuthViewModel,
    position: Int,
    total: Int,
    allItems: List<ReadingItem>,
    onDismiss: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onUserRead: (Boolean) -> Unit,
    onHighValue: (Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        ReadingDetailContent(
            item = item, vm = vm, position = position, total = total, allItems = allItems,
            onClose = onDismiss, onPrev = onPrev, onNext = onNext,
            onUserRead = onUserRead, onHighValue = onHighValue,
            modifier = Modifier.fillMaxHeight(0.92f),
        )
    }
}

/** 详情内容（手机套底部抽屉 / 平板直接渲染右栏，单一来源） */
@Composable
private fun ReadingDetailContent(
    item: ReadingItem,
    vm: AuthViewModel,
    position: Int,
    total: Int,
    allItems: List<ReadingItem>,
    onClose: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onUserRead: (Boolean) -> Unit,
    onHighValue: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    var tab by remember { mutableStateOf("详情") }

    Column(modifier) {
            // 顶部：关闭 + 位置指示
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "第 $position 篇 / 共 $total 篇",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 分段切换：详情 / 历史 / 评论
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DetailTabButton("详情", tab == "详情") { tab = "详情" }
                DetailTabButton("历史", tab == "历史") { tab = "历史" }
                DetailTabButton("评论", tab == "评论") { tab = "评论" }
            }

            when (tab) {
                "历史" -> Box(Modifier.weight(1f).fillMaxWidth()) {
                    HistoryTab(vm = vm, contentId = item.contentId)
                }
                "评论" -> Box(Modifier.weight(1f).fillMaxWidth()) {
                    CommentsTab(vm = vm, contentId = item.contentId)
                }
                else -> {
                    // 可滚动内容（详情）
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                    ) {
                // 封面图 + 左下查看原文
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(MaterialTheme.shapes.medium),
                ) {
                    val cover = item.coverImage
                    if (!cover.isNullOrBlank()) {
                        AsyncImage(
                            model = cover,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Box(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("无封面图", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (item.url.isNotBlank()) {
                        Surface(
                            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                            shape = MaterialTheme.shapes.small,
                            color = Color(0xCC000000),
                            contentColor = Color.White,
                            onClick = { uriHandler.openUri(item.url) },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("查看原文", style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(item.title.ifBlank { "（无标题）" }, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                SourceTypeBadge(item = item)

                Spacer(Modifier.height(12.dp))
                // tags（暖灰底）/ keywords（鲜明蓝底）
                if (item.tags.isNotEmpty()) {
                    ChipRow(label = "tags", chips = item.tags, container = Color(0xFFF5F0E6), content = Color(0xFF5D4E37))
                    Spacer(Modifier.height(8.dp))
                }
                if (item.keywords.isNotEmpty()) {
                    ChipRow(label = "keywords", chips = item.keywords, container = Color(0xFFE3F2FD), content = Color(0xFF1565C0))
                    Spacer(Modifier.height(8.dp))
                }
                // AI 维度
                AiDimsGrid(item = item)
                Spacer(Modifier.height(12.dp))
                // 摘要（独立卡片，浅黄底）
                DetailBlock(
                    title = "摘要",
                    text = item.summary ?: "（无摘要）",
                    containerColor = Color(0xFFFFFDE7),
                    textColor = Color(0xFF3E2723),
                )
                Spacer(Modifier.height(8.dp))
                // 立场摘要（浅青底）
                if (item.hasStance) {
                    DetailBlock(
                        title = "立场摘要",
                        text = item.stanceSummary.ifBlank { "（未提供）" },
                        containerColor = Color(0xFFE0F7FA),
                        textColor = Color(0xFF263238),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                // 聚类进度脚注：同簇已读进度
                if (item.clusterId.isNotBlank()) {
                    val clusterItems = allItems.filter { it.clusterId == item.clusterId }
                    val readCount = clusterItems.count { it.isUserRead }
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            "聚类：${item.clusterLabel} · 已读 $readCount/${clusterItems.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // 底部操作栏：上一篇 / 标记 / 下一篇
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedNavButton(icon = Icons.AutoMirrored.Filled.ArrowBack, text = "上一篇", enabled = position > 1, onClick = onPrev)
                Spacer(Modifier.width(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onUserRead(!item.isUserRead) },
                ) {
                    Icon(
                        if (item.isUserRead) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                        contentDescription = "实际已读",
                        tint = if (item.isUserRead) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("实际已读", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onHighValue(!item.isHighValue) },
                ) {
                    Icon(
                        if (item.isHighValue) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "高价值",
                        tint = if (item.isHighValue) Color(0xFFBA7517) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("高价值", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.weight(1f))
                OutlinedNavButton(icon = Icons.AutoMirrored.Filled.ArrowForward, text = "下一篇", enabled = position < total, onClick = onNext, trailing = true)
            }
            } // 关闭 else 块（详情 tab 内容 + 底部操作栏）
        } // 关闭 when(tab)
    } // 关闭 Column(modifier)
} // 关闭 ReadingDetailContent 函数体

@Composable
private fun DetailTabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = onClick,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
    }
}

@Composable
private fun HistoryTab(vm: AuthViewModel, contentId: String) {
    var items by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(contentId) {
        loading = true
        items = vm.loadHistory(contentId)
        loading = false
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (loading) item { Text("加载中…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(items) { h -> HistoryRow(h) }
        if (!loading && items.isEmpty()) item {
            Text("暂无历史记录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HistoryRow(h: HistoryEntry) {
    val (icon, color) = when (h.eventType) {
        "PULL" -> "⬇" to Color(0xFF1565C0)
        "LOCAL_EDIT" -> "✎" to Color(0xFF2E7D32)
        "PUSH" -> "⬆" to Color(0xFFEF6C00)
        "PULL_OVERRIDE" -> "⇄" to Color(0xFF7B1FA2)
        "COMMENT_ADD" -> "💬" to Color(0xFF2E7D32)
        "COMMENT_EDIT" -> "✎" to Color(0xFF2E7D32)
        "COMMENT_DELETE" -> "🗑" to Color(0xFFC62828)
        "COMMENT_PUSH" -> "⬆" to Color(0xFFEF6C00)
        "COMMENT_PULL_ADD" -> "⬇" to Color(0xFF1565C0)
        "COMMENT_PULL_EDIT" -> "⇄" to Color(0xFF7B1FA2)
        "COMMENT_PULL_DELETE" -> "🗑" to Color(0xFFC62828)
        "COMMENT_DELETE_SYNC" -> "🗑" to Color(0xFFC62828)
        else -> "•" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, color = color, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(6.dp))
            Text(formatMillis(h.eventTime), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(if (h.actor == "CLOUD") "云端" else "本地", style = MaterialTheme.typography.labelSmall, color = color)
        }
        Spacer(Modifier.height(2.dp))
        Text(historyLabel(h), style = MaterialTheme.typography.bodyMedium)
        val f = h.field
        val showCommentDiff = (h.eventType == "COMMENT_EDIT" || h.eventType == "COMMENT_PULL_EDIT") &&
            h.oldValue != null && h.newValue != null && h.oldValue != h.newValue
        val showCommentAdd = h.eventType == "COMMENT_PULL_ADD" && h.newValue != null
        when {
            h.eventType == "PULL_OVERRIDE" && f != null && f != "*" && h.oldValue != h.newValue -> {
                Spacer(Modifier.height(2.dp))
                Text(
                    "${fieldLabel(f)}：${h.oldValue ?: "—"} → ${h.newValue ?: "—"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            showCommentDiff -> {
                Spacer(Modifier.height(2.dp))
                Text(
                    "${h.oldValue ?: "—"} → ${h.newValue ?: "—"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            showCommentAdd -> {
                Spacer(Modifier.height(2.dp))
                Text(
                    "→ ${h.newValue ?: "—"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    HorizontalDivider()
}

private fun historyLabel(h: HistoryEntry): String = when (h.eventType) {
    "PULL" -> "拉取到本地"
    "LOCAL_EDIT" -> "本地编辑（${fieldLabel(h.field ?: "")}）"
    "PUSH" -> "本地覆盖云端（推送到 Notion）"
    "PULL_OVERRIDE" -> "云端覆盖本地"
    "COMMENT_ADD" -> "新增评论"
    "COMMENT_EDIT" -> "编辑评论"
    "COMMENT_DELETE" -> "删除评论（本地）"
    "COMMENT_PUSH" -> "评论推送到 Notion"
    "COMMENT_PULL_ADD" -> "从 Notion 拉回评论"
    "COMMENT_PULL_EDIT" -> "Notion 评论被编辑，已同步"
    "COMMENT_PULL_DELETE" -> "Notion 评论已删除"
    "COMMENT_DELETE_SYNC" -> "删除已同步到 Notion"
    else -> h.eventType
}

private fun fieldLabel(field: String): String = when (field) {
    "is_user_read" -> "实际已读"
    "is_high_value" -> "高价值"
    "title" -> "标题"
    "source_name" -> "来源名"
    "source_type" -> "来源类型"
    "origin_category" -> "来源分类"
    "focus_title" -> "关注点"
    "published_at" -> "发布时间"
    "tags" -> "标签"
    "url" -> "链接"
    "keywords" -> "关键词"
    "content_type" -> "内容类型"
    "content_depth" -> "内容深度"
    "tone" -> "语气"
    "style" -> "风格"
    "has_action" -> "含行动项"
    "has_stance" -> "含立场"
    "stance_summary" -> "立场摘要"
    "cluster_label" -> "聚类标签"
    "cluster_size" -> "聚类大小"
    "is_cluster_rep" -> "聚类代表"
    else -> field
}

@Composable
private fun CommentsTab(vm: AuthViewModel, contentId: String) {
    var comments by remember { mutableStateOf<List<CommentEntry>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun reload() = scope.launch { comments = vm.loadComments(contentId) }
    LaunchedEffect(contentId) { reload() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        LazyColumn(Modifier.weight(1f)) {
            if (comments.isEmpty()) item {
                Text("暂无评论，写下第一条吧", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(comments) { c ->
                CommentRow(
                    c,
                    onDelete = { scope.launch { vm.deleteComment(c.id); reload() } },
                    onEdit = { newBody -> scope.launch { vm.editComment(c.id, newBody); reload() } },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("写评论…（作者：土豆）") },
                singleLine = false,
                maxLines = 3,
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                if (input.isNotBlank()) {
                    scope.launch {
                        vm.addComment(contentId, input.trim())
                        input = ""
                        comments = vm.loadComments(contentId)
                    }
                }
            }) { Text("发送") }
        }
    }
}

@Composable
private fun CommentRow(c: CommentEntry, onDelete: () -> Unit, onEdit: (String) -> Unit) {
    val editable = c.actor == "LOCAL" // 云端他人的评论本地只读
    var showEdit by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(c.body) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.author, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(
                if (c.actor == "CLOUD") "云端" else syncStateLabel(c.syncState),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (editable) {
                TextButton(onClick = { showEdit = true }) { Text("编辑") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(c.body, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(2.dp))
        val tsParts = buildList {
            if (c.createdAt > 0) add("创建于 ${formatMillis(c.createdAt)}")
            val editedAt = c.editedAt
            if (editedAt != null && editedAt > 0) add("编辑于 ${formatMillis(editedAt)}")
            if (c.syncedAt > 0) add("同步于 ${formatMillis(c.syncedAt)}")
        }
        if (tsParts.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
            ) {
                tsParts.forEach { part ->
                    Text(
                        part,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    HorizontalDivider()
    if (showEdit) {
        AlertDialog(
            onDismissRequest = { showEdit = false },
            confirmButton = { OutlinedButton(onClick = { onEdit(editText.trim()); showEdit = false }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { showEdit = false }) { Text("取消") } },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    singleLine = false,
                    maxLines = 4,
                    label = { Text("编辑评论") },
                )
            },
        )
    }
}

private fun syncStateLabel(state: String): String = when (state) {
    CommentSyncState.LOCAL_NEW -> "待同步"
    CommentSyncState.SYNCED -> "已同步"
    CommentSyncState.EDIT_PENDING -> "待更新"
    else -> ""
}

private fun formatMillis(ts: Long): String {
    if (ts <= 0) return "—"
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return try { sdf.format(java.util.Date(ts)) } catch (_: Exception) { ts.toString() }
}

@Composable
private fun OutlinedNavButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    trailing: Boolean = false,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = onClick,
        enabled = enabled,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!trailing) Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
            if (trailing) Spacer(Modifier.width(4.dp))
            if (trailing) Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ChipRow(label: String, chips: List<String>, container: Color, content: Color) {
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(5.dp))
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(chips) { BgChip(it, container, content) }
        }
    }
}

@Composable
private fun BgChip(text: String, container: Color, content: Color) {
    Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), maxLines = 1)
    }
}

@Composable
private fun AiDimsGrid(item: ReadingItem) {
    val cells = listOf(
        Triple("内容类型", item.contentType, Color(0xFFF3E5F5) to Color(0xFF7B1FA2)),
        Triple("内容深度", item.contentDepth, Color(0xFFE3F2FD) to Color(0xFF1565C0)),
        Triple("语气", item.tone, Color(0xFFE8F5E9) to Color(0xFF2E7D32)),
        Triple("风格", item.style, Color(0xFFFFF3E0) to Color(0xFFEF6C00)),
    )
    Column {
        Text("内容分析", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            cells.forEach { (label, value, colors) ->
                DimCell(Modifier.weight(1f), label, value, colors)
            }
        }
    }
}

@Composable
private fun DimCell(
    modifier: Modifier,
    label: String,
    value: String,
    colors: Pair<Color, Color>,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = colors.first,
    ) {
        Column(Modifier.padding(8.dp, 7.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = colors.second.copy(alpha = 0.8f))
            Spacer(Modifier.height(2.dp))
            Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, color = colors.second, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DetailBlock(
    title: String,
    text: String,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    titleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, color = titleColor)
        Spacer(Modifier.height(5.dp))
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = containerColor,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                lineHeight = 22.sp,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

/** 阅读列表/详情页的来源徽章组：一行 badge + 同排发布时间。
 *
 * - 订阅源：badge1「订阅源」，badge2「公众号·公众号名」/「WEBSITE·站点名」/「X·X名」。
 * - 网页集：badge1「网页集」，badge2 同上。
 * - 关注点：badge1「关注点·关注点名」，badge2 同上（用文章真实来源名）。
 * 两个 badge 与发布时间放在同一行；badge 区域占用时间左侧所有可用空间；
 * 当 badge 超出区域时自动来回跑马灯滚动，用户手指划入 badge 区时停止跑马灯并切换为手动横向滚动，
 * 手指离开后从当前位置恢复来回跑马灯；发布时间始终完整显示并右对齐。
 */
@Composable
private fun SourceTypeBadge(item: ReadingItem) {
    val origin = OriginCategory.fromKey(item.originCategory)

    // 通道 badge：关注点只使用 focus_title，不再回退到 source_name（后者现在存的是真实公众号名）。
    val channel = when (origin) {
        OriginCategory.FOCUS -> {
            val title = item.focusTitle.trim()
            val label = if (title.isNotBlank()) "关注点·$title" else "关注点"
            label to (Color(0xFFFFF3E0) to Color(0xFFEF6C00))
        }
        OriginCategory.WEBSET -> "网页集" to (Color(0xFFE3F2FD) to Color(0xFF1565C0))
        else -> "订阅源" to (Color(0xFFF5F0E6) to Color(0xFF5D4E37))
    }

    // 平台 badge：把平台和真实来源名拼在一起。
    val sourceName = item.sourceName.removePrefix("[关注点]").trim().ifBlank { "未知来源" }
    val platform = when (item.sourceType.uppercase()) {
        "MP" -> "公众号·$sourceName" to (Color(0xFFE8F5E9) to Color(0xFF2E7D32))
        "WEBSITE" -> "WEBSITE·$sourceName" to (Color(0xFFF3E5F5) to Color(0xFF7B1FA2))
        "X" -> "X·$sourceName" to (Color(0xFFE0F7FA) to Color(0xFF006064))
        else -> {
            val typeLabel = if (item.sourceType.isBlank()) "公众号" else item.sourceType
            "$typeLabel·$sourceName" to (Color(0xFFF5F0E6) to Color(0xFF5D4E37))
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // badge 区域：占用时间左侧全部空间，太长时自动跑马灯并支持手动横向滚动
        MarqueeBadgeRow(
            modifier = Modifier.weight(1f),
        ) {
            SourceChip(channel.first, channel.second.first, channel.second.second)
            Spacer(Modifier.width(4.dp))
            SourceChip(platform.first, platform.second.first, platform.second.second)
        }
        Spacer(Modifier.width(8.dp))
        // 发布时间：始终完整显示，右对齐
        Text(
            formatDateTime(item.publishedAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 可自动跑马灯 + 手动横向滚动的 badge 行。
 * 内容宽度超过容器宽度时：idle 状态下自动来回往返滚动；用户拖动/滑动时立即停止自动滚动，
 * 让用户手动控制；手指离开后从当前位置恢复来回跑马灯。
 *
 * P0+P1 优化（相比旧实现）：
 * - 列表滚动（[LocalMarqueePaused]）时自动跑马灯暂停，避免每帧与列表布局抢占主线程造成卡顿。
 * - 不再用 horizontalScroll + scrollTo（每帧走 Scrollable 状态机并重 layout），改为
 *   onSizeChanged 量出 overflow，用 Animatable 驱动 Modifier.offset 做平移：仅改 draw transform，
 *   不触发内容 measure/layout，逐帧成本大幅下降。
 * - 动画由 Compose 统一动画时钟调度，列表不可见/暂停时自动不跑。
 */
@Composable
private fun MarqueeBadgeRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val paused by LocalMarqueePaused.current

    var containerWidth by remember { mutableStateOf(0) }
    var contentWidth by remember { mutableStateOf(0) }
    // 溢出量（px）：内容比容器宽多少才需要滚动
    val overflow = (contentWidth - containerWidth).coerceAtLeast(0)

    val offsetX = remember { mutableStateOf(0) }
    val anim = remember { Animatable(0f) }
    var isDragging by remember { mutableStateOf(false) }

    // 自动跑马灯：仅在溢出、未暂停、未手动拖动时运行。
    // key 含 paused：列表开始滚动(paused=true) → 立即取消当前动画；停止滚动(paused=false) → 续跑。
    LaunchedEffect(overflow, paused, isDragging) {
        // 非动画态（不溢出 / 暂停 / 拖动中）：把 anim 同步到当前手/自动位置，便于恢复
        anim.snapTo(offsetX.value.toFloat())
        if (overflow <= 0 || paused || isDragging) return@LaunchedEffect

        // 从当前位置往返：0 → -overflow → 0，端点各停留 800ms
        while (isActive) {
            delay(800)
            anim.animateTo(
                targetValue = -overflow.toFloat(),
                animationSpec = tween(
                    durationMillis = ((overflow / 200f) * 1000).toLong().coerceIn(1200L, 5000L).toInt(),
                    easing = FastOutSlowInEasing,
                ),
            ) { offsetX.value = value.roundToInt() }
            delay(800)
            anim.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = ((overflow / 200f) * 1000).toLong().coerceIn(1200L, 5000L).toInt(),
                    easing = FastOutSlowInEasing,
                ),
            ) { offsetX.value = value.roundToInt() }
        }
    }

    // 外层 Box：占用 weight 空间并裁剪；内部 Row 用 offset 平移（按内容自然宽度测量，不被省略号截断）。
    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { containerWidth = it.width }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onHorizontalDrag = { _, dragAmount ->
                        val max = -overflow.toFloat()
                        offsetX.value = (offsetX.value + dragAmount).coerceIn(max, 0f).roundToInt()
                    },
                )
            },
    ) {
        Row(
            modifier = Modifier
                // 让内部 Text 按无限宽度测量，不被省略号截断，从而得到真实 contentWidth；
                // 真正的位移动画走下面的 offset（只改 placement，不触发 measure）。
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .offset { IntOffset(x = offsetX.value, y = 0) }
                .onSizeChanged { contentWidth = it.width },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
}

@Composable
private fun SourceChip(label: String, container: Color, content: Color) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun formatDateTime(sec: Long): String {
    if (sec <= 0) return "未知时间"
    return try {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(sec * 1000))
    } catch (_: Throwable) {
        "未知时间"
    }
}
