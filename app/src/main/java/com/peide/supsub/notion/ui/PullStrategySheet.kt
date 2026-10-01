package com.peide.supsub.notion.ui
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.peide.supsub.api.PullFilter
import com.peide.supsub.api.PullStrategy
import com.peide.supsub.api.SourceRef
import com.peide.supsub.api.Subscription
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

private const val DAY_SEC = 86_400L

/** 时间范围快捷项。[days] 为 null 表示不限时间；[CUSTOM] 走手填起止日期。 */
private enum class TimeRange(val label: String, val days: Long?) {
    D7("近 7 天", 7),
    D30("近 30 天", 30),
    D90("近 90 天", 90),
    ALL("全部时间", null),
    CUSTOM("自定义", null),
}

/** 主界面上的当前策略摘要，如「订阅源：近 30 天 · 含已读 · 关注点：近 7 天」 */
fun pullStrategySummary(strategy: PullStrategy?, subscriptions: List<Subscription>): String {
    if (strategy == null) return "默认：只拉未读"
    val sub = strategy.sub
    val focus = strategy.focus
    if (sub == null && focus == null) return "默认：只拉未读"
    val parts = mutableListOf<String>()
    if (sub != null) parts += "订阅源：" + filterSummary(sub)
    if (focus != null) parts += "关注点：" + filterSummary(focus)
    return parts.joinToString(" · ")
}

private fun filterSummary(f: PullFilter): String {
    val parts = mutableListOf<String>()
    val types = f.sourceTypes
    parts += when {
        f.sources.isNotEmpty() -> "${f.sources.size} 个来源"
        !types.isNullOrEmpty() -> types.joinToString(" / ") { sourceTypeLabel(it) }
        else -> "全部来源"
    }
    parts += timeRangeLabel(f.fromTimeSec, f.toTimeSec)
    if (!f.unreadOnly) parts += "含已读"
    return parts.joinToString(" · ")
}

private fun timeRangeLabel(fromSec: Long?, toSec: Long?): String {
    val nowSec = System.currentTimeMillis() / 1000
    return when {
        fromSec == null && toSec == null -> "全部时间"
        fromSec != null && toSec == null -> {
            val days = ((nowSec - fromSec).toDouble() / DAY_SEC).roundToInt()
            when (days) {
                in 6..8 -> "近 7 天"
                in 28..32 -> "近 30 天"
                in 88..92 -> "近 90 天"
                else -> if (days <= 0) "今天起" else "近 $days 天"
            }
        }
        else -> "指定时间段"
    }
}

private fun dateFormat(): SimpleDateFormat =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
        isLenient = false
    }

/** "2026-07-01" → 当天 00:00 的 Unix 秒；解析失败返回 null */
private fun parseDateToSec(text: String, endOfDay: Boolean): Long? {
    val t = text.trim()
    if (t.isBlank()) return null
    val date = runCatching { dateFormat().parse(t) }.getOrNull() ?: return null
    val sec = date.time / 1000
    return if (endOfDay) sec + DAY_SEC - 1 else sec
}

private fun formatSecToDate(sec: Long?): String =
    if (sec == null) "" else dateFormat().format(java.util.Date(sec * 1000))

/** 把一份 [PullFilter] 的状态反推回 UI 的初始时间范围选择 */
private fun deriveRange(filter: PullFilter?): TimeRange = when {
    filter == null -> TimeRange.ALL
    filter.fromTimeSec == null && filter.toTimeSec == null -> TimeRange.ALL
    filter.toTimeSec != null || filter.fromTimeSec == null -> TimeRange.CUSTOM
    else -> {
        val days = ((System.currentTimeMillis() / 1000 - filter.fromTimeSec!!).toDouble() / DAY_SEC)
            .roundToInt()
        when (days) {
            in 6..8 -> TimeRange.D7
            in 28..32 -> TimeRange.D30
            in 88..92 -> TimeRange.D90
            else -> TimeRange.CUSTOM
        }
    }
}

/**
 * 由 UI 状态构造 [PullFilter]；若与「默认（不限时间 + 只拉未读 + 无来源选择）」完全一致则返回 null。
 * [chosen] 为选中的订阅源（关注点分区传空列表，关注点无逐源多选）。
 */
