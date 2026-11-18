package io.github.roberbarras.racha.desktop

import app.cash.sqldelight.Query
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.ConnectionManager
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * SQLite on one connection kept open for the app's lifetime. SQLDelight's `JdbcSqliteDriver` closes a file database's
 * connection after every statement outside a transaction, so each query paid for opening SQLite again (pragmas, WAL,
 * locks): hundreds per screen with a large library. The app already runs every database call on one serialized lane
 * ([io.github.roberbarras.racha.data.LocalDatabase]), so one connection is enough, as Android's driver does.
 */
class PersistentSqliteDriver(url: String, properties: Properties) : JdbcDriver() {
    private val connection: Connection = DriverManager.getConnection(url, properties)
    private val listeners = LinkedHashMap<String, MutableSet<Query.Listener>>()

    override var transaction: ConnectionManager.Transaction? = null

    override fun getConnection(): Connection = connection

    override fun closeConnection(connection: Connection) = Unit

    override fun close() = connection.close()

    override fun Connection.beginTransaction() = run(this, "BEGIN TRANSACTION")

    override fun Connection.endTransaction() = run(this, "END TRANSACTION")

    override fun Connection.rollbackTransaction() = run(this, "ROLLBACK TRANSACTION")

    private fun run(connection: Connection, sql: String) {
        connection.prepareStatement(sql).use { it.execute() }
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) { queryKeys.forEach { listeners.getOrPut(it) { LinkedHashSet() }.add(listener) } }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) { queryKeys.forEach { listeners[it]?.remove(listener) } }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        val toNotify = LinkedHashSet<Query.Listener>()
        synchronized(listeners) { queryKeys.forEach { listeners[it]?.let(toNotify::addAll) } }
        toNotify.forEach(Query.Listener::queryResultsChanged)
    }

    companion object {
        /** Opens [url], creating the schema on first run and migrating it later (`PRAGMA user_version`), like SQLDelight's. */
        fun open(url: String, properties: Properties, schema: SqlSchema<QueryResult.Value<Unit>>): PersistentSqliteDriver {
            val driver = PersistentSqliteDriver(url, properties)
            val version = driver.executeQuery(null, "PRAGMA user_version", { cursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
            }, 0).value
            if (version < schema.version) {
                // All or nothing: a failed migration leaves the previous version untouched.
                object : TransacterImpl(driver) {}.transaction {
                    if (version == 0L) schema.create(driver) else schema.migrate(driver, version, schema.version)
                    driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
                }
            }
            return driver
        }
    }
}
