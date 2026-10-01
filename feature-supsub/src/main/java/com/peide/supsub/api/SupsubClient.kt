package com.peide.supsub.api

import android.content.Context
import kotlinx.serialization.json.Json
import android.util.Log
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** 服务端限流特征串（命中即视为被限流）。该服务端用中文文案「您的请求过于频繁，请稍后再试」 */
private val RATE_LIMIT_HINTS = listOf("请求过于频繁", "请求频率过高", "rate limit", "rate_limit")

/**
 * 账号级限速策略（令牌桶参数），集中配置、运行时可调。
 *
 * 之前拉取走 [PullEngine] 的 4 路并发 + 后台未读统计独立打 [SupsubRepository.countUnreadByType]，
 * 二者叠加会在瞬间把请求打爆，触发服务端「请求过于频繁」并把账号锁一段时间。
 * 在 HTTP 出口统一限速，比在每个调用点各自 sleep 更彻底。
 *
 * - [perSecond]：稳定速率（个/秒）。2 个/秒对服务端很温和，足以避免再被限流。
 * - [burst]：突发容量（令牌桶上限）。允许开拉瞬间最多连发这么多个，之后回落到稳定速率。
 * - [penaltyMs]：已触发限流后的冷静期。被锁期间若继续猛打只会锁更久，故强制歇这么久。
 *
 * 均为 @Volatile 可变：P2-3 探测服务端真实阈值后可直接改值热生效，无需改调用方。
 */
object RateLimitPolicy {
    @Volatile
    var perSecond: Double = 2.0

    @Volatile
    var burst: Int = 3

    @Volatile
    var penaltyMs: Long = 15_000L
}

/**
 * SupSub HTTP 客户端工厂。
 *
 * Base URL 可配置（对齐 CLI 的 SUPSUB_API_URL 环境变量），默认 https://supsub.net。
 */
class SupsubClient private constructor(
    val baseUrl: String,
    val tokenStore: TokenStore,
    val authApi: AuthApi,
    val api: SupsubApi,
    val tokenRefresher: TokenRefresher,
) {
    companion object {

        const val DEFAULT_BASE_URL = "https://supsub.net/"

        /** 账号级限流器单例：跨 Base URL 切换（reset）也复用同一份限速状态 */
        @Volatile
        private var rateLimiter: RateLimiter? = null

        private fun rateLimiter(): RateLimiter = rateLimiter
            ?: synchronized(this) {
                rateLimiter
                    ?: RateLimiter(RateLimitPolicy.perSecond, RateLimitPolicy.burst)
                        .also { rateLimiter = it }
            }

        /** 服务端字段可能变动，一律忽略未知字段，避免整条解析失败 */
        val json: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
            encodeDefaults = false
        }

        @Volatile
        private var instance: SupsubClient? = null

        fun get(context: Context, baseUrl: String = DEFAULT_BASE_URL): SupsubClient {
            return instance ?: synchronized(this) {
                instance ?: create(context, baseUrl).also { instance = it }
            }
        }

        /** 切换 Base URL（调试/自建服务用），会重建单例 */
        fun reset(context: Context, baseUrl: String) {
            synchronized(this) { instance = create(context, baseUrl) }
        }

        private fun create(context: Context, baseUrl: String): SupsubClient {
            val store = TokenStore(context)
            val converter = json.asConverterFactory("application/json".toMediaType())

            val logging = HttpLoggingInterceptor { message ->
                // 仅 debug 构建输出：请求体含设备码 / 令牌，响应体含文章摘要。
                // Release 下整条链路静默，避免经 logcat 外泄。
                if (BuildConfig.DEBUG) Log.d("SupsubHttp", message)
            }.apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                else HttpLoggingInterceptor.Level.NONE
            }

            // ① 裸客户端：只做超时与日志，供免鉴权端点使用
            val bareClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build()

            val authRetrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(bareClient)
                .addConverterFactory(converter)
                .build()
            val authApi = authRetrofit.create(AuthApi::class.java)

            val refresher = TokenRefresher(store, authApi)

            // ② 鉴权客户端：注入头 + 预续期 + 401 续期重试
            //    最外层先挂账号级限速拦截器：每个对 supsub 的请求发出前先取令牌，
            //    从源头压住并发（拉取 4 路 + 后台未读统计）叠加出来的瞬时尖峰。
            val authedClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(RateLimitInterceptor(rateLimiter(), { RateLimitPolicy.penaltyMs }))
                .addInterceptor(AuthInterceptor(store, refresher))
                .build()

            val apiRetrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(authedClient)
                .addConverterFactory(converter)
                .build()

            return SupsubClient(
                baseUrl = baseUrl,
                tokenStore = store,
                authApi = authApi,
                api = apiRetrofit.create(SupsubApi::class.java),
                tokenRefresher = refresher,
            )
        }
    }
}

