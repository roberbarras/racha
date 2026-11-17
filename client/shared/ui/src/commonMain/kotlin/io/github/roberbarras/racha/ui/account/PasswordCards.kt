package io.github.roberbarras.racha.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import io.github.roberbarras.racha.application.sync.PasswordOutcome
import io.github.roberbarras.racha.ui.design.ButtonKind
import io.github.roberbarras.racha.ui.design.RButton
import io.github.roberbarras.racha.ui.design.RCard
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.RTextField
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.cancel
import io.github.roberbarras.racha.ui.resources.error_too_many
import io.github.roberbarras.racha.ui.resources.error_unreachable
import io.github.roberbarras.racha.ui.resources.password_change
import io.github.roberbarras.racha.ui.resources.password_change_note
import io.github.roberbarras.racha.ui.resources.password_changed
import io.github.roberbarras.racha.ui.resources.password_code
import io.github.roberbarras.racha.ui.resources.password_code_sent
import io.github.roberbarras.racha.ui.resources.password_current
import io.github.roberbarras.racha.ui.resources.password_failed
import io.github.roberbarras.racha.ui.resources.password_forgot
import io.github.roberbarras.racha.ui.resources.password_forgot_message
import io.github.roberbarras.racha.ui.resources.password_invalid_code
import io.github.roberbarras.racha.ui.resources.password_mail_unavailable
import io.github.roberbarras.racha.ui.resources.password_mismatch
import io.github.roberbarras.racha.ui.resources.password_new
import io.github.roberbarras.racha.ui.resources.password_repeat
import io.github.roberbarras.racha.ui.resources.password_resend
import io.github.roberbarras.racha.ui.resources.password_reset_done
import io.github.roberbarras.racha.ui.resources.password_save
import io.github.roberbarras.racha.ui.resources.password_send_code
import io.github.roberbarras.racha.ui.resources.password_signed_out
import io.github.roberbarras.racha.ui.resources.password_too_weak
import io.github.roberbarras.racha.ui.resources.password_working
import io.github.roberbarras.racha.ui.resources.password_wrong
import org.jetbrains.compose.resources.stringResource

/** Changing the password while signed in; [result] is the last attempt's outcome. */
data class PasswordChange(
    val open: Boolean = false,
    val current: String = "",
    val replacement: String = "",
    val repeat: String = "",
    val busy: Boolean = false,
    val result: PasswordOutcome? = null,
) {
    val mismatch: Boolean get() = repeat.isNotEmpty() && repeat != replacement
    val ready: Boolean get() = current.isNotEmpty() && replacement.length >= MIN_PASSWORD && repeat == replacement && !busy
}

/** Recovering a forgotten password: ask for a code, then type it with the new password. */
data class PasswordRecovery(
    val open: Boolean = false,
    val codeSent: Boolean = false,
    val code: String = "",
    val replacement: String = "",
    val repeat: String = "",
    val busy: Boolean = false,
    val result: PasswordOutcome? = null,
) {
    val mismatch: Boolean get() = repeat.isNotEmpty() && repeat != replacement
    val ready: Boolean get() = code.isNotBlank() && replacement.length >= MIN_PASSWORD && repeat == replacement && !busy
}

/** The server's rule (10–128 characters): checked here too, so the button says so before asking. */
const val MIN_PASSWORD = 10

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PasswordChangeCard(state: PasswordChange, edit: ((PasswordChange) -> PasswordChange) -> Unit, submit: () -> Unit) {
    RCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Racha.space.l), verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
            if (!state.open) {
                if (state.result == PasswordOutcome.Done) Outcome(stringResource(Res.string.password_changed), ok = true)
                RButton(stringResource(Res.string.password_change), { edit { PasswordChange(open = true) } }, kind = ButtonKind.Secondary, icon = RachaIcons.Pencil)
                return@Column
            }
            RText(stringResource(Res.string.password_change), style = Racha.type.heading)
            RText(stringResource(Res.string.password_change_note), style = Racha.type.caption, color = Racha.colors.inkMuted)
            RTextField(state.current, { v -> edit { it.copy(current = v, result = null) } }, stringResource(Res.string.password_current), Modifier.fillMaxWidth(), password = true)
            NewPassword(state.replacement, state.repeat, state.mismatch, { v -> edit { it.copy(replacement = v, result = null) } },
                { v -> edit { it.copy(repeat = v, result = null) } }, submit)
            state.result?.takeIf { it != PasswordOutcome.Done }?.let { Outcome(outcomeText(it), ok = false) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.s), verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
                RButton(stringResource(if (state.busy) Res.string.password_working else Res.string.password_save), submit, enabled = state.ready, icon = RachaIcons.Check)
                RButton(stringResource(Res.string.cancel), { edit { PasswordChange() } }, kind = ButtonKind.Ghost)
            }
        }
    }
}

