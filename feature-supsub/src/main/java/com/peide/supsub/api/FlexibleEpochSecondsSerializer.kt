package com.peide.supsub.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * publishedAt 双形态解析：
 *  - 数字（Unix 秒，也兼容毫秒）→ 直接用
 *  - 字符串 "yyyy-MM-dd HH:mm:ss" / ISO8601 → 解析为 Unix 秒
 *  - 解析不了 → null（宁可少一个时间，也不要整条数据崩掉）
 *
 * 统一输出 **Unix 秒**。
 */
object FlexibleEpochSecondsSerializer : KSerializer<Long?> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleEpochSeconds", PrimitiveKind.STRING)

    private val patterns = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd",
    )

    override fun deserialize(decoder: Decoder): Long? {
        val jsonDecoder = decoder as? JsonDecoder ?: return null
        val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive ?: return null

        // 数字形态
        primitive.content.toLongOrNull()?.let { raw ->
            // 大于 1e12 视为毫秒，折算成秒
            return if (raw > 1_000_000_000_000L) raw / 1000 else raw
        }

        // 字符串形态
        val text = primitive.content.takeIf { it.isNotBlank() } ?: return null
        for (p in patterns) {
            try {
                val fmt = SimpleDateFormat(p, Locale.US)
                if (p.endsWith("'Z'")) fmt.timeZone = TimeZone.getTimeZone("UTC")
                return fmt.parse(text)?.time?.div(1000)
            } catch (_: Exception) {
                // 换下一个格式继续试
            }
        }
        return null
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) encoder.encodeNull() else encoder.encodeLong(value)
    }
}
