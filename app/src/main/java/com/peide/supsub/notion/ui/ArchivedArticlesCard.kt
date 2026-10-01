package com.peide.supsub.notion.ui
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.peide.supsub.data.ArticleSummary
import com.peide.supsub.data.ShardKeys
import com.peide.supsub.data.SyncStatus
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 主页上的入口：常驻卡片，点击打开「已拉取文章概况」弹窗。
 * 这样列表再长也不会把主页撑长——详情都收在带滚动条的弹窗里。
 */
@Composable
fun ArchivedArticlesCard(
    total: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "已拉取文章概况",
        subtitle = if (total > 0) {
            "点击查看全部 $total 篇：订阅源 / 标题 / 时间 / 是否同步"
        } else {
            "暂无已拉取的文章"
        },
        modifier = modifier,
    ) {
        TextButton(
            onClick = onClick,
            enabled = total > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (total > 0) "查看文章概况（$total 篇）" else "暂无内容")
        }
    }
}

/**
 * 「已拉取文章概况」弹窗：底部面板上滑入场，内部用 [LazyColumn] 承载全部文章，
 * 右侧配一个可拖动的滚动条。每行展示：订阅源（类型 · 名称）、标题、发布时间、
 * 是否同步（药丸）、是否本次新增（徽章）。
 */
