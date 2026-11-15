package io.github.roberbarras.racha.remote

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.roberbarras.racha.application.library.EnsureBuiltInCategories
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.application.sync.AccountService
import io.github.roberbarras.racha.application.sync.CredentialStore
import io.github.roberbarras.racha.application.sync.DeviceDescriptor
import io.github.roberbarras.racha.application.sync.RemoteError
import io.github.roberbarras.racha.application.sync.ServerAddress
import io.github.roberbarras.racha.application.sync.StoredSession
import io.github.roberbarras.racha.application.sync.SyncEngine
import io.github.roberbarras.racha.application.sync.SyncOutcome
import io.github.roberbarras.racha.data.LocalDatabase
import io.github.roberbarras.racha.domain.learning.Bookmark
import io.github.roberbarras.racha.domain.learning.Favorite
import io.github.roberbarras.racha.domain.learning.FavoriteTarget
import io.github.roberbarras.racha.domain.learning.HistoryEntry
import io.github.roberbarras.racha.domain.learning.Progress
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.domain.preferences.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Criterion 5 end to end: two devices, each with its own SQLite, synchronising through a real server with the real
 * engine, account service and HTTP adapter. Needs RACHA_E2E_URL (e.g. http://localhost:18080); skipped otherwise.
 */
class EndToEndSyncTest {
    private val url = System.getenv("RACHA_E2E_URL").orEmpty()

    private class MemoryCredentials : CredentialStore {
        var session: StoredSession? = null
        override suspend fun read() = session
        override suspend fun write(session: StoredSession) { this.session = session }
        override suspend fun clear() { session = null }
    }

    /** One installation: its database, preferences, account and sync engine. */
    private inner class Device(val name: String, val platform: String) {
        // Real time: the server clamps clocks running ahead, so the scenario must not live in the future.
        var now = kotlin.time.Clock.System.now() - 1.hours
        val clock = Clock { now }
        val db = LocalDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { LocalDatabase.schema.create(it) }, Dispatchers.IO)
        val preferences = Preferences(db.settings, clock)
        val credentials = MemoryCredentials()
        val server = KtorSyncServer()
        val account = AccountService(server, credentials, db.sync, db.accounts, preferences, { DeviceDescriptor(null, name, platform, "e2e") }, clock)
        val engine = SyncEngine(db.sync, server, account, preferences, clock)

        suspend fun sync(): SyncOutcome.Done = assertIs<SyncOutcome.Done>(engine.syncNow())

        suspend fun setUp() = EnsureBuiltInCategories(db.categories, clock)()
    }

    private fun address(): ServerAddress {
        val uri = URI(url)
        val https = uri.scheme == "https"
        return ServerAddress(uri.host, if (uri.port > 0) uri.port else if (https) 443 else 80, https, uri.path.orEmpty())
    }

    private val lesson = ContentId(UUID.randomUUID().toString().replace("-", "").repeat(2)) // 64 hex chars, like a SHA-256

    @Test
    fun twoDevicesStayInSync() = runBlocking<Unit> {
        if (url.isBlank()) return@runBlocking println("RACHA_E2E_URL not set: end-to-end sync skipped")
        val laptop = Device("Portátil", "linux").apply { setUp() }
        val phone = Device("Móvil", "android").apply { setUp() }
        val email = "e2e-${UUID.randomUUID()}@example.com"

        // Sign up on the laptop (its existing data goes up), sign in on the phone.
        laptop.db.progress.save(Progress.start(lesson, 2710, laptop.now).recordPosition(754, 2710, laptop.now))
        laptop.account.register(address(), email, "correct horse battery", "Ana")
        assertTrue(laptop.sync().pushed > 0)
        phone.account.login(address(), email, "correct horse battery")
        phone.sync()

        // Criterion 5: what was watched on the laptop is on the phone, at the same second.
        assertEquals(754, phone.db.progress.get(lesson)!!.positionSec)

        // Bookmarks, history and favourites travel too.
        laptop.now += 1.minutes
        laptop.db.bookmarks.save(Bookmark("bm-${UUID.randomUUID()}", lesson, 120, "IAM roles", laptop.now, laptop.now))
        laptop.db.history.add(HistoryEntry("h-${UUID.randomUUID()}", lesson, laptop.now, 754))
        laptop.db.favorites.add(Favorite(FavoriteTarget.Element(lesson), laptop.now))
        laptop.sync()
        phone.sync()
        assertEquals("IAM roles", phone.db.bookmarks.observe(lesson).first().single().note)
        assertEquals(1, phone.db.history.observeRecent(10).first().count { it.elementId == lesson })
        assertTrue(phone.db.favorites.observeAll().first().any { it.target == FavoriteTarget.Element(lesson) })

        // The phone starts the lesson over (newer): it wins, and the furthest point reached is kept everywhere.
        phone.now = laptop.now + 5.minutes
        phone.db.progress.save(phone.db.progress.get(lesson)!!.recordPosition(10, 2710, phone.now))
        phone.sync()
        laptop.sync()
        val onLaptop = laptop.db.progress.get(lesson)!!
        assertEquals(10, onLaptop.positionSec)
        assertEquals(754, onLaptop.maxPositionSec)

        // Settings changed on both sides while apart: the phone's change is older, so it loses to the laptop's and
        // the phone asks the user instead of silently dropping it (§6.3).
        phone.now += 10.minutes
        laptop.now = phone.now + 1.minutes
        phone.preferences.set(Settings.Theme, ThemeMode.LIGHT)
        laptop.preferences.set(Settings.Theme, ThemeMode.DARK)
        laptop.sync()
        phone.sync()
        val conflict = phone.db.sync.observe().first().conflicts.single()
        assertEquals(Settings.Theme.key, conflict.key)
        assertEquals(ThemeMode.LIGHT, phone.preferences.get(Settings.Theme))
        phone.db.sync.resolve(conflict.key, keepLocal = true)
        phone.sync()
        laptop.sync()
        assertEquals(ThemeMode.LIGHT, laptop.preferences.get(Settings.Theme))
        assertTrue(phone.db.sync.observe().first().conflicts.isEmpty())

        // Offline: the change waits in the queue and goes up when the server is reachable again.
        phone.preferences.set(Settings.SyncPort, 1)
        phone.now += 1.minutes
        phone.db.progress.save(phone.db.progress.get(lesson)!!.recordPosition(300, 2710, phone.now))
        val offline = phone.engine.syncNow()
        assertIs<SyncOutcome.Failed>(offline)
        assertIs<RemoteError.Unreachable>(offline.error)
        assertTrue(phone.db.sync.observe().first().pending > 0)
        phone.preferences.set(Settings.SyncPort, address().port)
        phone.sync()
        laptop.sync()
        assertEquals(300, laptop.db.progress.get(lesson)!!.positionSec)

        // The access token is refreshed against the real server (rotation) and still works.
        val before = phone.credentials.session!!.refreshToken
        phone.account.accessToken(forceRefresh = true)!!
        assertTrue(phone.credentials.session!!.refreshToken != before)
        phone.sync()
    }
}
