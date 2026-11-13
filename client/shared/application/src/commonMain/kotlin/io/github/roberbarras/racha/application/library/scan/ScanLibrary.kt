package io.github.roberbarras.racha.application.library.scan

import io.github.roberbarras.racha.application.learning.ProgressRepository
import io.github.roberbarras.racha.application.library.CategoryRepository
import io.github.roberbarras.racha.application.library.LibraryRepository
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.domain.learning.ItemProgress
import io.github.roberbarras.racha.domain.library.Category
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.domain.library.LibraryItem
import io.github.roberbarras.racha.domain.library.MediaInfo
import io.github.roberbarras.racha.domain.library.PlayableElement
import io.github.roberbarras.racha.domain.library.Section
import io.github.roberbarras.racha.domain.library.Subtitle
import io.github.roberbarras.racha.domain.library.scan.DetectedElement
import io.github.roberbarras.racha.domain.library.scan.DetectedItem
import io.github.roberbarras.racha.domain.library.scan.ExclusionRules
import io.github.roberbarras.racha.domain.library.scan.LibraryLayout
import io.github.roberbarras.racha.domain.preferences.ChangeDetection
import io.github.roberbarras.racha.domain.preferences.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/** What a scan changed; [newItems] feeds the "Novedades" row and new-content notifications. */
data class ScanReport(
    val added: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val archived: Int = 0,
    val restored: Int = 0,
    val relinked: Int = 0,
    val probed: Int = 0,
    val hashed: Int = 0,
    val newItems: List<ContentId> = emptyList(),
) {
    operator fun plus(other: ScanReport) = ScanReport(
        added + other.added, updated + other.updated, unchanged + other.unchanged, archived + other.archived,
        restored + other.restored, relinked + other.relinked, probed + other.probed, hashed + other.hashed,
        newItems + other.newItems,
    )
}

/**
 * Incremental scan of category roots (spec §3.3, §3.4):
 * - only new or changed files (size or modification time, or content hash in HASH mode) are probed;
 * - user edits (title/order overrides) and first-seen dates survive rescans;
 * - files that disappear are archived, never deleted, and restored when they come back at the same path;
 * - a new file whose content hash matches an archived element re-links that element's progress (moves/renames).
 * Scans are serialized: a watcher-triggered scan waits for a manual one instead of racing it.
 */
