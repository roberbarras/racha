package io.github.roberbarras.racha.application.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The stored session as one small JSON string, for secret stores that keep a single value. */
object SessionCodec {
    fun encode(s: StoredSession): String = JsonObject(
        mapOf(
            "v" to JsonPrimitive(1), "serverUrl" to JsonPrimitive(s.serverUrl), "userId" to JsonPrimitive(s.userId),
            "deviceId" to JsonPrimitive(s.deviceId), "email" to JsonPrimitive(s.email), "refreshToken" to JsonPrimitive(s.refreshToken),
        ),
    ).toString()

    fun decode(text: String): StoredSession? = runCatching {
        val o = Json.parseToJsonElement(text).jsonObject
        fun f(name: String) = o.getValue(name).jsonPrimitive.content
        StoredSession(f("serverUrl"), f("userId"), f("deviceId"), f("email"), f("refreshToken"))
    }.getOrNull()
}
