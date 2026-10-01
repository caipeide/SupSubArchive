package com.peide.supsub.api

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 令牌存储。
 *
 * 用 EncryptedSharedPreferences（AES256）落盘，避免明文 token 被其他 App / 备份读走。
 * 若设备 Keystore 异常导致加密存储不可用，降级为普通 SharedPreferences 并标记，
 * 保证功能不中断（对齐布导翁的容错做法）。
 */
class TokenStore(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    var usingFallback: Boolean = false
        private set

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                FILE_ENCRYPTED,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Throwable) {
            usingFallback = true
            appContext.getSharedPreferences(FILE_PLAIN, Context.MODE_PRIVATE)
        }
    }

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS, null)
        set(value) = prefs.edit().putString(KEY_ACCESS, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)
        set(value) = prefs.edit().putString(KEY_REFRESH, value).apply()

    /** 客户端标识，随请求头 X-Client-ID 发送 */
    var clientId: String
        get() = prefs.getString(KEY_CLIENT_ID, null) ?: DEFAULT_CLIENT_ID
        set(value) = prefs.edit().putString(KEY_CLIENT_ID, value).apply()

    val isLoggedIn: Boolean get() = !accessToken.isNullOrBlank()

    fun save(access: String?, refresh: String?) {
        prefs.edit()
            .putString(KEY_ACCESS, access)
            .putString(KEY_REFRESH, refresh)
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_ACCESS).remove(KEY_REFRESH).apply()
    }

    companion object {
        private const val FILE_ENCRYPTED = "supsub_auth_enc"
        private const val FILE_PLAIN = "supsub_auth_plain"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_CLIENT_ID = "client_id"
        const val DEFAULT_CLIENT_ID = "supsub-android"
    }
}
