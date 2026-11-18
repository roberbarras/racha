package io.github.roberbarras.racha.ui

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.roberbarras.racha.application.browse.GetElement
import io.github.roberbarras.racha.application.browse.ManageRoots
import io.github.roberbarras.racha.application.browse.MarkProgress
import io.github.roberbarras.racha.application.browse.ObserveCategory
import io.github.roberbarras.racha.application.browse.ObserveHistory
import io.github.roberbarras.racha.application.browse.ObserveHome
import io.github.roberbarras.racha.application.browse.ObserveItemDetail
import io.github.roberbarras.racha.application.browse.ObserveMyList
import io.github.roberbarras.racha.application.browse.SearchLibrary
import io.github.roberbarras.racha.application.browse.SetMode
import io.github.roberbarras.racha.application.browse.ToggleFavorite
import io.github.roberbarras.racha.application.browse.UpdateItemProgress
import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.application.library.scan.HashEngagedElements
import io.github.roberbarras.racha.application.library.scan.KeepLibraryUpToDate
import io.github.roberbarras.racha.application.library.scan.LibrarySource
import io.github.roberbarras.racha.application.library.scan.LocalDates
import io.github.roberbarras.racha.application.library.scan.RefreshThumbnails
import io.github.roberbarras.racha.application.library.scan.ScanLibrary
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.data.LocalDatabase
import io.github.roberbarras.racha.data.db.RachaDatabase
import io.github.roberbarras.racha.domain.learning.HistoryEntry
import io.github.roberbarras.racha.domain.learning.Progress
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.domain.library.LibraryItem
import io.github.roberbarras.racha.domain.library.MediaInfo
import io.github.roberbarras.racha.domain.library.PlayableElement
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.ResourceType
import io.github.roberbarras.racha.domain.library.RootKey
import io.github.roberbarras.racha.domain.library.Section
import io.github.roberbarras.racha.domain.library.SectionKind
import io.github.roberbarras.racha.domain.library.StructureKind
import io.github.roberbarras.racha.domain.library.scan.CalendarDate
import io.github.roberbarras.racha.domain.library.scan.FileEntry
import io.github.roberbarras.racha.ui.app.PlatformHooks
import io.github.roberbarras.racha.ui.app.RachaDependencies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.Properties
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** A realistic in-memory library for UI tests and screenshots (no files involved). */
object DemoLibrary {
    val now: Instant = Instant.parse("2026-11-13T09:33:00Z")
    private val root = RootKey("0b4f1b5e-6d1f-4a35-9a51-6a3a1b0c9d10")
    private val utc = LocalDates { i -> CalendarDate.ofEpochDay(i.epochSeconds.floorDiv(86_400)) }

    lateinit var awsIamLesson: PlayableElement
    /** The database behind the last [dependencies] (tests that check what was stored). */
    lateinit var database: LocalDatabase
    lateinit var aws: LibraryItem
    lateinit var seriesEpisode: PlayableElement