/** Under the sign-in form: the e-mail and server of the form are used. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PasswordRecoveryCard(
    state: PasswordRecovery,
    canAsk: Boolean,
    edit: ((PasswordRecovery) -> PasswordRecovery) -> Unit,
    sendCode: () -> Unit,
    reset: () -> Unit,
) {
    if (!state.open) {
        if (state.result == PasswordOutcome.Done) Outcome(stringResource(Res.string.password_reset_done), ok = true)
        RButton(stringResource(Res.string.password_forgot), { edit { PasswordRecovery(open = true) } }, kind = ButtonKind.Ghost)
        return
    }
    RCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Racha.space.l), verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
            RText(stringResource(Res.string.password_forgot), style = Racha.type.heading)
            if (!state.codeSent) {
                RText(stringResource(Res.string.password_forgot_message), style = Racha.type.body, color = Racha.colors.inkMuted)
                state.result?.let { Outcome(outcomeText(it), ok = false) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.s), verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
                    RButton(stringResource(if (state.busy) Res.string.password_working else Res.string.password_send_code), sendCode,
                        enabled = canAsk && !state.busy, icon = RachaIcons.Check)
                    RButton(stringResource(Res.string.cancel), { edit { PasswordRecovery() } }, kind = ButtonKind.Ghost)
                }
                return@Column
            }
            Outcome(stringResource(Res.string.password_code_sent), ok = true)
            RTextField(state.code, { v -> edit { it.copy(code = v.uppercase().take(12), result = null) } }, stringResource(Res.string.password_code),
                Modifier.fillMaxWidth(), KeyboardType.Ascii)
            NewPassword(state.replacement, state.repeat, state.mismatch, { v -> edit { it.copy(replacement = v, result = null) } },
                { v -> edit { it.copy(repeat = v, result = null) } }, reset)
            state.result?.takeIf { it != PasswordOutcome.Done }?.let { Outcome(outcomeText(it), ok = false) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.s), verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
                RButton(stringResource(if (state.busy) Res.string.password_working else Res.string.password_save), reset, enabled = state.ready, icon = RachaIcons.Check)
                RButton(stringResource(Res.string.password_resend), { edit { it.copy(codeSent = false, result = null) } }, kind = ButtonKind.Secondary)
                RButton(stringResource(Res.string.cancel), { edit { PasswordRecovery() } }, kind = ButtonKind.Ghost)
            }
        }
    }
}

@Composable
private fun NewPassword(replacement: String, repeat: String, mismatch: Boolean, onReplacement: (String) -> Unit, onRepeat: (String) -> Unit, submit: () -> Unit) {
    RTextField(replacement, onReplacement, stringResource(Res.string.password_new), Modifier.fillMaxWidth(), password = true)
    RTextField(repeat, onRepeat, stringResource(Res.string.password_repeat), Modifier.fillMaxWidth(), password = true, imeAction = ImeAction.Go, onSubmit = submit)
    if (mismatch) Outcome(stringResource(Res.string.password_mismatch), ok = false)
}

@Composable
private fun Outcome(text: String, ok: Boolean) {
    RText(text, style = Racha.type.body, color = if (ok) Racha.colors.ink else Racha.colors.danger,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun outcomeText(outcome: PasswordOutcome): String = when (outcome) {
    PasswordOutcome.Done -> stringResource(Res.string.password_changed)
    PasswordOutcome.WrongPassword -> stringResource(Res.string.password_wrong)
    PasswordOutcome.InvalidCode -> stringResource(Res.string.password_invalid_code)
    PasswordOutcome.TooWeak -> stringResource(Res.string.password_too_weak)
    PasswordOutcome.TooManyAttempts -> stringResource(Res.string.error_too_many)
    PasswordOutcome.MailUnavailable -> stringResource(Res.string.password_mail_unavailable)
    PasswordOutcome.Unreachable -> stringResource(Res.string.error_unreachable)
    PasswordOutcome.SignedOut -> stringResource(Res.string.password_signed_out)
    is PasswordOutcome.Failed -> stringResource(Res.string.password_failed, outcome.detail ?: "")
}
