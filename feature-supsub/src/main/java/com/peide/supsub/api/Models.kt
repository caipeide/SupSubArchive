package com.peide.supsub.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * SupSub API 数据模型。
 *
 * 契约来源：supsub-cli 开源实现（MIT, github.com/SupSub-AI/supsub-cli, master 分支）
 *   - src/lib/types.ts
 *   - src/api/auth.ts / src/api/subscription.ts
 *
 * 注意：这不是官方开放文档，服务端字段可能变更（源码里已有 articleId → contentId 的先例）。
 * 因此所有解析都开启 ignoreUnknownKeys，并对已知的命名不一致做 @JsonNames 容错。
 */

// ─── 用户 ────────────────────────────────────────────────────

@Serializable
data class UserInfo(
    val id: Long = 0,
    val email: String = "",
    val name: String = "",
    val avatar: String = "",
    val google: Boolean = false,
    /** true 表示套餐已过期，多数内容接口会拒绝 */
    val expired: Boolean = false,
    /** 套餐到期时间（Unix 秒） */
    val endAt: Long = 0,
    val opml: String = "",
    val onboardingCompleted: Boolean = false,
    val referralSourceSubmitted: Boolean = false,
)

// ─── 设备授权流 ───────────────────────────────────────────────

@Serializable
data class DeviceCodeResponse(
    /** 设备码：轮询用的密钥，不展示给用户 */
    val deviceCode: String,
    /** 短码：展示给用户，在网页里输入 */
    val userCode: String,
    /** 授权页地址（不带码） */
    val verificationUri: String = "",
    /** 授权页地址（已带码，可直接打开） */
    val verificationUriComplete: String = "",
    /** 建议轮询间隔（秒） */
    val interval: Int = 5,
    /** 设备码有效期（秒） */
    val expiresIn: Int = 600,
)

@Serializable
data class DeviceCodeRequest(val placeholder: String? = null)

@Serializable
data class DeviceTokenRequest(val deviceCode: String)

/**
 * 轮询结果。
 * 后端 status 取值：pending / authorized / expired。
 * accessToken / refreshToken 命名在 schema 与 example 间不一致，两种都兼容。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DeviceTokenResponse(
    val status: String = "pending",
    @JsonNames("access_token")
    val accessToken: String? = null,
    @JsonNames("refresh_token")
    val refreshToken: String? = null,
)

@Serializable
data class RefreshTokenRequest(val refreshToken: String)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RefreshTokenResponse(
    @JsonNames("access_token")
    val accessToken: String? = null,
    @JsonNames("refresh_token")
    val refreshToken: String? = null,
)

// ─── 订阅与内容 ───────────────────────────────────────────────

@Serializable
data class Subscription(
    /** MP（公众号） / WEBSITE / X */
    val sourceType: String = "",
    val sourceId: Long = 0,
    val name: String = "",
    val img: String = "",
    val description: String = "",
    val unreadCount: Int = 0,
)

/**
 * 文章条目。
 *
 * ⚠️ 重要：该接口**不返回正文全文**，只有 summary（摘要）。
 * 全文需走 deep-read（消耗不可逆额度）或客户端抓取 url。
 */
@Serializable
data class Article(
    /** 旧契约为 articleId，2026-07 线上已统一为 contentId */
    @SerialName("contentId")
    val contentId: String = "",
    val url: String = "",
    val title: String = "",
    val coverImage: String = "",
    val tags: List<String> = emptyList(),
    val summary: String = "",
    /** schema 是整数时间戳，example 出现过 "yyyy-MM-dd HH:mm:ss" 字符串，统一归一为 Unix 秒 */
    @Serializable(with = FlexibleEpochSecondsSerializer::class)
    val publishedAt: Long? = null,
    val isRead: Boolean = false,

    // ─── 以下字段线上确实返回，但此前未在模型里声明，被 ignoreUnknownKeys 静默丢弃 ───
    // （2026-07-31 保留原始报文后实测发现，见归档模板的 keywords / analysis 块）

    /** 服务端回传的来源类型，与订阅源上的 sourceType 一致 */
    val sourceType: String = "",
    /** 关键词：比 tags 更细粒度的实体词（如 "Ling-3.0-flash" "MoE"） */
    val keywords: List<String> = emptyList(),
    /** 内容类型：分析 / 资讯 / 教程 … */
    val contentType: String = "",
    /** 内容深度：深度 / 一般 … */
    val contentDepth: String = "",
    /** 语气：客观 / 主观 … */
    val tone: String = "",
    /** 体裁：新闻 / 评论 … */
    val style: String = "",
    /** 是否包含可执行的行动建议 */
    val hasAction: Boolean = false,
    /** 是否表达了明确观点立场 */
    val hasStance: Boolean = false,
    /** 观点立场摘要（hasStance 为 true 时通常非空） */
    val stanceSummary: String = "",
)

@Serializable
data class MarkAsReadRequest(val sourceType: String, val sourceId: Long)

// ─── 关注点（focus）────────────────────────────────────────────
// 关注点是「跨源聚合」的概念，与 /api/subscriptions 里的 MP/WEBSITE/X 订阅源是两套数据。
// 它不在 /api/subscriptions 列表里，需要单独调 /api/focuses 及其内容接口。

/** GET /api/focuses — 关注点列表项 */
@Serializable
data class Focus(
    val id: Long = 0,
    /** emoji 图标 */
    val icon: String = "",
    val title: String = "",
    val unreadCount: Int = 0,
)

/**
 * GET /api/focuses/{id}/contents — 关注点内容条目。
 * 一条内容可能来自 MP/WEBSITE/X 中的任一源（sourceType/sourceName 标记真实来源）。
 */
@Serializable
data class FocusContent(
    /** 旧契约为 articleId，2026-07 线上已统一为 contentId */
    @SerialName("contentId") val contentId: String = "",
    val url: String = "",
    val title: String = "",
    val coverImage: String = "",
    val keywords: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val summary: String = "",
    val sourceType: String = "",
    val sourceName: String = "",
    /** schema 是整数时间戳，example 出现过字符串，统一归一为 Unix 秒 */
    @Serializable(with = FlexibleEpochSecondsSerializer::class)
    val publishedAt: Long? = null,
    val isRead: Boolean = false,

    // 同 Article：此前被 ignoreUnknownKeys 丢弃的 AI 分析维度
    val contentType: String = "",
    val contentDepth: String = "",
    val tone: String = "",
    val style: String = "",
    val hasAction: Boolean = false,
    val hasStance: Boolean = false,
    val stanceSummary: String = "",
)

/** POST /api/focuses/{focusId}/mark-as-read 请求体。contentId/sourceType 省略时表示整点已读。 */
@Serializable
data class FocusMarkAsReadRequest(
    val contentId: String? = null,
    val sourceType: String? = null,
)

/** 服务端错误体：{code, message, status} */
@Serializable
data class ErrorEnvelope(
    val code: String = "",
    val message: String = "",
    val status: Int = 0,
)
