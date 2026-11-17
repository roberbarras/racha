package io.github.roberbarras.racha.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.roberbarras.racha.application.sync.AccountState
import io.github.roberbarras.racha.application.sync.LocalSyncState
import io.github.roberbarras.racha.application.sync.PasswordOutcome
import io.github.roberbarras.racha.application.sync.RemoteError
import io.github.roberbarras.racha.application.sync.ServerAddress
import io.github.roberbarras.racha.application.sync.SettingConflict
import io.github.roberbarras.racha.application.sync.SyncControls
import io.github.roberbarras.racha.application.sync.SyncRun
import io.github.roberbarras.racha.domain.preferences.ConflictPolicy
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.ui.app.RachaDependencies
import io.github.roberbarras.racha.ui.common.IconBadge
import io.github.roberbarras.racha.ui.common.ResponsiveColumn
import io.github.roberbarras.racha.ui.common.ScreenTitle
import io.github.roberbarras.racha.ui.design.ButtonKind
import io.github.roberbarras.racha.ui.design.RButton
import io.github.roberbarras.racha.ui.design.RCard
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.RTextField
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.design.SectionHeader
import io.github.roberbarras.racha.ui.design.SegmentedToggle
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.account_create
import io.github.roberbarras.racha.ui.resources.account_email
import io.github.roberbarras.racha.ui.resources.account_expired
import io.github.roberbarras.racha.ui.resources.account_message
import io.github.roberbarras.racha.ui.resources.account_name
import io.github.roberbarras.racha.ui.resources.account_password
import io.github.roberbarras.racha.ui.resources.account_section
import io.github.roberbarras.racha.ui.resources.account_server
import io.github.roberbarras.racha.ui.resources.account_sign_in
import io.github.roberbarras.racha.ui.resources.account_sign_out
import io.github.roberbarras.racha.ui.resources.account_sign_out_note
import io.github.roberbarras.racha.ui.resources.account_signed_in_as
import io.github.roberbarras.racha.ui.resources.account_title
import io.github.roberbarras.racha.ui.resources.account_working
import io.github.roberbarras.racha.ui.resources.ago_days
import io.github.roberbarras.racha.ui.resources.ago_hours
import io.github.roberbarras.racha.ui.resources.ago_minutes
import io.github.roberbarras.racha.ui.resources.ago_now
import io.github.roberbarras.racha.ui.resources.conflict_ask
import io.github.roberbarras.racha.ui.resources.conflict_keep_this
import io.github.roberbarras.racha.ui.resources.conflict_last_wins
import io.github.roberbarras.racha.ui.resources.conflict_other_device
import io.github.roberbarras.racha.ui.resources.conflict_this_device
import io.github.roberbarras.racha.ui.resources.conflict_use_other
import io.github.roberbarras.racha.ui.resources.conflicts_message
import io.github.roberbarras.racha.ui.resources.conflicts_title
import io.github.roberbarras.racha.ui.resources.error_credentials
import io.github.roberbarras.racha.ui.resources.error_email_taken
import io.github.roberbarras.racha.ui.resources.error_invalid_input
import io.github.roberbarras.racha.ui.resources.error_invalid_server
import io.github.roberbarras.racha.ui.resources.error_registration_closed
import io.github.roberbarras.racha.ui.resources.error_too_many
import io.github.roberbarras.racha.ui.resources.error_unreachable
import io.github.roberbarras.racha.ui.resources.minutes_short
import io.github.roberbarras.racha.ui.resources.server_path
import io.github.roberbarras.racha.ui.resources.server_host
import io.github.roberbarras.racha.ui.resources.server_port
import io.github.roberbarras.racha.ui.resources.server_section
import io.github.roberbarras.racha.ui.resources.sync_conflict_policy
import io.github.roberbarras.racha.ui.resources.sync_error_other
import io.github.roberbarras.racha.ui.resources.sync_error_unauthorized
import io.github.roberbarras.racha.ui.resources.sync_error_unreachable
import io.github.roberbarras.racha.ui.resources.sync_interval
import io.github.roberbarras.racha.ui.resources.sync_last
import io.github.roberbarras.racha.ui.resources.sync_never
import io.github.roberbarras.racha.ui.resources.sync_now
import io.github.roberbarras.racha.ui.resources.sync_pending
import io.github.roberbarras.racha.ui.resources.sync_up_to_date
import io.github.roberbarras.racha.ui.resources.syncing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** What the sign-in form holds; the server part is kept in settings once a sign-in succeeds. */
data class AccountForm(
    val host: String = "",
    val port: String = "443",
    val https: Boolean = true,
    val basePath: String = "",
    val email: String = "",
    val password: String = "",
    val name: String = "",
    val register: Boolean = false,
)