    /** [sync] builds the account and sync controls over the same database (account screen tests). */
    fun dependencies(
        links: kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.emptyFlow(),
        /** A first run: built-in categories only, nothing indexed (onboarding shows). */
        empty: Boolean = false,
        /** What the folder picker returns, and the files of that folder (adding folders). Nothing is read from disk. */
        picked: String? = null,
        pickedFiles: List<String> = emptyList(),
        /** AI summaries (H17) against this simulated provider: the IAM lesson gets English subtitles. */
        chat: io.github.roberbarras.racha.application.ai.ChatClient? = null,
        /** Files the user "saved" (name to text). */
        saved: MutableList<Pair<String, String>> = mutableListOf(),
        /** The player's platform part (player screen tests) and "send to" (H18). */
        player: io.github.roberbarras.racha.ui.player.PlayerHooks? = null,
        cast: io.github.roberbarras.racha.application.cast.CastServices? = null,
        remote: io.github.roberbarras.racha.ui.app.RemoteFolders? = null,
        /** URLs the app asked to open (sign-in pages). */
        openedUrls: MutableList<String> = mutableListOf(),
        sync: ((io.github.roberbarras.racha.data.LocalDatabase, Preferences, Clock) -> io.github.roberbarras.racha.application.sync.SyncControls)? = null,
    ): RachaDependencies {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { put("foreign_keys", "true") })
        RachaDatabase.Schema.create(driver)
        val db = LocalDatabase(driver, Dispatchers.IO)
        database = db
        val clock = Clock { now }
        val preferences = Preferences(db.settings, clock)
        runBlocking { if (empty) EnsureBuiltInCategories(db.categories, clock)() else seed(db, clock) }
        val emptySource = object : LibrarySource {
            override fun displayName(location: String) = location
            override suspend fun list(location: String, descend: (RelativePath) -> Boolean) = emptyList<FileEntry>()
            override fun uri(location: String, path: RelativePath) = location
        }
        val pickedSource = object : LibrarySource {
            override fun displayName(location: String) = location.trimEnd('/').substringAfterLast('/')
            override suspend fun list(location: String, descend: (RelativePath) -> Boolean) =
                if (location != picked) emptyList() else pickedFiles.map { FileEntry(RelativePath.of(it), isDirectory = false, size = 1_000, modifiedAt = now - 2.days) }
            override fun uri(location: String, path: RelativePath) = "$location/${path.value}"
        }
        val scan = ScanLibrary(db.categories, db.library, db.progress, if (picked != null) pickedSource else emptySource, { _, _ -> null }, { _, _ -> "" }, preferences, clock, utc)
        val update = UpdateItemProgress(db.library, db.progress)
        val keep = KeepLibraryUpToDate(
            db.categories, scan, HashEngagedElements(db.categories, db.library) { _, _ -> "" },
            RefreshThumbnails(db.categories, db.library, { _, _, _, _, _ -> null }, preferences), { kotlinx.coroutines.flow.emptyFlow() }, preferences,
        )
        val summaryKeys = io.github.roberbarras.racha.application.ai.ProviderKeys { id ->
            object : io.github.roberbarras.racha.application.shared.SecretStore {
                override suspend fun read() = "test-key-$id"
                override suspend fun write(value: String) {}
                override suspend fun clear() {}
            }
        }
        if (chat != null || player != null) runBlocking {
            db.categories.upsertRoot(io.github.roberbarras.racha.domain.library.CategoryRoot(root, CategoryKey.COURSES, "file:///demo", 0))
            val srt = awsIamLesson.relativePath.value.substringBeforeLast('.') + ".en.srt"
            db.library.upsertSubtitle(io.github.roberbarras.racha.domain.library.Subtitle(
                ContentId.forSubtitle(CategoryKey.COURSES, root, RelativePath.of(srt)), awsIamLesson.id,
                io.github.roberbarras.racha.domain.library.LanguageCode("en"), RelativePath.of(srt), now,
            ))
        }
        val subtitleFiles = object : io.github.roberbarras.racha.application.resource.ResourceFiles {
            override suspend fun readText(location: String, path: RelativePath, maxBytes: Int) =
                io.github.roberbarras.racha.application.resource.ResourceTextFile("1\n00:00:01,000 --> 00:00:04,000\nIAM users, groups and policies.\n", false)
            override fun localPath(location: String, path: RelativePath): String? = null
        }
        return RachaDependencies(
            clock = clock,
            localDates = utc,
            preferences = preferences,
            categories = db.categories,
            observeHome = ObserveHome(db.categories, db.library, db.progress, db.history, preferences, clock, utc),
            observeCategory = ObserveCategory(db.categories, db.library),
            observeItemDetail = ObserveItemDetail(db.categories, db.library, db.progress, db.favorites),
            observeHistory = ObserveHistory(db.history, db.library),
            observeMyList = ObserveMyList(db.favorites, db.library),
            searchLibrary = SearchLibrary(db.library, db.resourceText),
            markProgress = MarkProgress(db.library, db.progress, update, clock),
            getElement = GetElement(db.library, db.progress),
            toggleFavorite = ToggleFavorite(db.favorites, clock),
            manageRoots = ManageRoots(db.categories, db.library, scan, clock),
            setMode = SetMode(preferences),
            keepLibraryUpToDate = keep,
            platform = object : PlatformHooks {
                override suspend fun pickFolder(): String? = picked
                override val player = player
                override fun openUrl(url: String) { openedUrls += url }
                override val deepLinks = links
                override suspend fun saveTextFile(suggestedName: String, text: String): Boolean = saved.add(suggestedName to text)
            },
            resourceViewer = {
                io.github.roberbarras.racha.application.resource.ResourceViewer(
                    db.library, db.categories,
                    object : io.github.roberbarras.racha.application.resource.ResourceFiles {
                        override suspend fun readText(location: String, path: RelativePath, maxBytes: Int) = null
                        override fun localPath(location: String, path: RelativePath): String? = null
                    },
                    db.progress, db.history, update, clock,
                )
            },
            playbackController = { engine, scope ->
                io.github.roberbarras.racha.application.playback.PlaybackController(
                    engine, scope, db.library, db.categories, emptySource, db.progress, db.bookmarks, db.history, db.stats,
                    preferences, update, clock, utc,
                ) { "en" }
            },
            appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default),
            sync = sync?.invoke(db, preferences, clock),
            manageCategories = io.github.roberbarras.racha.application.browse.ManageCategories(db.categories, scan, clock),
            storage = io.github.roberbarras.racha.application.library.StorageMaintenance(
                db.library, db.history,
                object : io.github.roberbarras.racha.application.library.ThumbnailCache {
                    override val locationPrefix = "file:///demo-thumbs/"
                    override suspend fun clear() = 0L
                },
                keep,
            ),
            configTransfer = io.github.roberbarras.racha.application.preferences.ConfigTransfer(preferences, db.categories, clock, "demo"),
            loadPersonalStats = io.github.roberbarras.racha.application.stats.LoadPersonalStats(
                db.categories, db.library, db.progress, db.history, db.stats, utc, clock,
            ),
            summaries = chat?.let {
                io.github.roberbarras.racha.application.ai.SummarizeWithAi(db.library, db.categories, subtitleFiles, db.summaries, it, preferences, summaryKeys, clock) { "en" }
            },
            summaryProviders = chat?.let { io.github.roberbarras.racha.application.ai.SummaryProviders(preferences, summaryKeys) },
            cast = cast,
            remote = remote,
        )
    }

    private class Course(val category: CategoryKey, val title: String, val sections: List<Pair<String, List<Pair<String, Long>>>>, val kind: StructureKind = StructureKind.COURSE)

    private suspend fun seed(db: LocalDatabase, clock: Clock) {
        EnsureBuiltInCategories(db.categories, clock)()
        val courses = listOf(
            Course(CategoryKey.COURSES, "AWS Certified Solutions Architect", listOf(
                "Introduction" to listOf("Course Introduction" to 540L, "Creating an AWS Account" to 760L, "AWS Account Activation Troubleshooting" to 0L),
                "Code & Slides Download" to listOf("Code & Slides Download" to 0L, "Course-Material-Download" to 0L),
                "IAM - Identity and Access Management" to listOf("IAM Introduction: Users, Groups, Policies" to 2710L, "IAM Policies Hands On" to 1320L, "Identity and Access Management - Quiz" to 0L),
                "EC2 Fundamentals" to listOf("S3 Buckets & Security" to 1125L, "EC2 Launch & Spot Instances" to 1330L),
            )),
            Course(CategoryKey.COURSES, "Advanced Go Patterns", listOf("Concurrency" to listOf("Goroutines and channels" to 2400L, "Worker pools" to 3735L, "Context cancellation" to 1980L))),
            Course(CategoryKey.COURSES, "Kubernetes Architecture", listOf("Core concepts" to listOf("Pods and nodes" to 2700L, "Control plane" to 2700L, "Networking" to 2200L))),
            Course(CategoryKey.COURSES, "Introduction to PostgreSQL", listOf("Basics" to listOf("Installing PostgreSQL" to 3130L, "Indexes" to 2800L))),
            Course(CategoryKey.COURSES, "Linux Permissions", listOf("Files" to listOf("Users and groups" to 1500L, "chmod and umask" to 1700L))),
            Course(CategoryKey.COURSES, "Git in Depth", listOf("Branching" to listOf("Git Rebase vs Merge" to 860L, "Bisect" to 900L))),
            Course(CategoryKey.SERIES, "Ciencia en cinco minutos", listOf("" to listOf("El vacío" to 300L, "La luz" to 320L, "El tiempo" to 310L)), StructureKind.SERIES),
            Course(CategoryKey.LIVE, "Directos de Arquitectura", listOf("2026-11-06" to listOf("Q&A semanal" to 5400L), "2026-11-12" to listOf("Diseño de colas" to 6100L)), StructureKind.LIVE),
        )
        val created = courses.mapIndexed { index, course -> course.title to create(db, course, index) }.toMap()
        aws = created.getValue("AWS Certified Solutions Architect").first
        val awsElements = created.getValue("AWS Certified Solutions Architect").second
        awsIamLesson = awsElements.first { it.title.startsWith("IAM Introduction") }
        seriesEpisode = created.getValue("Ciencia en cinco minutos").second.first()

        suspend fun watch(element: PlayableElement, position: Long, ago: kotlin.time.Duration) =
            db.progress.save(Progress.start(element.id, element.media.durationSec, now - ago).recordPosition(position, null, now - ago))
        awsElements.take(2).forEach { db.progress.save(Progress.start(it.id, it.media.durationSec, now - 3.days).finish(now - 3.days)) }
        watch(awsIamLesson, 754, 20.minutes) // 12:34 / 45:10
        watch(created.getValue("Advanced Go Patterns").second[1], 2465, 1.days) // 41:05 / 1:02:15
        watch(created.getValue("Kubernetes Architecture").second[0], 2292, 2.days)
        watch(created.getValue("Introduction to PostgreSQL").second[0], 330, 3.days)
        watch(seriesEpisode, 120, 5.hours)
        listOf(aws, created.getValue("Advanced Go Patterns").first).forEach { UpdateItemProgress(db.library, db.progress)(it.id) }
        (0 until 7).forEach { d -> db.history.add(HistoryEntry("h$d", awsIamLesson.id, now - d.days - 1.hours, 754)) }
        // Months of watch time for the statistics screen: most days a little, some days a lot, a few days off.
        (0 until 200).forEach { d ->
            val minutes = listOf(0, 12, 25, 45, 70, 20, 0, 35, 90, 15)[(d * 7 + d / 9) % 10]
            if (minutes == 0 && d >= 7) return@forEach
            val day = utc.dateOf(now - d.days).toString()
            val seconds = (if (d < 7) maxOf(minutes, 20) else minutes) * 60.0
            db.stats.save(io.github.roberbarras.racha.domain.learning.LocalStat("watchSeconds", day, seconds, now))
            db.stats.save(io.github.roberbarras.racha.domain.learning.LocalStat("watchSeconds.category:" + (if (d % 4 == 0) "series" else "courses"), day, seconds, now))
        }
        // Running totals for the breakdowns (H16): evenings and early mornings, weekends heavier, a few titles.
        suspend fun total(metric: String, value: Double) = db.stats.save(io.github.roberbarras.racha.domain.learning.LocalStat(metric, "all", value, now))
        mapOf(7 to 40, 8 to 55, 13 to 20, 19 to 60, 20 to 110, 21 to 150, 22 to 120, 23 to 45).forEach { (h, m) -> total("watchSeconds.hour:$h", m * 60.0) }
        listOf(70, 55, 80, 60, 40, 140, 160).forEachIndexed { i, m -> total("watchSeconds.weekday:${i + 1}", m * 60.0) }
        total("sessions", 46.0); total("sessions.seconds", 36_000.0); total("sessions.speedSeconds", 50_400.0); total("sessions.withSubtitles", 21.0)
        total("watchSeconds.item:" + aws.id.value, 21_600.0)
        total("watchSeconds.item:" + created.getValue("Advanced Go Patterns").first.id.value, 9_000.0)
        total("watchSeconds.item:" + created.getValue("Introduction to PostgreSQL").first.id.value, 3_300.0)
        // Newly detected: two AWS lessons and a Git lesson that appeared recently.
        listOf(
            awsElements.first { it.title.startsWith("S3") } to 2.hours,
            awsElements.first { it.title.startsWith("EC2") } to 2.hours,
            created.getValue("Git in Depth").second.first() to 1.days,
        ).forEach { (e, ago) -> db.library.upsertElement(e.copy(firstSeenAt = now - ago)) }
    }

    private suspend fun create(db: LocalDatabase, course: Course, order: Int): Pair<LibraryItem, List<PlayableElement>> {
        val itemPath = RelativePath.of(course.title)
        val item = LibraryItem(
            ContentId.forFolder(course.category, root, itemPath), course.category, root, itemPath, course.kind, course.title, null,
            null, null, order, null, 0, 0, 0.0, false, now - 30.days, now - 30.days,
        )
        db.library.upsertItem(item)
        val elements = mutableListOf<PlayableElement>()
        course.sections.forEachIndexed { sIndex, (sectionTitle, lessons) ->
            val sectionPath = itemPath.resolve(sectionTitle.ifEmpty { "Season 1" })
            val section = Section(
                ContentId.forFolder(course.category, root, sectionPath), item.id,
                when (course.kind) { StructureKind.SERIES -> SectionKind.SEASON; StructureKind.LIVE -> SectionKind.DATE; else -> SectionKind.MODULE },
                sectionTitle, null, sIndex + 1, null, now - 30.days,
            )
            db.library.upsertSection(section)
            lessons.forEachIndexed { lIndex, (title, duration) ->
                val resource = duration == 0L
                val ext = if (resource) (if (title.contains("Material")) "url" else "html") else "mp4"
                val path = sectionPath.resolve("%03d %s.%s".format(lIndex + 1, title, ext))
                val element = PlayableElement(
                    if (resource) ContentId.forResource(course.category, root, path) else ContentId.forVideo(course.category, root, path),
                    item.id, section.id, if (resource) ElementKind.RESOURCE else ElementKind.VIDEO,
                    if (resource) (if (ext == "url") ResourceType.URL else ResourceType.HTML) else null,
                    title, null, path, if (resource) MediaInfo.UNKNOWN else MediaInfo(duration, 1920, 1080, "h264", 30.0, 2400),
                    lIndex + 1, null, false, if (resource) 12_000 else duration * 300_000, now - 30.days, null, now - 30.days, now - 30.days,
                )
                db.library.upsertElement(element)
                elements += element
            }
        }
        db.library.upsertItem(item.copy(totalItems = elements.size, totalDurationSec = elements.sumOf { it.media.durationSec ?: 0 }))
        return item to elements
    }
}
