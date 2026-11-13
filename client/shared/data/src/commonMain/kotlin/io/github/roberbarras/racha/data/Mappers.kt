package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.domain.learning.Bookmark
import io.github.roberbarras.racha.domain.learning.HistoryEntry
import io.github.roberbarras.racha.domain.learning.LocalStat
import io.github.roberbarras.racha.domain.learning.Progress
import io.github.roberbarras.racha.domain.library.Category
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.domain.library.LanguageCode
import io.github.roberbarras.racha.domain.library.LibraryItem
import io.github.roberbarras.racha.domain.library.MediaInfo
import io.github.roberbarras.racha.domain.library.PlayableElement
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.ResourceType
import io.github.roberbarras.racha.domain.library.RootKey
import io.github.roberbarras.racha.domain.library.Section
import io.github.roberbarras.racha.domain.library.SectionKind
import io.github.roberbarras.racha.domain.library.StructureKind
import io.github.roberbarras.racha.domain.library.Subtitle
import kotlin.time.Instant
import io.github.roberbarras.racha.data.db.Bookmark as BookmarkRow
import io.github.roberbarras.racha.data.db.Category as CategoryRow
import io.github.roberbarras.racha.data.db.Category_root as CategoryRootRow
import io.github.roberbarras.racha.data.db.History_entry as HistoryRow
import io.github.roberbarras.racha.data.db.Library_item as LibraryItemRow
import io.github.roberbarras.racha.data.db.Local_stat as LocalStatRow
import io.github.roberbarras.racha.data.db.Playable_element as ElementRow
import io.github.roberbarras.racha.data.db.Progress as ProgressRow
import io.github.roberbarras.racha.data.db.Section as SectionRow
import io.github.roberbarras.racha.data.db.Subtitle as SubtitleRow

// Row <-> domain mapping. Enums are stored by name; timestamps as epoch milliseconds; lists newline-separated.

internal fun Instant.millis(): Long = toEpochMilliseconds()

internal fun Long.instant(): Instant = Instant.fromEpochMilliseconds(this)

internal fun CategoryRow.toDomain() = Category(
    key = CategoryKey(key),
    name = name,
    icon = icon,
    color = color,
    isPrimary = is_primary,
    order = sort_order.toInt(),
    visibleInStudy = visible_in_study,
    visibleInLeisure = visible_in_leisure,
    excludePatterns = exclude_patterns.lines().filter(String::isNotEmpty),
    createdAt = created_at.instant(),
    updatedAt = updated_at.instant(),
)

internal fun Category.toRow() = CategoryRow(
    key = key.value,
    name = name,
    icon = icon,
    color = color,
    is_primary = isPrimary,
    sort_order = order.toLong(),
    visible_in_study = visibleInStudy,
    visible_in_leisure = visibleInLeisure,
    exclude_patterns = excludePatterns.joinToString("\n"),
    created_at = createdAt.millis(),
    updated_at = updatedAt.millis(),
)

internal fun CategoryRootRow.toDomain() =
    CategoryRoot(RootKey(key), CategoryKey(category_key), location, sort_order.toInt())

internal fun CategoryRoot.toRow() = CategoryRootRow(key.value, categoryKey.value, location, order.toLong())

internal fun LibraryItemRow.toDomain() = LibraryItem(
    id = ContentId(id),
    categoryKey = CategoryKey(category_key),
    rootKey = RootKey(root_key),
    relativePath = RelativePath.of(relative_path),
    kind = StructureKind.valueOf(kind),
    title = title,
    titleOverride = title_override,
    description = description,
    posterPath = poster_path,
    order = sort_order.toInt(),
    orderOverride = order_override?.toInt(),
    totalItems = total_items.toInt(),
    totalDurationSec = total_duration_sec,
    progressPct = progress_pct,
    archived = archived,
    firstSeenAt = first_seen_at.instant(),
    updatedAt = updated_at.instant(),
)

internal fun LibraryItem.toRow() = LibraryItemRow(
    id = id.value,
    category_key = categoryKey.value,
    root_key = rootKey.value,
    relative_path = relativePath.value,
    kind = kind.name,
    title = title,
    title_override = titleOverride,
    description = description,
    poster_path = posterPath,
    sort_order = order.toLong(),
    order_override = orderOverride?.toLong(),
    total_items = totalItems.toLong(),
    total_duration_sec = totalDurationSec,
    progress_pct = progressPct,
    archived = archived,
    first_seen_at = firstSeenAt.millis(),
    updated_at = updatedAt.millis(),
)

