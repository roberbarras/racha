package io.github.roberbarras.racha.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toAwtImage
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.application.sync.AccountService
import io.github.roberbarras.racha.application.sync.AuthServer
import io.github.roberbarras.racha.application.sync.CredentialStore
import io.github.roberbarras.racha.application.sync.DeviceDescriptor
import io.github.roberbarras.racha.application.sync.PullPage
import io.github.roberbarras.racha.application.sync.PushOutcome
import io.github.roberbarras.racha.application.sync.PushResult
import io.github.roberbarras.racha.application.sync.RemoteError
import io.github.roberbarras.racha.application.sync.ServerAddress
import io.github.roberbarras.racha.application.sync.StoredSession
import io.github.roberbarras.racha.application.sync.SyncChange
import io.github.roberbarras.racha.application.sync.SyncControls
import io.github.roberbarras.racha.application.sync.SyncEngine
import io.github.roberbarras.racha.application.sync.SyncRecord
import io.github.roberbarras.racha.application.sync.SyncScheduler
import io.github.roberbarras.racha.application.sync.SyncServer
import io.github.roberbarras.racha.application.sync.Tokens
import io.github.roberbarras.racha.data.LocalDatabase
import io.github.roberbarras.racha.domain.preferences.ConflictPolicy
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.domain.preferences.ThemeMode
import io.github.roberbarras.racha.ui.app.RachaApp
import io.github.roberbarras.racha.ui.app.RachaDependencies
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** Account and sync screen (§7.4, §12 criterion 5) against an in-memory fake server: no network. */
@OptIn(ExperimentalTestApi::class)
class AccountUiTest {
    private val originalLocale = Locale.getDefault()
    private val server = FakeServer()
    private lateinit var db: LocalDatabase
    private lateinit var preferences: Preferences
    private lateinit var controls: SyncControls

    @AfterTest
    fun restoreLocale() = Locale.setDefault(originalLocale)

    private fun dependencies(): RachaDependencies = DemoLibrary.dependencies { database, prefs, clock ->
        db = database
        preferences = prefs
        val credentials = MemoryCredentials()
        val account = AccountService(server, credentials, database.sync, database.accounts, prefs, { DeviceDescriptor(null, "Test", "linux", "test") }, clock)
        val engine = SyncEngine(database.sync, server, account, prefs, clock)
        SyncControls(account, engine, SyncScheduler(engine, prefs), database.sync).also { controls = it }
    }

    @Composable
    private fun App(deps: RachaDependencies) {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { RachaApp(deps) }
    }

    @Test
    fun signsInWithTheServerAddressAndShowsTheAccount() = runDesktopComposeUiTest(1440, 900) {
        Locale.setDefault(Locale.ENGLISH)
        setContent { App(dependencies()) }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Account").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Account").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Account and sync").fetchSemanticsNodes().isNotEmpty() }

        onNode(hasContentDescription("Host")).performTextInput("sync.example.com")
        onNode(hasContentDescription("Email")).performTextInput("ana@example.com")
        onNode(hasContentDescription("Password")).performTextInput("wrong")
        onNode(hasText("Sign in") and hasClickAction() and !isSelectable()).performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Wrong email or password.").fetchSemanticsNodes().isNotEmpty() }

        onNode(hasContentDescription("Password")).performTextReplacement("correct horse")
        onNode(hasText("Sign in") and hasClickAction() and !isSelectable()).performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Signed in as ana@example.com").fetchSemanticsNodes().isNotEmpty() }

