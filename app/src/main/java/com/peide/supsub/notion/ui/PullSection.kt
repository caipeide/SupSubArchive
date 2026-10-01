package com.peide.supsub.notion.ui
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.peide.supsub.api.PullTargetKind

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.peide.supsub.notion.vm.BackupUiState
import com.peide.supsub.notion.vm.FocusMarkUiState
import com.peide.supsub.notion.vm.PullUiState
import com.peide.supsub.notion.vm.SourceMarkUiState

/** SAF 树 URI → 「Documents/SupSubArchive」这样的可读路径 */
fun prettyDir(uri: Uri?): String {
    if (uri == null) return "未选择"
    val seg = uri.lastPathSegment ?: return uri.toString()
    return seg.substringAfterLast(':').ifBlank { seg }
}

/**
 * 单行「标签 + 数值」行：左侧标签占满剩余宽度，文字过长时自动横向滚动（跑马灯）；
 * 右侧数值（如进度 / 成功数）固定右对齐。用于标记已读徽章的主信息行。
 */
@Composable
private fun MarkBadgeRow(
    title: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .basicMarquee(),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = valueColor,
        )
    }
}

/**
 * 底部：拉取操作区。
 *
 * - 「拉取更新」：按当前策略把云端内容拉到本地归档（只更新本地，不动云端未读）。
 * - 「标记已读（云端）」：对本次拉取涉及的订阅源 / 关注点，在 supsub 云端整源 / 整点标记已读。
 *   两个动作相互独立，进度分两条提示展示（订阅源整源标读 + 关注点整点标读）。
 */
