package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.application.sync.LocalSyncState
import io.github.roberbarras.racha.application.sync.PendingChange
import io.github.roberbarras.racha.application.sync.RemoteRoot
import io.github.roberbarras.racha.application.sync.SettingConflict
import io.github.roberbarras.racha.application.sync.SyncChange
import io.github.roberbarras.racha.application.sync.SyncLocalStore
import io.github.roberbarras.racha.application.sync.SyncRecord
import io.github.roberbarras.racha.data.db.App_setting
import io.github.roberbarras.racha.data.db.RachaDatabase
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.preferences.ConflictPolicy
import io.github.roberbarras.racha.domain.preferences.SettingScope
import io.github.roberbarras.racha.domain.preferences.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import io.github.roberbarras.racha.data.db.Bookmark as BookmarkRow
import io.github.roberbarras.racha.data.db.Category as CategoryRow
import io.github.roberbarras.racha.data.db.Favorite as FavoriteRow
import io.github.roberbarras.racha.data.db.History_entry as HistoryRow
import io.github.roberbarras.racha.data.db.Progress as ProgressRow
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant

/**
 * The local side of sync (ADR-0012) on SQLite. Outgoing: the trigger-filled queue, collapsed to one change per
 * entity and read as the row's current state (a missing row is a deletion). Incoming: last writer wins per row,
 * applied in one transaction with `sync_state.applying = 1` so nothing is queued back and no local write slips in
 * unrecorded. Content scanned on this device only takes the user's edits (titles, order, intro/outro) from others.
 */
class SqlSyncLocalStore(private val db: RachaDatabase, private val io: CoroutineContext) : SyncLocalStore {
    private val sync get() = db.syncQueries
    private val changes = MutableStateFlow(0L)

    override suspend fun startRecording() = withContext(io) {
        sync.transaction {
            sync.setRecording(1)
            sync.seedCategory(); sync.seedCategoryRoot(); sync.seedLibraryItem(); sync.seedSection(); sync.seedElement()
            sync.seedSubtitle(); sync.seedProgress(); sync.seedBookmark(); sync.seedHistory(); sync.seedFavorite(); sync.seedSetting()
        }
        bump()
    }

    override suspend fun stopRecording() = withContext(io) {
        sync.transaction {
            sync.setRecording(0)
            sync.clearQueue()
            sync.clearRemoteState()
            sync.clearConflicts()
            sync.resetSync()
            db.telemetryQueries.clearTelemetry() // signed out: telemetry has nowhere to go
        }
        bump()
    }

    override suspend fun pending(limit: Int): List<PendingChange> = withContext(io) {
        sync.pendingByEntity(limit.toLong()).executeAsList().mapNotNull { row ->
            val upTo = row.last_id ?: return@mapNotNull null
            val changedAt = Instant.fromEpochMilliseconds(row.changed_at ?: 0)
            val change = outgoing(row.entity_type, row.entity_id, changedAt)
            if (change == null) {
                // Nothing to send (a device-only setting): drop it from the queue.
                sync.deleteQueuedUpTo(row.entity_type, row.entity_id, upTo)
                null
            } else {
                PendingChange(change, upTo)
            }
        }
    }

    override suspend fun acknowledge(changes: List<PendingChange>) = withContext(io) {
        sync.transaction { changes.forEach { sync.deleteQueuedUpTo(it.change.entity, it.change.id.localId(it.change.entity), it.upTo) } }
        bump()
    }

    override suspend fun fail(change: PendingChange, error: String) = withContext(io) {
        val id = change.change.id.localId(change.change.entity)
        sync.failQueuedUpTo(error, change.change.entity, id, change.upTo)
        // A change the server keeps refusing would block nothing else, but should not be retried forever.
        val retries = db.systemQueries.selectPending(Long.MAX_VALUE).executeAsList()
            .filter { it.entity_type == change.change.entity && it.entity_id == id }.maxOfOrNull { it.retries } ?: 0
        if (retries >= MAX_RETRIES) sync.deleteQueuedUpTo(change.change.entity, id, change.upTo)
        bump()
    }

    override suspend fun applyRemote(records: List<SyncRecord>, policy: ConflictPolicy) = withContext(io) {
        sync.transaction {
            sync.setApplying(1)
            try {
                records.forEach { apply(it, policy) }
            } finally {
                sync.setApplying(0)
            }
        }
        bump()
    }

