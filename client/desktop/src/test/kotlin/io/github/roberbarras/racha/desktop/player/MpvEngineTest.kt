package io.github.roberbarras.racha.desktop.player

import io.github.roberbarras.racha.application.playback.EngineSnapshot
import io.github.roberbarras.racha.application.playback.EngineState
import io.github.roberbarras.racha.application.playback.MediaSource
import io.github.roberbarras.racha.application.playback.SubtitleSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spec §4.1 formats and §4.2 basics on libmpv without video output (same checks as Media3EngineTest on Android). */
class MpvEngineTest {
    private val engine = MpvEngine(audio = false).apply { attachHeadless() }
    private val dir: Path = Files.createTempDirectory("racha-mpv")

    @AfterTest
    fun release() {
        engine.release()
        dir.toFile().deleteRecursively()
    }

    private fun media(name: String): Path = Path.of("../testdata/media", name).toAbsolutePath()

    private fun await(what: String, condition: (EngineSnapshot) -> Boolean): EngineSnapshot = runBlocking {
        runCatching { withTimeout(15_000) { engine.snapshot.first(condition) } }
            .getOrElse { throw AssertionError("timed out waiting for $what; last ${engine.snapshot.value}") }
    }

    private fun playsToTheEnd(file: Path) {
        engine.load(MediaSource(file.toUri().toString(), emptyList()), startMs = 0, playWhenReady = true)
        val ready = await("ready") { it.state == EngineState.READY && it.playing && it.videoWidth > 0 && it.videoHeight > 0 && it.durationMs != null }
        assertEquals(160, ready.videoWidth)
        assertEquals(90, ready.videoHeight)
        assertTrue(ready.durationMs!! in 1_800..2_400, "duration ${ready.durationMs}")
        assertTrue(await("audio tracks") { it.audioTracks.isNotEmpty() }.audioTracks.isNotEmpty())
        await("ended") { it.state == EngineState.ENDED }
        assertEquals(false, engine.snapshot.value.playing)
    }

    @Test
    fun playsMp4() = playsToTheEnd(media("sample.mp4"))

    @Test
    fun playsMkv() = playsToTheEnd(media("sample.mkv"))

    @Test
    fun playsWebm() = playsToTheEnd(media("sample.webm"))

    @Test
    fun playsMov() = playsToTheEnd(media("sample.mov"))

    @Test
    fun playsAvi() = playsToTheEnd(media("sample.avi"))

    @Test
    fun playsAnHlsPlaylist() = playsToTheEnd(media("hls/sample.m3u8"))

    @Test
    fun pathsWithSpacesAndSymbolsWork() {
        val video = media("sample.mp4").copyTo(dir.resolve("01 - Intro & Setup #1 (ñ).mp4"))
        playsToTheEnd(video)
    }

    @Test
    fun subtitlesStartOffAndShowTheSelectedLanguage() {
        val video = media("sample.mp4").copyTo(dir.resolve("Lesson.mp4"))
        val es = dir.resolve("Lesson_es.srt").apply { writeText("1\n00:00:00,000 --> 00:00:01,900\nHola mundo\n") }
        val en = dir.resolve("Lesson_en.srt").apply { writeText("1\n00:00:00,000 --> 00:00:01,900\nHello world\n") }
        val subtitles = listOf(
            SubtitleSource("sub-es", es.toUri().toString(), "es", "es"),
            SubtitleSource("sub-en", en.toUri().toString(), "en", "en"),
        )
        engine.load(MediaSource(video.toUri().toString(), subtitles), startMs = 0, playWhenReady = false)
        val ready = await("ready") { it.state == EngineState.READY }
        assertEquals(null, ready.selectedSubtitle, "subtitles are off by default (spec §4.2)")
        assertEquals(null, ready.subtitleText)

        engine.selectSubtitle("sub-es")
        assertEquals("Hola mundo", await("spanish cue") { it.subtitleText != null }.subtitleText)
        assertEquals("sub-es", engine.snapshot.value.selectedSubtitle)

        engine.selectSubtitle("sub-en")
        assertEquals("Hello world", await("english cue") { it.subtitleText == "Hello world" }.subtitleText)

        engine.selectSubtitle(null)
        assertEquals(null, await("subtitles off") { it.selectedSubtitle == null }.subtitleText)
    }

    @Test
    fun startsWhereAskedAndAppliesSpeedAndSeek() {
        engine.load(MediaSource(media("long.mp4").toUri().toString(), emptyList()), startMs = 12_000, playWhenReady = false)
        await("ready") { it.state == EngineState.READY }
        assertTrue(engine.positionMs() in 11_500..12_500, "starts where asked: ${engine.positionMs()}")

        engine.setSpeed(2f)
        assertEquals(2f, await("speed") { it.speed == 2f }.speed)

        engine.seekTo(3_000)
        val deadline = System.currentTimeMillis() + 5_000
        while (engine.positionMs() !in 2_500..3_500 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(engine.positionMs() in 2_500..3_500, "seeked: ${engine.positionMs()}")

        engine.play()
        assertTrue(await("playing") { it.playing }.playing)
    }

    /** AWT and GTK reset the C locale at start-up; with a decimal comma mpv would read "1.5" as 1. */
    @Test
    fun numbersSurviveADecimalCommaLocale() {
        val comma = listOf("es_ES.UTF-8", "es_ES.utf8", "de_DE.UTF-8", "de_DE.utf8").firstOrNull { setNumericLocale(it) }
            ?: return println("no decimal-comma locale installed: skipped")
        try {
            engine.load(MediaSource(media("long.mp4").toUri().toString(), emptyList()), startMs = 2_500, playWhenReady = false)
            await("ready") { it.state == EngineState.READY }
            setNumericLocale(comma)
            engine.setSpeed(1.5f)
            assertEquals(1.5f, await("speed 1.5") { it.speed == 1.5f }.speed)
            assertTrue(engine.positionMs() in 2_000..3_000, "fractional position read back: ${engine.positionMs()}")
        } finally {
            setNumericLocale("C")
        }
    }

    private fun setNumericLocale(name: String): Boolean {
        val linker = java.lang.foreign.Linker.nativeLinker()
        val setlocale = linker.downcallHandle(
            linker.defaultLookup().find("setlocale").orElseThrow(),
            java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.ADDRESS, java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.ADDRESS),
        )
        java.lang.foreign.Arena.ofConfined().use { arena ->
            val result = setlocale.invokeWithArguments(1, arena.allocateFrom(name)) as java.lang.foreign.MemorySegment
            return result != java.lang.foreign.MemorySegment.NULL
        }
    }

    @Test
    fun loadsQueuedBeforeTheOutputExists() {
        val late = MpvEngine(audio = false)
        try {
            late.load(MediaSource(media("sample.mp4").toUri().toString(), emptyList()), startMs = 0, playWhenReady = true)
            late.attachHeadless()
            runBlocking { withTimeout(15_000) { late.snapshot.first { it.playing } } }
        } finally {
            late.release()
        }
    }
}
