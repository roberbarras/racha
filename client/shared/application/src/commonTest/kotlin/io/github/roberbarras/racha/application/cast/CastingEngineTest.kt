package io.github.roberbarras.racha.application.cast

import io.github.roberbarras.racha.application.playback.EngineSnapshot
import io.github.roberbarras.racha.application.playback.EngineState
import io.github.roberbarras.racha.application.playback.MediaSource
import io.github.roberbarras.racha.application.playback.PlayerEngine
import io.github.roberbarras.racha.application.playback.SubtitleSource
import io.github.roberbarras.racha.domain.cast.CastDevice
import io.github.roberbarras.racha.domain.cast.CastKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Sending to a TV and back (H18): same position, the controller's calls reach the TV, a lost TV falls back here. */
@OptIn(ExperimentalCoroutinesApi::class)
class CastingEngineTest {
    private val lesson = MediaSource("file:///courses/AWS/02%20IAM.mp4", listOf(SubtitleSource("es", "file:///courses/AWS/02%20IAM.es.srt", "es", "Español")))
    private val next = MediaSource("file:///courses/AWS/03%20EC2.mp4", emptyList())
    private val tv = CastDevice("cc-1", "Salón", CastKind.CHROMECAST, "192.168.1.20:8009")

    private class FakeLocal : PlayerEngine {
        override val snapshot = MutableStateFlow(EngineSnapshot(EngineState.READY, playing = true, durationMs = 600_000))
        var position = 0L
        val calls = mutableListOf<String>()
        override fun positionMs() = position
        override fun load(source: MediaSource, startMs: Long, playWhenReady: Boolean) { calls += "load ${source.uri.substringAfterLast('/')} $startMs $playWhenReady"; position = startMs }
        override fun play() { calls += "play"; snapshot.value = snapshot.value.copy(playing = true) }
        override fun pause() { calls += "pause"; snapshot.value = snapshot.value.copy(playing = false) }
        override fun seekTo(positionMs: Long) { calls += "seek $positionMs"; position = positionMs }
        override fun setSpeed(speed: Float) { calls += "speed $speed" }
        override fun selectSubtitle(id: String?) { calls += "subtitle $id" }
        override fun selectAudio(id: String?) {}
        override fun release() { calls += "release" }
    }

    private class FakeTv(override val supportsSpeed: Boolean = true) : RemotePlayer {
        val calls = mutableListOf<String>()
        var status = RemoteStatus(EngineState.IDLE, false, 0, null)
        var failing = false
        override suspend fun load(media: RemoteMedia, play: Boolean) {
            calls += "load ${media.url} ${media.startMs} $play ${media.subtitle?.url} ${media.title}"
            status = RemoteStatus(EngineState.BUFFERING, play, media.startMs, 600_000)
        }
        override suspend fun play() { calls += "play" }
        override suspend fun pause() { calls += "pause" }
        override suspend fun seek(positionMs: Long) { calls += "seek $positionMs" }
        override suspend fun setSpeed(speed: Float) { calls += "speed $speed" }
        override suspend fun status(): RemoteStatus { if (failing) error("gone"); return status }
        override suspend fun stop() { calls += "stop" }
        override fun close() { calls += "close" }
    }

    private class FakePublisher : MediaPublisher {
        val published = mutableListOf<String>()
        var stopped = 0
        override suspend fun publishFile(uri: String, contentType: String, forHost: String) =
            "http://192.168.1.5:4000/m/${uri.substringAfterLast('/')}".also { published += "$it $contentType for $forHost" }
        override suspend fun publishSubtitle(uri: String, forHost: String) = "http://192.168.1.5:4000/s/${uri.substringAfterLast('/')}.vtt"
        override fun stop() { stopped++ }
    }

    private fun TestScope.engine(local: FakeLocal, tv: FakeTv, publisher: FakePublisher = FakePublisher()) =
        CastingEngine(local, { tv }, publisher, backgroundScope, time = testScheduler.timeSource)

    @Test
    fun castingKeepsThePositionAndTheTvObeysTheController() = runTest {
        val local = FakeLocal()
        val remote = FakeTv()
        val publisher = FakePublisher()
        val engine = engine(local, remote, publisher)
        engine.load(lesson, 0, true)
        local.position = 754_000
        engine.selectSubtitle("es")

        engine.castTo(tv)
        runCurrent()
        assertIs<CastState.Casting>(engine.cast.value)
        assertEquals("load http://192.168.1.5:4000/m/02%20IAM.mp4 754000 true http://192.168.1.5:4000/s/02%20IAM.es.srt.vtt IAM", remote.calls.first())
        assertEquals("http://192.168.1.5:4000/m/02%20IAM.mp4 video/mp4 for 192.168.1.20", publisher.published.single())
        assertTrue("pause" in local.calls, "this device stops playing")
        assertEquals(754_000, engine.positionMs())

        // The TV reports; the controller drives it.
        remote.status = RemoteStatus(EngineState.READY, true, 760_000, 600_000_0)
        advanceTimeBy(1_100)
        assertTrue(engine.snapshot.value.playing)
        engine.pause()
        engine.seekTo(800_000)
        engine.setSpeed(1.5f)
        runCurrent()
        assertEquals(listOf("pause", "seek 800000", "speed 1.5"), remote.calls.drop(1))
        assertEquals(800_000, engine.positionMs())

        // The next lesson goes to the TV, not here.
        engine.load(next, 0, true)
        runCurrent()
        assertTrue(remote.calls.last().startsWith("load http://192.168.1.5:4000/m/03%20EC2.mp4 0 true null EC2"))
        // Idle right after loading is not "ended".
        remote.status = RemoteStatus(EngineState.ENDED, false, 0, null)
        advanceTimeBy(1_100)
        assertEquals(EngineState.BUFFERING, engine.snapshot.value.state)

        // Back here: the lesson the TV was on, where it was, paused.
        remote.status = RemoteStatus(EngineState.READY, false, 42_000, 900_000)
        advanceTimeBy(1_100)
        engine.stopCasting()
        runCurrent()
        assertEquals(CastState.Local, engine.cast.value)
        assertEquals("load 03%20EC2.mp4 42000 false", local.calls.last())
        assertEquals(listOf("stop", "close"), remote.calls.takeLast(2))
        assertEquals(1, publisher.stopped)
    }

    @Test
    fun aTvThatCannotBeReachedOrGetsLostLeavesThePlayerHere() = runTest {
        val local = FakeLocal()
        val failing = CastingEngine(local, { error("refused") }, FakePublisher(), backgroundScope, time = testScheduler.timeSource)
        failing.load(lesson, 0, true)
        failing.castTo(tv)
        runCurrent()
        assertEquals(CastState.Failed(tv, "connect"), failing.cast.value)
        assertTrue("pause" !in local.calls, "keeps playing here")

        val local2 = FakeLocal()
        val remote = FakeTv(supportsSpeed = false)
        val engine = engine(local2, remote)
        engine.load(lesson, 0, true)
        local2.position = 100_000
        engine.castTo(tv)
        runCurrent()
        engine.setSpeed(2f)
        runCurrent()
        assertTrue(remote.calls.none { it.startsWith("speed") }, "a TV without speed control is not asked")
        remote.failing = true
        advanceTimeBy(1_000L * (CastingEngine.LOST_AFTER + 1))
        assertEquals(CastState.Failed(tv, "lost"), engine.cast.value)
        assertEquals("seek 100000", local2.calls.dropLast(1).last())
        assertEquals("pause", local2.calls.last())
    }
}
