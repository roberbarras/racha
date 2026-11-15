package io.github.roberbarras.racha.application.telemetry

import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.domain.preferences.Settings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/**
 * Records usage events and client errors for the account's server (§8). Only while signed in (sync on): without an
 * account there is nowhere to send them, and nothing is kept. Sending is [TelemetryUploader]'s job.
 */
class Telemetry(
    private val outbox: TelemetryOutbox,
    private val preferences: Preferences,
    private val clock: Clock,
    private val platform: String,
    private val appVersion: String,
) : UsageEvents {
    override suspend fun record(type: String, properties: JsonObject) {
        if (!enabled()) return
        outbox.add(TelemetryEvent(Uuid.random().toString(), type, clock.now(), properties))
    }

    /** "Active users, by platform and mode" (§8). */
    suspend fun appOpened() = record("app_open", buildJsonObject {
        put("platform", platform)
        put("appVersion", appVersion)
        put("mode", preferences.get(Settings.Mode).name.lowercase())
    })

    suspend fun error(error: Throwable, context: Map<String, String> = emptyMap()) {
        if (!enabled()) return
        outbox.add(
            ClientError(
                Uuid.random().toString(), platform, appVersion,
                (error.message ?: error::class.simpleName ?: "error").take(MAX_MESSAGE), error.stackTraceToString().take(MAX_STACK),
                clock.now(), JsonObject(context.mapValues { JsonPrimitive(it.value) }),
            ),
        )
    }

    private suspend fun enabled() = runCatching { preferences.get(Settings.SyncEnabled) }.getOrDefault(false)

    companion object {
        const val MAX_MESSAGE = 2_000
        const val MAX_STACK = 32_000

        /** A feature the user used (§8 "PiP, A-B loop, marcadores…"). */
        suspend fun UsageEvents.feature(name: String, details: Map<String, String> = emptyMap()) =
            record("feature", buildJsonObject {
                put("name", name)
                details.forEach { (k, v) -> put(k, v) }
            })
    }
}