/**
 * 鉴权拦截器，行为对齐 CLI 的 http/client.ts：
 *  1. 发送前：access_token 快过期 → 先续期（失败不阻断，让服务端用 401 说话）
 *  2. 注入 Authorization / X-Client-ID
 *  3. 收到 401 → 续期成功则**重试一次**；refreshToken 也废了 → 清凭证
 *  4. 续期因网络/5xx 没跑成 → 保留凭证，不要因一次抖动把用户登出
 */
internal class AuthInterceptor(
    private val store: TokenStore,
    private val refresher: TokenRefresher,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        // 预续期
        if (Jwt.isExpiringSoon(store.accessToken)) {
            refresher.refreshBlocking()
        }

        var response = chain.proceed(chain.request().withAuth())

        if (response.status401()) {
            when (refresher.refreshBlocking()) {
                RefreshOutcome.REFRESHED -> {
                    response.close()
                    response = chain.proceed(chain.request().withAuth())
                }
                RefreshOutcome.UNAVAILABLE -> {
                    // 续期请求本身没跑成，本地凭证很可能还好着，别登出
                    response.close()
                    throw SupsubException.network("令牌续期失败，请稍后重试")
                }
                RefreshOutcome.INVALID, RefreshOutcome.SKIPPED -> Unit // 落到下面清凭证
            }
        }

        if (response.status401()) {
            response.close()
            store.clear()
            throw SupsubException.unauthorized()
        }

        return response
    }

    private fun Response.status401() = code == 401

    private fun Request.withAuth(): Request {
        val builder = newBuilder()
            .header("X-Client-ID", store.clientId)
            .header("Accept", "application/json")
        store.accessToken?.takeIf { it.isNotBlank() }?.let {
            builder.header("Authorization", "Bearer $it")
        }
        return builder.build()
    }
}

enum class RefreshOutcome {
    /** 换到新令牌，可重试原请求 */
    REFRESHED,

    /** refreshToken 已失效，需要重新登录 */
    INVALID,

    /** 续期请求本身失败（网络 / 5xx），稍后重试 */
    UNAVAILABLE,

    /** 压根没有 refreshToken，没有续期通道 */
    SKIPPED,
}

/**
 * 令牌续期器。
 *
 * 关键点：**加锁**。多个并发请求同时撞上 401 时，只让第一个真正去续期，
 * 其余等锁释放后发现令牌已经变了，直接复用，避免续期风暴。
 */
class TokenRefresher(
    private val store: TokenStore,
    private val authApi: AuthApi,
) {
    private val lock = Any()

    fun refreshBlocking(): RefreshOutcome = synchronized(lock) {
        val refresh = store.refreshToken
        if (refresh.isNullOrBlank()) return RefreshOutcome.SKIPPED

        val before = store.accessToken

        return try {
            val resp = authApi.refreshTokens(RefreshTokenRequest(refresh)).execute()

            // 400 / 401 → refreshToken 已失效，不可重试
            if (resp.code() == 400 || resp.code() == 401) {
                store.clear()
                return RefreshOutcome.INVALID
            }
            // ⚠️ 成功是 201，不能写死 200
            if (!resp.isSuccessful) return RefreshOutcome.UNAVAILABLE

            val body = resp.body()
            val newAccess = body?.accessToken
            if (newAccess.isNullOrBlank()) return RefreshOutcome.UNAVAILABLE

            // 响应没带回新 refreshToken 时沿用旧的（服务端不作废旧令牌）
            store.save(newAccess, body.refreshToken ?: refresh)
            RefreshOutcome.REFRESHED
        } catch (_: Throwable) {
            // 网络抖动：如果期间别的线程已经把令牌换掉了，也算成功
            if (store.accessToken != before && !store.accessToken.isNullOrBlank()) {
                RefreshOutcome.REFRESHED
            } else {
                RefreshOutcome.UNAVAILABLE
            }
        }
    }
}

/**
 * 账号级限流器（令牌桶）。
 *
 * 为什么放在 HTTP 层：拉取、未读统计、标记已读、同步回查……所有对 supsub 服务端的请求
 * 最终都从同一个 OkHttpClient 出去。在「出口」统一限速，比在每个调用点各自加 sleep 更彻底，
 * 也避免了「拉取 4 路并发 + 后台未读统计」叠加把账号打爆（曾导致「请求过于频繁」被锁一段时间）。
 *
 * 令牌桶：允许瞬时限 [capacity] 个（突发），之后按 [refillPerSecond] 个/秒稳定补充。
 * [penalize] 在【已触发限流】时追加一段冷静期，避免被锁期间继续猛打把锁期拖长。
 */
