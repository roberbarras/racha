package io.github.roberbarras.racha.application.preferences

import io.github.roberbarras.racha.application.library.CategoryRepository
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.domain.library.Category
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.preferences.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What an import changed; the rest of the file (unknown keys, invalid values) was skipped. */
data class ImportReport(val settings: Int, val categories: Int, val skipped: Int)

/** The file is not a Racha configuration, or comes from a newer version this one cannot read. */
class InvalidConfigFile(message: String) : Exception(message)

/**
 * Export/import of the configuration (spec §9 "Almacenamiento", ADR-0010): settings the user changed and the
 * categories with their visibility per mode and exclusions. Never secrets (sync session, API keys), folders (paths
 * are per device) or whether sync is on (it needs signing in).
 */
class ConfigTransfer(
    private val preferences: Preferences,
    private val categories: CategoryRepository,
    private val clock: Clock,
    private val appVersion: String,
) {
    suspend fun export(): String {
        val changed = preferences.changed()
        val settings = changed.filterKeys { it !in NOT_EXPORTED }
        val all = categories.all().sortedBy { it.order }
        val json = buildJsonObject {
            put("format", FORMAT)
            put("schemaVersion", SCHEMA_VERSION)
            put("appVersion", appVersion)
            put("exportedAt", clock.now().toString())
            putJsonObject("settings") { settings.toSortedMap().forEach { (k, v) -> put(k, v) } }
            putJsonArray("categories") {
                all.forEach { c ->
                    add(buildJsonObject {
                        put("key", c.key.value); put("name", c.name); put("icon", c.icon); put("color", c.color)
                        put("isPrimary", c.isPrimary); put("order", c.order)
                        putJsonArray("excludePatterns") { c.excludePatterns.forEach { add(JsonPrimitive(it)) } }
                    })
                }
            }
            putJsonObject("modes") {
                put("current", changed[Settings.Mode.key] ?: Settings.Mode.encode(Settings.Mode.default))
                put("study", buildJsonArray { all.filter { it.visibleInStudy }.forEach { add(JsonPrimitive(it.key.value)) } })
                put("leisure", buildJsonArray { all.filter { it.visibleInLeisure }.forEach { add(JsonPrimitive(it.key.value)) } })
            }
        }
        return PRETTY.encodeToString(JsonObject.serializer(), json)
    }

    /** @throws InvalidConfigFile when [text] is not a configuration this version understands. */
    suspend fun import(text: String): ImportReport {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw InvalidConfigFile("not JSON")
        if (root.str("format") != FORMAT) throw InvalidConfigFile("not a Racha configuration")
        val version = root["schemaVersion"]?.jsonPrimitive?.intOrNull ?: throw InvalidConfigFile("no schemaVersion")
        if (version > SCHEMA_VERSION) throw InvalidConfigFile("made by a newer version ($version)")
        // Version 1 is the only one so far: older files will be migrated here, one version at a time.

        var settings = 0
        var categoriesChanged = 0
        var skipped = 0
        root["settings"]?.let { it as? JsonObject }?.forEach { (key, value) ->
            val raw = (value as? JsonPrimitive)?.contentOrNull
            if (key !in NOT_EXPORTED && raw != null && preferences.setEncoded(key, raw)) settings++ else skipped++
        }
        val modes = root["modes"] as? JsonObject
        val study = modes?.keys("study")
        val leisure = modes?.keys("leisure")
        val now = clock.now()
        var primary: CategoryKey? = null
        (root["categories"] as? JsonArray)?.forEach { element ->
            val c = element as? JsonObject
            val key = c?.str("key")?.let { runCatching { CategoryKey(it) }.getOrNull() }
            if (c == null || key == null) return@forEach run { skipped++ }
            val existing = categories.get(key)
            val updated = runCatching {
                Category(
                    key = key,
                    name = c.str("name")?.takeIf { it.isNotBlank() } ?: existing?.name.takeIf { key.isBuiltIn },
                    icon = c.str("icon") ?: existing?.icon ?: "folder",
                    color = c.str("color") ?: existing?.color ?: "#7B61FF",
                    isPrimary = existing?.isPrimary ?: false,
                    order = c["order"]?.jsonPrimitive?.intOrNull ?: existing?.order ?: 0,
                    visibleInStudy = study?.contains(key.value) ?: existing?.visibleInStudy ?: true,
                    visibleInLeisure = leisure?.contains(key.value) ?: existing?.visibleInLeisure ?: true,
                    excludePatterns = (c["excludePatterns"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        ?: existing?.excludePatterns ?: Category.DEFAULT_EXCLUDES,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
            }.getOrNull()
            if (updated == null || (!key.isBuiltIn && updated.name == null)) return@forEach run { skipped++ }
            if (updated != existing?.copy(updatedAt = now)) {
                categories.upsert(updated)
                categoriesChanged++
            }
            if (c["isPrimary"]?.jsonPrimitive?.booleanOrNull == true) primary = key
        }
        primary?.let { key -> if (categories.get(key)?.isPrimary != true) categories.setPrimary(key, now) }
        modes?.str("current")?.let { if (preferences.setEncoded(Settings.Mode.key, it)) settings++ }
        return ImportReport(settings, categoriesChanged, skipped)
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.keys(key: String): Set<String>? =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()

    companion object {
        const val FORMAT = "racha-config"
        const val SCHEMA_VERSION = 1

        /** Turning sync on needs a sign-in; the mode travels in "modes". */
        private val NOT_EXPORTED = setOf(Settings.SyncEnabled.key, Settings.Mode.key)

        private val PRETTY = Json { prettyPrint = true }
    }
}
