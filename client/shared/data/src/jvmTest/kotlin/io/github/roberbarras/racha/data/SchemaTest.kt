package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.data.Fixtures.courses
import io.github.roberbarras.racha.data.Fixtures.item
import io.github.roberbarras.racha.data.Fixtures.section
import io.github.roberbarras.racha.data.Fixtures.video
import io.github.roberbarras.racha.data.db.RachaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull

class SchemaTest {
    private val expectedTables = setOf(
        "category", "category_root", "library_item", "section", "playable_element", "subtitle",
        "progress", "bookmark", "history_entry", "favorite", "local_stat",
        "app_setting", "sync_queue", "device", "account",
    )

    @Test
    fun freshDatabaseHasEveryTableOfTheModelAndRecordsItsVersion() {
        val driver = Fixtures.driver()
        RachaDatabase.Schema.create(driver)

        val tables = driver.executeQuery(null, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'", {
            val names = mutableSetOf<String>()
            while (it.next().value) names += it.getString(0)!!
            app.cash.sqldelight.db.QueryResult.Value(names)
        }, 0).value

        assertEquals(expectedTables, tables)
        assertEquals(1, RachaDatabase.Schema.version)
    }

    @Test
    fun foreignKeysAreEnforced() = runBlocking {
        val db = Fixtures.newDatabase()
        val orphan = item() // its category was never inserted

        assertFails { db.library.upsertItem(orphan) }
        Unit
    }

    @Test
    fun dataSurvivesReopeningAFileDatabaseAndMigratingToTheSameVersionIsANoOp() = runBlocking {
        val file = Files.createTempFile("racha", ".db").also { Files.delete(it) }
        val url = "jdbc:sqlite:$file"
        Fixtures.driver(url).use { driver ->
            RachaDatabase.Schema.create(driver)
            val db = LocalDatabase(driver, Dispatchers.Default)
            db.categories.upsert(courses())
            val item = item()
            db.library.upsertItem(item)
            val section = section(item, "01 - Introduction", 1)
            db.library.upsertSection(section)
            db.library.upsertElement(video(item, section, "001 Course Introduction.mp4", 1))
        }

        Fixtures.driver(url).use { driver ->
            RachaDatabase.Schema.migrate(driver, RachaDatabase.Schema.version, RachaDatabase.Schema.version)
            val db = LocalDatabase(driver, Dispatchers.Default)
            assertEquals(1, db.library.elements(item().id).size)
            assertNotNull(db.categories.get(courses().key))
        }
        Files.deleteIfExists(file)
        Unit
    }
}
