package io.github.roberbarras.racha.application.sync

import io.github.roberbarras.racha.application.preferences.Preferences
import kotlin.coroutines.cancellation.CancellationException

/** How a password action ended, for the account screen. */
sealed interface PasswordOutcome {
    data object Done : PasswordOutcome
    data object WrongPassword : PasswordOutcome
    data object InvalidCode : PasswordOutcome
    data object TooWeak : PasswordOutcome
    data object TooManyAttempts : PasswordOutcome
    /** The server cannot send e-mail: only its administrator can fix it. */
    data object MailUnavailable : PasswordOutcome
    data object Unreachable : PasswordOutcome
    data object SignedOut : PasswordOutcome
    data class Failed(val detail: String?) : PasswordOutcome
}

/**
 * Changing the password while signed in (the other devices are signed out, this one stays), and recovering it with a
 * code the server e-mails (every session ends: the user signs in again with the new one).
 */
class ManagePassword(private val server: PasswordServer, private val account: AccountService, private val preferences: Preferences) {

    suspend fun change(current: String, replacement: String): PasswordOutcome = attempt {
        val address = ServerAddress.from(preferences) ?: return@attempt PasswordOutcome.SignedOut
        val token = account.accessToken() ?: return@attempt PasswordOutcome.SignedOut
        try {
            server.changePassword(address, token, current, replacement)
        } catch (e: RemoteError.Unauthorized) {
            server.changePassword(address, account.accessToken(forceRefresh = true) ?: return@attempt PasswordOutcome.SignedOut, current, replacement)
        }
        PasswordOutcome.Done
    }

    suspend fun requestCode(address: ServerAddress, email: String, language: String): PasswordOutcome = attempt {
        server.requestRecoveryCode(address, email.trim(), language)
        PasswordOutcome.Done
    }

    suspend fun reset(address: ServerAddress, email: String, code: String, replacement: String): PasswordOutcome = attempt {
        server.resetPassword(address, email.trim(), code.trim(), replacement)
        PasswordOutcome.Done
    }

    private inline fun attempt(block: () -> PasswordOutcome): PasswordOutcome = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteError.Unreachable) {
        PasswordOutcome.Unreachable
    } catch (e: RemoteError.Unauthorized) {
        PasswordOutcome.SignedOut
    } catch (e: RemoteError.Rejected) {
        when {
            e.code == "wrong_password" -> PasswordOutcome.WrongPassword
            e.code == "invalid_recovery_code" -> PasswordOutcome.InvalidCode
            e.code == "mail_not_configured" -> PasswordOutcome.MailUnavailable
            e.code == "too_many_attempts" || e.status == 429 -> PasswordOutcome.TooManyAttempts
            e.code == "invalid_input" && e.message?.contains("password") == true -> PasswordOutcome.TooWeak
            else -> PasswordOutcome.Failed(e.message)
        }
    }
}
