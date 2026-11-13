package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.application.library.scan.HashEngagedElements
import io.github.roberbarras.racha.application.library.scan.KeepLibraryUpToDate
import io.github.roberbarras.racha.application.library.scan.LibrarySource
import io.github.roberbarras.racha.application.library.scan.LibraryWatcher
import io.github.roberbarras.racha.application.library.scan.LocalDates
import io.github.roberbarras.racha.application.library.scan.RefreshThumbnails
import io.github.roberbarras.racha.application.library.scan.ScanLibrary
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.MediaInfo
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.RootKey
import io.github.roberbarras.racha.domain.library.scan.CalendarDate
import io.github.roberbarras.racha.domain.library.scan.FileEntry
import io.github.roberbarras.racha.domain.preferences.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class KeepLibraryUpToDateTest {
    private val db = Fixtures.newDatabase()
    private val preferences = Preferences(db.settings, Clock { Fixtures.t0 })
    private val coursesRoot = CategoryRoot(RootKey("0b4f1b5e-6d1f-4a35-9a51-6a3a1b0c9d10"), CategoryKey.COURSES, "file:///courses", 0)
    private val seriesRoot = CategoryRoot(RootKey("9c3a8f8e-1d51-4e2c-8f0e-5d0b7a2e4c11"), CategoryKey.SERIES, "file:///series", 0)
    private val listed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val watched: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val events = mapOf(coursesRoot.location to MutableSharedFlow<Unit>(), seriesRoot.location to MutableSharedFlow())

    private val source = object : LibrarySource {
        override fun displayName(location: String) = location.substringAfterLast('/')
        override suspend fun list(location: String, descend: (RelativePath) -> Boolean): List<FileEntry> {
            listed += location
            return emptyList()
        }
        override fun uri(location: String, path: RelativePath) = "$location/$path"
    }

    private val watcher = LibraryWatcher { location -> watched += location; events.getValue(location) as Flow<Unit> }

    private val keeper = run {
        val scan = ScanLibrary(
            db.categories, db.library, db.progress, source, { _, _ -> MediaInfo.UNKNOWN }, { _, _ -> "" }, preferences,
            Clock { Fixtures.t0 }, LocalDates { CalendarDate(2026, 11, 13) },
        )
        KeepLibraryUpToDate(
            db.categories, scan, HashEngagedElements(db.categories, db.library) { _, _ -> "" },
            RefreshThumbnails(db.categories, db.library, { _, _, _, _, _ -> null }, preferences), watcher, preferences,
            quietPeriod = 200.milliseconds,
        )
    }

    private suspend fun setUp() {
        EnsureBuiltInCategories(db.categories, Clock { Fixtures.t0 })()
        db.categories.upsertRoot(coursesRoot)
        db.categories.upsertRoot(seriesRoot)
    }

    @Test
    fun scansOnStartThenRescansEveryChangedCategoryOncePerBurst() = runBlocking {
        setUp()
        val job = launch(Dispatchers.Default) { keeper.run() }
        delay(500)
        assertEquals(setOf(coursesRoot.location, seriesRoot.location), listed.toSet(), "initial scan")
        listed.clear()

        repeat(5) { events.getValue(coursesRoot.location).emit(Unit) } // e.g. copying a course
        events.getValue(seriesRoot.location).emit(Unit)
        delay(800)

        assertEquals(listOf(coursesRoot.location, seriesRoot.location).sorted(), listed.sorted())
        job.cancel()
    }

    @Test
    fun respectsTheAutomaticScanAndFileWatcherSettings() = runBlocking {
        setUp()
        preferences.set(Settings.AutoScan, false)
        preferences.set(Settings.FileWatcher, false)

        val job = launch(Dispatchers.Default) { keeper.run() }
        delay(500)

        assertEquals(emptyList(), listed.toList(), "no scan on start")
        assertEquals(emptyList(), watched.toList(), "no watcher")
        job.cancel()

        keeper.scanNow()
        assertEquals(2, listed.size, "manual scan always runs")
    }
}
