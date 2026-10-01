package com.peide.supsub.data

/**
 * 内容的「来源分类」。
 *
 * 与 [ArticleRecord.sourceType]（MP / WEBSITE / X 这类**具体来源类型**）不同，
 * originCategory 描述的是「这条内容是从哪个**拉取通道**进来的」：
 *
 *  - [SUBSCRIPTION] 来自 /api/subscriptions 的订阅源（公众号 / 网站 / X）
 *  - [FOCUS]        来自 /api/focuses 的关注点聚合内容（跨源）
 *  - [WEBSET]       来自后续的「网页集（webset）」能力（目前服务端 API 尚未开放，预留）
 *
 * Notion 知识库需要同时区分这两层：
 *  「来源类型」(MP/WEBSITE/X) 用彩色 select 标记渠道细节；
 *  「来源分类」(订阅源/关注点/网页集) 用另一个彩色 select 标记拉取通道，便于后续按关注点 / webset 检索。
 */
enum class OriginCategory(val key: String, val label: String) {
    SUBSCRIPTION("SUBSCRIPTION", "订阅源"),
    FOCUS("FOCUS", "关注点"),
    WEBSET("WEBSET", "网页集");

    companion object {
        /**
         * 把「枚举 key（SUBSCRIPTION/FOCUS/WEBSET，大小写不敏感）」或「中文 label（订阅源/关注点/网页集）」
         * 都解析回枚举。早期双向同步把 Notion select 的中文 label 直接写回了 origin_category 列，
         * 必须能双向解析，否则中文 label 会被错误 fallthrough 到 SUBSCRIPTION，把关注点也归并掉。
         */
        fun fromKey(key: String?): OriginCategory {
            if (key.isNullOrBlank()) return SUBSCRIPTION
            val k = key.trim()
            return values().firstOrNull { it.key.equals(k, ignoreCase = true) }
                ?: values().firstOrNull { it.label == k }
                ?: SUBSCRIPTION
        }
    }
}
