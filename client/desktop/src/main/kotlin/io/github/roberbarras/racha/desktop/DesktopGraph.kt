package io.github.roberbarras.racha.desktop

import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.application.library.scan.HashEngagedElements
import io.github.roberbarras.racha.application.library.scan.KeepLibraryUpToDate
import io.github.roberbarras.racha.application.library.scan.RefreshThumbnails
import io.github.roberbarras.racha.application.library.scan.ScanLibrary
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.data.LocalDatabase
import io.github.roberbarras.racha.desktop.library.MpvMediaProbe
import io.github.roberbarras.racha.desktop.library.MpvThumbnailRenderer
import io.github.roberbarras.racha.desktop.library.WatchServiceLibraryWatcher
import io.github.roberbarras.racha.files.NioLibrarySource
import io.github.roberbarras.racha.files.Sha256FileHasher
import io.github.roberbarras.racha.files.SystemLocalDates

/** Desktop composition root: wires adapters into use cases by hand (no reflection, no DI container). */
class DesktopGraph(val database: LocalDatabase = DesktopDatabase.open()) : AutoCloseable {
    val clock = Clock.SYSTEM
    val preferences = Preferences(database.settings, clock)
    private val probe = MpvMediaProbe()
    private val hasher = Sha256FileHasher()

    val ensureBuiltInCategories = EnsureBuiltInCategories(database.categories, clock)
    val scanLibrary = ScanLibrary(
        database.categories, database.library, database.progress, NioLibrarySource(), probe, hasher, preferences, clock, SystemLocalDates(),
    )
    val keepLibraryUpToDate = KeepLibraryUpToDate(
        database.categories,
        scanLibrary,
        HashEngagedElements(database.categories, database.library, hasher),
        RefreshThumbnails(database.categories, database.library, MpvThumbnailRenderer(DesktopPaths.thumbnails), preferences),
        WatchServiceLibraryWatcher(),
        preferences,
    )

    override fun close() = probe.close()
}