    override suspend fun adoptServerTimes(pushed: List<Pair<SyncChange, SyncRecord>>) = withContext(io) {
        sync.transaction {
            sync.setApplying(1)
            try {
                pushed.forEach { (change, server) ->
                    val id = change.id.localId(change.entity)
                    val at = server.updatedAt.toEpochMilliseconds()
                    val pushedAt = change.updatedAt.toEpochMilliseconds()
                    // Only if the row is still the one that was pushed (same time): a newer local edit keeps its own.
                    when (change.entity) {
                        "category" -> sync.adoptCategoryTime(at, id, pushedAt)
                        "libraryItem" -> sync.adoptItemTime(at, id, pushedAt)
                        "section" -> sync.adoptSectionTime(at, id, pushedAt)
                        "element" -> sync.adoptElementTime(at, id, pushedAt)
                        "subtitle" -> sync.adoptSubtitleTime(at, id, pushedAt)
                        "progress" -> sync.adoptProgressTime(at, id, pushedAt)
                        "bookmark" -> sync.adoptBookmarkTime(at, id, pushedAt)
                        "setting" -> sync.adoptSettingTime(at, id, pushedAt)
                    }
                }
            } finally {
                sync.setApplying(0)
            }
        }
    }

    override suspend fun cursor(): Long = withContext(io) { sync.selectSyncState().executeAsOne().cursor }

    override suspend fun setCursor(cursor: Long) = withContext(io) { sync.setCursor(cursor); Unit }

    override suspend fun recordSuccess(at: Instant) = withContext(io) { sync.recordSuccess(at.toEpochMilliseconds()); bump() }

    override suspend fun recordError(message: String) = withContext(io) { sync.recordError(message.take(500)); bump() }

    /** Re-read after every change made through this store (queue, conflicts, last result). */
    override fun observe(): Flow<LocalSyncState> = changes.map { state() }

    private suspend fun state(): LocalSyncState = withContext(io) {
        val s = sync.selectSyncState().executeAsOne()
        LocalSyncState(
            pending = db.systemQueries.countQueued().executeAsOne(),
            lastSuccessAt = s.last_success_at?.let(Instant::fromEpochMilliseconds),
            lastError = s.last_error,
            conflicts = sync.selectConflicts().executeAsList().map {
                SettingConflict(it.setting_key, it.local_value, it.remote_value, Instant.fromEpochMilliseconds(it.remote_updated_at))
            },
        )
    }

    override suspend fun resolve(key: String, keepLocal: Boolean) = withContext(io) {
        val conflict = sync.selectConflicts().executeAsList().firstOrNull { it.setting_key == key } ?: return@withContext
        val now = currentMillis()
        sync.transaction {
            if (keepLocal) {
                // Newer than the other device's value: wins the next push.
                db.systemQueries.updateSetting(conflict.local_value, now, key)
                db.systemQueries.enqueue("setting", key, "UPSERT", null, now)
            } else {
                sync.setApplying(1)
                db.systemQueries.insertOrIgnoreSetting(App_setting(key, conflict.remote_value, conflict.remote_updated_at))
                db.systemQueries.updateSetting(conflict.remote_value, conflict.remote_updated_at, key)
                sync.setApplying(0)
                sync.deleteQueuedFor("setting", key)
            }
            sync.deleteConflict(key)
        }
        bump()
    }

    override suspend fun remoteRoots(categoryKey: String): List<RemoteRoot> = withContext(io) {
        sync.selectRemoteRoots(categoryKey).executeAsList().map { RemoteRoot(it.key, it.category_key, it.name, rootLayout(it.layout)) }
    }

    private fun bump() {
        changes.value++
    }

    // ---- outgoing ------------------------------------------------------------------------------------------------

