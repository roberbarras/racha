package io.github.roberbarras.racha.android

import android.content.Context
import io.github.roberbarras.racha.android.library.AndroidMediaProbe
import io.github.roberbarras.racha.android.library.AndroidThumbnailRenderer
import io.github.roberbarras.racha.android.library.FileObserverLibraryWatcher
import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.application.library.scan.HashEngagedElements
import io.github.roberbarras.racha.application.library.scan.KeepLibraryUpToDate
import io.github.roberbarras.racha.application.library.scan.RefreshThumbnails
import io.github.roberbarras.racha.application.library.scan.ScanLibrary
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.files.NioLibrarySource
import io.github.roberbarras.racha.files.Sha256FileHasher
import io.github.roberbarras.racha.files.SystemLocalDates
import java.io.File

/** Android composition root: one per process (held by [RachaApplication]), wired by hand. */
class AndroidGraph(context: Context) {
    val database = AndroidDatabase.create(context)
    val clock = Clock.SYSTEM
    val preferences = Preferences(database.settings, clock)
    private val hasher = Sha256FileHasher()

    val ensureBuiltInCategories = EnsureBuiltInCategories(database.categories, clock)
    val scanLibrary = ScanLibrary(
        database.categories, database.library, database.progress, NioLibrarySource(), AndroidMediaProbe(), hasher, preferences, clock,
        SystemLocalDates(),
    )
    val keepLibraryUpToDate = KeepLibraryUpToDate(
        database.categories,
        scanLibrary,
        HashEngagedElements(database.categories, database.library, hasher),
        RefreshThumbnails(database.categories, database.library, AndroidThumbnailRenderer(File(context.cacheDir, "thumbs")), preferences),
        FileObserverLibraryWatcher(),
        preferences,
    )
}
