package com.peide.supsub.notion.ui
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.peide.supsub.data.ArchiveStats
import com.peide.supsub.data.OriginCategory

/**
 * 需求 2：本地归档校验状态。
 *
 * ViewModel 在启动 / 拉取完 / 同步完都会自动重算 [ArchiveStats]，这里把它可视化：
 * 四个核心指标 + 来源分类分布 + 一致性告警 + 兼容模式说明，并支持手动重新校验。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArchiveStatusCard(
    stats: ArchiveStats?,
    hint: String?,
    validating: Boolean = false,
    onRevalidate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val inconsistent = stats != null && stats.inconsistent > 0

    // 出现不一致时，整张卡片底色渐变到警告色，比单行红字更容易被注意到
    val containerColor by animateColorAsState(
        targetValue = if (inconsistent) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        animationSpec = tweenSlow(),
        label = "archive-container",
    )

    SectionCard(
        title = "本地归档",
        modifier = modifier,
        subtitle = if (stats == null) null else "每次启动 / 拉取 / 同步后自动校验",
        containerColor = containerColor,
        trailing = {
            TextButton(onClick = onRevalidate, enabled = !validating) { Text("重新校验") }
        },
    ) {
        AnimatedContent(
            targetState = stats,
            transitionSpec = { statusTransform() },
            contentKey = { it == null },
            label = "archive-stats",
        ) { s ->
            if (s == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(16.dp).width(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "尚未选择归档目录，或正在校验中。完成一次拉取后这里会显示本地归档统计。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        MetricCell("总数", s.total, modifier = Modifier.weight(1f))
                        MetricCell("待同步", s.pendingSync, modifier = Modifier.weight(1f), emphasize = true)
                        MetricCell("待重推", s.pendingProp, modifier = Modifier.weight(1f), emphasize = true)
                        MetricCell("同步失败", s.syncFailed, modifier = Modifier.weight(1f), warn = true)
                    }

                    Spacer(Modifier.height(14.dp))
                    OriginDistribution(byOrigin = s.byOrigin, bySourceType = s.bySourceType)

                    // 索引与实际文件对不上：给出明确的警告与处置建议
                    AnimatedVisibility(visible = s.inconsistent > 0, enter = BannerEnter, exit = BannerExit) {
                        Column {
                            Spacer(Modifier.height(12.dp))
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ) {
                                Text(
                                    "索引与归档文件不一致 ${s.inconsistent} 条，建议重新校验以重建索引",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }

                    // 兼容模式：Room 索引不可用，退化为逐个扫描 JSON
                    AnimatedVisibility(visible = s.source == "JSON_SCAN", enter = BannerEnter, exit = BannerExit) {
                        Column {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "当前设备使用兼容模式索引（扫描 JSON 文件），统计与查询会慢一些，结果仍然准确。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (!hint.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        TextButton(
                            onClick = { expanded = !expanded },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        ) {
                            Text(
                                if (expanded) "收起校验详情" else "查看校验详情",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        AnimatedVisibility(visible = expanded, enter = CardEnter, exit = CardExit) {
                            Text(
                                hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        // 重新校验进行中：在统计下方显示进度提示（保留旧统计，不闪烁）
        AnimatedVisibility(visible = validating, enter = CardEnter, exit = CardExit) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.height(16.dp).width(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "正在校验本地归档…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 来源分类（订阅源 / 关注点 / 网页集）+ 来源类型（公众号 / 网站 / X）分布 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OriginDistribution(
    byOrigin: Map<String, Int>,
    bySourceType: Map<String, Int>,
) {
    if (byOrigin.isEmpty() && bySourceType.isEmpty()) return
    Column {
        if (byOrigin.isNotEmpty()) {
            Text(
                "来源分类",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                byOrigin.forEach { (key, count) ->
                    StatPill("${OriginCategory.fromKey(key).label} $count")
                }
            }
        }
        if (bySourceType.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "来源类型",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                bySourceType.forEach { (key, count) ->
                    StatPill("${sourceTypeLabel(key)} $count")
                }
            }
        }
    }
}
