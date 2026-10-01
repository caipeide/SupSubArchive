package com.peide.supsub.data

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 按天分片键工具。
 *
 * 云端 Notion 不再用「一个大数据库装所有文章」，而是**按天切一个子数据库**：
 *  - 键形如 `YYYY-MM-DD`（发布当天），如 `2026-08-05`。
 *
 * 为什么按天而不用半个月：按当前每天近百篇的量级，单天约 100 条，
 * 正好落在 Notion 单库「筛选/排序仍然顺滑」的舒适区内；按月则接近 3000 条会明显变卡，
 * 按周又会让库的数量偏多、难以浏览。按天既避免单库过大，又把库数量控制在一年 365 个以内、
 * 可按日期清晰定位。
 *
 * 分片键**以发布时间为准**（而不是入库时间），这样补拉历史文章时会自动归入它本该在的那一档，
 * 不会因为「今天才拉到」而堆进当前分片。发布时间缺失（0）时才退回入库时间。
 */
object ShardKeys {

    /**
     * 计算按天分片键。
     *
     * @param publishedAtSec 发布时间（Unix **秒**，0/负数表示未知）
     * @param capturedAtMs   入库时间（Unix **毫秒**），发布时间未知时的兜底
     * @return 形如 `2026-08-05`
     */
    fun of(publishedAtSec: Long, capturedAtMs: Long = System.currentTimeMillis()): String {
        val epochMs = if (publishedAtSec > 0) publishedAtSec * 1000 else capturedAtMs
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = epochMs
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
    }

    /**
     * 分片键 → Notion 子数据库标题。
     *
     * 标题同时是**去重依据**：路由在容器页里按标题匹配已存在的子库，
     * 因此这个函数的输出必须稳定，改动它等于让旧库失联、重新建库。
     */
    fun titleOf(shardKey: String): String {
        val parts = shardKey.split("-")
        if (parts.size != 3) return "订阅归档 $shardKey"
        val (y, m, d) = parts
        return "订阅归档 $y-$m-$d"
    }

    /** 分片键 → 人类可读短标签（UI 分库概览用），如 `2026-08-05` */
    fun labelOf(shardKey: String): String {
        val parts = shardKey.split("-")
        if (parts.size != 3) return shardKey
        val (y, m, d) = parts
        return "$y-$m-$d"
    }

    /**
     * Notion 子数据库标题 → 分片键（[titleOf] 的逆运算）。
     *
     * 冷启动 / 跨设备时，平板端从未推送过本地文章、[notion_shards] 为空，
     * 需要从容器页已存在的子库标题反推 shardKey 登记进本地表，拉取阶段才能枚举到这些分库。
     */
    fun fromTitle(title: String): String {
        val prefix = "订阅归档 "
        val rest = if (title.startsWith(prefix)) title.removePrefix(prefix) else title
        // 校验形如 YYYY-MM-DD（年 4 位、月日 1-2 位）
        val parts = rest.split("-")
        if (parts.size == 3) {
            val (y, m, d) = parts
            val yi = y.toIntOrNull(); val mi = m.toIntOrNull(); val di = d.toIntOrNull()
            if (yi != null && mi != null && di != null && y.length == 4 &&
                mi in 1..12 && di in 1..31
            ) {
                return String.format(Locale.US, "%04d-%02d-%02d", yi, mi, di)
            }
        }
        return rest
    }
}