/** Why the last sign-in did not work, worded by the screen. */
sealed interface FormError {
    data object InvalidServer : FormError
    data object Credentials : FormError
    data object EmailTaken : FormError
    data object RegistrationClosed : FormError
    data object TooManyAttempts : FormError
    data object Unreachable : FormError
    data class Rejected(val detail: String) : FormError
}

class AccountViewModel(private val deps: RachaDependencies, private val sync: SyncControls) : ViewModel() {
    private val mutableForm = MutableStateFlow(AccountForm())
    val form: StateFlow<AccountForm> = mutableForm.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()
    private val mutableError = MutableStateFlow<FormError?>(null)
    val error: StateFlow<FormError?> = mutableError.asStateFlow()

    val account: StateFlow<AccountState> = sync.account
    val run: StateFlow<SyncRun> = sync.run
    val status: StateFlow<LocalSyncState?> = sync.status().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val interval: StateFlow<Int?> = deps.preferences.observe(Settings.SyncIntervalMinutes).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val policy: StateFlow<ConflictPolicy?> = deps.preferences.observe(Settings.SyncConflictPolicy).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            val p = deps.preferences
            val host = p.get(Settings.SyncHost)
            mutableForm.update {
                it.copy(
                    host = host,
                    port = p.get(Settings.SyncPort).toString(),
                    https = p.get(Settings.SyncHttps),
                    basePath = p.get(Settings.SyncBasePath),
                    email = p.get(Settings.SyncUsername),
                )
            }
        }
    }

    fun edit(change: (AccountForm) -> AccountForm) {
        mutableForm.update(change)
        mutableError.value = null
    }

    fun submit() {
        if (mutableBusy.value) return
        val f = mutableForm.value
        val address = runCatching { ServerAddress(f.host.trim(), f.port.trim().toInt(), f.https, f.basePath.trim()) }.getOrNull()
        if (address == null) {
            mutableError.value = FormError.InvalidServer
            return
        }
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                if (f.register) sync.register(address, f.email.trim(), f.password, f.name.trim().ifEmpty { null })
                else sync.login(address, f.email.trim(), f.password)
                mutableForm.update { it.copy(password = "") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RemoteError) {
                mutableError.value = e.asFormError()
            } finally {
                mutableBusy.value = false
            }
        }
    }

    private val mutableChange = MutableStateFlow(PasswordChange())
    val passwordChange: StateFlow<PasswordChange> = mutableChange.asStateFlow()
    private val mutableRecovery = MutableStateFlow(PasswordRecovery())
    val passwordRecovery: StateFlow<PasswordRecovery> = mutableRecovery.asStateFlow()

    fun editChange(change: (PasswordChange) -> PasswordChange) = mutableChange.update(change)

    fun editRecovery(change: (PasswordRecovery) -> PasswordRecovery) = mutableRecovery.update(change)

    fun changePassword() {
        val state = mutableChange.value
        if (!state.ready) return
        mutableChange.update { it.copy(busy = true, result = null) }
        viewModelScope.launch {
            val outcome = sync.changePassword(state.current, state.replacement)
            mutableChange.value = if (outcome == PasswordOutcome.Done) PasswordChange(result = outcome) else state.copy(busy = false, result = outcome)
        }
    }

    /** Uses the server and e-mail of the sign-in form; the mail comes in the app's language. */
    fun sendRecoveryCode() {
        val f = mutableForm.value
        val address = runCatching { ServerAddress(f.host.trim(), f.port.trim().toInt(), f.https, f.basePath.trim()) }.getOrNull()
        if (address == null || f.email.isBlank()) {
            mutableError.value = FormError.InvalidServer
            return
        }
        mutableRecovery.update { it.copy(busy = true, result = null) }
        viewModelScope.launch {
            val outcome = sync.requestRecoveryCode(address, f.email, androidx.compose.ui.text.intl.Locale.current.language)
            mutableRecovery.update { it.copy(busy = false, codeSent = outcome == PasswordOutcome.Done, result = outcome.takeIf { o -> o != PasswordOutcome.Done }) }
        }
    }

    fun resetPassword() {
        val state = mutableRecovery.value
        val f = mutableForm.value
        val address = runCatching { ServerAddress(f.host.trim(), f.port.trim().toInt(), f.https, f.basePath.trim()) }.getOrNull() ?: return
        if (!state.ready) return
        mutableRecovery.update { it.copy(busy = true, result = null) }
        viewModelScope.launch {
            val outcome = sync.resetPassword(address, f.email, state.code, state.replacement)
            if (outcome == PasswordOutcome.Done) {
                mutableRecovery.value = PasswordRecovery(result = outcome)
                mutableForm.update { it.copy(password = "", register = false) }
            } else {
                mutableRecovery.value = state.copy(busy = false, result = outcome)
            }
        }
    }

    fun logout() = viewModelScope.launch { sync.logout() }

    fun syncNow() = sync.syncNow()

    fun resolve(conflict: SettingConflict, keepLocal: Boolean) = viewModelScope.launch { sync.resolve(conflict, keepLocal) }

    fun setInterval(minutes: Int) = viewModelScope.launch { deps.preferences.set(Settings.SyncIntervalMinutes, minutes) }

    fun setPolicy(policy: ConflictPolicy) = viewModelScope.launch { deps.preferences.set(Settings.SyncConflictPolicy, policy) }

    fun now(): Instant = deps.clock.now()

    private fun RemoteError.asFormError(): FormError = when (this) {
        is RemoteError.Unreachable -> FormError.Unreachable
        is RemoteError.Unauthorized -> FormError.Credentials
        is RemoteError.Rejected -> when (status) {
            401 -> FormError.Credentials
            403 -> FormError.RegistrationClosed
            409 -> FormError.EmailTaken
            429 -> FormError.TooManyAttempts
            404 -> FormError.InvalidServer // nothing listens at that base path
            else -> FormError.Rejected(message ?: status.toString())
        }
    }
}