class ScanLibrary(
    private val categories: CategoryRepository,
    private val library: LibraryRepository,
    private val progress: ProgressRepository,
    private val source: LibrarySource,
    private val probe: MediaProbe,
    private val hasher: FileHasher,
    private val preferences: Preferences,
    private val clock: Clock,
    private val localDates: LocalDates,
) {
    private val mutex = Mutex()

    suspend fun all(): ScanReport = mutex.withLock {
        categories.all().fold(ScanReport()) { report, category -> report + scanCategory(category) }
    }

    suspend fun category(key: CategoryKey): ScanReport = mutex.withLock {
        categories.get(key)?.let { scanCategory(it) } ?: ScanReport()
    }

    private suspend fun scanCategory(category: Category): ScanReport =
        categories.roots(category.key).fold(ScanReport()) { report, root -> report + scanRoot(category, root) }

    private suspend fun scanRoot(category: Category, root: CategoryRoot): ScanReport {
        val now = clock.now()
        val hashMode = preferences.get(Settings.ChangeDetectionMode) == ChangeDetection.HASH
        val exclusions = ExclusionRules(category.excludePatterns)
        val entries = source.list(root.location) { dir -> !exclusions.excludes(dir, isDirectory = true) }
        val detected = LibraryLayout.detect(
            category.key, root.key, source.displayName(root.location), entries, exclusions, localDates::dateOf,
        )
        val oldItems = library.itemsInRoot(category.key, root.key).associateBy { it.id }
        val oldElements = library.elementsInRoot(category.key, root.key).associateBy { it.id }
        // Files no longer on disk are archived first (never deleted, progress kept), so that a moved file
        // processed below can find its previous self among the archived elements and re-link it.
        val seenElements = detected.items.flatMap { it.elements }.map { it.id }.toSet()
        val gone = oldElements.values.filter { !it.archived && it.id !in seenElements }
        gone.forEach { library.upsertElement(it.copy(archived = true, updatedAt = now)) }
        var report = ScanReport(archived = gone.size)

        for (found in detected.items) {
            val old = oldItems[found.id]
            // Sections and elements reference their item: a new item is stored first, totals are filled in below.
            if (old == null) library.upsertItem(itemOf(found, root, category, null, emptyList(), now))
            val oldSections = library.sections(found.id).associateBy { it.id }
            val elements = mutableListOf<PlayableElement>()
            for (section in found.sections) {
                library.upsertSection(sectionOf(found, section, oldSections[section.id], now))
                for (element in section.elements) {
                    val (entity, delta) = reconcile(root, found, section.id, element, oldElements[element.id], hashMode, now)
                    library.upsertElement(entity)
                    syncSubtitles(entity.id, element, now)
                    elements += entity
                    report += delta
                }
            }
            if (old == null) report += ScanReport(newItems = listOf(found.id))
            library.upsertItem(itemOf(found, root, category, old, elements, now))
        }

        val seenItems = detected.items.map { it.id }.toSet()
        oldItems.values.filter { !it.archived && it.id !in seenItems }.forEach { library.upsertItem(it.copy(archived = true, updatedAt = now)) }
        return report
    }

    private suspend fun reconcile(
        root: CategoryRoot,
        item: DetectedItem,
        sectionId: ContentId,
        found: DetectedElement,
        old: PlayableElement?,
        hashMode: Boolean,
        now: Instant,
    ): Pair<PlayableElement, ScanReport> {
        var report = ScanReport()
        var unchanged = old != null && old.fileSize == found.size && old.fileModifiedAt == found.modifiedAt
        var hash = if (unchanged) old?.fileHash else null
        if (!unchanged && hashMode && old?.fileHash != null && old.fileSize == found.size) {
            // Same size but touched (e.g. copied): compare content before paying for a re-probe.
            hash = hasher.sha256(root.location, found.path).also { report += ScanReport(hashed = 1) }
            unchanged = hash == old.fileHash
        }
        val media = when {
            unchanged -> old!!.media
            found.kind == ElementKind.VIDEO -> (probe.probe(root.location, found.path) ?: MediaInfo.UNKNOWN).also { report += ScanReport(probed = 1) }
            else -> MediaInfo.UNKNOWN
        }
        if (old == null) {
            report += ScanReport(added = 1)
            val candidates = library.archivedElementsWithSize(found.size)
            if (candidates.isNotEmpty()) {
                val newHash = hash ?: hasher.sha256(root.location, found.path).also { report += ScanReport(hashed = 1) }
                hash = newHash
                candidates.firstOrNull { it.fileHash == newHash }?.let { moved ->
                    library.relinkElement(moved.id, found.id)
                    report += ScanReport(relinked = 1)
                }
            }
        } else {
            report += when {
                old.archived -> ScanReport(restored = 1)
                unchanged -> ScanReport(unchanged = 1)
                else -> ScanReport(updated = 1)
            }
        }
        val element = PlayableElement(
            id = found.id,
            libraryItemId = item.id,
            sectionId = sectionId,
            kind = found.kind,
            resourceType = found.resourceType,
            title = found.title,
            titleOverride = old?.titleOverride,
            relativePath = found.path,
            media = media,
            order = found.order,
            orderOverride = old?.orderOverride,
            archived = false,
            fileSize = found.size,
            fileModifiedAt = found.modifiedAt,
            fileHash = hash,
            firstSeenAt = old?.firstSeenAt ?: now,
            updatedAt = if (old != null && unchanged && !old.archived && old.sectionId == sectionId && old.order == found.order && old.title == found.title) old.updatedAt else now,
        )
        return element to report
    }

    private suspend fun syncSubtitles(elementId: ContentId, found: DetectedElement, now: Instant) {
        val current = library.subtitles(elementId).associateBy { it.id }
        found.subtitles.forEach { s ->
            if (current[s.id]?.let { it.language == s.language && it.relativePath == s.path } != true) {
                library.upsertSubtitle(Subtitle(s.id, elementId, s.language, s.path, now))
            }
        }
        (current.keys - found.subtitles.map { it.id }.toSet()).forEach { library.deleteSubtitle(it) }
    }

    private fun sectionOf(item: DetectedItem, section: io.github.roberbarras.racha.domain.library.scan.DetectedSection, old: Section?, now: Instant) = Section(
        id = section.id,
        libraryItemId = item.id,
        kind = section.kind,
        title = section.title,
        titleOverride = old?.titleOverride,
        order = section.order,
        orderOverride = old?.orderOverride,
        updatedAt = if (old != null && old.title == section.title && old.order == section.order && old.kind == section.kind) old.updatedAt else now,
    )

    private suspend fun itemOf(
        found: DetectedItem,
        root: CategoryRoot,
        category: Category,
        old: LibraryItem?,
        elements: List<PlayableElement>,
        now: Instant,
    ): LibraryItem {
        val progressById = progress.forItem(found.id).associateBy { it.elementId }
        val folderPoster = found.poster?.let { source.uri(root.location, it) }
        return LibraryItem(
            id = found.id,
            categoryKey = category.key,
            rootKey = root.key,
            relativePath = found.path,
            kind = found.kind,
            title = found.title,
            titleOverride = old?.titleOverride,
            description = old?.description,
            posterPath = folderPoster ?: old?.posterPath,
            order = found.order,
            orderOverride = old?.orderOverride,
            totalItems = elements.size,
            totalDurationSec = elements.sumOf { it.media.durationSec ?: 0 },
            progressPct = ItemProgress.percent(elements, progressById),
            archived = false,
            firstSeenAt = old?.firstSeenAt ?: now,
            updatedAt = now,
        )
    }
}
