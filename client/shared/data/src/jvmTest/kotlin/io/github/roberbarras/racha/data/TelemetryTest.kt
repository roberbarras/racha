package io.github.roberbarras.racha.data

import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.application.stats.RemoteStats
import io.github.roberbarras.racha.application.stats.RemoteStatsSource
import io.github.roberbarras.racha.application.stats.StatsServer
import io.github.roberbarras.racha.application.sync.AccountService
import io.github.roberbarras.racha.application.sync.AccountState
import io.github.roberbarras.racha.application.sync.AuthServer
import io.github.roberbarras.racha.application.sync.CredentialStore
import io.github.roberbarras.racha.application.sync.DeviceDescriptor
import io.github.roberbarras.racha.application.sync.RemoteError
import io.github.roberbarras.racha.application.sync.ServerAddress
import io.github.roberbarras.racha.application.sync.StoredSession
import io.github.roberbarras.racha.application.sync.Tokens
import io.github.roberbarras.racha.application.telemetry.ClientError
import io.github.roberbarras.racha.application.telemetry.Telemetry
import io.github.roberbarras.racha.application.telemetry.Telemetry.Companion.feature
import io.github.roberbarras.racha.application.telemetry.TelemetryEvent
import io.github.roberbarras.racha.application.telemetry.TelemetryServer
import io.github.roberbarras.racha.application.telemetry.TelemetryUploader
import io.github.roberbarras.racha.domain.preferences.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Telemetry (spec §8, §11): recorded only while signed in, sent in batches with backoff, never blocking. */
class TelemetryTest {
    private var now = Instant.parse("2026-11-16T10:00:00Z")
    private val clock = Clock { now }
    private val db = Fixtures.newDatabase()
    private val preferences = Preferences(db.settings, clock)
    private val telemetry = Telemetry(db.telemetry, preferences, clock, "linux", "1.2.3")
    private val server = FakeTelemetryServer()
    private val auth = FakeAuth()
    private val account = AccountService(auth, MemoryCredentials(), db.sync, db.accounts, preferences, { DeviceDescriptor(null, "PC", "linux", "1.2.3") }, clock)
    private val uploader = TelemetryUploader(db.telemetry, server, account, preferences, clock)

    private suspend fun signIn() = account.login(ServerAddress("racha.example.com", 443, true, "racha"), "ana@example.com", "pw")

    @Test
    fun nothingIsKeptWhileSignedOut() = runBlocking {
        telemetry.appOpened()
        telemetry.error(IllegalStateException("boom"))
        assertTrue(db.telemetry.events(10).isEmpty() && db.telemetry.errors(10).isEmpty())
    }

    @Test
    fun eventsAndErrorsGoOutInBatchesAndLeaveTheQueue() = runBlocking {
        signIn()
        telemetry.appOpened()
        now += 1.seconds
        telemetry.feature("pip")
        telemetry.error(IllegalStateException("boom"), mapOf("screen" to "player"))

        assertEquals(3, uploader.upload())
        assertEquals(listOf("app_open", "feature"), server.events.map { it.type })
        assertEquals("https://racha.example.com/racha", server.baseUrls.first())
        assertEquals("linux", server.events.first().properties["platform"]?.jsonPrimitive?.content)
        assertEquals("study", server.events.first().properties["mode"]?.jsonPrimitive?.content)
        val error = server.errors.single()
        assertEquals("boom", error.message)
        assertEquals("1.2.3", error.appVersion)
        assertTrue(error.stackTrace!!.startsWith("java.lang.IllegalStateException: boom"))
        assertEquals("player", error.context["screen"]?.jsonPrimitive?.content)
        assertEquals(0, uploader.upload(), "nothing left")
    }

    @Test
    fun failuresBackOffAndRetryLater() = runBlocking {
        signIn()
        telemetry.feature("pip")
        server.failWith = RemoteError.Unreachable(null)
        assertEquals(0, uploader.upload())
        server.failWith = null
        now += 30.seconds
        assertEquals(0, uploader.upload(), "waits a minute after the first failure")
        now += 1.minutes
        assertEquals(1, uploader.upload())
        assertEquals(1.minutes, TelemetryUploader.backoff(1))
        assertEquals(8.minutes, TelemetryUploader.backoff(4))
        assertEquals(60.minutes, TelemetryUploader.backoff(20))
    }

