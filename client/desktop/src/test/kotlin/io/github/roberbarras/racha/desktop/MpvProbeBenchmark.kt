package io.github.roberbarras.racha.desktop

import io.github.roberbarras.racha.desktop.library.MpvMediaProbe
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.files.NioLibrarySource
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test

/**
 * Opt-in measurement for ADR-0008 (metadata cost with a reused libmpv core):
 * `RACHA_PROBE_BENCH=/path/to/video.mp4 ./gradlew :desktop:test --tests '*MpvProbeBenchmark*'`.
 */
class MpvProbeBenchmark {
    @Test
    fun measure() = runBlocking {
        val file = System.getenv("RACHA_PROBE_BENCH")?.let(Path::of) ?: return@runBlocking
        val location = NioLibrarySource.locationOf(file.parent)
        val path = RelativePath.of(file.fileName.toString())
        MpvMediaProbe().use { probe ->
            val timings = (1..20).map {
                val start = System.nanoTime()
                val info = probe.probe(location, path)
                ((System.nanoTime() - start) / 1_000_000).also { ms -> if (it == 1) println("BENCH first=${ms}ms info=$info") }
            }
            val warm = timings.drop(1).sorted()
            println("BENCH warm median=${warm[warm.size / 2]}ms p90=${warm[(warm.size * 9) / 10]}ms max=${warm.last()}ms")
        }
    }
}
