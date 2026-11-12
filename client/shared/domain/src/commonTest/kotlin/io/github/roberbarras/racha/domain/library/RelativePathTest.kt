package io.github.roberbarras.racha.domain.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelativePathTest {
    @Test
    fun normalisesSeparatorsDotsAndDuplicateSlashes() {
        assertEquals("01 - Intro/001 A.mp4", RelativePath.of("./01 - Intro//./001 A.mp4/").value)
        assertEquals("01 - Intro/001 A.mp4", RelativePath.of("\\01 - Intro\\001 A.mp4").value)
    }

    @Test
    fun preservesCase() {
        assertEquals("AWS/Intro.MP4", RelativePath.of("AWS/Intro.MP4").value)
    }

    @Test
    fun rejectsParentTraversal() {
        assertFailsWith<IllegalArgumentException> { RelativePath.of("a/../b") }
    }

    @Test
    fun exposesNameParentAndLowerCaseExtension() {
        val path = RelativePath.of("02 - Code/001 Slides.HTML")

        assertEquals("001 Slides.HTML", path.name)
        assertEquals("02 - Code", path.parent.value)
        assertEquals("html", path.extension)
        assertEquals("02 - Code/001 Slides", path.withoutExtension().value)
    }

    @Test
    fun dotFilesAndNamesWithoutExtensionHaveNoExtension() {
        assertNull(RelativePath.of("a/.nomedia").extension)
        assertNull(RelativePath.of("a/external-links").extension)
        assertNull(RelativePath.of("a/trailing.").extension)
    }

    @Test
    fun rootIsEmptyAndResolvesChildren() {
        assertTrue(RelativePath.of("").isRoot)
        assertEquals(RelativePath.ROOT, RelativePath.of("a").parent)
        assertEquals("a/b.mp4", RelativePath.ROOT.resolve("a").resolve("b.mp4").value)
    }
}
