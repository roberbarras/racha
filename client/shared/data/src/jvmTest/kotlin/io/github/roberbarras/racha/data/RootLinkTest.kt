package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.data.Fixtures.root
import io.github.roberbarras.racha.data.Fixtures.section
import io.github.roberbarras.racha.data.Fixtures.video
import io.github.roberbarras.racha.domain.learning.Bookmark
import io.github.roberbarras.racha.domain.learning.Favorite
import io.github.roberbarras.racha.domain.learning.FavoriteTarget
import io.github.roberbarras.racha.domain.learning.Progress
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.library.RootKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** ADR-0004: linking a folder to the same one on another device keeps everything the user did. */
class RootLinkTest {
    private val now = Instant.parse("2026-11-16T10:00:00Z")
    private val clock = Clock { now }
    private val db = Fixtures.newDatabase()
    private val item = Fixtures.item("AWS Course")
    private val intro = section(item, "01 - Introduction", 1)
    private val lesson = video(item, intro, "001 Course Introduction.mp4", 1)
    private val phoneKey = RootKey("6f0c3a51-9d4e-4f8e-8f0e-3b7a2c1d5e90")

    @Test
    fun userDataFollowsTheNewIds() = runBlocking {
        EnsureBuiltInCategories(db.categories, clock)()
        val localRoot = CategoryRoot(root, CategoryKey.COURSES, "file:///home/ana/Cursos/", 0)
        db.categories.upsertRoot(localRoot)
        db.library.upsertItem(item)
        db.library.upsertSection(intro)
        db.library.upsertElement(lesson)
        db.progress.save(Progress.start(lesson.id, 2710, now).recordPosition(754, 2710, now))
        db.bookmarks.save(Bookmark("bm-1", lesson.id, 120, "IAM roles", now, now))
        db.favorites.add(Favorite(FavoriteTarget.Element(lesson.id), now))
        db.favorites.add(Favorite(FavoriteTarget.Item(item.id), now))

        // The phone's progress for the same lesson already arrived (newer, but not as far).
        val linkedLesson = ContentId.forVideo(CategoryKey.COURSES, phoneKey, lesson.relativePath)
        val later = now + 1.hours
        db.progress.save(Progress.start(linkedLesson, 2710, later).recordPosition(100, 2710, later))

        db.rootRelinker.relink(localRoot, phoneKey)

        val merged = db.progress.get(linkedLesson)!!
        assertEquals(100, merged.positionSec, "the newer state wins")
        assertEquals(754, merged.maxPositionSec, "the furthest point reached is kept")
        assertNull(db.progress.get(lesson.id))
        assertEquals("IAM roles", db.bookmarks.observe(linkedLesson).first().single().note)
        val favourites = db.favorites.observeAll().first().map { it.target }
        assertTrue(FavoriteTarget.Element(linkedLesson) in favourites)
        assertTrue(FavoriteTarget.Item(ContentId.forFolder(CategoryKey.COURSES, phoneKey, item.relativePath)) in favourites)
        assertEquals(listOf(phoneKey), db.categories.roots(CategoryKey.COURSES).map { it.key })
        assertNull(db.library.item(item.id), "the old rows go; the next scan rebuilds them with the new ids")
    }
}
