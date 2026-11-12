package io.github.roberbarras.racha.domain.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** Golden vectors were computed independently (Python hashlib); a change here breaks cross-device sync. */
class ContentIdTest {
    private val root = RootKey("0b4f1b5e-6d1f-4a35-9a51-6a3a1b0c9d10")

    @Test
    fun videoIdHashesThePathWithoutExtension() {
        val id = ContentId.forVideo(CategoryKey.COURSES, root, RelativePath.of("01 - Introduction/001 Course Introduction.mp4"))

        assertEquals("87dd7019639ce1617d368d235ff32c6aeb0001a27ec3a2f2f066fe0ce93db2b6", id.value)
    }

    @Test
    fun resourceIdKeepsTheExtension() {
        val path = RelativePath.of("01 - Introduction/003 AWS Account Activation Troubleshooting.html")

        assertEquals(
            "ed43c24f2868306962bf2da9aba79045ab3887da770d6de83f43e7c7e8bf19e4",
            ContentId.forResource(CategoryKey.COURSES, root, path).value,
        )
    }

    @Test
    fun folderIdEndsWithSlash() {
        assertEquals(
            "3d092e1d903459e0972fa6122533c7f03760c4f728b53c544be69f0cc9e5cb4d",
            ContentId.forFolder(CategoryKey.COURSES, root, RelativePath.of("AWS Course")).value,
        )
    }

    @Test
    fun decomposedUnicodeAndBackslashesYieldTheSameIdAsComposedForwardSlashes() {
        val composed = RelativePath.of("Café/S01E01 Piloto.mkv")
        val decomposedWindowsStyle = RelativePath.of(".\\Café\\\\S01E01 Piloto.mkv")

        assertEquals(composed, decomposedWindowsStyle)
        assertEquals(
            "9219290347a68a595633a521e7389117044012d5c18c58cc99fe4209205247d8",
            ContentId.forVideo(CategoryKey.SERIES, root, decomposedWindowsStyle).value,
        )
    }

    @Test
    fun videoAndSameNamedResourceOrFolderNeverCollide() {
        val video = ContentId.forVideo(CategoryKey.COURSES, root, RelativePath.of("01/003 X.mp4"))
        val resource = ContentId.forResource(CategoryKey.COURSES, root, RelativePath.of("01/003 X.html"))
        val folder = ContentId.forFolder(CategoryKey.COURSES, root, RelativePath.of("01/003 X"))

        assertEquals(3, setOf(video, resource, folder).size)
    }

    @Test
    fun differentRootsOrCategoriesProduceDifferentIdsForTheSamePath() {
        val path = RelativePath.of("video.mp4")
        val otherRoot = RootKey("9c3a8f8e-1d51-4e2c-8f0e-5d0b7a2e4c11")

        assertNotEquals(ContentId.forVideo(CategoryKey.COURSES, root, path), ContentId.forVideo(CategoryKey.COURSES, otherRoot, path))
        assertNotEquals(ContentId.forVideo(CategoryKey.COURSES, root, path), ContentId.forVideo(CategoryKey.SERIES, root, path))
    }

    @Test
    fun syntheticGroupsAreStableAndDistinctFromTheirParent() {
        val channel = ContentId.forFolder(CategoryKey.LIVE, root, RelativePath.of("Channel"))

        assertEquals(ContentId.forGroup(channel, "2026-11-12"), ContentId.forGroup(channel, "2026-11-12"))
        assertNotEquals(channel, ContentId.forGroup(channel, "2026-11-12"))
    }

    @Test
    fun rejectsMalformedValuesAndKeys() {
        assertFailsWith<IllegalArgumentException> { ContentId("ABC") }
        assertFailsWith<IllegalArgumentException> { RootKey("not-a-uuid") }
        assertFailsWith<IllegalArgumentException> { CategoryKey("Bad Key") }
    }
}
