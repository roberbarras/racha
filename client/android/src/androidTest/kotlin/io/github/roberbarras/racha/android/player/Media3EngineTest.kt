package io.github.roberbarras.racha.android.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.roberbarras.racha.application.playback.EngineSnapshot
import io.github.roberbarras.racha.application.playback.EngineState
import io.github.roberbarras.racha.application.playback.MediaSource
import io.github.roberbarras.racha.application.playback.SubtitleSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spec §4.1 formats and §4.2 basics on the device's real decoders: MP4, MKV, WebM, MOV, AVI and an HLS playlist. */
@RunWith(AndroidJUnit4::class)
class Media3EngineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val assets = instrumentation.context.assets
    private val dir = File(context.filesDir, "player-test").apply { deleteRecursively(); mkdirs() }
    private val engine: Media3Engine = main { Media3Engine(context) }

    @After
    fun release() = main { engine.release() }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun copy(asset: String, name: String = asset.substringAfterLast('/')): File =
        File(dir, name).apply { parentFile!!.mkdirs(); assets.open(asset).use { input -> outputStream().use { input.copyTo(it) } } }

    private fun await(what: String, condition: (EngineSnapshot) -> Boolean): EngineSnapshot = runBlocking {
        runCatching { withTimeout(15_000) { engine.snapshot.first(condition) } }
            .getOrElse { throw AssertionError("timed out waiting for $what; last ${engine.snapshot.value}") }
    }

    private fun playsToTheEnd(file: File) {
        main { engine.load(MediaSource(file.toURI().toString(), emptyList()), startMs = 0, playWhenReady = true) }
        val ready = await("ready") { it.state == EngineState.READY && it.playing }
        assertEquals(160, ready.videoWidth)
        assertEquals(90, ready.videoHeight)
        assertTrue(ready.durationMs!! in 1_800..2_400, "duration (MP3 in AVI pads ~0.25 s) ${ready.durationMs}")
        assertTrue(ready.audioTracks.isNotEmpty(), "audio tracks listed")
        await("ended") { it.state == EngineState.ENDED }
        assertEquals(false, engine.snapshot.value.playing)
    }

    @Test
    fun playsMp4() = playsToTheEnd(copy("sample.mp4"))

    @Test
    fun playsMkv() = playsToTheEnd(copy("sample.mkv"))

    @Test
    fun playsWebm() = playsToTheEnd(copy("sample.webm"))

    @Test
    fun playsMov() = playsToTheEnd(copy("sample.mov"))

    @Test
    fun playsAvi() = playsToTheEnd(copy("sample.avi"))

    @Test
    fun playsAnHlsPlaylist() {
        copy("hls/segment0.ts", "hls/segment0.ts")
        playsToTheEnd(copy("hls/sample.m3u8", "hls/sample.m3u8"))
    }

    @Test
    fun subtitlesStartOffAndShowTheSelectedLanguage() {
        val video = copy("sample.mp4", "Lesson.mp4")
        val es = File(dir, "Lesson_es.srt").apply { writeText("1\n00:00:00,000 --> 00:00:01,900\nHola mundo\n") }
        val en = File(dir, "Lesson_en.srt").apply { writeText("1\n00:00:00,000 --> 00:00:01,900\nHello world\n") }
        val subtitles = listOf(
            SubtitleSource("sub-es", es.toURI().toString(), "es", "es"),
            SubtitleSource("sub-en", en.toURI().toString(), "en", "en"),
        )
        main { engine.load(MediaSource(video.toURI().toString(), subtitles), startMs = 0, playWhenReady = false) }
        val ready = await("ready") { it.state == EngineState.READY }
        assertEquals(null, ready.selectedSubtitle, "subtitles are off by default (spec §4.2)")
        assertEquals(null, ready.subtitleText)

        main { engine.selectSubtitle("sub-es"); engine.play() }
        assertEquals("Hola mundo", await("spanish cue") { it.subtitleText != null }.subtitleText)
        assertEquals("sub-es", engine.snapshot.value.selectedSubtitle)

        main { engine.selectSubtitle(null) }
        assertEquals(null, await("subtitles off") { it.selectedSubtitle == null }.subtitleText)
    }

    @Test
    fun speedAndSeekApply() {
        val video = copy("sample.mp4")
        main { engine.load(MediaSource(video.toURI().toString(), emptyList()), startMs = 1_000, playWhenReady = false) }
        await("ready") { it.state == EngineState.READY }
        assertTrue(main { engine.positionMs() } in 900..1_100, "starts where asked")

        main { engine.setSpeed(2f) }
        assertEquals(2f, await("speed") { it.speed == 2f }.speed)

        main { engine.seekTo(200) }
        await("ready after seek") { it.state == EngineState.READY }
        assertTrue(main { engine.positionMs() } < 500)
    }
}
