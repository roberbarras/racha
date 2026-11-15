package io.github.roberbarras.racha.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** JSON shapes of the server API (server/docs: SERVIDOR.md, OpenAPI at /v3/api-docs). */
@Serializable
internal data class DeviceDto(val id: String? = null, val name: String, val platform: String, val appVersion: String)

@Serializable
internal data class RegisterDto(val email: String, val password: String, val displayName: String? = null, val device: DeviceDto)

@Serializable
internal data class LoginDto(val email: String, val password: String, val device: DeviceDto)

@Serializable
internal data class RefreshDto(val refreshToken: String)

@Serializable
internal data class TokenDto(
    val userId: String, val deviceId: String, val accessToken: String, val expiresIn: Long, val refreshToken: String, val tokenType: String = "Bearer",
)

@Serializable
internal data class ChangeDto(val entity: String, val id: String, val updatedAt: String, val deleted: Boolean, val data: JsonObject)

@Serializable
internal data class PushDto(val changes: List<ChangeDto>)

@Serializable
internal data class RecordDto(val entity: String, val id: String, val updatedAt: String, val deleted: Boolean, val seq: Long, val data: JsonObject)

@Serializable
internal data class ResultDto(val entity: String? = null, val id: String, val outcome: String, val reason: String? = null, val server: RecordDto? = null)

@Serializable
internal data class PushResponseDto(val results: List<ResultDto>, val applied: Int = 0, val stale: Int = 0, val invalid: Int = 0)

@Serializable
internal data class PageDto(val changes: List<RecordDto>, val cursor: Long, val hasMore: Boolean)

/** RFC 9457 problem details, with the server's stable `code`. */
@Serializable
internal data class ProblemDto(val code: String? = null, val detail: String? = null, val status: Int? = null)

@Serializable
internal data class EventDto(val id: String, val type: String, val occurredAt: String, val properties: JsonObject)

@Serializable
internal data class ErrorDto(
    val id: String, val platform: String, val appVersion: String, val message: String, val stackTrace: String? = null, val occurredAt: String,
    val context: JsonObject,
)

@Serializable
internal data class DayWatchDto(val day: String, val seconds: Long)

@Serializable
internal data class CategoryWatchDto(val categoryId: String? = null, val seconds: Long)

@Serializable
internal data class PersonalStatsDto(
    val watchSeconds: Long = 0,
    val watchByDay: List<DayWatchDto> = emptyList(),
    val watchByCategory: List<CategoryWatchDto> = emptyList(),
)
