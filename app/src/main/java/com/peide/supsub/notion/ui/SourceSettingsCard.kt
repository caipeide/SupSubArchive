package com.peide.supsub.notion.ui
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 中部：内容来源设置（哪些渠道参与拉取） */
@Composable
fun SourceSettingsCard(
    includeFocus: Boolean,
    enabledSourceTypes: Set<String>,
    subscriptionCountByType: Map<String, Int>,
    /** 各订阅源类型的云端未读合计（红框区域「云端未读 X」展示） */
    unreadByType: Map<String, Int> = emptyMap(),
    /** 关注点精确云端未读合计（红框区域「云端未读 X 篇」展示） */
    focusCloudUnread: Int = 0,
    /** 本地已归档的关注点文章数（与服务页「来源分类·关注点」一致，真实准确） */
    localFocusArchived: Int,
    onToggleIncludeFocus: (Boolean) -> Unit,
    onToggleSourceType: (String, Boolean) -> Unit,
    onProbe: () -> Unit,
    probing: Boolean = false,
    /** 计数正在重新统计（拉取完成 / 勾选变化后触发），用于提示数字尚未定稿 */
    countsRefreshing: Boolean = false,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = "内容来源",
        modifier = modifier,
        subtitle = "选择参与拉取的渠道",
    ) {
        FocusSwitchRow(
            includeFocus = includeFocus,
            localFocusArchived = localFocusArchived,
            focusCloudUnread = focusCloudUnread,
            onToggleIncludeFocus = onToggleIncludeFocus,
        )

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("订阅源类型", style = MaterialTheme.typography.bodyMedium)
            // 重新统计时明说「统计中…」：数字刷新要等逐源请求跑完，
            // 不给提示的话用户会把「还没算完的旧值」当成「算完就是这个值」
            if (countsRefreshing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(12.dp).width(12.dp),
                        strokeWidth = 1.5.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "统计中…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                Text(
                    "源数",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        listOf("MP", "WEBSITE", "X").forEach { type ->
            SourceTypeRow(
                type = type,
                checked = enabledSourceTypes.contains(type),
                sourceCount = subscriptionCountByType[type] ?: 0,
                cloudUnread = unreadByType[type] ?: 0,
                onCheckedChange = { onToggleSourceType(type, it) },
            )
        }

        Spacer(Modifier.height(4.dp))
        if (probing) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.height(16.dp).width(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "正在测试拉取订阅源…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            TextButton(onClick = onProbe) { Text("测试拉取订阅源") }
        }
    }
}

/** 单行来源类型。源数与云端未读随后台统计刷新，用动画过渡避免数字跳变 */
@Composable
private fun SourceTypeRow(
    type: String,
    checked: Boolean,
    sourceCount: Int,
    cloudUnread: Int,
    onCheckedChange: (Boolean) -> Unit,
) {
    val animatedCount by animateIntAsState(sourceCount, tweenNormal(), label = "count-$type")
    val animatedUnread by animateIntAsState(cloudUnread, tweenNormal(), label = "unread-$type")

    val label = when (type) {
        "MP" -> "公众号 (MP)"
        "WEBSITE" -> "网站 (WEBSITE)"
        "X" -> "X (Twitter)"
        else -> type
    }
    CheckboxRow(
        label = label,
        checked = checked,
        trailingPrimary = "云端未读 $animatedUnread",
        trailingSecondary = "源 $animatedCount",
        onCheckedChange = onCheckedChange,
    )
}

/**
 * 关注点开关行：在「已归档关注点 X 篇」之后追加主色高亮的「云端未读 Y 篇」，
 * 让用户一眼区分「本地攒了多少」与「服务端还剩多少没读」。
 */
@Composable
private fun FocusSwitchRow(
    includeFocus: Boolean,
    localFocusArchived: Int,
    focusCloudUnread: Int,
    onToggleIncludeFocus: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text("包含关注点内容", style = MaterialTheme.typography.bodyLarge)
            Text(
                "关闭时只拉订阅源（网站 / 公众号 / X）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已归档关注点 $localFocusArchived 篇 · ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "云端未读 $focusCloudUnread 篇",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Switch(checked = includeFocus, onCheckedChange = onToggleIncludeFocus)
    }
}
