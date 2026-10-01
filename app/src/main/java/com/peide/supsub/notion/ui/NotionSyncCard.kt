package com.peide.supsub.notion.ui
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.peide.supsub.data.NotionShard
import com.peide.supsub.data.ShardKeys
import com.peide.supsub.notionsync.SyncProgress
import com.peide.supsub.notionsync.SyncItemReport
import com.peide.supsub.notionsync.SyncAction
import com.peide.supsub.notion.vm.AuthViewModel

/** 底部：Notion 同步配置 + 进度 */
@Composable
fun NotionSyncCard(vm: AuthViewModel, offline: Boolean = false, modifier: Modifier = Modifier) {
    val config by vm.notionConfig.collectAsState()
    val syncState by vm.notionSyncState.collectAsState()
    val verify by vm.notionVerify.collectAsState()
    val shards by vm.shards.collectAsState()
    val notionCounts by vm.notionCounts.collectAsState()
    val notionCounting by vm.notionCounting.collectAsState()
    val bidirectionalSync by vm.bidirectionalSync.collectAsState()

    var token by remember { mutableStateOf(config.first) }
    var parentPageId by remember { mutableStateOf(config.second) }
    var forceResync by remember { mutableStateOf(false) }
    var bidirectional by remember { mutableStateOf(bidirectionalSync) }
    var configVisible by remember { mutableStateOf(false) }
    var shardSheetVisible by remember { mutableStateOf(false) }
    // 配置从 DataStore 载入后回填输入框
    LaunchedEffect(config) { token = config.first; parentPageId = config.second }
    // 双向同步开关默认值跟随持久化配置（默认开启）
    LaunchedEffect(bidirectionalSync) { bidirectional = bidirectionalSync }

    val isRunning = syncState is SyncProgress.Running

    SectionCard(
        title = "Notion 同步",
        modifier = modifier,
        subtitle = "把本地归档按天分库同步到 Notion（容器页下的子数据库）",
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { vm.loadShardOverview(); shardSheetVisible = true }, enabled = !isRunning) {
                    Text("分库概览")
                }
                TextButton(onClick = { configVisible = true }, enabled = !isRunning) { Text("配置") }
            }
        },
    ) {
        // Token / 容器页 ID / 验证连接 移入右上角「配置」对话框，主卡只展示一行状态摘要
        val configured = token.isNotBlank() && parentPageId.isNotBlank()
        Text(
            if (configured) "已配置 Token 与容器页 ID（点右上角「配置」可修改）"
            else "尚未配置，点右上角「配置」填写 Token 与容器页 ID",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(checked = forceResync, onCheckedChange = { forceResync = it }, enabled = !isRunning)
            Spacer(Modifier.width(8.dp))
            Column {
                Text("强制全量重推", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "重新推送全部条目（忽略已同步记录）；已同步页面会先归档旧页、再新建，每篇仅 2 次请求、速度更快；旧页进入回收站",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "改了正文模板想让旧文章应用新排版时，开启后再同步即可",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(checked = bidirectional, onCheckedChange = { bidirectional = it; vm.setBidirectionalSync(it) }, enabled = !isRunning)
            Spacer(Modifier.width(8.dp))
            Column {
                Text("双向同步（默认开）", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "推送完成后，把 Notion 端较新的改动回拉本地：每篇比较本地编辑时间与 Notion 的 last_edited_time，" +
                        "以较新者为准互相覆盖（正文/摘要不回拉）。关掉则只单向推送本地→Notion",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))

        Spacer(Modifier.height(12.dp))
        AnimatedContent(
            targetState = syncState,
            transitionSpec = { statusTransform() },
            contentKey = {
                when (it) {
                    is SyncProgress.Running -> 0
                    is SyncProgress.Done -> 1
                    is SyncProgress.Error -> 2
                    else -> 3
                }
            },
            label = "notion-sync",
            modifier = Modifier.fillMaxWidth(),
        ) { s ->
            when (s) {
                is SyncProgress.Running -> SyncRunningBlock(s, onCancel = vm::cancelNotionSync)

                is SyncProgress.Done -> Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        MetricCell("推送", s.synced, modifier = Modifier.weight(1f), emphasize = true)
                        MetricCell("拉取", s.pulled, modifier = Modifier.weight(1f))
                        MetricCell("跳过", s.skipped, modifier = Modifier.weight(1f))
                        MetricCell("冲突", s.conflicts, modifier = Modifier.weight(1f))
                        MetricCell("失败", s.failed, modifier = Modifier.weight(1f), warn = true)
                    }
                    if (s.createdShards.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "本次新建分库：${s.createdShards.joinToString()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    val pushOverview = s.pushOverview
                    val pullOverview = s.pullOverview
                    if (pushOverview.isNotEmpty() || pullOverview.isNotEmpty()) {
                        var overviewExpanded by remember { mutableStateOf(false) }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { overviewExpanded = !overviewExpanded },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "本次同步概览（推送 ${pushOverview.size} · 拉取 ${pullOverview.size}）" +
                                    if (overviewExpanded) " ▲" else " ▼",
                            )
                        }
                        if (overviewExpanded) {
                            Spacer(Modifier.height(8.dp))
                            if (pushOverview.isNotEmpty()) {
                                val createN = pushOverview.count { it.action == SyncAction.PUSH_CREATE }
                                val updateN = pushOverview.count { it.action == SyncAction.PUSH_UPDATE }
                                val propN = pushOverview.count { it.action == SyncAction.PUSH_PROP_BACKFILL }
                                Text(
                                    "推送：新建 $createN · 更新 $updateN · 属性补齐 $propN",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 220.dp),
                                ) {
                                    items(pushOverview, key = { it.contentId }) { SyncOverviewRow(it) }
                                }
                            }
                            if (pullOverview.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                val conflictN = pullOverview.count { it.action == SyncAction.PULL_CONFLICT }
                                Text(
                                    "拉取：${pullOverview.size} 篇" +
                                        if (conflictN > 0) "（其中冲突覆盖 $conflictN 篇）" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 220.dp),
                                ) {
                                    items(pullOverview, key = { it.contentId }) { SyncOverviewRow(it) }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { vm.startNotionSync(token, parentPageId, forceResync, bidirectional) },
                        enabled = !offline,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("再次同步") }
                }

                is SyncProgress.Error -> Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "同步失败：${s.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { vm.startNotionSync(token, parentPageId, forceResync, bidirectional) },
                        enabled = !offline,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("重试同步") }
                }

                else -> Button(
                    onClick = { vm.startNotionSync(token, parentPageId, forceResync, bidirectional) },
                    enabled = !offline,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("同步到 Notion") }
            }
        }
    }

    // 隐藏配置：Token / 容器页 ID / 验证连接
    MotionDialog(visible = configVisible, onDismissRequest = { configVisible = false }) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Notion 配置", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("Integration Token（ntn_…）") },
                singleLine = true, enabled = !isRunning,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = parentPageId, onValueChange = { parentPageId = it },
                label = { Text("容器页 ID（子库挂在它下面）") },
                singleLine = true, enabled = !isRunning,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.verifyNotionConnection(token, parentPageId) }, enabled = !isRunning && !offline) { Text("验证连接") }
                AnimatedVisibility(visible = verify != null, enter = CardEnter, exit = CardExit) {
                    verify?.let {
                        Text(it.message, style = MaterialTheme.typography.bodySmall,
                            color = if (it.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { configVisible = false }) { Text("完成") }
            }
        }
    }

    if (shardSheetVisible) {
        ShardOverviewSheet(
            shards = shards,
            notionCounts = notionCounts,
            counting = notionCounting,
            onRefresh = vm::refreshShardOverview,
            onDismiss = { shardSheetVisible = false },
        )
    }
}

