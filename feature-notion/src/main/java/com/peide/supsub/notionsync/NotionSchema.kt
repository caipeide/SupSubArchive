package com.peide.supsub.notionsync

import org.json.JSONArray
import org.json.JSONObject

/**
 * Notion 归档库的列定义（建库与写入共用同一份真相）。
 *
 * 拆出来的原因：按天分库后「建库」和「写页面」不再是同一个人的事——
 * [NotionShardRouter] 负责建库、[SupSubSyncEngine] 负责写页面，
 * 两边的列名必须严格一致，否则建出来的库和写入时匹配的列对不上，
 * 结果就是页面只有标题、结构化属性全空。
 *
 * 列名采用「候选名列表」：中文模板名优先，附英文别名。
 * 写入时按候选名顺序找**已存在且类型匹配**的列，找不到就跳过该字段（不影响正文），
 * 这样用户手动改过列名的旧库也能继续用。
 */
object NotionSchema {

    // ─── 基础列 ───
    val PROP_SOURCE = listOf("来源", "来源App", "Source", "Source App", "App")
    val PROP_SOURCE_TYPE = listOf("来源类型", "分类", "类别", "Category", "Type")

    /** 来源分类（订阅源 / 关注点 / 网页集）：与「来源类型」(MP/WEBSITE/X) 区分，按拉取通道聚合 */
    val PROP_ORIGIN_CATEGORY = listOf("来源分类", "拉取通道", "OriginCategory", "Origin")
    /** 关注点：文章归属的关注点名称（仅 FOCUS 通道有值）。select 列，便于在 Notion 里按关注点分组/筛选 */
    val PROP_FOCUS = listOf("关注点", "Focus", "Topic Focus")
    val PROP_CAPTURED_AT = listOf("采集时间", "发布时间", "时间", "日期", "Date", "Created")
    val PROP_TAGS = listOf("标签", "Tags")
    val PROP_DEEPLINK = listOf("原文链接", "链接", "URL", "Link")

    // ─── 关键词（实体词，比 tags 更细粒度）───
    val PROP_KEYWORDS = listOf("关键词", "Keywords", "实体词", "Entities")

    // ─── AI 分析维度：4 个 select + 2 个 checkbox + 1 个 rich_text ───
    val PROP_CONTENT_TYPE = listOf("内容类型", "内容分类", "ContentType", "Type")
    val PROP_CONTENT_DEPTH = listOf("内容深度", "深度", "ContentDepth", "Depth")
    val PROP_TONE = listOf("语气", "Tone")
    val PROP_STYLE = listOf("体裁", "风格", "Style")
    val PROP_HAS_ACTION = listOf("含行动建议", "有行动建议", "HasAction", "Actionable")
    val PROP_HAS_STANCE = listOf("含观点立场", "有观点", "HasStance")
    val PROP_STANCE_SUMMARY = listOf("立场摘要", "观点摘要", "StanceSummary")

    /** 新建库时的标题列名（Notion 要求每个数据库有且只有一个 title 列） */
    const val TITLE_PROP = "Name"

    // ─── 聚类（本地相似文章归并，同步后写入）───
    /** 聚类主题：同簇文章共享一个紧凑标签，可在 Notion 里按主题分组 / 筛选 / 排序 */
    val PROP_CLUSTER_TOPIC = listOf("聚类主题", "ClusterTopic", "Topic", "聚类")
    /** 相似篇数：该文章所在簇的成员数（>1 才有意义） */
    val PROP_CLUSTER_COUNT = listOf("相似篇数", "RelatedCount", "ClusterSize", "簇大小")
    /** 是否代表：该文章是否为本簇的代表文章（每簇恰好一篇=true），便于在 Notion 里筛选「簇入口」 */
    val PROP_IS_CLUSTER_REP = listOf("是否代表", "IsClusterRep", "代表文章", "Rep")

    /**
     * 「实际已读」：用户在 App 阅读页手动勾选的「我实际读过」标志，与 supsub 服务端的 is_read（拉取去重用）无关，
     * 也不同于手动维护列「已读」（用户在 Notion 里手勾）。这是受管列，由同步引擎写入 Notion。
     */
    val PROP_USER_READ = listOf("实际已读", "UserRead", "实际已读标记")

    /**
     * 「高价值」：用户在 App 阅读页手动标记的「高价值」标志。
     * 与 [PROP_USER_READ] 同属受管列——由同步引擎写入 Notion，并在拉取方向把 Notion 端的值带回本地，实现双向同步。
     */
    val PROP_HIGH_VALUE = listOf("高价值", "HighValue")

    /**
     * 跨设备主键：supsub 返回的全局稳定 contentId。
     * 手机/平板各自独立抓取同一篇文章会得到相同的 contentId——它是三方同步「按它去重/映射」的基石。
     * 推送必带（建/更页都写），拉取按它把远端页映射回本地行；两设备按 contentId 去重后都落到同一个 Notion 页。
     */
    val PROP_CONTENT_ID = listOf("内容ID", "_cid", "ContentID", "ContentId")

    /**
     * 删除信号（checkbox，而非 archived）。
     * 故意不用 `archived`：Notion 的 `queryDatabase` 默认**排除**已归档页，会导致「拉取扫描」漏掉被删页、
     * 无法检测删除。用独立 checkbox 标记，删除既能跨设备传播、又不影响拉取枚举。
     */
    val PROP_DELETED = listOf("已删除", "_del", "Deleted")

