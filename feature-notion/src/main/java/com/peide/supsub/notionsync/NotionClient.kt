package com.peide.supsub.notionsync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

/**
 * Notion 同步配置。
 *
 * V2 起这里存的是**容器页 id**而不是数据库 id——文章按天分库，
 * 每天一个子数据库，全部挂在这个容器页下面，由 [NotionShardRouter] 按需发现/创建。
 */
data class NotionConfig(
    val token: String,
    /** 容器页 id（一个普通 Notion page，按天子数据库都建在它下面） */
    val parentPageId: String,
)

private val JSON = "application/json; charset=utf-8".toMediaType()

interface NotionApi {
    @Headers("Notion-Version: 2022-06-28")
    @POST("v1/pages")
    suspend fun createPage(@Body body: RequestBody): ResponseBody

    /** 读取页面元信息，用于校验容器页 id 是否有效、是否已共享给 integration。 */
    @Headers("Notion-Version: 2022-06-28")
    @GET("v1/pages/{id}")
    suspend fun getPage(@Path("id") id: String): ResponseBody

    @Headers("Notion-Version: 2022-06-28")
    @GET("v1/databases/{id}")
    suspend fun getDatabase(@Path("id") id: String): ResponseBody

    /** 查询数据库内的页面（按属性过滤），用于「新建遇 409 时按原文链接找回已存在页」自愈。 */
    @Headers("Notion-Version: 2022-06-28")
    @POST("v1/databases/{id}/query")
    suspend fun queryDatabase(@Path("id") id: String, @Body body: RequestBody): ResponseBody

    /**
     * 新建数据库（按天分库用）。
     * body 需含 parent.page_id、title、properties 完整 schema。
     */
    @Headers("Notion-Version: 2022-06-28")
    @POST("v1/databases")
    suspend fun createDatabase(@Body body: RequestBody): ResponseBody

    /**
     * 更新数据库 schema：为 select / multi_select 属性预置带颜色的选项。
     * PATCH 仅合并所提及的属性，不影响其它列。
     */
    @Headers("Notion-Version: 2022-06-28")
    @PATCH("v1/databases/{id}")
    suspend fun updateDatabase(@Path("id") id: String, @Body body: RequestBody): ResponseBody

    /**
     * PATCH 已存在页面，用途有二：
     * 1) 原地更新结构化属性（不含正文 children；children 由 blocks 接口单独改写）；
     * 2) 强制全量重推（E 优化）时设 archived=true 归档旧页，随后由 createPage 新建，实现每篇仅 2 请求。
     */
    @Headers("Notion-Version: 2022-06-28")
    @PATCH("v1/pages/{id}")
    suspend fun updatePageProperties(@Path("id") id: String, @Body body: RequestBody): ResponseBody

    /**
     * 列出块的子块（children）。两处在用：
     * 1) 重推前清空页面旧正文；
     * 2) 在容器页下发现已存在的按天子数据库（type=child_database），此时需要 [startCursor] 翻页。
     */
    @Headers("Notion-Version: 2022-06-28")
    @GET("v1/blocks/{id}/children")
    suspend fun getBlockChildren(
        @Path("id") id: String,
        @Query("page_size") pageSize: Int = 100,
        @Query("start_cursor") startCursor: String? = null,
    ): ResponseBody

    /** 向页面追加子块（重推时写入新正文）。 */
    @Headers("Notion-Version: 2022-06-28")
    @PATCH("v1/blocks/{id}/children")
    suspend fun appendBlockChildren(@Path("id") id: String, @Body body: RequestBody): ResponseBody

    /** 删除单个子块（重推时清空旧正文，按 id 逐个删除；Notion 无批量删除接口）。 */
    @Headers("Notion-Version: 2022-06-28")
    @DELETE("v1/blocks/{id}")
    suspend fun deleteBlock(@Path("id") id: String): ResponseBody

