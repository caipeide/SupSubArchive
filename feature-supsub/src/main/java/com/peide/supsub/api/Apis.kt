package com.peide.supsub.api

import kotlinx.serialization.json.JsonObject
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 免鉴权端点（设备授权流 + 令牌续期）。
 *
 * 这三个端点必须**独立于**带鉴权的 OkHttpClient：
 *  1. 它们处于未登录态，不该触发「401 → 清凭证」的统一行为；
 *  2. 续期本身就是 401 的补救手段，走同一条链会递归。
 */
interface AuthApi {

    /** POST /api/auth/device/code — 申请设备码 */
    @POST("api/auth/device/code")
    suspend fun requestDeviceCode(@Body body: DeviceCodeRequest): DeviceCodeResponse

    /** POST /api/auth/device/token — 轮询授权状态 */
    @POST("api/auth/device/token")
    suspend fun pollDeviceToken(@Body body: DeviceTokenRequest): DeviceTokenResponse

    /**
     * POST /api/auth/tokens — 用 refreshToken 换新令牌对。
     * 返回 Call 而非 suspend：需要在 OkHttp Interceptor 里同步执行。
     * ⚠️ 成功返回 201（不是 200），必须用 isSuccessful 判定。
     */
    @POST("api/auth/tokens")
    fun refreshTokens(@Body body: RefreshTokenRequest): Call<RefreshTokenResponse>
}

/** 需鉴权端点 */
interface SupsubApi {

    /** GET /api/user/info */
    @GET("api/user/info")
    suspend fun getUserInfo(): UserInfo

    /** GET /api/subscriptions */
    @GET("api/subscriptions")
    suspend fun listSubscriptions(
        @Query("sourceType") sourceType: String? = null,
    ): List<Subscription>

    /**
     * GET /api/subscriptions/contents
     *
     * ⚠️ 返回 `JsonObject` 而非 `Article`：因为 `Article` 开了 ignoreUnknownKeys，
     * 直接反序列化会把服务端新增/未知字段**静默丢弃且无留档**（articleId → contentId 就有先例）。
     * 这里保留每条内容的原始 JSON，由 Repository 再解析成 `Article`，
     * 原文同时落进归档的 `raw{}`，从而可追溯契约漂移。
     */
    @GET("api/subscriptions/contents")
    suspend fun getContents(
        @Query("sourceType") sourceType: String,
        @Query("sourceId") sourceId: Long,
        /** all | unread */
        @Query("type") type: String = "all",
        @Query("page") page: Int = 1,
        /** 服务端上限 100 */
        @Query("pageSize") pageSize: Int = 20,
    ): List<JsonObject>

    /** POST /api/subscriptions/contents/mark-as-read — 整源已读，不可逆 */
    @POST("api/subscriptions/contents/mark-as-read")
    suspend fun markAsRead(@Body body: MarkAsReadRequest)

    // ─── 关注点（focus）────────────────────────────────────────

    /** GET /api/focuses — 关注点列表 */
    @GET("api/focuses")
    suspend fun listFocuses(): List<Focus>

    /** GET /api/focuses/{focusId}/contents — 关注点聚合内容（同样保留原始 JSON，理由见 getContents） */
    @GET("api/focuses/{focusId}/contents")
    suspend fun getFocusContents(
        @Path("focusId") focusId: Long,
        /** all | unread */
        @Query("type") type: String = "all",
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20,
    ): List<JsonObject>

    /**
     * POST /api/focuses/{focusId}/mark-as-read
     * 整点已读（body 为 {}）。后端亦接受 `contentId` + `sourceType` 只标记单篇，
     * 用于「关注点与订阅源 URL 重叠时舍弃关注点重复项，并把该关注点重复项在云端标为已读」。
     * 单篇标记按 focus 下的 contentId 作用域，不会影响同名订阅源文章（其 contentId 不同）。
     */
    @POST("api/focuses/{focusId}/mark-as-read")
    suspend fun markFocusAsRead(
        @Path("focusId") focusId: Long,
        @Body body: FocusMarkAsReadRequest,
    )
}
