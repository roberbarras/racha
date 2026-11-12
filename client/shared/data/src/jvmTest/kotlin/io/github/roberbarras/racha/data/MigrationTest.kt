package io.github.roberbarras.racha.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import io.github.roberbarras.racha.data.db.RachaDatabase
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every committed schema snapshot (`databases/<version>.db`, one per released schema) must migrate to exactly the
 * schema a fresh install creates. Add a snapshot with `generateCommonMainRachaDatabaseSchema` whenever the schema
 * version changes; `verifyCommonMainRachaDatabaseMigration` checks the `.sqm` files at build time as well.
 */
class MigrationTest {
    private val snapshots = File("src/commonMain/sqldelight/databases").listFiles { f -> f.name.matches(Regex("\\d+\\.db")) }
        .orEmpty()
        .sortedBy { it.nameWithoutExtension.toInt() }

    @Test
    fun thereIsASnapshotForTheCurrentSchemaVersion() {
        assertTrue(snapshots.any { it.nameWithoutExtension.toLong() == RachaDatabase.Schema.version }, "missing ${RachaDatabase.Schema.version}.db")
    }

    @Test
    fun everySnapshotMigratesToTheFreshSchema() {
        val fresh = Fixtures.driver().also { RachaDatabase.Schema.create(it) }.use(::schemaOf)

        snapshots.forEach { snapshot ->
            val copy = Files.createTempFile("racha-migration", ".db")
            Files.copy(snapshot.toPath(), copy, StandardCopyOption.REPLACE_EXISTING)
            Fixtures.driver("jdbc:sqlite:$copy").use { driver ->
                RachaDatabase.Schema.migrate(driver, snapshot.nameWithoutExtension.toLong(), RachaDatabase.Schema.version)
                assertEquals(fresh, schemaOf(driver), "schema after migrating ${snapshot.name}")
            }
            Files.deleteIfExists(copy)
        }
    }

    private fun schemaOf(driver: SqlDriver): List<String> = driver.executeQuery(
        null,
        "SELECT type || ' ' || name || ': ' || coalesce(sql, '') FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name",
        { cursor ->
            val rows = mutableListOf<String>()
            while (cursor.next().value) rows += cursor.getString(0)!!.replace(Regex("\\s+"), " ")
            QueryResult.Value(rows)
        },
        0,
    ).value
}