class RateLimiter(
    private val refillPerSecond: Double,
    capacity: Int,
) {
    private val capacity = capacity.toDouble().coerceAtLeast(1.0)
    private val lock = Any()

    /**
     * 桶内令牌。**允许为负**：负值表示「已预扣 / 欠下的令牌」。
     *
     * 为什么必须负记账：若桶空时只把 tokens 清零、让线程各自 sleep，则排队者 sleep 期间
     * refill 补出的令牌会被其他线程「截胡」——排队者醒来直接发请求（不重新取令牌），
     * 同一时刻补出的令牌又被后来者拿走，实际吞吐 ≈ 理论速率 × 2（实测 4 路并发下
     * 2/s 的桶跑出 ~7.8/s，触发服务端限流）。负记账让 refill 先还债、再累积，
     * 后来者永远拿不到已被预扣的令牌，多线程下严格按 refillPerSecond 串行放行。
     */
    private var tokens = capacity.toDouble().coerceAtLeast(1.0)
    private var lastRefillNanos = System.nanoTime()
    private var penaltyUntilNanos = 0L

    /** 取一个令牌；不够则阻塞到可用。返回前会先等过冷静期。 */
    fun acquire() {
        val waitNanos = synchronized(lock) {
            val now = System.nanoTime()
            refill(now)
            // 先等冷静期（被限流后的强制歇息）
            val penaltyWait = if (penaltyUntilNanos > now) penaltyUntilNanos - now else 0L
            if (tokens >= 1.0) {
                tokens -= 1.0
                penaltyWait
            } else {
                // 桶不足：预扣差额（tokens 变为更负）。等待时长 = 欠账清偿所需时间，
                // 多个并发线程依次把 tokens 推负，醒来时刻自然错开 → 全局串行限速。
                val deficit = 1.0 - tokens
                tokens = -deficit
                penaltyWait + (deficit / refillPerSecond * 1_000_000_000L).toLong()
            }
        }
        if (BuildConfig.DEBUG) {
            Log.d("RateLimit", "acquire 需等待 ${waitNanos / 1_000_000}ms（refill=${refillPerSecond}/s）")
        }
        if (waitNanos > 0) sleep(waitNanos)
    }

    /** 触发限流后的冷静期：被服务端限流后，强制歇 [millis] 毫秒再发下一个请求。 */
    fun penalize(millis: Long) {
        synchronized(lock) {
            val until = System.nanoTime() + millis * 1_000_000L
            if (until > penaltyUntilNanos) penaltyUntilNanos = until
        }
    }

    private fun refill(now: Long) {
        val elapsed = (now - lastRefillNanos) / 1_000_000_000.0
        if (elapsed > 0) {
            // 先还债（tokens 为负时先补回 0 再累积），封顶 capacity
            tokens = (tokens + elapsed * refillPerSecond).coerceAtMost(capacity)
            lastRefillNanos = now
        }
    }

    private fun sleep(nanos: Long) {
        try {
            Thread.sleep(nanos / 1_000_000, (nanos % 1_000_000).toInt())
        } catch (_: InterruptedException) {
            // 被取消（协程取消会置线程中断位）就直接放行，由上层 ensureActive / CancellationException 兜底
        }
    }
}

/**
 * 出口限速拦截器：每个对 supsub 服务端的请求发出前，先向 [RateLimiter] 取令牌（不够则阻塞排队）。
 *
 * 同时识别「请求过于频繁」类限流响应：命中则转成 [SupsubException.rateLimited] 并追加冷静期，
 * 让 app 立刻收手，而不是在锁定期内继续猛打把账号锁更久。
 *
 * 冷静期时长：优先遵循服务端 Retry-After 头（响应明确说等多久就等多久），
 * 没有该头时才用 [penaltyMs] 提供的默认值。
 */
internal class RateLimitInterceptor(
    private val limiter: RateLimiter,
    private val penaltyMs: () -> Long,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        limiter.acquire()
        if (BuildConfig.DEBUG) {
            Log.d("RateLimit", "发出请求: ${chain.request().url}")
        }
        val response = chain.proceed(chain.request())

        if (isRateLimited(response)) {
            // 这里直接抛，不再走 repo 的 toSupsubException；peekBody 不影响原 response，但反正要关
            val retryAfterSec = response.header("Retry-After")?.toLongOrNull()
            response.close()
            // 尊重 Retry-After：服务端让等多久就歇多久（再乘 1.2 冗余，避免边界抖动），缺省用策略默认值
            val coolDown = retryAfterSec?.takeIf { it > 0 }
                ?.let { (it * 1200L).coerceAtLeast(penaltyMs()) }
                ?: penaltyMs()
            limiter.penalize(coolDown)
            throw SupsubException.rateLimited(retryAfterSec)
        }
        return response
    }

    private fun isRateLimited(response: Response): Boolean {
        if (response.code == 429) return true
        // 该服务端用自定义文案「您的请求过于频繁，请稍后再试」，body 里带「频繁」
        val peek = runCatching { response.peekBody(4_000L).string() }.getOrNull().orEmpty()
        return RATE_LIMIT_HINTS.any { peek.contains(it, ignoreCase = true) }
    }
}
