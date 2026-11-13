package io.github.roberbarras.racha.domain.library.scan

import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.domain.library.LanguageCode
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.ResourceType
import io.github.roberbarras.racha.domain.library.RootKey
import io.github.roberbarras.racha.domain.library.SectionKind
import io.github.roberbarras.racha.domain.library.StructureKind
import kotlin.time.Instant

data class DetectedSubtitle(val id: ContentId, val language: LanguageCode, val path: RelativePath)

data class DetectedElement(
    val id: ContentId,
    val kind: ElementKind,
    val resourceType: ResourceType?,
    val title: String,
    val path: RelativePath,
    val order: Int,
    val size: Long,
    val modifiedAt: Instant,
    val subtitles: List<DetectedSubtitle>,
)

/** [title] is empty for synthetic groups (loose files, seasons from `SxxEyy`): the UI labels them from [kind]/[order]. */
data class DetectedSection(
    val id: ContentId,
    val kind: SectionKind,
    val title: String,
    val order: Int,
    val elements: List<DetectedElement>,
)

/** [title] is empty for the "Loose files" item of a category root: the UI labels it. */
data class DetectedItem(
    val id: ContentId,
    val path: RelativePath,
    val kind: StructureKind,
    val title: String,
    val order: Int,
    val poster: RelativePath?,
    val sections: List<DetectedSection>,
) {
    val elements: List<DetectedElement> get() = sections.flatMap { it.elements }
}

data class DetectedLibrary(val items: List<DetectedItem>)

/**
 * Turns the file list of one category root into courses/series/channels, sections and elements, applying the
 * cascade of spec §3.2. Pure: no I/O, deterministic for a given input.
 *
 * Root level: if most top-level folders look like sections (`NN - Title`, seasons, dates) the root itself is one
 * item (the reference layout of spec §3.1); otherwise each top-level folder is an item and files lying directly in
 * the root form the "Loose files" item.
 */
object LibraryLayout {

    /**
     * @param rootName display name of the root folder, used as title when the root itself is a course.
     * @param localDate converts a modification time to the user's local calendar date (mtime grouping, rule 2).
     */
    fun detect(
        category: CategoryKey,
        root: RootKey,
        rootName: String,
        entries: List<FileEntry>,
        exclusions: ExclusionRules,
        localDate: (Instant) -> CalendarDate,
    ): DetectedLibrary = Detection(category, root, rootName, Tree(entries, exclusions), localDate).run()

    private class Tree(entries: List<FileEntry>, exclusions: ExclusionRules) {
        val files: List<FileEntry> = entries.filter { !it.isDirectory && !exclusions.excludes(it.path, false) }
        private val filesByDir: Map<RelativePath, List<FileEntry>> = files.groupBy { it.path.parent }
        val dirs: Set<RelativePath> = buildSet {
            entries.filter { it.isDirectory && !exclusions.excludes(it.path, true) }.forEach { add(it.path) }
            files.forEach { f -> var p = f.path.parent; while (!p.isRoot) { add(p); p = p.parent } }
        }

        /** Directories holding an HLS playlist: their `.ts` files are segments, not episodes. */
        private val hlsDirs: Set<RelativePath> = files.filter { it.path.extension == "m3u8" }.map { it.path.parent }.toSet()

        fun childDirs(dir: RelativePath) = dirs.filter { it.parent == dir && it != dir }

        fun filesIn(dir: RelativePath): List<FileEntry> = filesByDir[dir].orEmpty()

        fun isElement(file: FileEntry): Boolean =
            MediaFiles.isPlayable(file.path) && !(file.path.extension == "ts" && file.path.parent in hlsDirs)

        fun elementsIn(dir: RelativePath) = filesIn(dir).filter(::isElement)

        fun elementsUnder(dir: RelativePath) = files.filter { isElement(it) && isUnder(it.path, dir) }

        fun hasElementsUnder(dir: RelativePath) = files.any { isElement(it) && isUnder(it.path, dir) }

        private fun isUnder(path: RelativePath, dir: RelativePath) = dir.isRoot || path.value.startsWith(dir.value + "/")
    }