internal fun SectionRow.toDomain() = Section(
    id = ContentId(id),
    libraryItemId = ContentId(library_item_id),
    kind = SectionKind.valueOf(kind),
    title = title,
    titleOverride = title_override,
    order = sort_order.toInt(),
    orderOverride = order_override?.toInt(),
    updatedAt = updated_at.instant(),
)

internal fun Section.toRow() = SectionRow(
    id = id.value,
    library_item_id = libraryItemId.value,
    kind = kind.name,
    title = title,
    title_override = titleOverride,
    sort_order = order.toLong(),
    order_override = orderOverride?.toLong(),
    updated_at = updatedAt.millis(),
)

internal fun ElementRow.toDomain() = PlayableElement(
    id = ContentId(id),
    libraryItemId = ContentId(library_item_id),
    sectionId = ContentId(section_id),
    kind = ElementKind.valueOf(kind),
    resourceType = resource_type?.let(ResourceType::valueOf),
    title = title,
    titleOverride = title_override,
    relativePath = RelativePath.of(relative_path),
    media = MediaInfo(duration_sec, width?.toInt(), height?.toInt(), video_codec, fps, bitrate_kbps?.toInt()),
    order = sort_order.toInt(),
    orderOverride = order_override?.toInt(),
    archived = archived,
    fileSize = file_size,
    fileModifiedAt = file_modified_at.instant(),
    fileHash = file_hash,
    firstSeenAt = first_seen_at.instant(),
    updatedAt = updated_at.instant(),
    skipIntroSec = skip_intro_sec?.toInt(),
    skipOutroSec = skip_outro_sec?.toInt(),
)

internal fun PlayableElement.toRow() = ElementRow(
    id = id.value,
    library_item_id = libraryItemId.value,
    section_id = sectionId.value,
    kind = kind.name,
    resource_type = resourceType?.name,
    title = title,
    title_override = titleOverride,
    relative_path = relativePath.value,
    duration_sec = media.durationSec,
    width = media.width?.toLong(),
    height = media.height?.toLong(),
    video_codec = media.videoCodec,
    fps = media.fps,
    bitrate_kbps = media.bitrateKbps?.toLong(),
    sort_order = order.toLong(),
    order_override = orderOverride?.toLong(),
    archived = archived,
    file_size = fileSize,
    file_modified_at = fileModifiedAt.millis(),
    file_hash = fileHash,
    first_seen_at = firstSeenAt.millis(),
    updated_at = updatedAt.millis(),
    skip_intro_sec = skipIntroSec?.toLong(),
    skip_outro_sec = skipOutroSec?.toLong(),
)

internal fun SubtitleRow.toDomain() =
    Subtitle(ContentId(id), ContentId(element_id), LanguageCode(language), RelativePath.of(relative_path), updated_at.instant())

internal fun Subtitle.toRow() =
    SubtitleRow(id.value, elementId.value, language.value, relativePath.value, updatedAt.millis())

internal fun ProgressRow.toDomain() = Progress(
    elementId = ContentId(element_id),
    positionSec = position_sec,
    durationSec = duration_sec,
    completed = completed,
    maxPositionSec = max_position_sec,
    lastWatchedAt = last_watched_at?.instant(),
    updatedAt = updated_at.instant(),
)

internal fun Progress.toRow() = ProgressRow(
    element_id = elementId.value,
    position_sec = positionSec,
    duration_sec = durationSec,
    completed = completed,
    max_position_sec = maxPositionSec,
    last_watched_at = lastWatchedAt?.millis(),
    updated_at = updatedAt.millis(),
)

internal fun BookmarkRow.toDomain() =
    Bookmark(id, ContentId(element_id), timestamp_sec, note, created_at.instant(), updated_at.instant())

internal fun Bookmark.toRow() =
    BookmarkRow(id, elementId.value, timestampSec, note, createdAt.millis(), updatedAt.millis())

internal fun HistoryRow.toDomain() = HistoryEntry(id, ContentId(element_id), watched_at.instant(), position_at_end_sec)

internal fun HistoryEntry.toRow() = HistoryRow(id, elementId.value, watchedAt.millis(), positionAtEndSec)

internal fun LocalStatRow.toDomain() = LocalStat(metric, period, value_, updated_at.instant())

internal fun LocalStat.toRow() = LocalStatRow(metric, period, value, updatedAt.millis())