/**
 * 「Notion 分库概览」弹窗：列出已登记/待创建的子库，
 * 展示每个分片的本地条目数与 Notion 侧建库状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShardOverviewSheet(
    shards: List<NotionShard>,
    notionCounts: Map<String, Int>,
    counting: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Sheet 延伸到状态栏下方；状态栏图标由弹窗自己的 Dialog 窗口控制（见下）。
        contentWindowInsets = { WindowInsets(0.dp) },
        dragHandle = null,
    ) {
        // ModalBottomSheet 运行在独立的 Dialog 窗口，状态栏/导航栏图标颜色由该窗口决定，
        // 改主 Activity 的 WindowInsetsController 对弹窗无效。浅色 Sheet 上必须把图标设为
        // 深色，否则白底白字（系统默认浅色图标）会糊成一片看不清。
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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Notion 分库概览", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onRefresh) { Text("刷新") }
            }
            Spacer(Modifier.height(8.dp))
            if (counting || notionCounts.isNotEmpty()) {
                val totalNotion = notionCounts.values.sum()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Notion 端共 $totalNotion 篇",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (counting) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (shards.isEmpty()) {
                Text(
                    "还没有分库记录。同步一次后，按天自动在容器页下创建子数据库。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                // 分库数量可能很多（30+ 天），用 LazyColumn 让列表在固定区域内部滚动，
                // 标题与「关闭」按钮始终固定在可视区域，不会随列表一起滚走。
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(shards) { shard ->
                        val label = shard.title.ifBlank { ShardKeys.labelOf(shard.shardKey) }
                        val built = shard.databaseId.isNotBlank()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                val nCount = notionCounts[shard.shardKey]
                                val showNotion = built && (counting || nCount != null)
                                Text(
                                    buildString {
                                        append("本地 ${shard.itemCount} 篇")
                                        if (showNotion) append(" · Notion ${nCount ?: "…"} 篇")
                                        append(" · ${if (built) "已建库" else "待创建"}")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (built) {
                                Text(
                                    shard.databaseId.take(8) + "…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
        }
    }
}

@Composable
private fun SyncRunningBlock(state: SyncProgress.Running, onCancel: () -> Unit) {
    val fraction = if (state.total > 0) {
        (state.index.toFloat() / state.total).coerceIn(0f, 1f)
    } else 0f
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tweenNormal(),
        label = "sync-progress",
    )
    val animatedSynced by animateIntAsState(state.synced, tweenNormal(), label = "sync-synced")

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("同步进度", style = MaterialTheme.typography.labelLarge)
            Text(
                "${(animatedFraction * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { animatedFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp)),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "已同步 $animatedSynced · 跳过 ${state.skipped} · 失败 ${state.failed}  (${state.index}/${state.total})",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "正在同步：${state.currentTitle.ifBlank { "—" }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) { Text("取消同步") }
    }
}

/** 概览列表里的单行：左侧动作标签（配色区分新建/更新/拉取/冲突），右侧标题 + 来源名 */
@Composable
private fun SyncOverviewRow(report: SyncItemReport) {
    val (label, color) = when (report.action) {
        SyncAction.PUSH_CREATE -> "新建" to MaterialTheme.colorScheme.primary
        SyncAction.PUSH_UPDATE -> "更新" to MaterialTheme.colorScheme.tertiary
        SyncAction.PUSH_PROP_BACKFILL -> "属性补齐" to MaterialTheme.colorScheme.secondary
        SyncAction.PULL -> "拉取" to MaterialTheme.colorScheme.primary
        SyncAction.PULL_CONFLICT -> "冲突覆盖" to MaterialTheme.colorScheme.error
        SyncAction.PULL_CREATE -> "冷启重建" to MaterialTheme.colorScheme.primary
        SyncAction.PULL_DELETE -> "已删(云)" to MaterialTheme.colorScheme.error
        SyncAction.PUSH_DELETE -> "删除同步" to MaterialTheme.colorScheme.error
        SyncAction.PULL_SKIPPED -> "分库跳过" to MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = color.copy(alpha = 0.12f),
            contentColor = color,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                report.title.ifBlank { "（无标题）" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (report.sourceName.isNotBlank()) {
                Text(
                    report.sourceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