    /** The change to push for a queued entity, from its current row; null when it is not synchronised. */
    private fun outgoing(entity: String, id: String, changedAt: Instant): SyncChange? {
        fun gone() = SyncChange(entity, wireId(entity, id), changedAt, true, JsonObject(emptyMap()))
        val lib = db.libraryQueries
        return when (entity) {
            "category" -> db.categoryQueries.selectByKey(id).executeAsOneOrNull()?.let { change(entity, id, it.updated_at, it.toJson()) } ?: gone()
            "categoryRoot" -> sync.rootByKey(id).executeAsOneOrNull()?.let { root ->
                change(entity, id, changedAt.toEpochMilliseconds(), buildJsonObject {
                    put("categoryId", root.category_key)
                    put("name", folderName(root.location))
                    put("sortOrder", root.sort_order)
                    put("layout", root.layout)
                })
            } ?: gone()
            "libraryItem" -> lib.selectItem(id).executeAsOneOrNull()?.let { r ->
                change(entity, id, r.updated_at, buildJsonObject {
                    put("categoryId", r.category_key); put("rootKey", r.root_key); put("relativePath", r.relative_path); put("kind", r.kind)
                    put("title", r.title); put("titleOverride", r.title_override); put("orderOverride", r.order_override); put("archived", r.archived)
                    put("totalItems", r.total_items); put("totalDurationSec", r.total_duration_sec)
                })
            } ?: gone()
            "section" -> sync.sectionById(id).executeAsOneOrNull()?.let { r ->
                change(entity, id, r.updated_at, buildJsonObject {
                    put("itemId", r.library_item_id); put("kind", r.kind); put("title", r.title); put("titleOverride", r.title_override)
                    put("sortOrder", r.sort_order); put("orderOverride", r.order_override)
                })
            } ?: gone()
            "element" -> lib.selectElement(id).executeAsOneOrNull()?.let { r ->
                change(entity, id, r.updated_at, buildJsonObject {
                    put("itemId", r.library_item_id); put("sectionId", r.section_id); put("kind", r.kind); put("resourceType", r.resource_type)
                    put("title", r.title); put("titleOverride", r.title_override); put("relativePath", r.relative_path)
                    put("durationSec", r.duration_sec); put("sortOrder", r.sort_order); put("orderOverride", r.order_override)
                    put("archived", r.archived); put("fileHash", r.file_hash); put("skipIntroSec", r.skip_intro_sec); put("skipOutroSec", r.skip_outro_sec)
                })
            } ?: gone()
            "subtitle" -> sync.subtitleById(id).executeAsOneOrNull()?.let { r ->
                change(entity, id, r.updated_at, buildJsonObject { put("elementId", r.element_id); put("language", r.language); put("relativePath", r.relative_path) })
            } ?: gone()
            "progress" -> db.learningQueries.selectProgress(id).executeAsOneOrNull()?.let { change(entity, id, it.updated_at, it.toJson()) } ?: gone()
            "bookmark" -> sync.bookmarkById(id).executeAsOneOrNull()?.let { change(entity, id, it.updated_at, it.toJson()) } ?: gone()
            "history" -> sync.historyById(id).executeAsOneOrNull()?.let { change(entity, id, it.watched_at, it.toJson()) } ?: gone()
            "favorite" -> {
                val (type, target) = id.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                sync.favoriteByKey(type, target).executeAsOneOrNull()?.let {
                    change(entity, id, it.created_at, buildJsonObject { put("targetType", it.target_type); put("targetId", it.target_id) })
                } ?: gone()
            }
            "setting" -> {
                // Only per-user settings travel; per-device ones (folders, player engine…) stay here.
                if (Settings.byKey(id)?.scope != SettingScope.USER) return null
                sync.settingRow(id).executeAsOneOrNull()?.let { change(entity, id, it.updated_at, buildJsonObject { put("value", it.value_) }) } ?: gone()
            }
            else -> null
        }
    }

    private fun change(entity: String, id: String, updatedAt: Long, data: JsonObject) =
        SyncChange(entity, wireId(entity, id), Instant.fromEpochMilliseconds(updatedAt), false, data)

    // ---- incoming ------------------------------------------------------------------------------------------------