    /**
     * 把块移动到父级顶部。新建按天子库后调用，让新一天的子库显示在容器页最上方
     * （Notion 默认把新建数据库加到页面底部）。
     *
     * body 只含 `parent`、不含 `after` → 块被移到父级第一个子块之前（顶部）。
     */
    @Headers("Notion-Version: 2022-06-28")
    @POST("v1/blocks/{block_id}/move")
    suspend fun moveBlock(@Path("block_id") blockId: String, @Body body: RequestBody): ResponseBody

    // ─── 评论 API（2025 版新增；仅这三个端点抬版本头，不动其余）───
    /**
     * 在指定页面下创建一条页面级评论。
     * body: {"parent":{"page_id":<id>},"rich_text":[{"type":"text","text":{"content":<text>}}]}
     * 注意：Notion API 不支持修改评论正文，编辑须走「DELETE 旧 + 本接口重建」。
     */
    @Headers("Notion-Version: 2025-09-03")
    @POST("v1/comments")
    suspend fun createComment(@Body body: RequestBody): ResponseBody

    /** 列出某页面（以 block_id 传入 page_id）下的全部评论。 */
    @Headers("Notion-Version: 2025-09-03")
    @GET("v1/comments")
    suspend fun listComments(@Query("block_id") pageId: String): ResponseBody

    /** 删除一条评论（2025 版新增；远端已无该评论时返回 404，可视为成功）。 */
    @Headers("Notion-Version: 2025-09-03")
    @DELETE("v1/comments/{id}")
    suspend fun deleteComment(@Path("id") id: String): ResponseBody
}

/** 构建带鉴权的 Notion API 客户端；仅对 api.notion.com 的请求附加 Bearer Token。 */
fun buildNotionApi(token: String): NotionApi {
    val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val req = chain.request()
            val newReq = if (req.url.host == "api.notion.com") {
                req.newBuilder().addHeader("Authorization", "Bearer $token").build()
            } else {
                req
            }
            chain.proceed(newReq)
        }
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    return Retrofit.Builder()
        .baseUrl("https://api.notion.com/")
        .client(client)
        .build()
        .create(NotionApi::class.java)
}

fun String.toJsonBody(): RequestBody = this.toRequestBody(JSON)

/** 构造「把某块移到 pageId 顶部」的请求体：`{"parent":{"page_id":"..."}}`，不含 `after` → 置于父级开头。 */
fun moveBlockToTopBody(pageId: String): RequestBody =
    JSONObject().apply {
        put("parent", JSONObject().put("page_id", pageId))
    }.toString().toJsonBody()

/** 构造「在 pageId 下创建评论」的请求体（纯文本 rich_text）。 */
fun commentBody(pageId: String, text: String): RequestBody =
    JSONObject().apply {
        put("parent", JSONObject().put("page_id", pageId))
        put(
            "rich_text",
            org.json.JSONArray().put(
                JSONObject().apply {
                    put("type", "text")
                    put("text", JSONObject().put("content", text))
                },
            ),
        )
    }.toString().toJsonBody()

/** 从 createComment 的响应里取新建评论 id。 */
fun commentIdOf(resp: ResponseBody): String = JSONObject(resp.string()).getString("id")

/**
 * 解析 listComments 响应，返回 (commentId, plainText) 列表。
 * 每条约等于：`results[].{id, rich_text[].plain_text}`。
 */
fun parseComments(json: String): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    val root = JSONObject(json)
    val results = root.optJSONArray("results") ?: return out
    for (i in 0 until results.length()) {
        val c = results.optJSONObject(i) ?: continue
        val id = c.optString("id").takeIf { it.isNotBlank() } ?: continue
        val rt = c.optJSONArray("rich_text")
        val sb = StringBuilder()
        if (rt != null) {
            for (j in 0 until rt.length()) {
                sb.append(rt.optJSONObject(j)?.optString("plain_text").orEmpty())
            }
        }
        out.add(id to sb.toString())
    }
    return out
}

/**
 * 校验 Token + 容器页 id 是否可用（设置页「验证连接」调用）。
 *
 * 这里必须校验的是**页面**而不是数据库：V2 把按天子库建在容器页下，
 * 用户很容易误填一个数据库 id——那样建库会失败（数据库不能作为数据库的父节点），
 * 所以先探测一次，误填时直接给出可操作的提示。
 */
