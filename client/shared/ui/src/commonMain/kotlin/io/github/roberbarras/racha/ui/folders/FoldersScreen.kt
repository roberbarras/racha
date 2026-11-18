package io.github.roberbarras.racha.ui.folders

import io.github.roberbarras.racha.ui.common.ResponsiveColumn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.roberbarras.racha.domain.library.Category
import io.github.roberbarras.racha.domain.library.CategoryKey
import io.github.roberbarras.racha.domain.library.CategoryRoot
import io.github.roberbarras.racha.domain.library.RootKey
import io.github.roberbarras.racha.domain.library.RootLayout
import io.github.roberbarras.racha.application.sync.RemoteRoot
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import io.github.roberbarras.racha.ui.resources.cancel
import io.github.roberbarras.racha.ui.resources.link_folder
import io.github.roberbarras.racha.ui.resources.link_message
import io.github.roberbarras.racha.ui.resources.link_new
import io.github.roberbarras.racha.ui.resources.link_title
import io.github.roberbarras.racha.ui.resources.link_to
import io.github.roberbarras.racha.ui.app.AppViewModel
import io.github.roberbarras.racha.ui.app.RachaDependencies
import io.github.roberbarras.racha.ui.common.IconBadge
import io.github.roberbarras.racha.ui.common.ScreenTitle
import io.github.roberbarras.racha.ui.common.StorageAccessBanner
import io.github.roberbarras.racha.ui.common.categoryIcon
import io.github.roberbarras.racha.ui.common.categoryName
import io.github.roberbarras.racha.ui.design.ButtonKind
import io.github.roberbarras.racha.ui.design.RButton
import io.github.roberbarras.racha.ui.design.RIconButton
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.change_layout
import io.github.roberbarras.racha.ui.resources.folders_message
import io.github.roberbarras.racha.ui.resources.folders_title
import io.github.roberbarras.racha.ui.resources.no_folders
import io.github.roberbarras.racha.ui.resources.remove_folder
import io.github.roberbarras.racha.ui.resources.scan_now
import io.github.roberbarras.racha.ui.resources.scan_report
import io.github.roberbarras.racha.ui.resources.scanning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** [linkable]: folders of this category on the account's other devices not linked here yet (ADR-0004). */
data class CategoryFolders(val category: Category, val roots: List<CategoryRoot>, val linkable: List<RemoteRoot>)

/**
 * Asking whether a folder is the same as one on another device: a new one ([location], to be added with [layout]) or
 * an existing [root].
 */
data class LinkQuestion(
    val category: CategoryKey,
    val options: List<RemoteRoot>,
    val location: String? = null,
    val root: CategoryRoot? = null,
    val layout: RootLayout = RootLayout.AUTO,
)

