package io.github.roberbarras.racha.data

import app.cash.sqldelight.db.SqlDriver
import io.github.roberbarras.racha.data.db.RachaDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlin.coroutines.CoroutineContext

/**
 * The local database and every repository adapter built on it. Platform composition roots create the [SqlDriver]
 * (Android: `AndroidSqliteDriver` with foreign keys on; desktop: `JdbcSqliteDriver` with `foreign_keys=true`).
 */
class LocalDatabase(driver: SqlDriver, dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    val database = RachaDatabase(driver)

    /**
     * Every query and transaction runs on one serialized lane: SQLite has a single writer anyway, and concurrent
     * transactions on a shared connection fail ("cannot start a transaction within a transaction").
     */
    private val io: CoroutineContext = dispatcher.limitedParallelism(1)

    val categories = SqlCategoryRepository(database, io)
    val library = SqlLibraryRepository(database, io)
    val progress = SqlProgressRepository(database, io)
    val bookmarks = SqlBookmarkRepository(database, io)
    val history = SqlHistoryRepository(database, io)
    val favorites = SqlFavoriteRepository(database, io)
    val stats = SqlLocalStatRepository(database, io)
    val settings = SqlSettingsStore(database, io)
    val syncQueue = SqlSyncQueueRepository(database, io)
    val devices = SqlDeviceRepository(database, io)
    val accounts = SqlAccountRepository(database, io)

    companion object {
        val schema = RachaDatabase.Schema
    }
}
