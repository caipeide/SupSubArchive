package com.peide.supsub.api

import android.util.Base64
import org.json.JSONObject

/**
 * 极简 JWT 解析：只为读 payload 里的 exp，不做签名校验（校验是服务端的事）。
 *
 * 用途：请求发出前判断 access_token 是否快过期，提前续期，省掉一趟注定 401 的往返。
 * CLI 实测：access_token ≈ 24h，refresh_token ≈ 14d。
 */
object Jwt {

    /** 返回 exp（Unix 秒）；解析失败返回 null，调用方应按「未知」处理而不是当作已过期 */
    fun expiresAtSeconds(token: String?): Long? {
        if (token.isNullOrBlank()) return null
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return null
            val payload = String(
                Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            )
            val exp = JSONObject(payload).optLong("exp", 0L)
            if (exp > 0) exp else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 是否即将过期。
     * @param skewSeconds 提前量，默认 5 分钟，抵消设备时钟偏差与网络耗时。
     * 解析不出 exp 时返回 false —— 本地算不准就别乱续期，让服务端用 401 说话。
     */
    fun isExpiringSoon(token: String?, skewSeconds: Long = 300): Boolean {
        val exp = expiresAtSeconds(token) ?: return false
        val now = System.currentTimeMillis() / 1000
        return now + skewSeconds >= exp
    }
}