class FoldersViewModel(private val deps: RachaDependencies) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    val folders: StateFlow<List<CategoryFolders>> = combine(deps.categories.observeAll(), refresh) { all, _ ->
        all.map { CategoryFolders(it, deps.manageRoots.roots(it.key), deps.manageRoots.linkable(it.key)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val mutableQuestion = MutableStateFlow<LinkQuestion?>(null)
    val question: StateFlow<LinkQuestion?> = mutableQuestion.asStateFlow()

    private val mutablePending = MutableStateFlow<PendingFolder?>(null)
    /** A picked folder shown as what it would become, before adding it (ADR-0013). */
    val pending: StateFlow<PendingFolder?> = mutablePending.asStateFlow()

    private val mutableEditing = MutableStateFlow<RootKey?>(null)
    /** The folder whose layout is being changed. */
    val editing: StateFlow<RootKey?> = mutableEditing.asStateFlow()

    fun add(category: CategoryKey, layout: RootLayout) = viewModelScope.launch {
        val location = deps.platform.pickFolder() ?: return@launch
        mutableQuestion.value = null
        mutablePending.value = PendingFolder(category, location, layout)
        val preview = deps.manageRoots.preview(category, location)
        mutablePending.value = mutablePending.value?.takeIf { it.location == location }?.copy(preview = preview)
    }

    fun previewAs(layout: RootLayout) {
        mutablePending.value = mutablePending.value?.copy(layout = layout)
    }

    fun cancelAdd() {
        mutablePending.value = null
    }

    fun confirmAdd() = viewModelScope.launch {
        val pending = mutablePending.value ?: return@launch
        mutablePending.value = null
        val options = deps.manageRoots.linkable(pending.category)
        if (options.isEmpty()) {
            deps.manageRoots.add(pending.category, pending.location, pending.layout)
            refresh.value++
        } else {
            // The folder with the same name on the other device is the likely match: offered first.
            val name = displayPath(pending.location).substringAfterLast('/')
            mutableQuestion.value = LinkQuestion(
                pending.category, options.sortedByDescending { it.name.equals(name, ignoreCase = true) }, location = pending.location, layout = pending.layout,
            )
        }
    }

    fun editLayout(root: CategoryRoot?) {
        mutableEditing.value = root?.key
    }

    fun changeLayout(root: CategoryRoot, layout: RootLayout) = viewModelScope.launch {
        mutableEditing.value = null
        deps.manageRoots.changeLayout(root, layout)
        refresh.value++
    }

    fun askLink(entry: CategoryFolders, root: CategoryRoot) {
        val name = displayPath(root.location).substringAfterLast('/')
        mutableQuestion.value = LinkQuestion(entry.category.key, entry.linkable.sortedByDescending { it.name.equals(name, ignoreCase = true) }, root = root)
    }

    /** [remote] null: a new folder (when adding) or no change (when linking an existing one). */
    fun answer(remote: RemoteRoot?) = viewModelScope.launch {
        val question = mutableQuestion.value ?: return@launch
        mutableQuestion.value = null
        val target = remote?.let { RootKey(it.key) }
        when {
            question.location != null -> deps.manageRoots.add(question.category, question.location, question.layout, linkTo = target)
            question.root != null && target != null -> deps.manageRoots.link(question.root, target)
        }
        refresh.value++
    }

    fun remove(root: CategoryRoot) = viewModelScope.launch {
        deps.manageRoots.remove(root)
        refresh.value++
    }
}

/** Folders per category (spec §9 "Categorías": several roots each) and "Scan now" (§3.3). */
@Composable
fun FoldersScreen(deps: RachaDependencies, app: AppViewModel) {
    val vm = viewModel { FoldersViewModel(deps) }
    val folders by vm.folders.collectAsStateWithLifecycle()
    val question by vm.question.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val scan by app.scan.collectAsStateWithLifecycle()
    val storageAccess by app.storageAccess.collectAsStateWithLifecycle()
    ResponsiveColumn(maxContentWidth = Racha.layout.readingMaxWidth) { _ ->
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ScreenTitle(stringResource(Res.string.folders_title), stringResource(Res.string.folders_message)) }
                RButton(
                    stringResource(if (scan.running) Res.string.scanning else Res.string.scan_now),
                    onClick = { app.scanNow() },
                    enabled = !scan.running,
                    kind = ButtonKind.Secondary,
                    icon = RachaIcons.RefreshCw,
                )
            }
        }
        scan.last?.let { report ->
            item { RText(stringResource(Res.string.scan_report, report.added, report.updated, report.archived), style = Racha.type.caption, color = Racha.colors.inkMuted) }
        }
        if (!storageAccess) item { StorageAccessBanner(onAllow = app::requestStorageAccess) }
        if (deps.remote != null) item(key = "remote") { RemoteFoldersSection(deps) }
        folders.forEach { entry ->
            item(key = entry.category.key.value) {
                CategoryFoldersCard(
                    entry,
                    question = question?.takeIf { it.category == entry.category.key },
                    pending = pending?.takeIf { it.category == entry.category.key },
                    editing = editing,
                    onAdd = { vm.add(entry.category.key, it) },
                    onPreviewAs = vm::previewAs,
                    onConfirmAdd = { vm.confirmAdd() },
                    onCancelAdd = vm::cancelAdd,
                    onEditLayout = vm::editLayout,
                    onChangeLayout = { root, layout -> vm.changeLayout(root, layout) },
                    onRemove = vm::remove,
                    onLink = { vm.askLink(entry, it) },
                    onAnswer = { vm.answer(it) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryFoldersCard(
    entry: CategoryFolders,
    question: LinkQuestion?,
    pending: PendingFolder?,
    editing: RootKey?,
    onAdd: (RootLayout) -> Unit,
    onPreviewAs: (RootLayout) -> Unit,
    onConfirmAdd: () -> Unit,
    onCancelAdd: () -> Unit,
    onEditLayout: (CategoryRoot?) -> Unit,
    onChangeLayout: (CategoryRoot, RootLayout) -> Unit,
    onRemove: (CategoryRoot) -> Unit,
    onLink: (CategoryRoot) -> Unit,
    onAnswer: (RemoteRoot?) -> Unit,
) {
    val shape = RoundedCornerShape(Racha.radius.l)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Racha.colors.surface).border(1.dp, Racha.colors.border, shape).padding(Racha.space.l),
        verticalArrangement = Arrangement.spacedBy(Racha.space.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.m)) {
            IconBadge(categoryIcon(entry.category))
            RText(categoryName(entry.category), style = Racha.type.heading, modifier = Modifier.weight(1f))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.s), verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
            AddFolderButtons(onAdd, enabled = pending == null)
        }
        if (entry.roots.isEmpty() && pending == null) {
            RText(stringResource(Res.string.no_folders), style = Racha.type.body, color = Racha.colors.inkSubtle)
        }
        entry.roots.forEach { root ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(Racha.colors.border))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.m)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Racha.space.xxs)) {
                    RText(displayPath(root.location), style = Racha.type.body, maxLines = 2)
                    RText(layoutName(root.layout), style = Racha.type.caption, color = Racha.colors.inkMuted)
                }
                RIconButton(RachaIcons.Pencil, stringResource(Res.string.change_layout), { onEditLayout(root) }, tint = Racha.colors.inkMuted)
                if (entry.linkable.isNotEmpty()) RIconButton(RachaIcons.Link, stringResource(Res.string.link_folder), { onLink(root) }, tint = Racha.colors.inkMuted)
                RIconButton(RachaIcons.Trash2, stringResource(Res.string.remove_folder), { onRemove(root) }, tint = Racha.colors.inkMuted)
            }
            if (editing == root.key) LayoutChooser(root.layout, { onChangeLayout(root, it) }, { onEditLayout(null) })
        }
        // A new folder goes after the existing ones.
        if (pending != null) FolderPreviewCard(pending, onPreviewAs, onConfirmAdd, onCancelAdd)
        if (question != null) LinkChooser(question, onAnswer)
    }
}