    private fun apply(r: SyncRecord, policy: ConflictPolicy) {
        val at = r.updatedAt.toEpochMilliseconds()
        val d = r.data
        when (r.entity) {
            "category" -> {
                val local = db.categoryQueries.selectByKey(r.id).executeAsOneOrNull()
                if (local != null && local.updated_at >= at) return
                if (r.deleted) {
                    if (local != null && CategoryKey(r.id) !in CategoryKey.BUILT_IN) db.categoryQueries.delete(r.id)
                    return
                }
                val row = CategoryRow(
                    key = r.id, name = d.str("name"), icon = d.str("icon") ?: local?.icon ?: "folder", color = d.str("color") ?: local?.color ?: "#E8812A",
                    is_primary = d.bool("isPrimary") ?: false, sort_order = d.long("sortOrder") ?: local?.sort_order ?: 0,
                    visible_in_study = d.bool("visibleInStudy") ?: true, visible_in_leisure = d.bool("visibleInLeisure") ?: true,
                    exclude_patterns = d.strings("excludePatterns")?.joinToString("\n") ?: local?.exclude_patterns ?: "",
                    created_at = local?.created_at ?: at, updated_at = at,
                )
                if (row.is_primary) db.categoryQueries.clearPrimary(at, row.key)
                db.categoryQueries.insertOrIgnore(row)
                db.categoryQueries.update(row.name, row.icon, row.color, row.is_primary, row.sort_order, row.visible_in_study,
                    row.visible_in_leisure, row.exclude_patterns, row.created_at, row.updated_at, row.key)
            }
            "categoryRoot" -> {
                if (sync.rootByKey(r.id).executeAsOneOrNull() != null) return // a root of this device
                if (r.deleted) sync.deleteRemoteRoot(r.id)
                else sync.upsertRemoteRoot(r.id, d.str("categoryId") ?: return, d.str("name") ?: "", at, d.str("layout"))
            }
            "libraryItem" -> db.libraryQueries.selectItem(r.id).executeAsOneOrNull()?.let { local ->
                if (!r.deleted && local.updated_at < at) sync.applyItemOverrides(d.str("titleOverride"), d.long("orderOverride"), at, r.id)
            }
            "section" -> sync.sectionById(r.id).executeAsOneOrNull()?.let { local ->
                if (!r.deleted && local.updated_at < at) sync.applySectionOverrides(d.str("titleOverride"), d.long("orderOverride"), at, r.id)
            }
            "element" -> db.libraryQueries.selectElement(r.id).executeAsOneOrNull()?.let { local ->
                if (!r.deleted && local.updated_at < at) {
                    sync.applyElementOverrides(d.str("titleOverride"), d.long("orderOverride"), d.long("skipIntroSec"), d.long("skipOutroSec"), at, r.id)
                }
            }
            "subtitle" -> Unit // files of this device decide its subtitles
            "progress" -> {
                val local = db.learningQueries.selectProgress(r.id).executeAsOneOrNull()
                if (local != null && local.updated_at >= at) return
                if (r.deleted) {
                    if (local != null) db.libraryQueries.deleteProgress(r.id)
                    return
                }
                val position = d.long("positionSec") ?: 0
                val row = ProgressRow(
                    element_id = r.id, position_sec = position, duration_sec = d.long("durationSec"), completed = d.bool("completed") ?: false,
                    max_position_sec = maxOf(local?.max_position_sec ?: 0, d.long("maxPositionSec") ?: 0, position),
                    last_watched_at = d.str("lastWatchedAt")?.let { Instant.parse(it).toEpochMilliseconds() } ?: local?.last_watched_at, updated_at = at,
                )
                db.learningQueries.insertOrIgnoreProgress(row)
                db.learningQueries.updateProgress(row.position_sec, row.duration_sec, row.completed, row.max_position_sec, row.last_watched_at, row.updated_at, row.element_id)
            }
            "bookmark" -> {
                val local = sync.bookmarkById(r.id).executeAsOneOrNull()
                if (local != null && local.updated_at >= at) return
                if (r.deleted) {
                    if (local != null) db.learningQueries.deleteBookmark(r.id)
                    return
                }
                val row = BookmarkRow(r.id, d.str("elementId") ?: return, d.long("positionSec") ?: 0, d.str("note") ?: "",
                    d.str("createdAt")?.let { Instant.parse(it).toEpochMilliseconds() } ?: at, at)
                db.learningQueries.insertOrIgnoreBookmark(row)
                db.learningQueries.updateBookmark(row.element_id, row.timestamp_sec, row.note, row.created_at, row.updated_at, row.id)
            }
            "history" -> if (!r.deleted) {
                val watched = d.str("watchedAt")?.let { Instant.parse(it).toEpochMilliseconds() } ?: at
                db.learningQueries.insertHistory(HistoryRow(r.id, d.str("elementId") ?: return, watched, d.long("positionSec") ?: 0))
            }
            "favorite" -> {
                val type = d.str("targetType") ?: r.id.substringBefore(':')
                val target = d.str("targetId") ?: r.id.substringAfter(':')
                if (r.deleted) db.learningQueries.deleteFavorite(type, target)
                else db.learningQueries.insertFavorite(FavoriteRow(type, target, at))
            }
            "setting" -> applySetting(r, policy)
        }
    }