/** 吸顶分组标题的高度，列表顶部留白与之等宽，避免标题压住首篇文章 */
private val STICKY_HEADER_HEIGHT = 44.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedArticlesSheet(
    articles: List<ArticleSummary>,
    total: Int,
    shardCounts: Map<String, Int> = emptyMap(),
    newIds: Set<String>,
    hasMore: Boolean,
    loading: Boolean,
    onLoadMore: () -> Unit,
    onLoadAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // 按天分片键分组（列表整体按发布时间倒序，同一天天然连续）
    val groups = remember(articles) { groupByDay(articles) }
    val labelByKey = remember(groups) { groups.associate { it.key to it.label } }
    val loadedCountByKey = remember(groups) { groups.associate { it.key to it.articles.size } }
    val groupStartIds = remember(groups) { groups.mapNotNull { it.articles.firstOrNull()?.contentId }.toSet() }
    // 每个按天分组首篇文章在 LazyColumn 中的 item 下标（列表内仅文章项，无 header 项）
    val groupStartIndexByKey = remember(groups) {
        val map = mutableMapOf<String, Int>()
        var idx = 0
        for (g in groups) { map[g.key] = idx; idx += g.articles.size }
        map
    }
    // 吸顶标题：随滚动定位到当前最顶端文章所属的按天分组
    val topKey by remember(articles) {
        derivedStateOf { articles.getOrNull(listState.firstVisibleItemIndex)?.shardKey ?: "" }
    }

    // 打开概况时一次性加载全部文章，保证所有日期分组都能正确渲染，
    // 而不是只显示已加载分页里的最近一天。
    LaunchedEffect(Unit) { onLoadAll() }

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    // Sheet 延伸到状态栏下方，顶部用与 Sheet 同色的浅色条覆盖状态栏区域，
    // 并配合深色状态栏图标，使时间、电量、信号清晰可见。
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 拖动手柄移到下方的浅色顶部条里，避免被状态栏遮挡。
        dragHandle = null,
        // Sheet 延伸到状态栏下方，内容区顶部垫一个浅色 Surface 保持视觉一致。
        contentWindowInsets = { WindowInsets(0.dp) },
    ) {
        // ModalBottomSheet 运行在独立的 Dialog 窗口里，状态栏图标由该窗口自己控制。
        // 这里把弹窗窗口的状态栏/导航栏图标强制设为深色，确保在浅色 Sheet 上可见。
        val view = LocalView.current
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        LaunchedEffect(dialogWindow) {
            dialogWindow?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = true
                    isAppearanceLightNavigationBars = true
                }
            }
        }

        // 面板内容整体淡入，配合系统自带的上滑，避免内容突兀出现
        var contentVisible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { contentVisible = true }
        val contentAlpha by animateFloatAsState(
            targetValue = if (contentVisible) 1f else 0f,
            animationSpec = tweenNormal(),
            label = "sheet-content",
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(contentAlpha),
        ) {
            // 顶部浅色条：与 Sheet 底色一致，配合深色状态栏图标（时间/电量/信号）清晰可见。
            // 拖动手柄也放在这里，作为 Sheet 顶部的视觉锚点。
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = statusBarPadding)
                        .padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BottomSheetDefaults.DragHandle(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 28.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                Text(
                    "已拉取文章概况${if (total > 0) "（共 $total 篇）" else ""}",
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = { close() }) { Text("关闭") }
            }
            Spacer(Modifier.height(12.dp))

            if (articles.isEmpty() && !loading) {
                Text(
                    "还没有拉取过文章。点主界面「拉取更新」开始归档。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                // 布局：吸顶标题放在滚动区外（自然固定），下方是滚动区。
                // 这样标题不会被 LazyColumnScrollbar 的全屏手势覆盖层截获点击。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    // 吸顶分组标题（可点击展开日期选择器，快速跳转）：始终显示当前顶部所在日期
                    if (articles.isNotEmpty()) {
                        var menuExpanded by remember { mutableStateOf(false) }
                        // 触发条：显示当前日期 + 篇数 + 下拉箭头
                        Surface(
                            onClick = { menuExpanded = !menuExpanded },
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(STICKY_HEADER_HEIGHT),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    if (menuExpanded) "▴" else "▾",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    labelByKey[topKey] ?: "",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ) {
                                    Text(
                                        "${shardCounts[topKey] ?: loadedCountByKey[topKey] ?: 0} 篇",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        // 分片选择器（内联展开）：不弹窗，直接在 sheet 内展开列表，避免与 ModalBottomSheet 的弹层/焦点冲突。
                        AnimatedVisibility(visible = menuExpanded) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp),
                            ) {
                                Column(
                                    modifier = Modifier.verticalScroll(rememberScrollState()),
                                ) {
                                    groups.forEachIndexed { gi, group ->
                                        val idx = groupStartIndexByKey[group.key] ?: 0
                                        val cnt = shardCounts[group.key] ?: group.articles.size
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    scope.launch { listState.scrollToItem(idx) }
                                                    menuExpanded = false
                                                }
                                                .padding(horizontal = 14.dp, vertical = 11.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                group.label,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                            Surface(
                                                shape = MaterialTheme.shapes.small,
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            ) {
                                                Text(
                                                    "$cnt 篇",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                        if (gi < groups.lastIndex) {
                                            HorizontalDivider(
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // 滚动区：用一个 Box 把列表、滚动条叠在一起
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(end = 10.dp),
                        ) {
                            // 按天分片键（shardKey）分组，组内保持原顺序；因列表整体按发布时间倒序，
                            // 同一天文章天然连续，分组在「加载更多」翻页时不会错位。
                            // 分组标题不放在列表内（避免与上方吸顶标题重复），仅用首篇间距做视觉分隔。
                            groups.forEach { group ->
                                items(group.articles, key = { it.contentId }) { article ->
                                    ArchivedArticleRow(
                                        article = article,
                                        isNew = article.contentId in newIds,
                                        isFirstInGroup = article.contentId in groupStartIds,
                                    )
                                }
                            }
                            if (hasMore) {
                                item {
                                    Spacer(Modifier.height(8.dp))
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (loading) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.width(20.dp).height(20.dp),
                                                strokeWidth = 2.dp,
                                            )
                                        } else {
                                            TextButton(onClick = onLoadMore) { Text("加载更多") }
                                        }
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
                Spacer(Modifier.height(8.dp))
                Text(
                    if (hasMore) "已显示 ${articles.size} 篇，可加载更多" else "已全部加载 ${articles.size} 篇",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
}

/** 列表与滚动条共用的几何量（像素）。内容不足一屏时返回 null。 */
data class ScrollMetrics(
    val thumbHeight: Float,
    val maxScroll: Float,
    val avg: Float,
    val viewport: Float,
)

fun computeScrollMetrics(listState: LazyListState): ScrollMetrics? {
    val layout = listState.layoutInfo
    val total = layout.totalItemsCount
    val visible = layout.visibleItemsInfo
    if (total == 0 || visible.isEmpty()) return null
    val viewport = layout.viewportSize.height.toFloat()
    val avg = visible.sumOf { it.size } / visible.size.toFloat()
    val totalHeight = avg * total
    if (totalHeight <= viewport) return null // 一屏装得下，不显示滚动条
    val maxScroll = totalHeight - viewport
    val thumbHeight = (viewport / totalHeight) * viewport
    return ScrollMetrics(thumbHeight, maxScroll, avg, viewport)
}

/**
 * 跟随列表滚动的滚动条，可拖动滑块滚动内容。
 * 内容不足一屏时自动隐藏。
 */
@Composable
fun LazyColumnScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val metrics by remember { derivedStateOf { computeScrollMetrics(listState) } }
    if (metrics == null) return

    val thumbTopRatio by remember {
        derivedStateOf {
            val m = computeScrollMetrics(listState) ?: return@derivedStateOf 0f
            val scrollOffset = listState.firstVisibleItemIndex * m.avg + listState.firstVisibleItemScrollOffset
            (scrollOffset / m.maxScroll).coerceIn(0f, 1f)
        }
    }

    val scope = rememberCoroutineScope()
    val dragState = rememberDraggableState { delta ->
        val m = computeScrollMetrics(listState) ?: return@rememberDraggableState
        val trackMovable = m.viewport - m.thumbHeight
        if (trackMovable <= 0) return@rememberDraggableState
        val currentScroll = listState.firstVisibleItemIndex * m.avg + listState.firstVisibleItemScrollOffset
        val currentRatio = (currentScroll / m.maxScroll).coerceIn(0f, 1f)
        val newRatio = (currentRatio + delta / trackMovable).coerceIn(0f, 1f)
        val target = newRatio * m.maxScroll
        val index = (target / m.avg).toInt().coerceAtLeast(0)
        val offset = (target - index * m.avg).coerceAtLeast(0f).toInt()
        scope.launch { listState.scrollToItem(index, offset) }
    }

    val thumbHeightDp = with(density) { metrics!!.thumbHeight.toDp() }
    val thumbTopPx = (thumbTopRatio * (metrics!!.viewport - metrics!!.thumbHeight)).roundToInt()

    Surface(
        modifier = modifier
            .offset { IntOffset(0, thumbTopPx) }
            .width(5.dp)
            .height(thumbHeightDp)
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                startDragImmediately = true,
            ),
        shape = RoundedCornerShape(3.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
    ) {}
}

/**
 * 按天分组：把同一 [ArticleSummary.shardKey] 的文章收拢到一起。
 * 列表整体按发布时间倒序，所以遍历时同一天的文章天然连续，
 * 这里只做「首次出现顺序」归组，分组顺序即倒序（最新日期在前）。
 */
private data class DayGroup(
    val key: String,
    val label: String,
    val articles: List<ArticleSummary>,
)

private fun groupByDay(articles: List<ArticleSummary>): List<DayGroup> {
    if (articles.isEmpty()) return emptyList()
    val buckets = LinkedHashMap<String, MutableList<ArticleSummary>>()
    for (a in articles) {
        // 保留原始分片键（可能为空串），与 shardCounts 的键对齐，便于查真实篇数
        val key = a.shardKey
        buckets.getOrPut(key) { mutableListOf() }.add(a)
    }
    return buckets.map { (key, list) ->
        DayGroup(
            key = key,
            label = if (key.isBlank()) "未分片（缺发布时间）" else ShardKeys.labelOf(key),
            articles = list,
        )
    }
}


/**
 * 一篇文章概况行：左上为标题，下方是「来源类型 · 名称」药丸与发布时间；
 * 右上角展示同步状态药丸与（本轮新增时）「新增」徽章。
 */
@Composable
private fun ArchivedArticleRow(
    article: ArticleSummary,
    isNew: Boolean,
    isFirstInGroup: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (isFirstInGroup) 12.dp else 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                article.title.ifBlank { "(无标题)" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatPill("${sourceTypeLabel(article.sourceType)} · ${article.sourceName.ifBlank { "未知来源" }}")
                Text(
                    formatPublishedAt(article.publishedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SyncStatusBadge(article.syncStatus)
            if (isNew) NewBadge()
        }
    }
}

/** 同步状态药丸：SYNCED=已同步（绿）/ FAILED=失败（红）/ PENDING(SYNCING)=待同步（灰）/ PENDING_PROP=待重推（蓝） */
@Composable
private fun SyncStatusBadge(status: String) {
    val (text, container, content) = when (status) {
        SyncStatus.SYNCED ->
            Triple("已同步", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        SyncStatus.FAILED ->
            Triple("同步失败", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        SyncStatus.PROP_RESNC ->
            Triple("待重推", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        else ->
            Triple("待同步", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/** 「新增」标记：本轮拉取新增的文章 */
@Composable
private fun NewBadge() {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text("新增", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/** 发布时间格式化（Unix 秒 → yyyy-MM-dd HH:mm）；0 表示未知 */
private fun formatPublishedAt(sec: Long): String {
    if (sec <= 0) return "未知时间"
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return fmt.format(Date(sec * 1000))
}

