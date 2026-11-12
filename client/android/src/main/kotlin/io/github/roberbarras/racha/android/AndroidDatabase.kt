package io.github.roberbarras.racha.android

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.github.roberbarras.racha.data.LocalDatabase

/** Persistence adapter wiring for Android: framework SQLite, foreign keys on, write-ahead logging. */
object AndroidDatabase {
    const val FILE_NAME = "racha.db"

    /** @param name null for an in-memory database (tests). */
    fun create(context: Context, name: String? = FILE_NAME): LocalDatabase {
        val driver = AndroidSqliteDriver(
            schema = LocalDatabase.schema,
            context = context,
            name = name,
            callback = object : AndroidSqliteDriver.Callback(LocalDatabase.schema) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    db.setForeignKeyConstraintsEnabled(true)
                    // Fewer fsyncs per write than the rollback journal: cheaper periodic progress saves.
                    if (name != null) db.enableWriteAheadLogging()
                }
            },
        )
        return LocalDatabase(driver)
    }
}
