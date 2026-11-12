package io.github.roberbarras.racha.desktop

import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.domain.library.CategoryKey
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopDatabaseTest {
    @Test
    fun createsTheDatabaseOnFirstRunAndKeepsDataAcrossRestarts() = runBlocking {
        val file = Files.createTempDirectory("racha-desktop").resolve("nested/racha.db")

        val first = DesktopDatabase.open(file)
        EnsureBuiltInCategories(first.categories, Clock.SYSTEM)()
        first.categories.upsert(first.categories.get(CategoryKey.SERIES)!!.copy(name = "Mis series"))

        val restarted = DesktopDatabase.open(file)
        EnsureBuiltInCategories(restarted.categories, Clock.SYSTEM)()

        assertEquals(CategoryKey.BUILT_IN, restarted.categories.all().map { it.key })
        assertEquals("Mis series", restarted.categories.get(CategoryKey.SERIES)?.name)
    }
}
