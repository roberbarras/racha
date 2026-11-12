package io.github.roberbarras.racha.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.roberbarras.racha.data.LocalDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Persistence adapter wiring for the desktop app. The driver creates the schema on first run and migrates it on
 * later runs (tracked with `PRAGMA user_version`).
 */
object DesktopDatabase {
    /** `$XDG_DATA_HOME/racha/racha.db`, defaulting to `~/.local/share/racha/racha.db`. */
    fun defaultFile(): Path {
        val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".local", "share")
        return dataHome.resolve("racha").resolve("racha.db")
    }

    fun open(file: Path = defaultFile()): LocalDatabase {
        Files.createDirectories(file.parent)
        val properties = Properties().apply {
            put("foreign_keys", "true")
            put("journal_mode", "WAL")
        }
        return LocalDatabase(JdbcSqliteDriver("jdbc:sqlite:$file", properties, LocalDatabase.schema))
    }
}
