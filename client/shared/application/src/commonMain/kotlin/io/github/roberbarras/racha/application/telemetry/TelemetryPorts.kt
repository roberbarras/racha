package io.github.roberbarras.racha.application.telemetry

import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/** A usage event (§8): `app_open`, `watch` (one viewing session), `resource_view`, `feature`, `sync_failed`. */
data class TelemetryEvent(val id: String, val type: String, val occurredAt: Instant, val properties: JsonObject)

/** A client error with its stack trace (§8). */
data class ClientError(
    val id: String,
    val platform: String,
    val appVersion: String,
    val message: String,
    val stackTrace: String?,
    val occurredAt: Instant,
    val context: JsonObject,
)

/** Inbound port for features that report what the user does; the app wires [Telemetry], tests and previews [NONE]. */
fun interface UsageEvents {
    suspend fun record(type: String, properties: JsonObject)

    companion object {
        val NONE = UsageEvents { _, _ -> }
    }
}

/** Outbound port: events and errors waiting to be sent, oldest first, bounded. */
interface TelemetryOutbox {
    suspend fun add(event: TelemetryEvent)

    suspend fun add(error: ClientError)

    suspend fun events(limit: Int): List<TelemetryEvent>

    suspend fun errors(limit: Int): List<ClientError>

    suspend fun remove(ids: Collection<String>)

    suspend fun clear()
}

/** Outbound port: `POST /analytics/events` and `POST /errors` (§7.3) at [baseUrl] (the sync server unless configured). */
interface TelemetryServer {
    suspend fun sendEvents(baseUrl: String, accessToken: String, events: List<TelemetryEvent>)

    suspend fun sendErrors(baseUrl: String, accessToken: String, errors: List<ClientError>)
}