    @Test
    fun aBatchTheServerRefusesIsDroppedAndAnExpiredTokenIsRefreshed() = runBlocking {
        signIn()
        telemetry.feature("pip")
        server.failWith = RemoteError.Rejected(400, "invalid_input", "bad batch")
        uploader.upload()
        assertTrue(db.telemetry.events(10).isEmpty(), "a refused batch cannot block the queue")

        server.failWith = null
        server.unauthorizedOnce = true
        telemetry.feature("ab_loop")
        assertEquals(1, uploader.upload())
        assertEquals(1, auth.refreshes)
    }

    @Test
    fun aConfiguredTelemetryEndpointIsUsedAndSigningOutForgetsTheQueue() = runBlocking {
        signIn()
        preferences.set(Settings.TelemetryEndpoint, "https://telemetry.example.com/")
        telemetry.feature("pip")
        uploader.upload()
        assertEquals("https://telemetry.example.com", server.baseUrls.last())

        telemetry.feature("bookmark")
        account.logout()
        assertTrue(db.telemetry.events(10).isEmpty())
    }

    @Test
    fun theQueueIsBounded() = runBlocking {
        signIn()
        repeat(SqlTelemetryOutbox.MAX_ROWS.toInt() + 3) {
            db.telemetry.add(TelemetryEvent("e$it".padStart(6, '0'), "feature", now + it.minutes, JsonObject(emptyMap())))
        }
        val oldest = db.telemetry.events(1).single()
        assertEquals(now + 3.minutes, oldest.occurredAt, "the oldest are dropped first")
    }

    private class FakeTelemetryServer : TelemetryServer {
        val events = mutableListOf<TelemetryEvent>()
        val errors = mutableListOf<ClientError>()
        val baseUrls = mutableListOf<String>()
        var failWith: RemoteError? = null
        var unauthorizedOnce = false

        override suspend fun sendEvents(baseUrl: String, accessToken: String, events: List<TelemetryEvent>) {
            check(baseUrl)
            this.events += events
        }

        override suspend fun sendErrors(baseUrl: String, accessToken: String, errors: List<ClientError>) {
            check(baseUrl)
            this.errors += errors
        }

        private fun check(baseUrl: String) {
            baseUrls += baseUrl
            failWith?.let { throw it }
            if (unauthorizedOnce) {
                unauthorizedOnce = false
                throw RemoteError.Unauthorized()
            }
        }
    }

    /** Stats share the account with telemetry: after a server restart with another key they renew the token once. */
    @Test
    fun statsRenewTheAccessTokenWhenTheServerRestartedWithAnotherKey() = runBlocking {
        signIn()
        var calls = 0
        val server = object : StatsServer {
            override suspend fun personalStats(address: ServerAddress, accessToken: String, zone: String): RemoteStats {
                calls++
                if (calls == 1) throw RemoteError.Unauthorized() // the access token was signed with the old key
                return RemoteStats(0, emptyMap(), emptyMap())
            }
        }
        val stats = RemoteStatsSource(server, account, preferences) { "UTC" }
        val refreshesBefore = auth.refreshes

        assertNotNull(stats(), "the session is still valid: the figures come back")
        assertEquals(2, calls)
        assertEquals(refreshesBefore + 1, auth.refreshes)
        assertTrue(account.state.value is AccountState.SignedIn)
    }

    private class FakeAuth : AuthServer {
        var refreshes = 0
        private val tokens = Tokens("6f1c2b8e-0000-4000-8000-000000000001", "6f1c2b8e-0000-4000-8000-000000000002", "access", 900, "refresh")

        override suspend fun register(address: ServerAddress, email: String, password: String, displayName: String?, device: DeviceDescriptor) = tokens
        override suspend fun login(address: ServerAddress, email: String, password: String, device: DeviceDescriptor) = tokens
        override suspend fun refresh(address: ServerAddress, refreshToken: String): Tokens {
            refreshes++
            return tokens
        }
        override suspend fun logout(address: ServerAddress, refreshToken: String) = Unit
    }

    private class MemoryCredentials : CredentialStore {
        private var session: StoredSession? = null
        override suspend fun read() = session
        override suspend fun write(session: StoredSession) {
            this.session = session
        }
        override suspend fun clear() {
            session = null
        }
    }
}