    private class Detection(
        val category: CategoryKey,
        val root: RootKey,
        val rootName: String,
        val tree: Tree,
        val localDate: (Instant) -> CalendarDate,
    ) {
        fun run(): DetectedLibrary {
            val topDirs = tree.childDirs(RelativePath.ROOT).filter(tree::hasElementsUnder)
            val rootIsItem = topDirs.isNotEmpty() && topDirs.count { isSectionLike(it.name) } * 2 > topDirs.size
            val items = if (rootIsItem) {
                listOf(item(RelativePath.ROOT, rootName, looseRoot = false))
            } else {
                topDirs.map { item(it, it.name, looseRoot = false) } +
                    listOfNotNull(if (tree.elementsIn(RelativePath.ROOT).isNotEmpty()) item(RelativePath.ROOT, "", looseRoot = true) else null)
            }
            val ordered = items.sortedWith(compareBy<DetectedItem> { it.kind == StructureKind.LOOSE }.thenComparator { a, b -> NaturalOrder.compare(a.title, b.title) })
            return DetectedLibrary(ordered.mapIndexed { index, item -> item.copy(order = index) })
        }

        private fun isSectionLike(name: String) =
            NameParser.sectionPrefix(name) != null || NameParser.season(name) != null || NameParser.date(name) != null

        private fun item(path: RelativePath, title: String, looseRoot: Boolean): DetectedItem {
            val id = ContentId.forFolder(category, root, path)
            val sectionDirs = if (looseRoot) emptyList() else tree.childDirs(path).filter(tree::hasElementsUnder)
            val direct = tree.elementsIn(path)
            val all = if (looseRoot) direct else tree.elementsUnder(path)
            val kind = when {
                looseRoot -> StructureKind.LOOSE
                sectionDirs.any { NameParser.season(it.name) != null } || all.any { NameParser.episode(stem(it)) != null } -> StructureKind.SERIES
                sectionDirs.isNotEmpty() && sectionDirs.all { NameParser.date(it.name) != null && NameParser.sectionPrefix(it.name) == null } -> StructureKind.LIVE
                sectionDirs.isEmpty() && direct.isNotEmpty() && direct.count { NameParser.date(stem(it)) != null } * 2 > direct.size -> StructureKind.LIVE
                else -> StructureKind.COURSE
            }
            val folderSections = sectionDirs.map { folderSection(id, it) }
            val sections = directSections(id, kind, direct) + folderSections.sortedWith(SECTION_ORDER).map { it.section }
            val numbered = sections.mapIndexed { index, s -> s.copy(order = index) }
            val poster = tree.filesIn(path).firstOrNull { MediaFiles.isPoster(it.path) }?.path
            return DetectedItem(id, path, kind, title, 0, poster, numbered)
        }

        /** A folder below the item: module (`NN - Title`), season or date group. Nested folders are flattened. */
        private fun folderSection(itemId: ContentId, dir: RelativePath): SortableSection {
            val name = dir.name
            val season = NameParser.season(name)
            val prefix = NameParser.sectionPrefix(name)
            val date = if (prefix == null) NameParser.date(name) else null
            val kind = when {
                season != null -> SectionKind.SEASON
                date != null -> SectionKind.DATE
                else -> SectionKind.MODULE
            }
            val files = tree.elementsUnder(dir)
            val section = DetectedSection(
                id = ContentId.forFolder(category, root, dir),
                kind = kind,
                title = prefix?.title ?: name,
                order = 0,
                elements = elements(files),
            )
            return SortableSection(section, season ?: prefix?.number, date, files.minOfOrNull { it.modifiedAt }, name)
        }

        /** Files lying directly in the item folder: seasons from `SxxEyy`, date groups for live, or one loose group. */
        private fun directSections(itemId: ContentId, kind: StructureKind, files: List<FileEntry>): List<DetectedSection> {
            if (files.isEmpty()) return emptyList()
            val bySeason = files.groupBy { NameParser.episode(stem(it))?.season }
            if (kind == StructureKind.SERIES && bySeason.keys.any { it != null }) {
                return bySeason.entries.sortedBy { it.key ?: 0 }.map { (season, group) ->
                    val key = season?.let { "season-$it" } ?: "loose"
                    DetectedSection(ContentId.forGroup(itemId, key), if (season != null) SectionKind.SEASON else SectionKind.LOOSE, "", 0, elements(group))
                }
            }
            if (kind == StructureKind.LIVE || (kind == StructureKind.LOOSE && files.count { NameParser.date(stem(it)) != null } * 2 > files.size)) {
                return files.groupBy { NameParser.date(stem(it)) ?: localDate(it.modifiedAt) }.entries.sortedBy { it.key }.map { (date, group) ->
                    DetectedSection(ContentId.forGroup(itemId, "date-$date"), SectionKind.DATE, date.toString(), 0, elements(group))
                }
            }
            return listOf(DetectedSection(ContentId.forGroup(itemId, "loose"), SectionKind.LOOSE, "", 0, elements(files)))
        }

        /**
         * Orders elements by number (`NNN`), then episode, then name date, then modification time (rule 2) and finally
         * natural name order; attaches `_xx.srt` subtitles to the video with the same stem in the same folder.
         */
        private fun elements(files: List<FileEntry>): List<DetectedElement> {
            val sorted = files.sortedWith(
                compareBy<FileEntry>(
                    { numberOf(it) ?: Int.MAX_VALUE },
                    { NameParser.date(stem(it))?.let { d -> d.year * 10_000 + d.month * 100 + d.day } ?: Int.MAX_VALUE },
                    { if (numberOf(it) == null && NameParser.date(stem(it)) == null) it.modifiedAt.toEpochMilliseconds() else 0L },
                ).thenComparator { a, b -> NaturalOrder.compare(a.path.name, b.path.name) },
            )
            val usedIds = mutableSetOf<ContentId>()
            return sorted.mapIndexed { index, file ->
                val resourceType = MediaFiles.resourceType(file.path)
                val isVideo = resourceType == null
                // Two videos with the same stem (x.mp4 + x.mkv): the second keeps its extension in the id.
                val videoId = ContentId.forVideo(category, root, file.path)
                val id = if (!isVideo || videoId in usedIds) ContentId.forResource(category, root, file.path) else videoId
                usedIds += id
                DetectedElement(
                    id = id,
                    kind = if (isVideo) ElementKind.VIDEO else ElementKind.RESOURCE,
                    resourceType = resourceType,
                    title = titleOf(file),
                    path = file.path,
                    order = index + 1,
                    size = file.size,
                    modifiedAt = file.modifiedAt,
                    subtitles = if (isVideo && id == videoId) subtitlesFor(file) else emptyList(),
                )
            }
        }

        private fun subtitlesFor(video: FileEntry): List<DetectedSubtitle> {
            val videoStem = stem(video)
            return tree.filesIn(video.path.parent)
                .filter { MediaFiles.isSubtitle(it.path) }
                .mapNotNull { srt ->
                    val (subtitleStem, language) = NameParser.subtitle(stem(srt))
                    when {
                        subtitleStem == videoStem && language != null -> DetectedSubtitle(ContentId.forSubtitle(category, root, srt.path), language, srt.path)
                        stem(srt) == videoStem -> DetectedSubtitle(ContentId.forSubtitle(category, root, srt.path), UNDETERMINED, srt.path)
                        else -> null
                    }
                }
                .sortedBy { it.language.value }
        }

        private fun numberOf(file: FileEntry): Int? {
            val stem = stem(file)
            NameParser.elementPrefix(stem)?.let { return it.number }
            return NameParser.episode(stem)?.let { (it.season ?: 0) * 1000 + it.episode }
        }

        private fun titleOf(file: FileEntry): String {
            val stem = stem(file)
            NameParser.elementPrefix(stem)?.let { return it.title }
            NameParser.episode(stem)?.let { e -> return e.title.ifEmpty { NameParser.cleanTitle(stem) } }
            return NameParser.cleanTitle(stem).ifEmpty { stem }
        }

        private fun stem(file: FileEntry) = file.path.withoutExtension().name
    }

    private class SortableSection(
        val section: DetectedSection,
        val number: Int?,
        val date: CalendarDate?,
        val firstModifiedAt: Instant?,
        val name: String,
    )

    /** Numbered sections first by number, then dated ones by date, then by oldest file (rule 2), then by name. */
    private val SECTION_ORDER = compareBy<SortableSection>(
        { it.number ?: Int.MAX_VALUE },
        { it.date },
        { if (it.number == null && it.date == null) it.firstModifiedAt else null },
    ).thenComparator { a, b -> NaturalOrder.compare(a.name, b.name) }

    private val UNDETERMINED = LanguageCode("und")
}
