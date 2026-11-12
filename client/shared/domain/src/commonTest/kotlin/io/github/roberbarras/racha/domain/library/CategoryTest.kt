package io.github.roberbarras.racha.domain.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class CategoryTest {
    private val now = Instant.parse("2026-11-12T10:00:00Z")

    @Test
    fun builtInsHaveExactlyOnePrimaryAndHideTempFolders() {
        val builtIns = Category.builtIns(now)

        assertEquals(CategoryKey.BUILT_IN, builtIns.map { it.key })
        assertEquals(listOf(CategoryKey.COURSES), builtIns.filter { it.isPrimary }.map { it.key })
        assertTrue(builtIns.all { "temp/" in it.excludePatterns })
    }

    @Test
    fun modesFilterVisibleCategories() {
        val builtIns = Category.builtIns(now)

        assertEquals(listOf(CategoryKey.COURSES, CategoryKey.LIVE), builtIns.filter { it.isVisibleIn(Mode.STUDY) }.map { it.key })
        assertEquals(listOf(CategoryKey.SERIES, CategoryKey.LIVE), builtIns.filter { it.isVisibleIn(Mode.LEISURE) }.map { it.key })
    }

    @Test
    fun rejectsBlankNamesAndInvalidColors() {
        val courses = Category.builtIns(now).first()

        assertFailsWith<IllegalArgumentException> { courses.copy(name = " ") }
        assertFailsWith<IllegalArgumentException> { courses.copy(color = "orange") }
    }
}
