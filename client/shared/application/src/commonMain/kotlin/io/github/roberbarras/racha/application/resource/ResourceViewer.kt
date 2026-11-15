package io.github.roberbarras.racha.application.resource

import io.github.roberbarras.racha.application.browse.UpdateItemProgress
import io.github.roberbarras.racha.application.learning.HistoryRepository
import io.github.roberbarras.racha.application.learning.ProgressRepository
import io.github.roberbarras.racha.application.library.CategoryRepository
import io.github.roberbarras.racha.application.library.LibraryRepository
import io.github.roberbarras.racha.application.playback.QueueEntry
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.domain.learning.HistoryEntry
import io.github.roberbarras.racha.domain.learning.Progress
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.domain.library.LibraryItem
import io.github.roberbarras.racha.domain.library.PlayableElement
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.ResourceType
import io.github.roberbarras.racha.domain.playback.PlaybackRules
import io.github.roberbarras.racha.domain.resource.InternetShortcut
import io.github.roberbarras.racha.domain.resource.TextLinks
import io.github.roberbarras.racha.domain.resource.TextSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Instant
import kotlin.uuid.Uuid
import io.github.roberbarras.racha.application.telemetry.UsageEvents
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Outbound port: reading resource files of a library root (spec §5). */
interface ResourceFiles {
    /** The file's text (encoding detected), at most [maxBytes] read; null if it cannot be read. */
    suspend fun readText(location: String, path: RelativePath, maxBytes: Int): ResourceTextFile?

    /** Absolute local path of a file or folder of the root, or null if it does not exist. */
    fun localPath(location: String, path: RelativePath): String?
}

data class ResourceTextFile(val text: String, val truncated: Boolean)

/** What the viewer shows for each resource type (spec §5). */
sealed interface ResourceContent {
    /**
     * A web page served only from its course folder [folder] (ADR-0003): [page] is relative to that folder, so the
     * page's relative CSS/JS/images work and nothing outside the course can be reached.
     */
    data class Html(val folder: String, val page: RelativePath) : ResourceContent

    /** A `.url` shortcut: the target, or null when it has none or an unsafe one. */
    data class Link(val url: String?) : ResourceContent

    data class Text(val segments: List<TextSegment>, val truncated: Boolean) : ResourceContent

    data class Pdf(val path: String) : ResourceContent

    /** The file is gone (archived) or unreadable. */
    data object Missing : ResourceContent
}

data class ResourceState(
    val item: LibraryItem? = null,
    val element: PlayableElement? = null,
    val queue: List<QueueEntry> = emptyList(),
    val content: ResourceContent? = null,
    val watched: Boolean = false,
) {
    val next: PlayableElement? get() = element?.let { e -> PlaybackRules.next(queue.map { it.element }, e.id) }
    val previous: PlayableElement? get() = element?.let { e -> PlaybackRules.previous(queue.map { it.element }, e.id) }
}

/**
 * The resource viewer's logic (spec §5): resources are course elements with "next" and a watched state, marked when
 * the viewer closes or explicitly. A visit of a few seconds also lands in the history, like a video session.
 */
class ResourceViewer(
    private val library: LibraryRepository,
    private val categories: CategoryRepository,
    private val files: ResourceFiles,
    private val progress: ProgressRepository,
    private val history: HistoryRepository,
    private val updateItemProgress: UpdateItemProgress,
    private val clock: Clock,
    private val usage: UsageEvents = UsageEvents.NONE,
) {
    private val mutableState = MutableStateFlow(ResourceState())
    val state: StateFlow<ResourceState> = mutableState.asStateFlow()
    private var openedAt: Instant? = null
    private var closed = false

    suspend fun open(id: ContentId) {
        val element = library.element(id) ?: return
        val item = library.item(element.libraryItemId) ?: return
        val progressById = progress.forItem(item.id).associateBy { it.elementId }
        val location = categories.roots(item.categoryKey).firstOrNull { it.key == item.rootKey }?.location
        mutableState.value = ResourceState(
            item = item,
            element = element,
            queue = library.elements(item.id).map { QueueEntry(it, progressById[it.id]) },
            content = location?.let { content(it, item, element) } ?: ResourceContent.Missing,
            watched = progressById[id]?.completed == true,
        )
        openedAt = clock.now()
    }

    /** Explicit "mark as watched" / "mark as not watched". */
    suspend fun setWatched(watched: Boolean) {
        val element = mutableState.value.element ?: return
        val now = clock.now()
        val current = progress.get(element.id) ?: Progress.start(element.id, null, now)
        val updated = if (watched) current.markWatched(now) else current.markUnwatched(now)
        progress.save(updated)
        updateItemProgress(element.libraryItemId)
        mutableState.update { s ->
            s.copy(watched = watched, queue = s.queue.map { if (it.element.id == element.id) it.copy(progress = updated) else it })
        }
    }

    /** Leaving the viewer (back, next, closing the app): watched, plus a history entry for a real visit. */
    suspend fun close() {
        if (closed) return
        closed = true
        val s = mutableState.value
        val element = s.element ?: return
        if (s.content != ResourceContent.Missing && !s.watched) setWatched(true)
        val start = openedAt ?: return
        val now = clock.now()
        if ((now - start).inWholeSeconds >= MIN_VISIT_SEC) {
            history.add(HistoryEntry(Uuid.random().toString(), element.id, start, 0))
        }
        usage.record("resource_view", buildJsonObject {
            put("elementId", element.id.value)
            put("itemId", element.libraryItemId.value)
            s.item?.let { put("categoryId", it.categoryKey.value) }
            element.resourceType?.let { put("resourceType", it.name.lowercase()) }
            put("startedAt", start.toString())
            put("endedAt", now.toString())
        })
    }

    private suspend fun content(location: String, item: LibraryItem, element: PlayableElement): ResourceContent {
        if (element.archived || element.kind != ElementKind.RESOURCE) return ResourceContent.Missing
        return when (element.resourceType) {
            ResourceType.HTML -> {
                val folder = files.localPath(location, item.relativePath) ?: return ResourceContent.Missing
                val prefix = item.relativePath.value.let { if (it.isEmpty()) "" else "$it/" }
                if (!element.relativePath.value.startsWith(prefix)) return ResourceContent.Missing
                ResourceContent.Html(folder, RelativePath.of(element.relativePath.value.removePrefix(prefix)))
            }
            ResourceType.URL -> files.readText(location, element.relativePath, SHORTCUT_MAX_BYTES)
                ?.let { ResourceContent.Link(InternetShortcut.url(it.text)) } ?: ResourceContent.Missing
            ResourceType.TEXT -> files.readText(location, element.relativePath, TEXT_MAX_BYTES)
                ?.let { ResourceContent.Text(TextLinks.split(it.text), it.truncated) } ?: ResourceContent.Missing
            ResourceType.PDF -> files.localPath(location, element.relativePath)?.let(ResourceContent::Pdf) ?: ResourceContent.Missing
            null -> ResourceContent.Missing
        }
    }

    companion object {
        const val MIN_VISIT_SEC = 5L
        const val TEXT_MAX_BYTES = 2 * 1024 * 1024
        private const val SHORTCUT_MAX_BYTES = 64 * 1024
    }
}