@Composable
fun PullActionCard(
    pullState: PullUiState,
    exportDir: Uri?,
    backupState: BackupUiState,
    strategySummary: String,
    focusMarkState: FocusMarkUiState = FocusMarkUiState.Idle,
    sourceMarkState: SourceMarkUiState = SourceMarkUiState.Idle,
    markReadScopeHint: String = "",
    offline: Boolean = false,
    onPull: () -> Unit,
    onMarkRead: () -> Unit,
    onCancelPull: () -> Unit,
    onChangeDir: () -> Unit,
    onBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = pullState is PullUiState.Running

    SectionCard(
        title = "拉取与归档",
        modifier = modifier,
        subtitle = "备份目录：${prettyDir(exportDir)}（正文已写入应用私有目录的 SQLite 单库）",
        trailing = {
            TextButton(onClick = onChangeDir, enabled = !running) { Text("设置备份目录") }
        },
    ) {
        AnimatedContent(
            targetState = pullState,
            transitionSpec = { statusTransform() },
            contentKey = {
                when (it) {
                    is PullUiState.Idle -> 0
                    is PullUiState.RequestingDir -> 1
                    is PullUiState.Running -> 2
                    is PullUiState.Done -> 3
                    is PullUiState.Error -> 4
                }
            },
            label = "pull-state",
            modifier = Modifier.fillMaxWidth(),
        ) { s ->
            when (s) {
                is PullUiState.Idle -> Text(
                    "将按「$strategySummary」拉取内容并写入本地归档。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is PullUiState.RequestingDir -> Text(
                    "请在系统窗口中选择归档目录…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is PullUiState.Running -> PullRunningBlock(s, onCancelPull)

                is PullUiState.Done -> PullDoneBlock(s)

                is PullUiState.Error -> Text(
                    "拉取失败：${s.message}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // 标记已读进度：订阅源整源 / 关注点整点，两条独立提示，互不阻塞
        AnimatedVisibility(visible = sourceMarkState !is SourceMarkUiState.Idle, enter = CardEnter, exit = CardExit) {
            Spacer(Modifier.height(14.dp))
            SourceMarkBadge(sourceMarkState)
        }
        AnimatedVisibility(visible = focusMarkState !is FocusMarkUiState.Idle, enter = CardEnter, exit = CardExit) {
            Spacer(Modifier.height(14.dp))
            FocusMarkBadge(focusMarkState)
        }

        AnimatedVisibility(visible = !running, enter = CardEnter, exit = CardExit) {
            Column {
                Spacer(Modifier.height(16.dp))
                if (offline) {
                    Text(
                        "离线模式：拉取与云端标记已读需联网后使用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Button(
                    onClick = onPull,
                    enabled = !offline,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("拉取更新") }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onMarkRead,
                    enabled = !offline && sourceMarkState !is SourceMarkUiState.Running && focusMarkState !is FocusMarkUiState.Running,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("标记已读（云端）") }
                if (markReadScopeHint.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = markReadScopeHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onBackup,
                    enabled = backupState !is BackupUiState.Running,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("导出备份 (.db)") }
                when (val bs = backupState) {
                    is BackupUiState.Running ->
                        Text("备份中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is BackupUiState.Done ->
                        Text("已备份：${bs.fileName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    is BackupUiState.Error ->
                        Text(bs.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun PullRunningBlock(state: PullUiState.Running, onCancelPull: () -> Unit) {
    // 订阅源 / 关注点两条独立进度：用「已完成数 / 总数」为准（单调递增且可信）。
    // 用「已拉取篇数 / 预计新增」会因大量重复内容被扫描而瞬间冲到 100%。
    val subFrac = if (state.subTotal > 0) {
        (state.subIndex.toFloat() / state.subTotal).coerceIn(0f, 1f)
    } else 0f
    val focusFrac = if (state.focusTotal > 0) {
        (state.focusIndex.toFloat() / state.focusTotal).coerceIn(0f, 1f)
    } else 0f
    val animatedSub by animateFloatAsState(subFrac, tweenNormal(), label = "pull-sub")
    val animatedFocus by animateFloatAsState(focusFrac, tweenNormal(), label = "pull-focus")
    val animatedNew by animateIntAsState(state.newArticles, tweenNormal(), label = "pull-new")
    val animatedFetched by animateIntAsState(state.fetchedArticles, tweenNormal(), label = "pull-fetched")

    Column(modifier = Modifier.fillMaxWidth()) {
        PullProgressBar(
            label = "订阅源拉取",
            frac = animatedSub,
            text = "${state.subIndex}/${state.subTotal}",
            active = state.kind == PullTargetKind.SUB,
        )
        Spacer(Modifier.height(10.dp))
        PullProgressBar(
            label = "关注点拉取",
            frac = animatedFocus,
            text = "${state.focusIndex}/${state.focusTotal}",
            active = state.kind == PullTargetKind.FOCUS,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "新增 $animatedNew 篇 · 已扫描 $animatedFetched 篇",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "正在拉取：${state.currentTitle.ifBlank { "—" }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onCancelPull,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) { Text("取消拉取") }
    }
}

/**
 * 拉取完成后的统计块：按「通道」拆分展示本轮新增，
 * 订阅源额外给出「原始 / 去重后」两档，便于核对抓取量与去重效果。
 */
@Composable
private fun PullDoneBlock(state: PullUiState.Done) {
    var reportExpanded by remember { mutableStateOf(false) }
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                "本次拉取完成",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))

            // 部分成功告警：个别订阅源/关注点拉取失败时给出降级提示，其余数据照常展示
            if (state.warnings.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                        Text(
                            "部分目标拉取失败（其余已成功）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        state.warnings.forEach { w ->
                            Text(
                                "· $w",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            InfoRow("订阅源新增", "${state.subNew} 篇")
            InfoRow("关注点原始拉取", "${state.focusRaw} 篇")
            InfoRow("关注点去重后新增", "${state.focusNew} 篇")
            InfoRow("合计新增", "${state.newOrUpdated} 篇")
            Spacer(Modifier.height(6.dp))
            Text(
                "订阅源 ${state.subscriptions} 个 · 关注点 ${state.pulledFocusIds.size} 个",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 逐关注点汇总：每个关注点给出 原始/新增/丢弃 三档，便于核对抓取与去重效果
            if (state.focusSummaries.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "逐关注点",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                state.focusSummaries.forEach { fs ->
                    FocusSummaryRow(fs)
                }
            }

            // 去重报告：被 URL/内容去重丢弃的明细，可展开查看具体是哪篇、属于哪个关注点、为何被丢弃
            val dropped = state.dedupReport
            if (dropped.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { reportExpanded = !reportExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "去重报告（${dropped.size} 篇被丢弃）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        if (reportExpanded) "收起 ▲" else "展开 ▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(reportExpanded) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .padding(top = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(dropped) { d -> DroppedItemRow(d) }
                    }
                }
            }
        }
    }
}

/** 单关注点汇总行：标题 + 「原始 / 新增 / 丢弃」三档计数。 */
@Composable
private fun FocusSummaryRow(fs: com.peide.supsub.api.FocusPullSummary) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            fs.focusTitle.ifBlank { "关注点#${fs.focusId}" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "原始 ${fs.raw} · 新增 ${fs.newCount} · 丢弃 ${fs.dropped}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 单条被丢弃项：标题 + 丢弃原因 + 归属关注点/链接。 */
@Composable
private fun DroppedItemRow(d: com.peide.supsub.api.DroppedItem) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                d.title.ifBlank { "(无标题)" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                d.reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            "${d.focusTitle.ifBlank { "关注点#${d.focusId}" }} · ${if (d.url.isNotBlank()) d.url else d.contentId}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PullProgressBar(
    label: String,
    frac: Float,
    text: String,
    active: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(6.dp))
    LinearProgressIndicator(
        progress = { frac },
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp)),
    )
}

/**
 * 「关注点整点云端标记已读」独立进度提示（点「标记已读（云端）」后由后台异步执行）。
 *
 * - Running：显示「云端标记已读 N/total」+ 进度条；
 * - Done：显示最终结果，成功/失败分别着色（有失败标为警告色）；
 * - Error：显示错误文案。
 * 该提示独立于主拉取进度条，限流时后台慢慢跑也不会卡 UI。
 */
@Composable
private fun FocusMarkBadge(state: FocusMarkUiState) {
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            when (val s = state) {
                is FocusMarkUiState.Idle -> {}
                is FocusMarkUiState.Running -> {
                    val frac = if (s.total > 0) {
                        ((s.marked + s.failed).toFloat() / s.total).coerceIn(0f, 1f)
                    } else 0f
                    val animatedFrac by animateFloatAsState(frac, tweenNormal(), label = "focus-mark-frac")
                    MarkBadgeRow(
                        title = "云端标记已读（关注点整点）",
                        value = "${s.marked + s.failed}/${s.total}",
                        valueColor = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { animatedFrac },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                    if (s.failed > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "已成功 ${s.marked} · 失败 ${s.failed}（多为限流，后台自动重试）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is FocusMarkUiState.Done -> {
                    val ok = s.failed == 0
                    MarkBadgeRow(
                        title = "关注点整点 · 云端标记已读完成",
                        value = "成功 ${s.marked}/${s.total}" + if (s.failed > 0) " · 失败 ${s.failed}" else "",
                        valueColor = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    if (s.failed > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "失败项多因服务端限流，可稍后重拉一次补齐。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is FocusMarkUiState.Error -> {
                    Text(
                        "关注点整点标记已读失败：${s.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * 「订阅源整源标读」独立进度提示（点「标记已读（云端）」后由后台异步执行）。
 *
 * - Running：显示「云端标记已读 N/total」+ 进度条；
 * - Done：显示最终结果，成功/失败分别着色（有失败标为警告色）；
 * - Error：显示错误文案。
 * 该提示独立于主拉取进度条与关注点标读提示，限流时后台慢慢跑也不会卡 UI。
 */
@Composable
private fun SourceMarkBadge(state: SourceMarkUiState) {
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            when (val s = state) {
                is SourceMarkUiState.Idle -> {}
                is SourceMarkUiState.Running -> {
                    val frac = if (s.total > 0) {
                        ((s.marked + s.failed).toFloat() / s.total).coerceIn(0f, 1f)
                    } else 0f
                    val animatedFrac by animateFloatAsState(frac, tweenNormal(), label = "source-mark-frac")
                    MarkBadgeRow(
                        title = "云端标记已读（订阅源整源）",
                        value = "${s.marked + s.failed}/${s.total}",
                        valueColor = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { animatedFrac },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                    if (s.failed > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "已成功 ${s.marked} · 失败 ${s.failed}（多为限流，后台自动重试）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is SourceMarkUiState.Done -> {
                    val ok = s.failed == 0
                    MarkBadgeRow(
                        title = "订阅源整源 · 云端标记已读完成",
                        value = "成功 ${s.marked}/${s.total}" + if (s.failed > 0) " · 失败 ${s.failed}" else "",
                        valueColor = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    if (s.failed > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "失败项多因服务端限流，可稍后重拉一次补齐。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is SourceMarkUiState.Error -> {
                    Text(
                        "订阅源整源标记已读失败：${s.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

