package io.github.roberbarras.racha.ui.folders

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.roberbarras.racha.application.remote.DownloadState
import io.github.roberbarras.racha.application.remote.NewRemoteSource
import io.github.roberbarras.racha.application.remote.RemoteCredentials
import io.github.roberbarras.racha.application.remote.RemoteDirectory
import io.github.roberbarras.racha.application.remote.RemotePreview
import io.github.roberbarras.racha.application.remote.RemoteSources
import io.github.roberbarras.racha.application.remote.WebDavAccount
import io.github.roberbarras.racha.domain.library.Category
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.RootLayout
import io.github.roberbarras.racha.domain.remote.DownloadMode
import io.github.roberbarras.racha.domain.remote.RemoteCourse
import io.github.roberbarras.racha.domain.remote.RemoteProvider
import io.github.roberbarras.racha.domain.remote.RemoteSource
import io.github.roberbarras.racha.ui.app.RachaDependencies
import io.github.roberbarras.racha.ui.app.RemoteFolders
import io.github.roberbarras.racha.ui.common.IconBadge
import io.github.roberbarras.racha.ui.common.categoryName
import io.github.roberbarras.racha.ui.common.fileSize
import io.github.roberbarras.racha.ui.design.ButtonKind
import io.github.roberbarras.racha.ui.design.ProgressBar
import io.github.roberbarras.racha.ui.design.RButton
import io.github.roberbarras.racha.ui.design.RIcon
import io.github.roberbarras.racha.ui.design.RIconButton
import io.github.roberbarras.racha.ui.design.RSwitch
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.RTextField
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.design.SegmentedToggle
import io.github.roberbarras.racha.ui.design.interactive
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.*
import io.github.roberbarras.racha.ui.settings.ConfirmBox
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.coroutines.cancellation.CancellationException

/** The steps of adding a remote folder (H19). */
sealed interface AddRemote {
    data object Provider : AddRemote
    data class NextcloudServer(val manual: Boolean = false, val error: String? = null, val busy: Boolean = false) : AddRemote
    data class NextcloudWaiting(val server: String) : AddRemote
    data class GoogleSetup(val error: String? = null) : AddRemote
    data object GoogleWaiting : AddRemote
    data class Browse(
        val provider: RemoteProvider, val credentials: RemoteCredentials, val account: String, val base: String,
        val trail: List<RemoteDirectory>, val folders: List<RemoteDirectory>?, val error: String? = null,
    ) : AddRemote
    data class Configure(
        val provider: RemoteProvider, val credentials: RemoteCredentials, val account: String, val base: String, val folder: RemoteDirectory,
        val category: CategoryKey, val layout: RootLayout, val preview: RemotePreview?, val mode: DownloadMode, val selected: Set<String>,
        val destination: String, val wifiOnly: Boolean, val error: String? = null, val busy: Boolean = false,
    ) : AddRemote
}

class RemoteFoldersViewModel(private val deps: RachaDependencies, private val remote: RemoteFolders) : ViewModel() {
    private val sources: RemoteSources get() = remote.sources
    val list: StateFlow<List<RemoteSource>> = sources.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val downloads: StateFlow<Map<String, DownloadState>> = sources.downloads
    val step = MutableStateFlow<AddRemote?>(null)
    val categories = MutableStateFlow<List<Category>>(emptyList())
    /** Courses of a folder being chosen (its id), loaded from the server. */
    val choosing = MutableStateFlow<Pair<String, List<RemoteCourse>>?>(null)
    val choosingError = MutableStateFlow<String?>(null)
    private var waiting: Job? = null

    init {
        viewModelScope.launch { categories.value = deps.categories.all() }
    }

    fun start() { step.value = AddRemote.Provider }

    fun cancel() {
        waiting?.cancel()
        step.value = null
    }

    fun pick(provider: RemoteProvider) = viewModelScope.launch {
        step.value = when (provider) {
            RemoteProvider.WEBDAV -> AddRemote.NextcloudServer()
            RemoteProvider.GOOGLE_DRIVE -> if (remote.accounts.googleReady()) AddRemote.GoogleWaiting.also { signInGoogle() } else AddRemote.GoogleSetup()
        }
    }

    fun manual(manual: Boolean) { step.value = AddRemote.NextcloudServer(manual = manual) }

