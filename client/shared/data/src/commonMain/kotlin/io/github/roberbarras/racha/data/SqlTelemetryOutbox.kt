package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.application.telemetry.ClientError
import io.github.roberbarras.racha.application.telemetry.TelemetryEvent
import io.github.roberbarras.racha.application.telemetry.TelemetryOutbox
import io.github.roberbarras.racha.data.db.RachaDatabase
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant

/** Telemetry outbox (spec §8): one JSON payload per row; at most [MAX_ROWS], the oldest go first. */
class SqlTelemetryOutbox(private val database: RachaDatabase, private val io: CoroutineContext) : TelemetryOutbox {
    private val q get() = database.telemetryQueries

    override suspend fun add(event: TelemetryEvent) = insert(event.id, EVENT, event.occurredAt, buildJsonObject {
        put("type", event.type)
        put("occurredAt", event.occurredAt.toString())
        put("properties", event.properties)
    })

    override suspend fun add(error: ClientError) = insert(error.id, ERROR, error.occurredAt, buildJsonObject {
        put("platform", error.platform)
        put("appVersion", error.appVersion)
        put("message", error.message)
        put("stackTrace", error.stackTrace)
        put("occurredAt", error.occurredAt.toString())
        put("context", error.context)
    })

    override suspend fun events(limit: Int): List<TelemetryEvent> = withContext(io) {
        q.oldestTelemetry(EVENT, limit.toLong()).executeAsList().mapNotNull { row ->
            runCatching {
                val o = Json.parseToJsonElement(row.payload).jsonObject
                TelemetryEvent(row.id, o.str("type")!!, Instant.parse(o.str("occurredAt")!!), o["properties"]?.jsonObject ?: JsonObject(emptyMap()))
            }.getOrNull()
        }
    }

    override suspend fun errors(limit: Int): List<ClientError> = withContext(io) {
        q.oldestTelemetry(ERROR, limit.toLong()).executeAsList().mapNotNull { row ->
            runCatching {
                val o = Json.parseToJsonElement(row.payload).jsonObject
                ClientError(
                    row.id, o.str("platform").orEmpty(), o.str("appVersion").orEmpty(), o.str("message")!!, o.str("stackTrace"),
                    Instant.parse(o.str("occurredAt")!!), o["context"]?.jsonObject ?: JsonObject(emptyMap()),
                )
            }.getOrNull()
        }
    }

    override suspend fun remove(ids: Collection<String>) = withContext(io) {
        ids.chunked(500).forEach { q.deleteTelemetry(it) }
    }

    override suspend fun clear() = withContext(io) { q.clearTelemetry(); Unit }

    private suspend fun insert(id: String, kind: String, at: Instant, payload: JsonObject) = withContext(io) {
        q.transaction {
            q.insertTelemetry(id, kind, payload.toString(), at.toEpochMilliseconds())
            val excess = q.countTelemetry().executeAsOne() - MAX_ROWS
            if (excess > 0) q.dropOldestTelemetry(excess)
        }
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    companion object {
        const val MAX_ROWS = 5_000L
        private const val EVENT = "event"
        private const val ERROR = "error"
    }
}
