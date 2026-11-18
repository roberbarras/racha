package io.github.roberbarras.racha.domain.learning

import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.domain.library.MediaInfo
import io.github.roberbarras.racha.domain.library.PlayableElement
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.library.ResourceType
import io.github.roberbarras.racha.domain.library.RootKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class WatchTimeTest {
    private val t = Instant.parse("2026-11-18T10:00:00Z")
    private val root = RootKey("6d0f8a52-1c1b-4c55-9a51-0e5d3c2b1a10")

    private fun element(name: String, duration: Long?, kind: ElementKind = ElementKind.VIDEO, archived: Boolean = false): PlayableElement {
        val path = RelativePath.of("Curso/01 - Intro/$name")
        val id = ContentId.forVideo(CategoryKey.COURSES, root, path)
        return PlayableElement(
            id, ContentId.forFolder(CategoryKey.COURSES, root, RelativePath.of("Curso")), ContentId.forFolder(CategoryKey.COURSES, root, path.parent),
            kind, if (kind == ElementKind.RESOURCE) ResourceType.HTML else null, name, null, path,
            MediaInfo(duration, null, null, null, null, null), 1, null, archived, 1, t, null, t, t,
        )
    }

    @Test
    fun finishedCountsWholeHalfWatchedUpToTheFurthestPointAndResourcesNothing() {
        val done = element("a.mp4", 600)
        val half = element("b.mp4", 1_000)
        val untouched = element("c.mp4", 400)
        val page = element("d.html", null, ElementKind.RESOURCE)
        val gone = element("e.mp4", 900, archived = true)
        val time = WatchTime.of(listOf(
            done to Progress.start(done.id, 600, t).finish(t),
            half to Progress.start(half.id, 1_000, t).recordPosition(700, 1_000, t).recordPosition(300, 1_000, t),
            untouched to null,
            page to null,
            gone to null,
        ))
        assertEquals(WatchTime(2_000, 1_300), time)
        assertEquals(700, time.leftSec)
        assertEquals(WatchTime(400, 0), WatchTime.of(listOf(untouched to null)))
    }
}