/** "Account and sync" (spec §7.4, §9 "Sync"): optional; everything else works without it. */
@Composable
fun AccountScreen(deps: RachaDependencies, sync: SyncControls) {
    val vm = viewModel { AccountViewModel(deps, sync) }
    val account by vm.account.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()
    val interval by vm.interval.collectAsStateWithLifecycle()
    val policy by vm.policy.collectAsStateWithLifecycle()
    val passwordChange by vm.passwordChange.collectAsStateWithLifecycle()
    val passwordRecovery by vm.passwordRecovery.collectAsStateWithLifecycle()
    ResponsiveColumn(maxContentWidth = Racha.layout.readingMaxWidth) { _ ->
        item { ScreenTitle(stringResource(Res.string.account_title), stringResource(Res.string.account_message)) }
        when (val a = account) {
            is AccountState.SignedIn -> {
                status?.conflicts?.takeIf { it.isNotEmpty() }?.let { conflicts ->
                    item(key = "conflicts") { ConflictsCard(conflicts, vm::resolve) }
                }
                item(key = "status") { StatusCard(a, status, run, vm.now(), onSyncNow = vm::syncNow, onLogout = { vm.logout() }) }
                item(key = "options") { OptionsCard(interval, policy, vm::setInterval, vm::setPolicy) }
                item(key = "password") { PasswordChangeCard(passwordChange, vm::editChange, vm::changePassword) }
            }
            is AccountState.Expired, AccountState.SignedOut -> {
                if (a is AccountState.Expired) item(key = "expired") { Notice(stringResource(Res.string.account_expired)) }
                item(key = "form") { SignInCard(form, busy, error, vm::edit, vm::submit) }
                if (!form.register) {
                    item(key = "recovery") {
                        PasswordRecoveryCard(passwordRecovery, canAsk = form.host.isNotBlank() && form.email.isNotBlank(), vm::editRecovery,
                            vm::sendRecoveryCode, vm::resetPassword)
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    RCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Racha.space.l), verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
            SectionHeader(title)
            content()
        }
    }
}

@Composable
private fun Notice(text: String) {
    RText(text, style = Racha.type.bodyStrong, color = Racha.colors.ink, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignInCard(form: AccountForm, busy: Boolean, error: FormError?, edit: ((AccountForm) -> AccountForm) -> Unit, submit: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.l)) {
        Section(stringResource(Res.string.server_section)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.m), verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
                RTextField(form.host, { v -> edit { it.copy(host = v) } }, stringResource(Res.string.server_host), Modifier.width(260.dp), KeyboardType.Uri)
                RTextField(form.port, { v -> edit { it.copy(port = v.filter(Char::isDigit).take(5)) } }, stringResource(Res.string.server_port), Modifier.width(110.dp), KeyboardType.Number)
                SegmentedToggle(
                    listOf("HTTPS" to true, "HTTP" to false), form.https,
                    { https -> edit { it.copy(https = https, port = if (it.port == "443" || it.port == "80") (if (https) "443" else "80") else it.port) } },
                    Modifier.align(Alignment.Bottom),
                )
            }
            RTextField(form.basePath, { v -> edit { it.copy(basePath = v) } }, stringResource(Res.string.server_path), Modifier.fillMaxWidth(), KeyboardType.Uri)
        }
        Section(stringResource(Res.string.account_section)) {
            SegmentedToggle(
                listOf(stringResource(Res.string.account_sign_in) to false, stringResource(Res.string.account_create) to true),
                form.register, { register -> edit { it.copy(register = register) } },
            )
            RTextField(form.email, { v -> edit { it.copy(email = v) } }, stringResource(Res.string.account_email), Modifier.fillMaxWidth(), KeyboardType.Email)
            if (form.register) RTextField(form.name, { v -> edit { it.copy(name = v) } }, stringResource(Res.string.account_name), Modifier.fillMaxWidth())
            RTextField(
                form.password, { v -> edit { it.copy(password = v) } }, stringResource(Res.string.account_password), Modifier.fillMaxWidth(),
                password = true, imeAction = ImeAction.Go, onSubmit = submit,
            )
            error?.let { RText(errorText(it), style = Racha.type.body, color = Racha.colors.danger, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            val ready = form.host.isNotBlank() && form.port.isNotBlank() && form.email.isNotBlank() && form.password.isNotEmpty()
            RButton(
                stringResource(
                    when {
                        busy -> Res.string.account_working
                        form.register -> Res.string.account_create
                        else -> Res.string.account_sign_in
                    },
                ),
                submit, enabled = ready && !busy, icon = RachaIcons.Cloud,
            )
        }
    }
}

@Composable
private fun errorText(error: FormError): String = when (error) {
    FormError.InvalidServer -> stringResource(Res.string.error_invalid_server)
    FormError.Credentials -> stringResource(Res.string.error_credentials)
    FormError.EmailTaken -> stringResource(Res.string.error_email_taken)
    FormError.RegistrationClosed -> stringResource(Res.string.error_registration_closed)
    FormError.TooManyAttempts -> stringResource(Res.string.error_too_many)
    FormError.Unreachable -> stringResource(Res.string.error_unreachable)
    is FormError.Rejected -> stringResource(Res.string.error_invalid_input, error.detail)
}

@Composable
private fun StatusCard(
    account: AccountState.SignedIn,
    status: LocalSyncState?,
    run: SyncRun,
    now: Instant,
    onSyncNow: () -> Unit,
    onLogout: () -> Unit,
) {
    RCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Racha.space.l), verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.m)) {
                IconBadge(if (status?.lastError != null) RachaIcons.CloudAlert else RachaIcons.Cloud)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Racha.space.xxs)) {
                    RText(stringResource(Res.string.account_signed_in_as, account.email), style = Racha.type.heading, maxLines = 2)
                    RText(stringResource(Res.string.account_server, account.serverUrl), style = Racha.type.caption, color = Racha.colors.inkMuted, maxLines = 2)
                }
            }
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
                val last = status?.lastSuccessAt
                RText(
                    if (last == null) stringResource(Res.string.sync_never) else stringResource(Res.string.sync_last, ago(last, now)),
                    style = Racha.type.body,
                )
                status?.let {
                    RText(
                        if (it.pending > 0) stringResource(Res.string.sync_pending, it.pending.toInt()) else stringResource(Res.string.sync_up_to_date),
                        style = Racha.type.body, color = Racha.colors.inkMuted,
                    )
                }
                status?.lastError?.let { RText(syncErrorText(it), style = Racha.type.body, color = Racha.colors.danger) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Racha.space.m)) {
                val syncing = run == SyncRun.SYNCING
                RButton(stringResource(if (syncing) Res.string.syncing else Res.string.sync_now), onSyncNow, enabled = !syncing, icon = RachaIcons.RefreshCw)
                RButton(stringResource(Res.string.account_sign_out), onLogout, kind = ButtonKind.Secondary)
            }
            RText(stringResource(Res.string.account_sign_out_note), style = Racha.type.caption, color = Racha.colors.inkSubtle)
        }
    }
}

