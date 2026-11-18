package io.github.roberbarras.racha.desktop

import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.domain.library.CategoryKey
import kotlinx.coroutines.runBlocking
import io.github.roberbarras.racha.data.LocalDatabase
import java.nio.file.Files
import java.nio.file.Path
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

    /** The oldest schema a user can have (v1) migrates to the current one on the persistent connection, data kept. */
    @Test
    fun anOldDatabaseIsMigratedOnOpening() = runBlocking<Unit> {
        val file = Files.createTempDirectory("racha-desktop").resolve("racha.db")
        Files.copy(Path.of("../shared/data/src/commonMain/sqldelight/databases/1.db"), file)
        // As the first version of the app left it (schema snapshots do not carry the version number).
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { c -> c.createStatement().use { it.execute("PRAGMA user_version = 1") } }
        val database = DesktopDatabase.open(file)
        EnsureBuiltInCategories(database.categories, Clock.SYSTEM)()
        assertEquals(CategoryKey.BUILT_IN, database.categories.all().map { it.key })
        val version = java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use { it.executeQuery("PRAGMA user_version").use { r -> r.next(); r.getLong(1) } }
        }
        assertEquals(LocalDatabase.schema.version, version)
        // Opening again finds it current and does nothing.
        DesktopDatabase.open(file).categories.all()
    }
}