    /** Nextcloud's login flow: the browser opens on the server, and this waits (up to 10 min) for the app password. */
    fun signInNextcloud(server: String) {
        waiting?.cancel()
        waiting = viewModelScope.launch {
            step.value = AddRemote.NextcloudServer(busy = true)
            val pending = try {
                remote.accounts.startNextcloud(server)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                step.value = AddRemote.NextcloudServer(error = RemoteSources.reasonOf(e).takeIf { it != "error" } ?: "not_nextcloud")
                return@launch
            }
            step.value = AddRemote.NextcloudWaiting(server)
            deps.platform.openUrl(pending.loginUrl)
            repeat(300) {
                delay(2_000)
                val account = runCatching { remote.accounts.pollNextcloud(pending) }.getOrNull()
                if (account != null) return@launch browseAccount(account)
            }
            step.value = AddRemote.NextcloudServer(error = "timeout")
        }
    }

    fun signInManual(server: String, user: String, password: String, nextcloud: Boolean) =
        browseAccount(remote.accounts.manual(server, user, password, nextcloud))

    private fun browseAccount(account: WebDavAccount) {
        val label = account.user + " · " + account.server.substringAfter("://").trimEnd('/')
        browse(AddRemote.Browse(RemoteProvider.WEBDAV, RemoteCredentials.WebDav(account.user, account.password), label, account.davBase, emptyList(), null))
    }

    fun saveGoogleClient(id: String, secret: String) = viewModelScope.launch {
        remote.accounts.setGoogleClient(id, secret)
        step.value = AddRemote.GoogleWaiting
        signInGoogle()
    }

