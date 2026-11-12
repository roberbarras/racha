package io.github.roberbarras.racha.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.roberbarras.racha.data.db.RachaDatabase
import io.github.roberbarras.racha.domain.learning.Progress
import io.github.roberbarras.racha.domain.library.Category
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.domain.library.LibraryItem
import io.github.roberbarras.racha.domain.library.MediaInfo
import io.github.roberbarras.racha.domain.library.PlayableElement
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.RootKey
import io.github.roberbarras.racha.domain.library.Section
import io.github.roberbarras.racha.domain.library.SectionKind
import io.github.roberbarras.racha.domain.library.StructureKind
import kotlinx.coroutines.Dispatchers
import java.util.Properties
import kotlin.time.Instant

internal object Fixtures {
    val t0: Instant = Instant.parse("2026-11-12T10:00:00Z")
    val root = RootKey("0b4f1b5e-6d1f-4a35-9a51-6a3a1b0c9d10")

    /** Same connection settings as the desktop app: foreign keys enforced. */
    fun driver(url: String = JdbcSqliteDriver.IN_MEMORY) =
        JdbcSqliteDriver(url, Properties().apply { put("foreign_keys", "true") })

    fun newDatabase(): LocalDatabase {
        val driver = driver()
        RachaDatabase.Schema.create(driver)
        return LocalDatabase(driver, Dispatchers.Default)
    }

    fun courses(): Category = Category.builtIns(t0).first()

    fun item(path: String = "AWS Course", order: Int = 0) = LibraryItem(
        id = ContentId.forFolder(CategoryKey.COURSES, root, RelativePath.of(path)),
        categoryKey = CategoryKey.COURSES,
        rootKey = root,
        relativePath = RelativePath.of(path),
        kind = StructureKind.COURSE,
        title = path,
        titleOverride = null,
        description = null,
        posterPath = null,
        order = order,
        orderOverride = null,
        totalItems = 0,
        totalDurationSec = 0,
        progressPct = 0.0,
        archived = false,
        firstSeenAt = t0,
        updatedAt = t0,
    )

    fun section(item: LibraryItem, folder: String, order: Int) = Section(
        id = ContentId.forFolder(CategoryKey.COURSES, root, item.relativePath.resolve(folder)),
        libraryItemId = item.id,
        kind = SectionKind.MODULE,
        title = folder,
        titleOverride = null,
        order = order,
        orderOverride = null,
        updatedAt = t0,
    )

    fun video(item: LibraryItem, section: Section, file: String, order: Int, hash: String? = null): PlayableElement {
        val path = item.relativePath.resolve(section.title).resolve(file)
        return PlayableElement(
            id = ContentId.forVideo(CategoryKey.COURSES, root, path),
            libraryItemId = item.id,
            sectionId = section.id,
            kind = ElementKind.VIDEO,
            resourceType = null,
            title = file.substringBeforeLast('.'),
            titleOverride = null,
            relativePath = path,
            media = MediaInfo(268, 1920, 1080, "h264", 30.0, 2152),
            order = order,
            orderOverride = null,
            archived = false,
            fileSize = 82_339_527,
            fileModifiedAt = t0,
            fileHash = hash,
            firstSeenAt = t0,
            updatedAt = t0,
        )
    }

    fun watched(element: PlayableElement, positionSec: Long, at: Instant) =
        Progress.start(element.id, element.media.durationSec, at).recordPosition(positionSec, null, at)
}
