package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.application.browse.UpdateItemProgress
import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.application.library.scan.LibrarySource
import io.github.roberbarras.racha.application.library.scan.LocalDates
import io.github.roberbarras.racha.application.playback.EngineSnapshot
import io.github.roberbarras.racha.application.playback.EngineState
import io.github.roberbarras.racha.application.playback.MediaSource
import io.github.roberbarras.racha.application.playback.PlaybackController
import io.github.roberbarras.racha.application.playback.PlayerEngine
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.data.Fixtures.root
import io.github.roberbarras.racha.data.Fixtures.section
import io.github.roberbarras.racha.data.Fixtures.video
import io.github.roberbarras.racha.domain.learning.Progress
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.domain.library.LanguageCode
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.ResourceType
import io.github.roberbarras.racha.domain.library.Subtitle
import io.github.roberbarras.racha.domain.library.scan.CalendarDate
import io.github.roberbarras.racha.domain.library.scan.FileEntry
import io.github.roberbarras.racha.domain.playback.StartMode
import io.github.roberbarras.racha.domain.preferences.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PlaybackControllerTest {
    private var now = Instant.parse("2026-11-13T10:00:00Z")
    private val clock = Clock { now }
    private val db = Fixtures.newDatabase()
    private val preferences = Preferences(db.settings, clock)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = FakeEngine()
    private val item = Fixtures.item()
    private val intro = section(item, "01 - Introduction", 1)
    private val first = video(item, intro, "001 Course Introduction.mp4", 1).copy(media = video(item, intro, "x.mp4", 1).media.copy(durationSec = 2710))
    private val second = video(item, intro, "002 Creating an AWS Account.mp4", 2)
    private val quizPath = item.relativePath.resolve("01 - Introduction/003 Quiz.html")
    private val quiz = second.copy(
        id = ContentId.forResource(CategoryKey.COURSES, root, quizPath), kind = ElementKind.RESOURCE, resourceType = ResourceType.HTML,
        relativePath = quizPath, order = 3,
    )
    private val uriSource = object : LibrarySource {
        override fun displayName(location: String) = location
        override suspend fun list(location: String, descend: (RelativePath) -> Boolean) = emptyList<FileEntry>()
        override fun uri(location: String, path: RelativePath) = "$location/${path.value}"
    }
    private val controller = PlaybackController(
        engine, scope, db.library, db.categories, uriSource, db.progress, db.bookmarks, db.history, db.stats, preferences,
        UpdateItemProgress(db.library, db.progress), clock, LocalDates { CalendarDate(2026, 11, 13) }, { "es" },
    )

    @AfterTest
    fun tearDown() = scope.cancel()

    private suspend fun seed() {
        EnsureBuiltInCategories(db.categories, clock)()
        db.categories.upsertRoot(CategoryRoot(root, CategoryKey.COURSES, "file:///cursos", 0))
        db.library.upsertItem(item)
        db.library.upsertSection(intro)
        listOf(first, second, quiz).forEach { db.library.upsertElement(it) }
    }

    private suspend fun eventually(check: suspend () -> Boolean) = withTimeout(5_000) { while (!check()) delay(20) }

    @Test
    fun resumeModeLoadsAtTheSavedPositionAndAsksWhenRequested() = runBlocking {
        seed()
        db.progress.save(Progress.start(first.id, 2710, now).recordPosition(754, null, now))

        controller.open(first.id, StartMode.RESUME)
        assertEquals(754_000L to true, engine.loaded!!.second to engine.loaded!!.third)
        assertEquals("file:///cursos/AWS Course/01 - Introduction/001 Course Introduction.mp4", engine.loaded!!.first.uri)

        controller.open(first.id, StartMode.ASK)
        assertEquals(754L, controller.state.value.resumeOffer)
        assertEquals(false, engine.loaded!!.third, "paused until the user answers")
        controller.answerResume(resume = false)
        assertEquals(0L, engine.seeks.last())
        assertTrue(engine.playing)
    }

    @Test
    fun pausingSavesTheExactSecondAndEndingCompletesAndPlaysTheNext() = runBlocking {
        seed()
        controller.open(first.id, StartMode.BEGINNING)
        engine.emitPlaying(true)
        now += 754.seconds
        engine.position = 754_000
        controller.pause()
        eventually { db.progress.get(first.id)?.positionSec == 754L }

        engine.position = 2_710_000
        engine.emit(EngineState.ENDED, playing = false)
        eventually { controller.state.value.element?.id == second.id }

        assertTrue(db.progress.get(first.id)!!.completed)
        assertEquals(1, db.history.observeRecentOnce().size, "one session in history")
        assertTrue(db.stats.get(PlaybackController.WATCH_SECONDS, "2026-11-13")!!.value >= 754.0)
    }

    @Test
    fun aResourceNextIsHandedToTheResourceViewer() = runBlocking {
        seed()
        controller.open(second.id, StartMode.BEGINNING)
        engine.emit(EngineState.ENDED, playing = false)

        eventually { controller.state.value.openResource?.id == quiz.id }
    }

    @Test
    fun autoplayOffStopsAtTheEndOfTheElement() = runBlocking {
        seed()
        preferences.set(Settings.AutoplayNext, false)
        controller.open(first.id, StartMode.BEGINNING)
        engine.emit(EngineState.ENDED, playing = false)

        eventually { db.progress.get(first.id)?.completed == true }
        assertEquals(first.id, controller.state.value.element?.id)
    }

    @Test
    fun skipIntroAndOutroPerElement() = runBlocking {
        seed()
        controller.open(first.id, StartMode.BEGINNING)
        controller.setSkips(introSec = 30, outroSec = 60)
        controller.open(first.id, StartMode.BEGINNING)
        assertEquals(30_000L, engine.loaded!!.second, "starts after the intro")

        engine.durationMs = 2_710_000
        engine.emitPlaying(true)
        engine.position = 2_651_000 // inside the outro
        eventually { db.progress.get(first.id)?.completed == true }
        assertEquals(30, db.library.element(first.id)!!.skipIntroSec, "stored per element")
    }

    @Test
    fun abLoopJumpsBackFromBToA() = runBlocking {
        seed()
        controller.open(first.id, StartMode.BEGINNING)
        engine.position = 10_000
        controller.markLoopA()
        engine.position = 20_000
        controller.markLoopB()
        engine.emitPlaying(true)
        engine.position = 20_500

        eventually { engine.seeks.lastOrNull() == 10_000L }
        controller.clearLoop()
        assertNull(controller.state.value.loopA)
    }

    @Test
    fun bookmarksSpeedAndSubtitles() = runBlocking {
        seed()
        val srt = Subtitle(ContentId.forSubtitle(CategoryKey.COURSES, root, RelativePath.of("x_es.srt")), first.id, LanguageCode("es"), RelativePath.of("x_es.srt"), now)
        db.library.upsertSubtitle(srt)
        controller.open(first.id, StartMode.BEGINNING)
        assertNull(engine.subtitle, "subtitles off by default (spec §4.2)")

        preferences.set(Settings.SubtitlesEnabled, true)
        controller.open(first.id, StartMode.BEGINNING)
        assertEquals(srt.id.value, engine.subtitle, "app language (es) picked when enabled")
        assertEquals(1, engine.loaded!!.first.subtitles.size)

        engine.position = 95_000
        controller.addBookmark("IAM policy example")
        eventually { controller.state.value.bookmarks.map { it.timestampSec } == listOf(95L) }

        controller.setSpeed(1.5)
        assertEquals(1.5f, engine.currentSpeed)
        controller.holdSpeed(true)
        eventually { engine.currentSpeed == 2f }
        controller.holdSpeed(false)
        eventually { engine.currentSpeed == 1.5f }
    }

    private suspend fun SqlHistoryRepository.observeRecentOnce() = observeRecent(10).first()

    private class FakeEngine : PlayerEngine {
        override val snapshot = MutableStateFlow(EngineSnapshot())
        var position = 0L
        var durationMs: Long? = 2_710_000
        var loaded: Triple<MediaSource, Long, Boolean>? = null
        val seeks = mutableListOf<Long>()
        var currentSpeed = 1f
        var subtitle: String? = null
        val playing get() = snapshot.value.playing

        fun emit(state: EngineState, playing: Boolean) {
            snapshot.value = snapshot.value.copy(state = state, playing = playing, durationMs = durationMs)
        }

        fun emitPlaying(playing: Boolean) = emit(EngineState.READY, playing)

        override fun positionMs() = position
        override fun load(source: MediaSource, startMs: Long, playWhenReady: Boolean) {
            loaded = Triple(source, startMs, playWhenReady)
            position = startMs
            snapshot.value = EngineSnapshot(state = EngineState.READY, playing = playWhenReady, durationMs = durationMs)
        }
        override fun play() = emitPlaying(true)
        override fun pause() = emitPlaying(false)
        override fun seekTo(positionMs: Long) { seeks += positionMs; position = positionMs }
        override fun setSpeed(speed: Float) { currentSpeed = speed }
        override fun selectSubtitle(id: String?) { subtitle = id }
        override fun selectAudio(id: String?) = Unit
        override fun release() = Unit
    }
}