/** "Is it the same folder as on another device?": linking makes ids, and so progress, match (ADR-0004). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkChooser(question: LinkQuestion, onAnswer: (RemoteRoot?) -> Unit) {
    val shape = RoundedCornerShape(Racha.radius.m)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Racha.colors.accentSoft).padding(Racha.space.l),
        verticalArrangement = Arrangement.spacedBy(Racha.space.s),
    ) {
        RText(stringResource(Res.string.link_title), style = Racha.type.bodyStrong)
        question.location?.let { RText(displayPath(it), style = Racha.type.caption, color = Racha.colors.inkMuted, maxLines = 2) }
        RText(stringResource(Res.string.link_message), style = Racha.type.body, color = Racha.colors.inkMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.s), verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
            question.options.forEach { remote ->
                RButton(stringResource(Res.string.link_to, remote.name), { onAnswer(remote) }, icon = RachaIcons.Link)
            }
            RButton(
                stringResource(if (question.location != null) Res.string.link_new else Res.string.cancel),
                { onAnswer(null) }, kind = ButtonKind.Secondary,
            )
        }
    }
}

/** `file:///home/leo/Cursos/` → `/home/leo/Cursos`, decoding %20 and friends. */
internal fun displayPath(location: String): String {
    val raw = location.removePrefix("file://").trimEnd('/')
    val bytes = buildList {
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '%' && i + 2 < raw.length) {
                add(raw.substring(i + 1, i + 3).toInt(16).toByte()); i += 3
            } else {
                raw[i].toString().encodeToByteArray().forEach(::add); i++
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}