suspend fun checkNotionParentPage(token: String, parentPageId: String): ConnectionCheck {
    val api = buildNotionApi(token)
    // 1) 先按「页面」读
    val asPage = runCatching { api.getPage(parentPageId).string() }
    if (asPage.isSuccess) {
        val title = runCatching { pageTitleOf(JSONObject(asPage.getOrThrow())) }.getOrNull()
        return ConnectionCheck(
            true,
            "✅ 容器页可访问：${title.orEmpty().ifBlank { "（无标题）" }}，按天子库将建在这里",
        )
    }
    // 2) 读页面失败，看看是不是填成了数据库 id——这是最常见的误填，单独给提示
    val asDb = runCatching { api.getDatabase(parentPageId).string() }
    if (asDb.isSuccess) {
        return ConnectionCheck(
            false,
            "❌ 这是一个数据库 id，不是页面。请改填一个普通页面的 id：按天子数据库需要挂在页面下（数据库不能作为数据库的父节点）",
        )
    }
    val err = (asPage.exceptionOrNull()?.message ?: "未知错误").take(160)
    return ConnectionCheck(false, "❌ 连接失败：$err（请确认已在 Notion 中把该页面共享给 integration）")
}

/** 校验单个数据库是否可访问（保留：分库概览里点开某个子库时可用） */
suspend fun checkNotionConnection(token: String, databaseId: String): ConnectionCheck {
    return try {
        val resp = buildNotionApi(token).getDatabase(databaseId).string()
        val props = JSONObject(resp).optJSONObject("properties")
        val title = props?.keys()?.asSequence()
            ?.firstOrNull { k -> props.optJSONObject(k)?.optString("type") == "title" } ?: "Name"
        ConnectionCheck(true, "✅ 连接成功，数据库可访问（标题属性：$title）")
    } catch (e: Exception) {
        ConnectionCheck(false, "❌ 连接失败：${(e.message ?: e.javaClass.simpleName).take(160)}")
    }
}

/**
 * 统计某个已建库子数据库里的**活跃页面总数**（archived=false）。
 *
 * Notion 的 query 接口不返回总数，只能翻页累加：每次最多 100 条，
 * 跟 `next_cursor` 一直翻到 `has_more=false`。循环里加 350ms 间隔，
 * 把请求速率压在 ~3 req/s 以下，避免触发 integration 限流（429）。
 *
 * 用于「分库概览」里实时统计 Notion 端文章数。单个库查询失败（被归档/取消共享等）
 * 由调用方用 runCatching 兜底为 0，这里只负责单次成功路径。
 */
suspend fun queryDatabaseCount(api: NotionApi, databaseId: String): Int {
    var cursor: String? = null
    var total = 0
    do {
        val body = JSONObject().apply {
            put("page_size", 100)
            if (cursor != null) put("start_cursor", cursor)
        }.toString().toJsonBody()
        val root = JSONObject(api.queryDatabase(databaseId, body).string())
        total += root.optJSONArray("results")?.length() ?: 0
        cursor = root.optString("next_cursor").takeIf { it.isNotBlank() && root.optBoolean("has_more") }
        delay(350)
    } while (cursor != null)
    return total
}

/**
 * 从 page 对象里取标题纯文本。
 * 页面的标题属性名不固定（数据库子页是列名，普通页是 "title"），因此按 type=="title" 找。
 */
internal fun pageTitleOf(page: JSONObject): String {
    val props = page.optJSONObject("properties") ?: return ""
    props.keys().forEach { k ->
        val p = props.optJSONObject(k) ?: return@forEach
        if (p.optString("type") != "title") return@forEach
        val arr = p.optJSONArray("title") ?: return@forEach
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            sb.append(arr.optJSONObject(i)?.optString("plain_text").orEmpty())
        }
        return sb.toString()
    }
    return ""
}

data class ConnectionCheck(val ok: Boolean, val message: String)