    private fun signInGoogle() {
        waiting?.cancel()
        waiting = viewModelScope.launch {
            val credentials = try {
                remote.accounts.signInGoogle { deps.platform.openUrl(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                step.value = AddRemote.GoogleSetup(error = RemoteSources.reasonOf(e))
                return@launch
            }
            browse(AddRemote.Browse(RemoteProvider.GOOGLE_DRIVE, credentials, "Google Drive", "", emptyList(), null))
        }
    }

    fun forgetGoogleClient() = viewModelScope.launch { remote.accounts.forgetGoogleClient(); step.value = AddRemote.GoogleSetup() }

    /** Opens [at] (the top with an empty trail) and lists its folders. */
    fun browse(at: AddRemote.Browse) {
        step.value = at.copy(folders = null, error = null)
        viewModelScope.launch {
            step.value = try {
                at.copy(folders = sources.folders(at.provider, at.credentials, at.base, at.trail.lastOrNull()?.address), error = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                at.copy(folders = emptyList(), error = RemoteSources.reasonOf(e))
            }
        }
    }

    fun open(at: AddRemote.Browse, folder: RemoteDirectory) = browse(at.copy(trail = at.trail + folder))

    fun up(at: AddRemote.Browse) = browse(at.copy(trail = at.trail.dropLast(1)))

    fun choose(at: AddRemote.Browse) {
        val folder = at.trail.lastOrNull() ?: return
        val category = categories.value.firstOrNull { it.isPrimary }?.key ?: CategoryKey.COURSES
        val configure = AddRemote.Configure(
            at.provider, at.credentials, at.account, at.base, folder, category, RootLayout.AUTO, null, DownloadMode.ALL, emptySet(),
            remote.defaultDestination(folder.name), wifiOnly = remote.offersWifiOnly,
        )
        step.value = configure
        preview(configure)
    }

    fun update(configure: AddRemote.Configure, newLayout: Boolean = false) {
        step.value = configure
        if (newLayout) preview(configure.copy(preview = null))
    }

    private fun preview(configure: AddRemote.Configure) = viewModelScope.launch {
        step.value = configure.copy(preview = null, busy = true, error = null)
        step.value = try {
            val preview = sources.preview(configure.provider, configure.credentials, configure.folder.address, configure.layout, configure.category, configure.folder.name)
            configure.copy(preview = preview, busy = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            configure.copy(busy = false, error = RemoteSources.reasonOf(e))
        }
    }

    fun changeDestination(configure: AddRemote.Configure) = viewModelScope.launch {
        deps.platform.pickFolder()?.let { step.value = configure.copy(destination = it) }
    }

    fun add(configure: AddRemote.Configure) = viewModelScope.launch {
        step.value = configure.copy(busy = true, error = null)
        try {
            val source = sources.add(
                NewRemoteSource(
                    configure.provider, configure.folder.name, configure.account, configure.folder.address,
                    configure.account + " · " + configure.folder.path, configure.category,
                    // The layout the preview showed, so what is chosen is what arrives.
                    configure.preview?.layout ?: configure.layout, configure.destination, configure.mode, configure.selected, configure.wifiOnly,
                ),
                configure.credentials,
            )
            step.value = null
            remote.download(source.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            step.value = configure.copy(busy = false, error = RemoteSources.reasonOf(e))
        }
    }

    fun downloadNow(id: String) = remote.download(id)

    fun stop(id: String) = sources.cancel(id)

    fun openChooser(id: String) = viewModelScope.launch {
        choosingError.value = null
        choosing.value = id to emptyList()
        try {
            choosing.value = id to sources.courses(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            choosing.value = null
            choosingError.value = RemoteSources.reasonOf(e)
        }
    }

    fun closeChooser() { choosing.value = null }

    fun saveChoice(id: String, mode: DownloadMode, selected: Set<String>) = viewModelScope.launch {
        sources.choose(id, mode, selected)
        choosing.value = null
        remote.download(id)
    }

    fun setWifiOnly(id: String, on: Boolean) = viewModelScope.launch { sources.setWifiOnly(id, on) }

    val freed = MutableStateFlow<Pair<String, Int>?>(null)

    fun freeSpace(id: String) = viewModelScope.launch { freed.value = id to sources.freeSpace(id) }

    fun remove(id: String, deleteFiles: Boolean) = viewModelScope.launch { sources.remove(id, deleteFiles) }
}

/** Folders screen → "Carpetas remotas" (H19). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemoteFoldersSection(deps: RachaDependencies) {
    val remote = deps.remote ?: return
    val vm = viewModel { RemoteFoldersViewModel(deps, remote) }
    val list by vm.list.collectAsStateWithLifecycle()
    val step by vm.step.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(Racha.radius.l)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Racha.colors.surface).border(1.dp, Racha.colors.border, shape).padding(Racha.space.l),
        verticalArrangement = Arrangement.spacedBy(Racha.space.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.m)) {
            IconBadge(RachaIcons.Cloud)
            Column(Modifier.weight(1f)) {
                RText(stringResource(Res.string.remote_title), style = Racha.type.heading)
                RText(stringResource(Res.string.remote_message), style = Racha.type.caption, color = Racha.colors.inkMuted)
            }
        }
        if (step == null) RButton(stringResource(Res.string.remote_add), { vm.start() }, kind = ButtonKind.Secondary, icon = RachaIcons.Plus)
        if (list.isEmpty() && step == null) RText(stringResource(Res.string.remote_none), style = Racha.type.body, color = Racha.colors.inkSubtle)
        list.forEach { source ->
            Hairline()
            SourceCard(vm, remote, source)
        }
        step?.let { AddWizard(vm, remote, it) }
    }
}

@Composable
private fun Hairline() = androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(vertical = 1.dp).background(Racha.colors.border).padding(0.5.dp))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceCard(vm: RemoteFoldersViewModel, remote: RemoteFolders, source: RemoteSource) {
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val choosing by vm.choosing.collectAsStateWithLifecycle()
    val freed by vm.freed.collectAsStateWithLifecycle()
    val state = downloads[source.id]
    var removing by remember { mutableStateOf(false) }
    var freeing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.m)) {
            RIcon(if (source.provider == RemoteProvider.GOOGLE_DRIVE) RachaIcons.HardDrive else RachaIcons.Cloud, null, tint = Racha.colors.accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Racha.space.xxs)) {
                RText(source.name, style = Racha.type.bodyStrong, maxLines = 1)
                RText(source.location, style = Racha.type.caption, color = Racha.colors.inkMuted, maxLines = 1)
                RText(stringResource(Res.string.remote_to, displayPath(source.destination)), style = Racha.type.caption, color = Racha.colors.inkMuted, maxLines = 2)
                RText(
                    if (source.mode == DownloadMode.ALL) stringResource(Res.string.remote_mode_all_short)
                    else pluralStringResource(Res.plurals.remote_mode_selected_short, source.selected.size, source.selected.size),
                    style = Racha.type.caption, color = Racha.colors.inkMuted,
                )
            }
        }
        when (state) {
            is DownloadState.Downloading -> {
                RText(stringResource(Res.string.remote_downloading, state.doneFiles + 1, state.files, fileSize(state.doneBytes), fileSize(state.bytes)),
                    style = Racha.type.caption)
                RText(state.file.substringAfterLast('/'), style = Racha.type.caption, color = Racha.colors.inkMuted, maxLines = 1)
                ProgressBar(state.fraction)
            }
            DownloadState.Listing -> RText(stringResource(Res.string.remote_listing), style = Racha.type.caption, color = Racha.colors.inkMuted)
            is DownloadState.Failed -> RText(reasonText(state.reason), style = Racha.type.caption, color = Racha.colors.danger)
            else -> {
                source.lastError?.let { RText(reasonText(it), style = Racha.type.caption, color = Racha.colors.danger) }
                if (source.lastError == null) {
                    RText(
                        if (source.lastSyncAt != null) stringResource(Res.string.remote_up_to_date) else stringResource(Res.string.remote_never),
                        style = Racha.type.caption, color = Racha.colors.inkMuted,
                    )
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.s), verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
            val busy = state is DownloadState.Downloading || state == DownloadState.Listing
            if (busy) RButton(stringResource(Res.string.remote_stop), { vm.stop(source.id) }, kind = ButtonKind.Secondary, icon = RachaIcons.X)
            else RButton(stringResource(Res.string.remote_download_now), { vm.downloadNow(source.id) }, kind = ButtonKind.Secondary, icon = RachaIcons.RefreshCw)
            RButton(stringResource(Res.string.remote_choose), { vm.openChooser(source.id) }, kind = ButtonKind.Ghost, icon = RachaIcons.ListVideo)
            if (source.mode == DownloadMode.SELECTED) RButton(stringResource(Res.string.remote_free_space), { freeing = true }, kind = ButtonKind.Ghost, icon = RachaIcons.HardDrive)
            RButton(stringResource(Res.string.remote_remove), { removing = true }, kind = ButtonKind.Ghost, icon = RachaIcons.Trash2)
        }
        if (remote.offersWifiOnly) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RText(stringResource(Res.string.remote_wifi_only), style = Racha.type.body, modifier = Modifier.weight(1f))
                RSwitch(source.wifiOnly, { vm.setWifiOnly(source.id, it) })
            }
        }
        freed?.takeIf { it.first == source.id }?.let { RText(stringResource(Res.string.remote_freed, it.second), style = Racha.type.caption, color = Racha.colors.success) }
        if (freeing) ConfirmBox(stringResource(Res.string.remote_free_space_confirm), { freeing = false; vm.freeSpace(source.id) }, { freeing = false }, danger = true)
        if (removing) RemoveBox(onKeep = { removing = false; vm.remove(source.id, false) }, onDelete = { removing = false; vm.remove(source.id, true) }, onCancel = { removing = false })
        choosing?.takeIf { it.first == source.id }?.let { (_, courses) -> CourseChooser(source, courses, vm) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemoveBox(onKeep: () -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Racha.colors.danger.copy(alpha = 0.10f), RoundedCornerShape(Racha.radius.m)).padding(Racha.space.m),
        verticalArrangement = Arrangement.spacedBy(Racha.space.s),
    ) {
        RText(stringResource(Res.string.remote_remove_question), style = Racha.type.body)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.s), verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
            RButton(stringResource(Res.string.remote_remove_keep), onKeep, kind = ButtonKind.Secondary)
            RButton(stringResource(Res.string.remote_remove_delete), onDelete)
            RButton(stringResource(Res.string.cancel), onCancel, kind = ButtonKind.Ghost)
        }
    }
}

/** "Everything" or the chosen courses, with their size and how much is already here. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CourseChooser(source: RemoteSource, courses: List<RemoteCourse>, vm: RemoteFoldersViewModel) {
    var mode by remember(source.id) { mutableStateOf(source.mode) }
    var selected by remember(source.id) { mutableStateOf(source.selected) }
    val shape = RoundedCornerShape(Racha.radius.m)
    Column(Modifier.fillMaxWidth().clip(shape).background(Racha.colors.accentSoft).padding(Racha.space.m), verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
        ModeToggle(mode) { mode = it }
        if (courses.isEmpty()) RText(stringResource(Res.string.remote_listing), style = Racha.type.caption, color = Racha.colors.inkMuted)
        CourseList(courses, if (mode == DownloadMode.ALL) courses.map { it.key }.toSet() else selected, enabled = mode == DownloadMode.SELECTED) { key ->
            selected = if (key in selected) selected - key else selected + key
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.s), verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
            RButton(stringResource(Res.string.save), { vm.saveChoice(source.id, mode, selected) })
            RButton(stringResource(Res.string.cancel), { vm.closeChooser() }, kind = ButtonKind.Secondary)
        }
    }
}

@Composable
private fun ModeToggle(mode: DownloadMode, onChange: (DownloadMode) -> Unit) = SegmentedToggle(
    listOf(stringResource(Res.string.remote_mode_all) to DownloadMode.ALL, stringResource(Res.string.remote_mode_selected) to DownloadMode.SELECTED),
    mode, onChange,
)

@Composable
private fun CourseList(courses: List<RemoteCourse>, selected: Set<String>, enabled: Boolean, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.xxs)) {
        courses.forEach { course ->
            val on = course.key in selected
            val shape = RoundedCornerShape(Racha.radius.s)
            Row(
                Modifier.fillMaxWidth().clip(shape).interactive({ onToggle(course.key) }, shape, enabled = enabled).padding(Racha.space.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Racha.space.m),
            ) {
                RIcon(if (on) RachaIcons.CircleCheck else RachaIcons.CircleDashed, null, tint = if (on) Racha.colors.accent else Racha.colors.inkSubtle)
                Column(Modifier.weight(1f)) {
                    RText(course.title.ifEmpty { stringResource(Res.string.loose_files) }, style = Racha.type.body, maxLines = 1)
                    RText(
                        pluralStringResource(Res.plurals.remote_course_size, course.files, course.files, fileSize(course.bytes)) +
                            if (course.complete) " · " + stringResource(Res.string.remote_course_here) else if (course.downloadedBytes > 0) " · " +
                                stringResource(Res.string.remote_course_partial, fileSize(course.downloadedBytes)) else "",
                        style = Racha.type.caption, color = Racha.colors.inkMuted,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddWizard(vm: RemoteFoldersViewModel, remote: RemoteFolders, step: AddRemote) {
    val shape = RoundedCornerShape(Racha.radius.m)
    Column(Modifier.fillMaxWidth().clip(shape).background(Racha.colors.accentSoft).padding(Racha.space.l), verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
        when (step) {
            AddRemote.Provider -> {
                RText(stringResource(Res.string.remote_where), style = Racha.type.bodyStrong)
                ProviderRow(RachaIcons.Cloud, stringResource(Res.string.remote_nextcloud), stringResource(Res.string.remote_nextcloud_hint)) { vm.pick(RemoteProvider.WEBDAV) }
                ProviderRow(RachaIcons.HardDrive, stringResource(Res.string.remote_drive), stringResource(Res.string.remote_drive_hint)) { vm.pick(RemoteProvider.GOOGLE_DRIVE) }
            }
            is AddRemote.NextcloudServer -> NextcloudForm(vm, step)
            is AddRemote.NextcloudWaiting -> {
                RText(stringResource(Res.string.remote_waiting_browser), style = Racha.type.body)
                RText(step.server, style = Racha.type.caption, color = Racha.colors.inkMuted)
            }
            is AddRemote.GoogleSetup -> GoogleForm(vm, step)
            AddRemote.GoogleWaiting -> RText(stringResource(Res.string.remote_waiting_browser), style = Racha.type.body)
            is AddRemote.Browse -> Browser(vm, step)
            is AddRemote.Configure -> Configure(vm, remote, step)
        }
        RButton(stringResource(Res.string.cancel), { vm.cancel() }, kind = ButtonKind.Ghost)
    }
}

@Composable
private fun ProviderRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, hint: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Racha.radius.m)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Racha.colors.surface).interactive(onClick, shape).padding(Racha.space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Racha.space.m),
    ) {
        RIcon(icon, null, tint = Racha.colors.accent)
        Column(Modifier.weight(1f)) {
            RText(title, style = Racha.type.bodyStrong)
            RText(hint, style = Racha.type.caption, color = Racha.colors.inkMuted)
        }
        RIcon(RachaIcons.ChevronRight, null, tint = Racha.colors.inkSubtle)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NextcloudForm(vm: RemoteFoldersViewModel, step: AddRemote.NextcloudServer) {
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var generic by remember { mutableStateOf(false) }
    RText(stringResource(if (step.manual) Res.string.remote_manual_title else Res.string.remote_nextcloud_title), style = Racha.type.bodyStrong)
    RTextField(server, { server = it }, stringResource(if (step.manual && generic) Res.string.remote_webdav_url else Res.string.remote_server), Modifier.fillMaxWidth(), KeyboardType.Uri)
    if (step.manual) {
        RTextField(user, { user = it }, stringResource(Res.string.remote_user), Modifier.fillMaxWidth())
        RTextField(password, { password = it }, stringResource(Res.string.remote_app_password), Modifier.fillMaxWidth(), password = true)
        RText(stringResource(Res.string.remote_app_password_hint), style = Racha.type.caption, color = Racha.colors.inkMuted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RText(stringResource(Res.string.remote_generic_webdav), style = Racha.type.body, modifier = Modifier.weight(1f))
            RSwitch(generic, { generic = it })
        }
    } else {
        RText(stringResource(Res.string.remote_nextcloud_flow_hint), style = Racha.type.caption, color = Racha.colors.inkMuted)
    }
    step.error?.let { RText(reasonText(it), style = Racha.type.caption, color = Racha.colors.danger) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.s), verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
        if (step.manual) {
            RButton(stringResource(Res.string.remote_continue), { vm.signInManual(server, user, password, nextcloud = !generic) },
                enabled = server.isNotBlank() && user.isNotBlank() && password.isNotBlank())
            RButton(stringResource(Res.string.remote_use_browser), { vm.manual(false) }, kind = ButtonKind.Secondary)
        } else {
            RButton(stringResource(Res.string.remote_sign_in_browser), { vm.signInNextcloud(server) }, enabled = server.isNotBlank() && !step.busy, icon = RachaIcons.ExternalLink)
            RButton(stringResource(Res.string.remote_use_password), { vm.manual(true) }, kind = ButtonKind.Secondary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoogleForm(vm: RemoteFoldersViewModel, step: AddRemote.GoogleSetup) {
    var id by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    RText(stringResource(Res.string.remote_google_setup_title), style = Racha.type.bodyStrong)
    RText(stringResource(Res.string.remote_google_setup_hint), style = Racha.type.caption, color = Racha.colors.inkMuted)
    RTextField(id, { id = it }, stringResource(Res.string.remote_google_client_id), Modifier.fillMaxWidth())
    RTextField(secret, { secret = it }, stringResource(Res.string.remote_google_client_secret), Modifier.fillMaxWidth(), password = true)
    step.error?.let { RText(reasonText(it), style = Racha.type.caption, color = Racha.colors.danger) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.s), verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
        RButton(stringResource(Res.string.remote_sign_in_google), { vm.saveGoogleClient(id, secret) }, enabled = id.isNotBlank() && secret.isNotBlank(), icon = RachaIcons.ExternalLink)
        if (step.error != null) RButton(stringResource(Res.string.remote_google_forget), { vm.forgetGoogleClient() }, kind = ButtonKind.Secondary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Browser(vm: RemoteFoldersViewModel, step: AddRemote.Browse) {
    RText(stringResource(Res.string.remote_pick_folder), style = Racha.type.bodyStrong)
    RText(step.account + "  " + (step.trail.lastOrNull()?.path ?: "/"), style = Racha.type.caption, color = Racha.colors.inkMuted, maxLines = 2)
    step.error?.let { RText(reasonText(it), style = Racha.type.caption, color = Racha.colors.danger) }
    if (step.trail.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.s)) {
            RButton(stringResource(Res.string.remote_use_this, step.trail.last().name), { vm.choose(step) }, icon = RachaIcons.Check)
            RButton(stringResource(Res.string.back), { vm.up(step) }, kind = ButtonKind.Secondary, icon = RachaIcons.ArrowLeft)
        }
    }
    val folders = step.folders
    if (folders == null) RText(stringResource(Res.string.remote_listing), style = Racha.type.caption, color = Racha.colors.inkMuted)
    else if (folders.isEmpty() && step.error == null) RText(stringResource(Res.string.remote_no_subfolders), style = Racha.type.caption, color = Racha.colors.inkMuted)
    folders?.forEach { folder ->
        val shape = RoundedCornerShape(Racha.radius.s)
        Row(
            Modifier.fillMaxWidth().clip(shape).background(Racha.colors.surface).interactive({ vm.open(step, folder) }, shape).padding(Racha.space.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Racha.space.m),
        ) {
            RIcon(RachaIcons.FolderOpen, null, tint = Racha.colors.inkMuted)
            RText(driveName(folder), style = Racha.type.body, modifier = Modifier.weight(1f), maxLines = 1)
            RIcon(RachaIcons.ChevronRight, null, tint = Racha.colors.inkSubtle)
        }
    }
}

@Composable
private fun driveName(folder: RemoteDirectory): String = when (folder.address) {
    "root" -> stringResource(Res.string.remote_drive_mine)
    "shared" -> stringResource(Res.string.remote_drive_shared)
    else -> folder.name
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Configure(vm: RemoteFoldersViewModel, remote: RemoteFolders, step: AddRemote.Configure) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    RText(stringResource(Res.string.remote_configure, step.folder.name), style = Racha.type.bodyStrong)
    RText(stringResource(Res.string.remote_category), style = Racha.type.caption, color = Racha.colors.inkMuted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.s), verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
        categories.forEach { category ->
            RButton(categoryName(category), { vm.update(step.copy(category = category.key), newLayout = true) },
                kind = if (category.key == step.category) ButtonKind.Primary else ButtonKind.Secondary)
        }
    }
    RText(stringResource(Res.string.remote_holds), style = Racha.type.caption, color = Racha.colors.inkMuted)
    SegmentedToggle(
        listOf(stringResource(Res.string.layout_library) to RootLayout.LIBRARY, stringResource(Res.string.layout_single_item) to RootLayout.SINGLE_ITEM,
            stringResource(Res.string.layout_auto) to RootLayout.AUTO),
        step.layout, { vm.update(step.copy(layout = it, selected = emptySet()), newLayout = true) },
    )
    RText(stringResource(Res.string.remote_download_what), style = Racha.type.caption, color = Racha.colors.inkMuted)
    ModeToggle(step.mode) { vm.update(step.copy(mode = it)) }
    val preview = step.preview
    when {
        step.busy && preview == null -> RText(stringResource(Res.string.remote_listing), style = Racha.type.caption, color = Racha.colors.inkMuted)
        preview != null -> {
            RText(pluralStringResource(Res.plurals.remote_total, preview.courses.size, preview.courses.size, fileSize(preview.courses.sumOf { it.bytes })), style = Racha.type.caption)
            CourseList(preview.courses, if (step.mode == DownloadMode.ALL) preview.courses.map { it.key }.toSet() else step.selected, step.mode == DownloadMode.SELECTED) { key ->
                vm.update(step.copy(selected = if (key in step.selected) step.selected - key else step.selected + key))
            }
        }
    }
    RText(stringResource(Res.string.remote_destination), style = Racha.type.caption, color = Racha.colors.inkMuted)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.s)) {
        RText(displayPath(step.destination), style = Racha.type.body, modifier = Modifier.weight(1f), maxLines = 2)
        RIconButton(RachaIcons.Pencil, stringResource(Res.string.remote_change_destination), { vm.changeDestination(step) }, tint = Racha.colors.inkMuted)
    }
    if (remote.offersWifiOnly) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RText(stringResource(Res.string.remote_wifi_only), style = Racha.type.body, modifier = Modifier.weight(1f))
            RSwitch(step.wifiOnly, { vm.update(step.copy(wifiOnly = it)) })
        }
    }
    step.error?.let { RText(reasonText(it), style = Racha.type.caption, color = Racha.colors.danger) }
    val nothing = step.mode == DownloadMode.SELECTED && step.selected.isEmpty()
    RButton(stringResource(Res.string.remote_add_and_download), { vm.add(step) }, enabled = !step.busy && !nothing && preview != null, icon = RachaIcons.Check)
}

@Composable
private fun reasonText(reason: String): String = when {
    reason == "credentials" -> stringResource(Res.string.remote_error_credentials)
    reason == "unreachable" -> stringResource(Res.string.remote_error_unreachable)
    reason == "space" -> stringResource(Res.string.remote_error_space)
    reason == "destination" -> stringResource(Res.string.remote_error_destination)
    reason == "timeout" -> stringResource(Res.string.remote_error_timeout)
    reason == "not_nextcloud" -> stringResource(Res.string.remote_error_not_nextcloud)
    reason.contains("no_google_client") -> stringResource(Res.string.remote_error_google_client)
    else -> stringResource(Res.string.remote_error_other, reason)
}
