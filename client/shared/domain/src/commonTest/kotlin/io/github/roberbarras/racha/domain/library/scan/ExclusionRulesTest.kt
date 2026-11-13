package io.github.roberbarras.racha.domain.library.scan

import io.github.roberbarras.racha.domain.library.RelativePath
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExclusionRulesTest {
    private fun RelativePath.excludedBy(vararg patterns: String, dir: Boolean = false) = ExclusionRules(patterns.toList()).excludes(this, dir)

    @Test
    fun directoryPatternExcludesTheFolderAndEverythingInsideAtAnyDepth() {
        assertTrue(RelativePath.of("temp").excludedBy("temp/", dir = true))
        assertTrue(RelativePath.of("temp/index_XXXXX_1920x1080.m3u8").excludedBy("temp/"))
        assertTrue(RelativePath.of("AWS/Temp/seg1.ts").excludedBy("temp/"))
        assertFalse(RelativePath.of("templates/a.mp4").excludedBy("temp/"))
        assertFalse(RelativePath.of("temp.mp4").excludedBy("temp/"))
    }

    @Test
    fun fileGlobsAndAnchoredPaths() {
        assertTrue(RelativePath.of("a/b/x.M3U8").excludedBy("*.m3u8"))
        assertFalse(RelativePath.of("a/b/x.mp4").excludedBy("*.m3u8"))
        assertTrue(RelativePath.of("Course/extras/bonus.mp4").excludedBy("Course/extras/"))
        assertFalse(RelativePath.of("Other/Course/extras/bonus.mp4").excludedBy("Course/extras/"))
    }

    @Test
    fun hiddenEntriesAreAlwaysSkipped() {
        assertTrue(RelativePath.of(".thumbnails/a.jpg").excludedBy())
        assertTrue(RelativePath.of("Course/.DS_Store").excludedBy())
    }
}