@Composable
private fun syncErrorText(code: String): String = when {
    code == "unreachable" -> stringResource(Res.string.sync_error_unreachable)
    code == "unauthorized" -> stringResource(Res.string.sync_error_unauthorized)
    else -> stringResource(Res.string.sync_error_other, code.removePrefix("rejected:"))
}

@Composable
private fun ago(at: Instant, now: Instant): String {
    val minutes = (now - at).inWholeMinutes
    return when {
        minutes < 1 -> stringResource(Res.string.ago_now)
        minutes < 60 -> stringResource(Res.string.ago_minutes, minutes.toInt())
        minutes < 60 * 24 -> stringResource(Res.string.ago_hours, (minutes / 60).toInt())
        else -> stringResource(Res.string.ago_days, (minutes / (60 * 24)).toInt())
    }
}

@Composable
private fun OptionsCard(interval: Int?, policy: ConflictPolicy?, setInterval: (Int) -> Unit, setPolicy: (ConflictPolicy) -> Unit) {
    if (interval == null || policy == null) return
    RCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Racha.space.l), verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
            RText(stringResource(Res.string.sync_interval), style = Racha.type.label, color = Racha.colors.inkMuted)
            val choices = (INTERVALS + interval).distinct().sorted()
            SegmentedToggle(choices.map { stringResource(Res.string.minutes_short, it) to it }, interval, setInterval)
            RText(stringResource(Res.string.sync_conflict_policy), style = Racha.type.label, color = Racha.colors.inkMuted)
            SegmentedToggle(
                listOf(stringResource(Res.string.conflict_ask) to ConflictPolicy.ASK, stringResource(Res.string.conflict_last_wins) to ConflictPolicy.LAST_WRITE_WINS),
                policy, setPolicy,
            )
        }
    }
}