    /**
     * 摘要（rich_text）：无损存摘要，拉取按属性直接重建本地 summary，替代脆弱的「解析 💡 摘要 blocks」。
     * 三方同步要「手机/平板 articles 表内容字段完全一致」，summary 必须能完整重建，不能只靠 blocks。
     */
    val PROP_SUMMARY = listOf("摘要", "Summary", "_summary")

    /** 封面图（url），拉取按属性直接重建本地 cover_image。 */
    val PROP_COVER = listOf("封面", "Cover", "_cover")

    /**
     * （已废弃）用户手动维护列：历史上「高价值」曾作为手动列预置，由用户在 Notion 里手勾、引擎永不写入。
     * 现「高价值」已提升为受管列（见 [PROP_HIGH_VALUE]），本列表清空，仅保留占位以便新建库逻辑结构不变。
     */
    @Suppress("unused")
    private val MANUAL_PROPS = listOf<Pair<String, String>>()

    /** 引擎会写入的列：(候选名, 类型, 建库时的类型配置) */
    val MANAGED: List<Triple<List<String>, String, () -> JSONObject>> = listOf(
        Triple(PROP_SOURCE, "rich_text") { JSONObject() },
        Triple(PROP_SOURCE_TYPE, "select") { JSONObject().put("options", JSONArray()) },
        Triple(PROP_ORIGIN_CATEGORY, "select") { JSONObject().put("options", JSONArray()) },
        Triple(PROP_FOCUS, "select") { JSONObject().put("options", JSONArray()) },
        Triple(PROP_CAPTURED_AT, "date") { JSONObject() },
        Triple(PROP_TAGS, "multi_select") { JSONObject().put("options", JSONArray()) },
        Triple(PROP_DEEPLINK, "url") { JSONObject() },
        Triple(PROP_KEYWORDS, "multi_select") { JSONObject().put("options", JSONArray()) },
        Triple(PROP_CONTENT_TYPE, "select") { JSONObject().put("options", JSONArray()) },
        Triple(PROP_CONTENT_DEPTH, "select") { JSONObject().put("options", JSONArray()) },
        Triple(PROP_TONE, "select") { JSONObject().put("options", JSONArray()) },
        Triple(PROP_STYLE, "select") { JSONObject().put("options", JSONArray()) },
        Triple(PROP_HAS_ACTION, "checkbox") { JSONObject() },
        Triple(PROP_HAS_STANCE, "checkbox") { JSONObject() },
        Triple(PROP_STANCE_SUMMARY, "rich_text") { JSONObject() },
        Triple(PROP_CLUSTER_TOPIC, "select") { JSONObject().put("options", JSONArray()) },
        Triple(PROP_CLUSTER_COUNT, "number") { JSONObject() },
        Triple(PROP_IS_CLUSTER_REP, "checkbox") { JSONObject() },
        Triple(PROP_USER_READ, "checkbox") { JSONObject() },
        Triple(PROP_HIGH_VALUE, "checkbox") { JSONObject() },
        // ─── 三方同步新增隐藏列（P0）───
        Triple(PROP_CONTENT_ID, "rich_text") { JSONObject() },
        Triple(PROP_DELETED, "checkbox") { JSONObject() },
        Triple(PROP_SUMMARY, "rich_text") { JSONObject() },
        Triple(PROP_COVER, "url") { JSONObject() },
    )

    /** Select / Multi-select 选项配色（避开 default/gray，保证可见彩色） */
    private val SELECT_COLORS = listOf("blue", "green", "red", "yellow", "orange", "purple", "pink", "brown")

    /**
     * 按名称稳定取色：同一个标签在所有按天子库里颜色一致。
     * 用哈希而不是轮转下标，才能做到「跨库、跨次同步都稳定」。
     */
    fun colorFor(name: String): String {
        if (name.isBlank()) return "default"
        val h = name.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }
        return SELECT_COLORS[h % SELECT_COLORS.size]
    }

    /**
     * 新建按天子库的完整 properties。
     *
     * 一次性把全部列建齐，好处是随后 `ensureSchema` 一个都补不出来、直接零请求返回；
     * 逐列后补则每建一个库都要多打一次 PATCH。
     */
    fun newDatabaseProperties(): JSONObject = JSONObject().apply {
        put(TITLE_PROP, JSONObject().put("title", JSONObject()))
        // 手动维护列（历史遗留，现已为空）紧跟标题列之后：保留结构位置以便未来扩展
        MANUAL_PROPS.forEach { (name, type) ->
            put(name, JSONObject().put(type, JSONObject()))
        }
        MANAGED.forEach { (candidates, type, spec) ->
            put(candidates.first(), JSONObject().put(type, spec()))
        }
    }

    /** 建库请求体：parent 必须是 page（数据库不能作为数据库的父节点） */
    fun createDatabaseBody(parentPageId: String, title: String): JSONObject = JSONObject().apply {
        put("parent", JSONObject().put("type", "page_id").put("page_id", parentPageId))
        put(
            "title",
            JSONArray().put(
                JSONObject().put("type", "text").put("text", JSONObject().put("content", title))
            ),
        )
        put("properties", newDatabaseProperties())
    }
}