    private fun applySetting(r: SyncRecord, policy: ConflictPolicy) {
        val key = r.id.removePrefix("user:")
        if (Settings.byKey(key)?.scope != SettingScope.USER) return
        val at = r.updatedAt.toEpochMilliseconds()
        val local = sync.settingRow(key).executeAsOneOrNull()
        if (local != null && local.updated_at >= at) return
        val remote = r.data.str("value")
        // An open question stays open: a later remote value only updates what the user is asked about.
        val open = sync.selectConflicts().executeAsList().firstOrNull { it.setting_key == key }
        if (open != null) {
            if (remote != null && at > open.remote_updated_at) sync.upsertConflict(key, open.local_value, remote, at, currentMillis())
            return
        }
        val changedHere = sync.hasPending("setting", key).executeAsOne() > 0
        if (policy == ConflictPolicy.ASK && changedHere && local != null && remote != null && local.value_ != remote) {
            sync.upsertConflict(key, local.value_, remote, at, currentMillis())
            return
        }
        if (r.deleted || remote == null) {
            db.systemQueries.deleteSetting(key)
        } else {
            db.systemQueries.insertOrIgnoreSetting(App_setting(key, remote, at))
            db.systemQueries.updateSetting(remote, at, key)
        }
        sync.deleteQueuedFor("setting", key)
    }

    private companion object {
        const val MAX_RETRIES = 5

        fun wireId(entity: String, localId: String) = if (entity == "setting") "user:$localId" else localId

        fun String.localId(entity: String) = if (entity == "setting") removePrefix("user:") else this

        fun currentMillis() = kotlin.time.Clock.System.now().toEpochMilliseconds()

        /** `file:///…/Cursos%20AWS/` → "Cursos AWS" (shown to the user when linking roots on another device). */
        fun folderName(location: String): String {
            val encoded = location.trimEnd('/').substringAfterLast('/')
            val bytes = ArrayList<Byte>(encoded.length)
            var i = 0
            while (i < encoded.length) {
                val hex = if (encoded[i] == '%' && i + 2 < encoded.length + 1 && i + 2 <= encoded.lastIndex) encoded.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (hex != null) {
                    bytes += hex.toByte()
                    i += 3
                } else {
                    encoded[i].toString().encodeToByteArray().forEach(bytes::add)
                    i++
                }
            }
            return bytes.toByteArray().decodeToString()
        }

        fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
        fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
        fun JsonObject.strings(key: String): List<String>? = runCatching { this[key]?.jsonArray?.map { it.jsonPrimitive.content } }.getOrNull()

        fun CategoryRow.toJson() = buildJsonObject {
            put("name", name); put("icon", icon); put("color", color); put("isPrimary", is_primary); put("sortOrder", sort_order)
            put("visibleInStudy", visible_in_study); put("visibleInLeisure", visible_in_leisure)
            putJsonArray("excludePatterns") { exclude_patterns.lines().filter(String::isNotEmpty).forEach { add(JsonPrimitive(it)) } }
        }

        fun ProgressRow.toJson() = buildJsonObject {
            put("positionSec", position_sec); put("durationSec", duration_sec); put("completed", completed); put("maxPositionSec", max_position_sec)
            put("lastWatchedAt", last_watched_at?.let { Instant.fromEpochMilliseconds(it).toString() })
        }

        fun BookmarkRow.toJson() = buildJsonObject {
            put("elementId", element_id); put("positionSec", timestamp_sec); put("note", note); put("createdAt", Instant.fromEpochMilliseconds(created_at).toString())
        }

        fun HistoryRow.toJson() = buildJsonObject {
            put("elementId", element_id); put("watchedAt", Instant.fromEpochMilliseconds(watched_at).toString()); put("positionSec", position_at_end_sec)
        }
    }
}
