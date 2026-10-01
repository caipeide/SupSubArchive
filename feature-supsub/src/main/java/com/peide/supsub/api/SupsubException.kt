package com.peide.supsub.api

import java.io.IOException

/**
 * 统一异常。code 沿用 CLI 的语义，便于 UI 分支处理：
 *  - UNAUTHORIZED         → 需重新登录（凭证已清）
 *  - INVALID_REFRESH_TOKEN→ refreshToken 失效，需重新登录
 *  - NETWORK_ERROR        → 网络/抖动，提示稍后重试，**不要登出**
 *  - PLAN_EXPIRED         → 套餐过期
 *  - SERVER_ERROR         → 其他非 2xx
 */
class SupsubException(
    val code: String,
    override val message: String,
    val status: Int = 0,
    /** 服务端 Retry-After 指示的等待时长（毫秒）；null 表示未指示，由调用方按指数退避 */
    val retryAfterMs: Long? = null,
) : IOException(message) {

    val needsRelogin: Boolean
        get() = code == CODE_UNAUTHORIZED || code == CODE_INVALID_REFRESH

    /**
     * 是否值得自动重试：限流（429）、网络抖动、5xx 服务端临时故障可重试；
     * 鉴权类（401/refresh 失效）、套餐过期等业务性错误重试无意义，直接抛给上层。
     */
    val isRetryable: Boolean
        get() = when (code) {
            CODE_RATE_LIMITED, CODE_NETWORK -> true
            CODE_SERVER -> status in 500..599
            else -> false
        }

    companion object {
        const val CODE_UNAUTHORIZED = "UNAUTHORIZED"
        const val CODE_INVALID_REFRESH = "INVALID_REFRESH_TOKEN"
        const val CODE_NETWORK = "NETWORK_ERROR"
        const val CODE_SERVER = "SERVER_ERROR"
        const val CODE_PLAN_EXPIRED = "PLAN_EXPIRED"
        /** 触发服务端限流（「请求过于频繁」）。不应登出，应提示稍后重试 */
        const val CODE_RATE_LIMITED = "RATE_LIMITED"

        fun unauthorized() =
            SupsubException(CODE_UNAUTHORIZED, "登录已失效，请重新登录", 401)

        /**
         * 服务端限流（「请求过于频繁，请稍后再试」）。
         * [retryAfterSec] 来自响应头 Retry-After；为 null 时由调用方按指数退避。
         */
        fun rateLimited(retryAfterSec: Long? = null) =
            SupsubException(
                CODE_RATE_LIMITED,
                "请求过于频繁，账号已被服务端限流。已自动降低请求频率，请稍候再试",
                429,
                retryAfterMs = retryAfterSec?.takeIf { it > 0 }?.times(1000),
            )

        fun network(detail: String? = null) =
            SupsubException(CODE_NETWORK, detail ?: "网络异常，请稍后重试", 0)
    }
}
