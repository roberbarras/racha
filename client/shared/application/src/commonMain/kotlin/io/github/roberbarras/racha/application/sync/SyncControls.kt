package io.github.roberbarras.racha.application.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Something about sync that needs the user, shown outside the account screen. */
enum class SyncAttention { SESSION_EXPIRED, SETTINGS_CONFLICT }

/**
 * What the "Account and sync" screen drives (spec §7.4, §9): sign in/out, status, "sync now", conflicts and the
 * password (change, recovery by e-mail).
 */
class SyncControls(
    private val accounts: AccountService,
    private val engine: SyncEngine,
    private val scheduler: SyncScheduler,
    private val local: SyncLocalStore,
    private val passwords: ManagePassword,
) {
    val account: StateFlow<AccountState> get() = accounts.state
    val run: StateFlow<SyncRun> get() = engine.run

    fun status(): Flow<LocalSyncState> = local.observe()

    fun attention(): Flow<SyncAttention?> = combine(accounts.state, local.observe()) { account, status ->
        when {
            account is AccountState.Expired -> SyncAttention.SESSION_EXPIRED
            account is AccountState.SignedIn && status.conflicts.isNotEmpty() -> SyncAttention.SETTINGS_CONFLICT
            else -> null
        }
    }.distinctUntilChanged()

    suspend fun login(address: ServerAddress, email: String, password: String) {
        accounts.login(address, email, password)
        scheduler.requestSync()
    }

    suspend fun register(address: ServerAddress, email: String, password: String, displayName: String?) {
        accounts.register(address, email, password, displayName)
        scheduler.requestSync()
    }

    suspend fun logout() = accounts.logout()

    fun syncNow() = scheduler.requestSync()

    /** Signed in: the other devices are signed out, this one stays. */
    suspend fun changePassword(current: String, replacement: String): PasswordOutcome = passwords.change(current, replacement)

    /** Signed out: e-mails a code to [email] (same answer whether the account exists or not). */
    suspend fun requestRecoveryCode(address: ServerAddress, email: String, language: String): PasswordOutcome =
        passwords.requestCode(address, email, language)

    /** With the e-mailed code; every session of the account ends, so this device signs in again afterwards. */
    suspend fun resetPassword(address: ServerAddress, email: String, code: String, replacement: String): PasswordOutcome =
        passwords.reset(address, email, code, replacement)

    /** Answers a settings conflict: keep this device's value (it is sent again) or take the other device's. */
    suspend fun resolve(conflict: SettingConflict, keepLocal: Boolean) {
        local.resolve(conflict.key, keepLocal)
        if (keepLocal) scheduler.requestSync()
    }
}
