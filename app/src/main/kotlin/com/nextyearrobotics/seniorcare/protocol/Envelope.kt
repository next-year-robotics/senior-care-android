package com.nextyearrobotics.seniorcare.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.*

/**
 * phone-bridge/1 공통 envelope (contracts/phone-bridge/v1/PROTOCOL.md 3.1).
 * payload는 type별 DTO로 2차 디코딩한다(TASKS M1-2). 알 수 없는 필드는 무시한다.
 */
@Serializable
data class Envelope(
    val version: Int,
    val type: String,
    val id: String,
    @SerialName("sent_at_ms") val sentAtMs: Long,
    @SerialName("reply_to") val replyTo: String?,
    val payload: JsonObject,
)

object Protocol {
    const val VERSION = 1
    const val MAX_FRAME_BYTES = 16 * 1024

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = true
        encodeDefaults = false
    }

    private val uuidV4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    /** 파싱 + envelope 수준 검증. 실패는 [ProtocolException]. payload 스키마 검증은 type별 디코더가 한다. */
    fun decode(text: String): Envelope {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_FRAME_BYTES) throw ProtocolException("INVALID_MESSAGE", "frame too large")
        val raw = try { json.parseToJsonElement(text) } catch (_: Exception) {
            throw ProtocolException("INVALID_MESSAGE", "malformed envelope")
        }
        if (raw is JsonObject) {
            val version = raw["version"] as? JsonPrimitive
            if (version != null && !version.isString && version.longOrNull != null && version.longOrNull != 1L)
                throw ProtocolException("UNSUPPORTED_VERSION", "unsupported version")
        }
        ContractValidation.validateEnvelope(raw)
        val env = try { json.decodeFromJsonElement(Envelope.serializer(), raw) } catch (_: Exception) {
            throw ProtocolException("INVALID_MESSAGE", "malformed envelope")
        }
        if (env.sentAtMs < 0) throw ProtocolException("INVALID_MESSAGE", "negative timestamp")
        if (env.version != VERSION) throw ProtocolException("UNSUPPORTED_VERSION", "version ${env.version}")
        if (!uuidV4.matches(env.id)) throw ProtocolException("INVALID_MESSAGE", "id is not uuid v4")
        if (env.replyTo != null && !uuidV4.matches(env.replyTo)) throw ProtocolException("INVALID_MESSAGE", "reply_to is not uuid v4")
        return env
    }

    fun encode(env: Envelope): String = json.encodeToString(Envelope.serializer(), env)
}

class ProtocolException(val code: String, message: String) : Exception("$code: $message")
