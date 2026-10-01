package com.peide.supsub.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * OAuth 设备授权流（手机形态）。
 *
 * CLI 的做法是「终端显示短码 + 用户去浏览器授权」，手机上流程一致：
 *   1. requestDeviceCode() 拿到 userCode 与 verificationUriComplete
 *   2. UI 展示 userCode（可复制），并用 Custom Tabs 打开 verificationUriComplete
 *   3. awaitAuthorization() 按 interval 轮询，直到 authorized / expired
 *
 * 轮询是 suspend 函数，页面销毁时 ViewModelScope 取消即自动停止（每轮都检查 isActive）。
 */
class DeviceFlowAuth(
    private val authApi: AuthApi,
    private val store: TokenStore,
) {

    sealed interface Result {
        /** 授权成功，令牌已写入 TokenStore */
        data object Authorized : Result

        /** 设备码过期，需要重新申请 */
        data object Expired : Result

        /** 轮询期间持续失败（网络等），带最后一次错误信息 */
        data class Failed(val message: String) : Result
    }

    /** 步骤 1：申请设备码 */
    suspend fun requestDeviceCode(): DeviceCodeResponse = try {
        authApi.requestDeviceCode(DeviceCodeRequest())
    } catch (e: SupsubException) {
        throw e
    } catch (e: Throwable) {
        throw SupsubException(
            SupsubException.CODE_SERVER,
            "无法获取设备码，请检查网络后重试",
        )
    }

    /**
     * 步骤 3：轮询授权状态。
     *
     * @param intervalSeconds 服务端建议的间隔，做下限保护（≥3s），别把接口打爆
     * @param expiresInSeconds 设备码有效期，到点即判定过期，不无限轮询
     * @param onTick 每轮回调剩余秒数，供 UI 显示倒计时
     */
    suspend fun awaitAuthorization(
        deviceCode: String,
        intervalSeconds: Int,
        expiresInSeconds: Int,
        onTick: (remainingSeconds: Int) -> Unit = {},
    ): Result {
        val interval = intervalSeconds.coerceAtLeast(3)
        val deadline = System.currentTimeMillis() + expiresInSeconds * 1000L
        var consecutiveFailures = 0
        var lastError = "授权轮询失败"

        while (System.currentTimeMillis() < deadline) {
            coroutineContext.ensureActive()

            val remaining = ((deadline - System.currentTimeMillis()) / 1000).toInt()
            onTick(remaining.coerceAtLeast(0))

            try {
                val resp = authApi.pollDeviceToken(DeviceTokenRequest(deviceCode))
                consecutiveFailures = 0
                when (resp.status.lowercase()) {
                    "authorized" -> {
                        val access = resp.accessToken
                        if (access.isNullOrBlank()) {
                            return Result.Failed("授权成功但未返回访问令牌")
                        }
                        store.save(access, resp.refreshToken)
                        return Result.Authorized
                    }
                    "expired" -> return Result.Expired
                    // "pending" 及未知状态一律继续等
                    else -> Unit
                }
            } catch (e: Throwable) {
                coroutineContext.ensureActive()
                // 轮询期间的错误多为瞬时（用户还没点授权时后端可能返回非 2xx），
                // 连续失败太多次才放弃，避免因一次抖动打断用户
                consecutiveFailures++
                lastError = (e as? SupsubException)?.message ?: "网络异常，正在重试"
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    return Result.Failed(lastError)
                }
            }

            delay(interval * 1000L)
        }
        return Result.Expired
    }

    fun logout() = store.clear()

    private companion object {
        const val MAX_CONSECUTIVE_FAILURES = 10
    }
}
