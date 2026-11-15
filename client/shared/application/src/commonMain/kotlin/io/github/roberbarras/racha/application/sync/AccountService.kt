package io.github.roberbarras.racha.application.sync

import io.github.roberbarras.racha.application.identity.AccountRepository
import io.github.roberbarras.racha.application.preferences.Preferences
import io.github.roberbarras.racha.application.shared.Clock
import io.github.roberbarras.racha.domain.identity.Account
import io.github.roberbarras.racha.domain.preferences.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

sealed interface AccountState {
    data object SignedOut : AccountState

    data class SignedIn(val email: String, val serverUrl: String) : AccountState

    /** The server stopped accepting this device's session (expired, revoked from elsewhere): sign in again. */
    data class Expired(val email: String, val serverUrl: String) : AccountState
}

/**
 * Signing in to the sync server (opt-in, §7.4). The refresh token lives in the platform secret store; the access
 * token only in memory and is refreshed when it is about to expire or the server rejects it.
 */
class AccountService(
    private val auth: AuthServer,
    private val credentials: CredentialStore,
    private val local: SyncLocalStore,
    private val accounts: AccountRepository,
    private val preferences: Preferences,
    private val device: suspend () -> DeviceDescriptor,
    private val clock: Clock,
) {
    private val mutableState = MutableStateFlow<AccountState>(AccountState.SignedOut)
    val state: StateFlow<AccountState> = mutableState.asStateFlow()

    private val lock = Mutex()
    private var access: String? = null
    private var accessExpiresAt: Instant = Instant.DISTANT_PAST

    /** Reads the stored session at start-up (no network). */
    suspend fun restore() {
        val session = credentials.read()
        mutableState.value = if (session == null || !preferences.get(Settings.SyncEnabled)) AccountState.SignedOut
        else AccountState.SignedIn(session.email, session.serverUrl)
    }

    suspend fun register(address: ServerAddress, email: String, password: String, displayName: String?) =
        start(address, email) { auth.register(address, email, password, displayName, device()) }

    suspend fun login(address: ServerAddress, email: String, password: String) =
        start(address, email) { auth.login(address, email, password, device()) }

    private suspend fun start(address: ServerAddress, email: String, call: suspend () -> Tokens) = lock.withLock {
        val tokens = call()
        val previous = credentials.read()
        val sameAccount = previous != null && previous.userId == tokens.userId && previous.serverUrl == address.baseUrl
        if (!sameAccount) local.stopRecording() // another account or server: start from a clean slate
        credentials.write(StoredSession(address.baseUrl, tokens.userId, tokens.deviceId, email.trim().lowercase(), tokens.refreshToken))
        preferences.set(Settings.SyncHost, address.host)
        preferences.set(Settings.SyncPort, address.port)
        preferences.set(Settings.SyncHttps, address.https)
        preferences.set(Settings.SyncBasePath, address.basePath)
        preferences.set(Settings.SyncUsername, email.trim().lowercase())
        preferences.set(Settings.SyncEnabled, true)
        accounts.save(Account(tokens.userId, email.trim().lowercase(), null))
        remember(tokens)
        local.startRecording()
        mutableState.value = AccountState.SignedIn(email.trim().lowercase(), address.baseUrl)
    }

    /** Signs this device out (best effort on the server) and stops syncing; local data stays. */
    suspend fun logout() = lock.withLock {
        val session = credentials.read()
        val address = ServerAddress.from(preferences)
        if (session != null && address != null) runCatching { auth.logout(address, session.refreshToken) }
        credentials.clear()
        accounts.clear()
        preferences.set(Settings.SyncEnabled, false)
        local.stopRecording()
        access = null
        mutableState.value = AccountState.SignedOut
    }

    /** A valid access token, refreshing it when needed; null when signed out or the session ended. */
    suspend fun accessToken(forceRefresh: Boolean = false): String? = lock.withLock {
        val now = clock.now()
        val cached = access
        if (!forceRefresh && cached != null && now < accessExpiresAt - EARLY_REFRESH) return@withLock cached
        val session = credentials.read() ?: return@withLock null
        val address = ServerAddress.from(preferences) ?: return@withLock null
        val tokens = try {
            auth.refresh(address, session.refreshToken)
        } catch (e: RemoteError.Unauthorized) {
            expire(session)
            return@withLock null
        } catch (e: RemoteError.Rejected) {
            if (e.status == 401) {
                expire(session)
                return@withLock null
            }
            throw e
        }
        credentials.write(session.copy(refreshToken = tokens.refreshToken, deviceId = tokens.deviceId))
        remember(tokens)
        tokens.accessToken
    }

    private suspend fun expire(session: StoredSession) {
        access = null
        mutableState.value = AccountState.Expired(session.email, session.serverUrl)
    }

    private fun remember(tokens: Tokens) {
        access = tokens.accessToken
        accessExpiresAt = clock.now() + tokens.expiresInSeconds.seconds
    }

    private companion object {
        val EARLY_REFRESH = 30.seconds
    }
}
