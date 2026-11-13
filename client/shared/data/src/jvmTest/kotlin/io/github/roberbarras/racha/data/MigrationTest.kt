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

    /**
     * Structural description (columns, types, nullability, defaults, keys, foreign keys, index definitions). The raw
     * CREATE TABLE text is not compared: ALTER TABLE ADD COLUMN stores equivalent SQL with different formatting.
     */
    private fun schemaOf(driver: SqlDriver): List<String> {
        fun rows(sql: String): List<List<String?>> = driver.executeQuery(null, sql, { cursor ->
            val out = mutableListOf<List<String?>>()
            while (cursor.next().value) out += (0 until 8).map { i -> runCatching { cursor.getString(i) }.getOrNull() }
            QueryResult.Value(out)
        }, 0).value
        val tables = rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").map { it[0]!! }
        val description = mutableListOf<String>()
        tables.forEach { table ->
            rows("PRAGMA table_info('$table')").forEach { c -> description += "$table.column ${c[1]} ${c[2]} notnull=${c[3]} default=${c[4]} pk=${c[5]}" }
            rows("PRAGMA foreign_key_list('$table')").forEach { f -> description += "$table.fk ${f[3]} -> ${f[2]}.${f[4]} on delete ${f[6]}" }
        }
        rows("SELECT name, sql FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL ORDER BY name")
            .forEach { i -> description += "index ${i[0]}: ${i[1]!!.replace(Regex("\\s+"), " ")}" }
        return description.sorted()
    }
}