        assertEquals("https://sync.example.com", server.lastAddress?.baseUrl)
        onNodeWithText("Server: https://sync.example.com").assertExists()
        onNodeWithText("Sync now").assertExists()
        runBlocking { assertEquals(true, preferences.get(Settings.SyncEnabled)) }
    }

    @Test
    fun aSettingChangedOnTwoDevicesIsAskedFromHomeAndAnswered() = runDesktopComposeUiTest(1440, 900) {
        Locale.setDefault(Locale.ENGLISH)
        val deps = dependencies()
        runBlocking {
            controls.login(ServerAddress("sync.example.com", 443, true, ""), "ana@example.com", "correct horse")
            db.sync.acknowledge(db.sync.pending(1000))
            preferences.set(Settings.Theme, ThemeMode.DARK) // changed here, not sent yet
            val remote = SyncRecord("setting", "user:general.theme", DemoLibrary.now + 1.minutes, false, 1, buildJsonObject { put("value", "light") })
            db.sync.applyRemote(listOf(remote), ConflictPolicy.ASK)
        }
        setContent { App(deps) }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Settings to review").fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("Review").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Settings changed on another device").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Theme").assertExists()
        onNodeWithText("This device: dark").assertExists()
        onNodeWithText("Other device: light").assertExists()

        onNodeWithText("Use the other").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Settings changed on another device").fetchSemanticsNodes().isEmpty() }
        runBlocking { assertEquals(ThemeMode.LIGHT, preferences.get(Settings.Theme)) }
    }

    private val out = java.io.File(System.getProperty("racha.screenshots") ?: "build/screenshots").apply { mkdirs() }

    private fun shoot(name: String, width: Int, height: Int, signedIn: Boolean) = runDesktopComposeUiTest(width, height) {
        Locale.setDefault(Locale.forLanguageTag("es"))
        val deps = dependencies()
        val nav = arrayOfNulls<androidx.navigation.NavHostController>(1)
        if (signedIn) runBlocking {
            controls.login(ServerAddress("racha.example.com", 443, true, ""), "ana@example.com", "correct horse")
            preferences.set(Settings.Theme, ThemeMode.DARK)
            val remote = SyncRecord("setting", "user:general.theme", DemoLibrary.now + 1.minutes, false, 1, buildJsonObject { put("value", "light") })
            db.sync.applyRemote(listOf(remote), ConflictPolicy.ASK)
        }
        setContent {
            val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
            val controller = androidx.navigation.compose.rememberNavController().also { nav[0] = it }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { RachaApp(deps, controller) }
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Cuenta").fetchSemanticsNodes().isNotEmpty() }
        runOnIdle { nav[0]!!.navigate(io.github.roberbarras.racha.ui.app.AccountRoute) }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Cuenta y sincronización").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", java.io.File(out, "$name.png"))
    }

    @Test fun desktopAccountSignedOutEs() = shoot("desktop-account-signed-out-es", 1440, 900, signedIn = false)
    @Test fun phoneAccountSignedInEs() = shoot("phone-account-signed-in-es", 412, 915, signedIn = true)

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

    private class FakeServer : AuthServer, SyncServer {
        var lastAddress: ServerAddress? = null
        private val tokens = Tokens("6f1c2b8e-0000-4000-8000-000000000001", "6f1c2b8e-0000-4000-8000-000000000002", "access", 900, "refresh")

        override suspend fun register(address: ServerAddress, email: String, password: String, displayName: String?, device: DeviceDescriptor) =
            login(address, email, password, device)

        override suspend fun login(address: ServerAddress, email: String, password: String, device: DeviceDescriptor): Tokens {
            lastAddress = address
            if (password != "correct horse") throw RemoteError.Rejected(401, "invalid_credentials", "invalid email or password")
            return tokens
        }

        override suspend fun refresh(address: ServerAddress, refreshToken: String) = tokens

        override suspend fun logout(address: ServerAddress, refreshToken: String) = Unit

        override suspend fun push(address: ServerAddress, accessToken: String, changes: List<SyncChange>) =
            changes.map { PushResult(it.entity, it.id, PushOutcome.APPLIED, null, null) }

        override suspend fun pull(address: ServerAddress, accessToken: String, since: Long, limit: Int) = PullPage(emptyList(), since, false)
    }
}