private fun buildFilter(
    range: TimeRange,
    fromText: String,
    toText: String,
    includeRead: Boolean,
    chosen: List<Subscription>,
): PullFilter? {
    val nowSec = System.currentTimeMillis() / 1000
    val fromSec: Long?
    val toSec: Long?
    when (range) {
        TimeRange.ALL -> {
            fromSec = null
            toSec = null
        }
        TimeRange.CUSTOM -> {
            fromSec = parseDateToSec(fromText, false)
            toSec = parseDateToSec(toText, true)
        }
        else -> {
            fromSec = nowSec - (range.days ?: 0) * DAY_SEC
            toSec = null
        }
    }
    // 默认（不限时间 + 只拉未读 + 无来源选择）时返回 null，表示走默认「只拉未读」
    if (fromSec == null && toSec == null && !includeRead && chosen.isEmpty()) return null
    return PullFilter(
        sourceTypes = chosen.map { it.sourceType }.toSet().ifEmpty { null },
        sources = chosen.map { SourceRef(it.sourceType, it.sourceId) }.toSet(),
        fromTimeSec = fromSec,
        toTimeSec = toSec,
        unreadOnly = !includeRead,
    )
}

/**
 * 需求 3（升级）：拉取策略选择器，订阅源与关注点各自独立设置。
 *
 * 每个分区可单独指定：时间范围（快捷 + 自定义日期）+ 是否「包含已读」；订阅源分区额外支持来源多选。
 * 确认后写入 [PullStrategy]（sub / focus 两个独立 [PullFilter]）；重置回到默认「只拉未读」。
 * 注意后端契约用的是 **Unix 秒**（fromTimeSec / toTimeSec），不是毫秒。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PullStrategySheet(
    visible: Boolean,
    subscriptions: List<Subscription>,
    includeFocus: Boolean,
    current: PullStrategy?,
    onConfirm: (PullStrategy?) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    /** 内部用「类型-id」复合键选中，避免不同类型下相同 sourceId 互相串选 */
    fun keyOf(s: Subscription) = "${s.sourceType}-${s.sourceId}"

    // 订阅源分区初始状态
    val subInit = remember(current) { deriveRange(current?.sub) }
    var subRange by remember(current) { mutableStateOf(subInit) }
    var subFromText by remember(current) { mutableStateOf(formatSecToDate(current?.sub?.fromTimeSec)) }
    var subToText by remember(current) { mutableStateOf(formatSecToDate(current?.sub?.toTimeSec)) }
    var subIncludeRead by remember(current) { mutableStateOf(current?.sub?.unreadOnly == false) }
    var subSelected by remember(current) {
        mutableStateOf(
            current?.sub?.sources?.let { set ->
                subscriptions.filter { SourceRef(it.sourceType, it.sourceId) in set }.map { keyOf(it) }.toSet()
            } ?: emptySet(),
        )
    }

    // 关注点分区初始状态
    val focusInit = remember(current) { deriveRange(current?.focus) }
    var focusRange by remember(current) { mutableStateOf(focusInit) }
    var focusFromText by remember(current) { mutableStateOf(formatSecToDate(current?.focus?.fromTimeSec)) }
    var focusToText by remember(current) { mutableStateOf(formatSecToDate(current?.focus?.toTimeSec)) }
    var focusIncludeRead by remember(current) { mutableStateOf(current?.focus?.unreadOnly == false) }

    val subDateInvalid = subRange == TimeRange.CUSTOM &&
        ((subFromText.isNotBlank() && parseDateToSec(subFromText, false) == null) ||
            (subToText.isNotBlank() && parseDateToSec(subToText, true) == null))
    val focusDateInvalid = focusRange == TimeRange.CUSTOM &&
        ((focusFromText.isNotBlank() && parseDateToSec(focusFromText, false) == null) ||
            (focusToText.isNotBlank() && parseDateToSec(focusToText, true) == null))
    val dateInvalid = subDateInvalid || focusDateInvalid

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
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
                .alpha(contentAlpha)
                .statusBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("拉取策略", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "订阅源与关注点可分别设置时间范围和「包含已读」。默认两者都只拉未读。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 订阅源可能上百个：中间区域滚动，底部操作按钮常驻可见
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                item {
                    Spacer(Modifier.height(20.dp))
                    SectionTitle("订阅源")
                    Spacer(Modifier.height(8.dp))
                    TimeRangePicker(
                        range = subRange,
                        fromText = subFromText,
                        toText = subToText,
                        dateInvalid = subDateInvalid,
                        onRange = { subRange = it },
                        onFrom = { subFromText = it },
                        onTo = { subToText = it },
                    )
                }

                item {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("包含已读内容", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "按时间范围回溯历史时建议开启，否则只会拉到未读条目。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = subIncludeRead, onCheckedChange = { subIncludeRead = it })
                    }
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("订阅源", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (subSelected.isEmpty()) {
                                    "未选择 = 全部已启用来源"
                                } else {
                                    "已选 ${subSelected.size} 个"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { subSelected = subscriptions.map { keyOf(it) }.toSet() }) {
                            Text("全选")
                        }
                        TextButton(onClick = { subSelected = emptySet() }) { Text("清空") }
                    }
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                }

                if (subscriptions.isEmpty()) {
                    item {
                        Text(
                            "暂无订阅源，请先刷新账号信息。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                } else {
                    items(subscriptions, key = { keyOf(it) }) { sub ->
                        val key = keyOf(sub)
                        val checked = subSelected.contains(key)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    subSelected = if (checked) subSelected - key else subSelected + key
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    sub.name.ifBlank { "未命名来源" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    sourceTypeLabel(sub.sourceType),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // ── 关注点分区 ──
                item {
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))
                    if (includeFocus) {
                        SectionTitle("关注点")
                        Spacer(Modifier.height(8.dp))
                        TimeRangePicker(
                            range = focusRange,
                            fromText = focusFromText,
                            toText = focusToText,
                            dateInvalid = focusDateInvalid,
                            onRange = { focusRange = it },
                            onFrom = { focusFromText = it },
                            onTo = { focusToText = it },
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text("包含已读内容", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "关注点底层全量拉取，开启后本地也会保留已读条目。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = focusIncludeRead, onCheckedChange = { focusIncludeRead = it })
                        }
                    } else {
                        Text(
                            "关注点：当前未在「拉取设置」中开启，开启后可在本策略中单独设置其时间与已读范围。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        onReset()
                        close()
                    },
                ) { Text("恢复默认") }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = !dateInvalid,
                    onClick = {
                        val subFilter = buildFilter(
                            subRange, subFromText, subToText, subIncludeRead,
                            subscriptions.filter { subSelected.contains(keyOf(it)) },
                        )
                        // 未开启关注点时忽略其分区设置
                        val focusFilter = if (includeFocus) {
                            buildFilter(focusRange, focusFromText, focusToText, focusIncludeRead, emptyList())
                        } else {
                            null
                        }
                        val strategy = if (subFilter == null && focusFilter == null) {
                            null
                        } else {
                            PullStrategy(sub = subFilter, focus = focusFilter)
                        }
                        onConfirm(strategy)
                        close()
                    },
                ) { Text("应用策略") }
            }
        }
    }
}

/** 时间范围快捷 + 自定义日期的一小段可复用 UI（订阅源 / 关注点分区共用） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimeRangePicker(
    range: TimeRange,
    fromText: String,
    toText: String,
    dateInvalid: Boolean,
    onRange: (TimeRange) -> Unit,
    onFrom: (String) -> Unit,
    onTo: (String) -> Unit,
) {
    Text("时间范围", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TimeRange.entries.forEach { r ->
            FilterChip(
                selected = range == r,
                onClick = { onRange(r) },
                label = { Text(r.label) },
            )
        }
    }
    AnimatedVisibility(
        visible = range == TimeRange.CUSTOM,
        enter = CardEnter,
        exit = CardExit,
    ) {
        Column {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = fromText,
                    onValueChange = onFrom,
                    label = { Text("开始日期") },
                    placeholder = { Text("2026-07-01") },
                    singleLine = true,
                    isError = fromText.isNotBlank() && parseDateToSec(fromText, false) == null,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = toText,
                    onValueChange = onTo,
                    label = { Text("结束日期") },
                    placeholder = { Text("留空至今") },
                    singleLine = true,
                    isError = toText.isNotBlank() && parseDateToSec(toText, true) == null,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "格式 yyyy-MM-dd，留空表示不限。",
                style = MaterialTheme.typography.bodySmall,
                color = if (dateInvalid) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** 分区小标题（订阅源 / 关注点） */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** 主界面上的策略摘要行：点击打开策略面板 */
@Composable
fun PullStrategyRow(
    summary: String,
    isCustom: Boolean,
    onEdit: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
            )
            .clickable(onClick = onEdit)
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "当前策略",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AnimatedVisibility(visible = isCustom, enter = CardEnter, exit = CardExit) {
            TextButton(onClick = onReset) { Text("重置") }
        }
        TextButton(onClick = onEdit) { Text("调整") }
    }
}