@Composable
private fun ConflictsCard(conflicts: List<SettingConflict>, resolve: (SettingConflict, Boolean) -> Unit) {
    RCard(Modifier.fillMaxWidth(), selected = true) {
        Column(Modifier.padding(Racha.space.l), verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.m)) {
                IconBadge(RachaIcons.CloudAlert)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Racha.space.xxs)) {
                    RText(stringResource(Res.string.conflicts_title), style = Racha.type.heading)
                    RText(stringResource(Res.string.conflicts_message), style = Racha.type.body, color = Racha.colors.inkMuted)
                }
            }
            conflicts.forEach { conflict -> ConflictRow(conflict, resolve) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConflictRow(conflict: SettingConflict, resolve: (SettingConflict, Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
        RText(settingName(conflict.key), style = Racha.type.bodyStrong)
        RText(stringResource(Res.string.conflict_this_device, settingValue(conflict.localValue)), style = Racha.type.body, color = Racha.colors.inkMuted)
        RText(stringResource(Res.string.conflict_other_device, settingValue(conflict.remoteValue)), style = Racha.type.body, color = Racha.colors.inkMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.s), verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
            RButton(stringResource(Res.string.conflict_keep_this), { resolve(conflict, true) }, kind = ButtonKind.Secondary)
            RButton(stringResource(Res.string.conflict_use_other), { resolve(conflict, false) }, kind = ButtonKind.Secondary)
        }
    }
}

private val INTERVALS = listOf(5, 15, 30, 60)
