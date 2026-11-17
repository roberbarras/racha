package io.github.roberbarras.racha.application.backup

import io.github.roberbarras.racha.application.learning.LocalStatRepository
import io.github.roberbarras.racha.application.library.CategoryRepository
import io.github.roberbarras.racha.application.library.scan.ScanLibrary
import io.github.roberbarras.racha.application.playback.PlaybackMemoryRepository
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.application.sync.SyncLocalStore
import io.github.roberbarras.racha.application.sync.SyncRecord
import io.github.roberbarras.racha.domain.learning.LocalStat
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.RootKey
import io.github.roberbarras.racha.domain.library.RootLayout
import io.github.roberbarras.racha.domain.playback.PlaybackChoice
import io.github.roberbarras.racha.domain.playback.SubtitleChoice
import io.github.roberbarras.racha.domain.preferences.ConflictPolicy
import io.github.roberbarras.racha.domain.preferences.SettingScope
import io.github.roberbarras.racha.domain.preferences.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Instant

class InvalidBackupFile(message: String) : Exception(message)

/** What a restore brought back. */
data class RestoreReport(val records: Int, val folders: Int, val settings: Int, val skipped: Int)

/**
 * The whole of this device's Racha in one file and back (H16, ADR-0015): every synced row (as sync pushes them), plus
 * what only this device has: its folders (paths and what they hold), its own settings, the player's remembered
 * choices and the running totals behind the statistics. Never secrets (session, API keys).
 *
 * The same `racha-backup` format a server exports: a server's file restores here too (without folders), and this one
 * imports into a server (which ignores the device part).
 */
class FullBackup(
    private val sync: SyncLocalStore,
    private val categories: CategoryRepository,
    private val preferences: Preferences,
    private val memory: PlaybackMemoryRepository,
    private val stats: LocalStatRepository,
    private val scan: ScanLibrary,
    private val clock: Clock,
    private val app: String,
) {
    suspend fun export(): String {
        val records = sync.snapshot()
        val roots = categories.all().flatMap { categories.roots(it.key) }
        val deviceSettings = preferences.changed().filterKeys { key ->
            Settings.byKey(key)?.scope == SettingScope.DEVICE && key !in NOT_EXPORTED
        }
        val json = buildJsonObject {
            put("format", FORMAT)
            put("schemaVersion", SCHEMA_VERSION)
            put("createdAt", clock.now().toString())
            putJsonObject("source") { put("kind", "device"); put("app", app) }
            putJsonArray("records") {
                records.forEach { r ->
                    add(buildJsonObject {
                        put("entity", r.entity); put("id", r.id); put("updatedAt", r.updatedAt.toString()); put("deleted", false); put("data", r.data)
                    })
                }
            }
            putJsonObject("device") {
                putJsonArray("roots") {
                    roots.forEach { root ->
                        add(buildJsonObject {
                            put("key", root.key.value); put("categoryKey", root.categoryKey.value); put("location", root.location)
                            put("order", root.order); put("layout", root.layout.name)
                        })
                    }
                }
                putJsonObject("settings") { deviceSettings.toSortedMap().forEach { (k, v) -> put(k, v) } }
                putJsonObject("playbackMemory") {
                    memory.all().forEach { (key, c) ->
                        putJsonObject(key) {
                            c.speed?.let { put("speed", it) }
                            c.audioLanguage?.let { put("audioLanguage", it) }
                            c.audioIndex?.let { put("audioIndex", it) }
                            when (val s = c.subtitles) {
                                null -> {}
                                SubtitleChoice.Off -> put("subtitles", "off")
                                is SubtitleChoice.Language -> put("subtitles", s.code)
                            }
                        }
                    }
                }
                putJsonArray("localStats") {
                    stats.all().forEach { s -> add(buildJsonArray { add(s.metric); add(s.period); add(s.value) }) }
                }
            }
        }
        return JSON.encodeToString(JsonObject.serializer(), json)
    }

    /**
     * Restores a backup over what is here: per row the newest wins (like sync), folders this device does not have yet
     * are added and scanned, running totals keep the larger figure. Nothing changes if the file is not a backup.
     */
    suspend fun import(text: String): RestoreReport {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw InvalidBackupFile("not JSON")
        if (root.str("format") != FORMAT) throw InvalidBackupFile("not a Racha backup")
        val version = root["schemaVersion"]?.jsonPrimitive?.intOrNull ?: throw InvalidBackupFile("no schemaVersion")
        if (version > SCHEMA_VERSION) throw InvalidBackupFile("made by a newer version ($version)")

        var skipped = 0
        val records = (root["records"] as? JsonArray).orEmpty().mapNotNull { element ->
            val o = element as? JsonObject
            val entity = o?.str("entity")
            val id = o?.str("id")
            val at = o?.str("updatedAt")?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val data = o?.get("data") as? JsonObject
            if (entity == null || id == null || at == null || data == null) null.also { skipped++ }
            else SyncRecord(entity, id, at, o["deleted"]?.jsonPrimitive?.booleanOrNull == true, 0, data)
        }
        // Categories first: folders belong to one.
        sync.applyRemote(records.filter { it.entity == "category" }, ConflictPolicy.LAST_WRITE_WINS)

        val device = root["device"] as? JsonObject
        var folders = 0
        val here = categories.all().flatMap { categories.roots(it.key) }
        (device?.get("roots") as? JsonArray).orEmpty().forEach { element ->
            val o = element as? JsonObject ?: return@forEach run { skipped++ }
            val key = o.str("key")?.let { runCatching { RootKey(it) }.getOrNull() }
            val category = o.str("categoryKey")?.let { runCatching { CategoryKey(it) }.getOrNull() }
            val location = o.str("location")
            if (key == null || category == null || location == null || categories.get(category) == null) return@forEach run { skipped++ }
            if (here.any { it.key == key || it.location == location }) return@forEach // already here
            val layout = RootLayout.entries.firstOrNull { it.name == o.str("layout") } ?: RootLayout.AUTO
            categories.upsertRoot(CategoryRoot(key, category, location, o["order"]?.jsonPrimitive?.intOrNull ?: 0, layout))
            folders++
        }

        var settings = 0
        (device?.get("settings") as? JsonObject)?.forEach { (key, value) ->
            val raw = (value as? JsonPrimitive)?.contentOrNull
            if (key !in NOT_EXPORTED && raw != null && preferences.setEncoded(key, raw)) settings++ else skipped++
        }

        val now = clock.now()
        (device?.get("playbackMemory") as? JsonObject)?.forEach { (key, value) ->
            val o = value as? JsonObject ?: return@forEach
            val subtitles = when (val s = o.str("subtitles")) {
                null -> null
                "off" -> SubtitleChoice.Off
                else -> SubtitleChoice.Language(s)
            }
            val choice = PlaybackChoice(o["speed"]?.jsonPrimitive?.doubleOrNull, o.str("audioLanguage"), o["audioIndex"]?.jsonPrimitive?.intOrNull, subtitles)
            memory.save(key, choice.orElse(memory.get(key)), now)
        }
        (device?.get("localStats") as? JsonArray).orEmpty().forEach { element ->
            val a = element as? JsonArray ?: return@forEach
            val metric = a.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return@forEach
            val period = a.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: return@forEach
            val value = a.getOrNull(2)?.jsonPrimitive?.doubleOrNull ?: return@forEach
            val current = stats.get(metric, period)?.value ?: 0.0
            if (value > current) stats.save(LocalStat(metric, period, value, now))
        }

        sync.applyRemote(records, ConflictPolicy.LAST_WRITE_WINS)
        // The restored folders' courses come from their files; then the user's edits of titles and order apply to them.
        if (folders > 0) {
            scan.all()
            sync.applyRemote(records.filter { it.entity in LIBRARY }, ConflictPolicy.LAST_WRITE_WINS)
        }
        // Signed in: send what came back to the server too (it keeps the newest of each).
        if (preferences.get(Settings.SyncEnabled)) sync.startRecording()
        return RestoreReport(records.size, folders, settings, skipped)
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val FORMAT = "racha-backup"
        const val SCHEMA_VERSION = 1
        private val JSON = Json { prettyPrint = false }

        /** Turning sync on needs signing in: a restore never does it by itself. */
        private val NOT_EXPORTED = setOf(Settings.SyncEnabled.key)
        private val LIBRARY = setOf("libraryItem", "section", "element")
    }
}
