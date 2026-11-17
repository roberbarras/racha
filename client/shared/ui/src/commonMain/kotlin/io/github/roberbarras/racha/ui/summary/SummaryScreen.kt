package io.github.roberbarras.racha.ui.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.roberbarras.racha.application.ai.Attempt
import io.github.roberbarras.racha.application.ai.SummarizeWithAi
import io.github.roberbarras.racha.application.ai.SummaryOutcome
import io.github.roberbarras.racha.domain.ai.Summary
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.ui.app.RachaDependencies
import io.github.roberbarras.racha.ui.common.ResponsiveColumn
import io.github.roberbarras.racha.ui.common.longDate
import io.github.roberbarras.racha.ui.design.ButtonKind
import io.github.roberbarras.racha.ui.design.RButton
import io.github.roberbarras.racha.ui.design.RIconButton
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.back
import io.github.roberbarras.racha.ui.resources.summary_action
import io.github.roberbarras.racha.ui.resources.summary_by
import io.github.roberbarras.racha.ui.resources.summary_delete
import io.github.roberbarras.racha.ui.resources.summary_empty
import io.github.roberbarras.racha.ui.resources.summary_empty_section
import io.github.roberbarras.racha.ui.resources.summary_export
import io.github.roberbarras.racha.ui.resources.summary_exported
import io.github.roberbarras.racha.ui.resources.summary_failed
import io.github.roberbarras.racha.ui.resources.summary_generate
import io.github.roberbarras.racha.ui.resources.summary_no_subtitles
import io.github.roberbarras.racha.ui.resources.summary_not_configured
import io.github.roberbarras.racha.ui.resources.summary_open_settings
import io.github.roberbarras.racha.ui.resources.summary_reason_empty
import io.github.roberbarras.racha.ui.resources.summary_reason_key
import io.github.roberbarras.racha.ui.resources.summary_reason_model
import io.github.roberbarras.racha.ui.resources.summary_reason_no_key
import io.github.roberbarras.racha.ui.resources.summary_reason_other
import io.github.roberbarras.racha.ui.resources.summary_reason_payment
import io.github.roberbarras.racha.ui.resources.summary_reason_quota
import io.github.roberbarras.racha.ui.resources.summary_reason_server
import io.github.roberbarras.racha.ui.resources.summary_reason_too_long
import io.github.roberbarras.racha.ui.resources.summary_reason_unreachable
import io.github.roberbarras.racha.ui.resources.summary_regenerate
import io.github.roberbarras.racha.ui.resources.summary_section_action
import io.github.roberbarras.racha.ui.resources.summary_unavailable
import io.github.roberbarras.racha.ui.resources.summary_working
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** What is summarised: a lesson, or a section of [item]. */
data class SummarySubject(val target: ContentId, val item: ContentId, val section: Boolean, val title: String)

/** "Resumen y claves" (H17), shared by the summary screen and the player's side panel. */
class SummaryViewModel(private val deps: RachaDependencies, val subject: SummarySubject) : ViewModel() {
    fun dateOf(summary: Summary) = deps.localDates.dateOf(summary.updatedAt)

    private val ai: SummarizeWithAi? = deps.summaries
    val summary: StateFlow<Summary?> = (ai?.observe(subject.target) ?: flowOf(null)).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val working = MutableStateFlow(false)
    /** The last attempt that did not end in a summary. */
    val problem = MutableStateFlow<SummaryOutcome?>(null)
    val exported = MutableStateFlow(false)

    fun generate(regenerate: Boolean = false) = viewModelScope.launch {
        val ai = ai ?: return@launch
        if (working.value) return@launch
        working.value = true
        problem.value = null
        val outcome = try {
            if (subject.section) ai.section(subject.item, subject.target, regenerate) else ai.lesson(subject.target, regenerate)
        } finally {
            working.value = false
        }
        if (outcome !is SummaryOutcome.Done) problem.value = outcome
    }

    fun delete() = viewModelScope.launch { ai?.delete(subject.target) }

    fun export() = viewModelScope.launch {
        val s = summary.value ?: return@launch
        val text = "# ${subject.title}\n\n${s.markdown.trim()}\n\n---\n${s.provider} · ${s.model} · ${s.updatedAt}\n"
        exported.value = deps.platform.saveTextFile(fileName(subject.title) + ".md", text)
    }

    companion object {
        /** A name the file system accepts, from the lesson's title. */
        fun fileName(title: String): String =
            title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "resumen" }
    }
}

@Composable
fun summaryViewModel(deps: RachaDependencies, subject: SummarySubject): SummaryViewModel =
    viewModel(key = "summary-${subject.target.value}") { SummaryViewModel(deps, subject) }

/** Full-screen summary of a lesson or a section. */
@Composable
fun SummaryScreen(deps: RachaDependencies, subject: SummarySubject, onBack: () -> Unit, onSettings: () -> Unit) {
    val vm = summaryViewModel(deps, subject)
    ResponsiveColumn(maxContentWidth = Racha.layout.readingMaxWidth) { _ ->
        item { RIconButton(RachaIcons.ArrowLeft, stringResource(Res.string.back), onBack) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
                RText(stringResource(if (subject.section) Res.string.summary_section_action else Res.string.summary_action), style = Racha.type.label,
                    color = Racha.colors.inkMuted)
                RText(subject.title, style = Racha.type.display)
            }
        }
        item { SummaryContent(vm, onSettings = onSettings) }
    }
}

/** The summary, or how to get one; [onRail] for the player's dark side panel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SummaryContent(vm: SummaryViewModel, modifier: Modifier = Modifier, onRail: Boolean = false, onSettings: (() -> Unit)? = null) {
    val summary by vm.summary.collectAsStateWithLifecycle()
    val working by vm.working.collectAsStateWithLifecycle()
    val problem by vm.problem.collectAsStateWithLifecycle()
    val exported by vm.exported.collectAsStateWithLifecycle()
    val ink = if (onRail) Racha.colors.onRail else Racha.colors.ink
    val muted = if (onRail) Racha.colors.onRailMuted else Racha.colors.inkMuted
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
        val current = summary
        if (current == null && !working && problem == null) {
            RText(stringResource(if (vm.subject.section) Res.string.summary_empty_section else Res.string.summary_empty), style = Racha.type.body, color = muted)
        }
        if (working) RText(stringResource(Res.string.summary_working), style = Racha.type.body, color = muted)
        problem?.let { Problem(it, muted, onSettings) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.s), verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
            if (current == null) {
                RButton(stringResource(Res.string.summary_generate), { vm.generate() }, enabled = !working, icon = RachaIcons.Sparkles)
            } else {
                RButton(stringResource(Res.string.summary_export), { vm.export() }, kind = ButtonKind.Secondary, icon = RachaIcons.FileText)
                RButton(stringResource(Res.string.summary_regenerate), { vm.generate(regenerate = true) }, kind = ButtonKind.Ghost, enabled = !working,
                    icon = RachaIcons.RefreshCw)
                RButton(stringResource(Res.string.summary_delete), { vm.delete() }, kind = ButtonKind.Ghost, enabled = !working, icon = RachaIcons.Trash2)
            }
        }
        if (exported) RText(stringResource(Res.string.summary_exported), style = Racha.type.caption, color = Racha.colors.success)
        if (current != null) {
            MarkdownView(current.markdown, color = ink, muted = muted)
            val date = longDate(vm.dateOf(current))
            RText(stringResource(Res.string.summary_by, current.provider, current.model, date), style = Racha.type.caption, color = muted)
        }
    }
}

@Composable
private fun Problem(outcome: SummaryOutcome, muted: Color, onSettings: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
        when (outcome) {
            SummaryOutcome.NotConfigured -> {
                RText(stringResource(Res.string.summary_not_configured), style = Racha.type.body, color = Racha.colors.danger)
                onSettings?.let { RButton(stringResource(Res.string.summary_open_settings), it, kind = ButtonKind.Secondary, icon = RachaIcons.Settings) }
            }
            SummaryOutcome.NoSubtitles -> RText(stringResource(Res.string.summary_no_subtitles), style = Racha.type.body, color = Racha.colors.danger)
            SummaryOutcome.Unavailable -> RText(stringResource(Res.string.summary_unavailable), style = Racha.type.body, color = Racha.colors.danger)
            is SummaryOutcome.Failed -> {
                RText(stringResource(Res.string.summary_failed), style = Racha.type.body, color = Racha.colors.danger)
                outcome.attempts.forEach { RText("• ${it.provider}: ${reason(it)}", style = Racha.type.caption, color = muted) }
                onSettings?.let { RButton(stringResource(Res.string.summary_open_settings), it, kind = ButtonKind.Ghost, icon = RachaIcons.Settings) }
            }
            is SummaryOutcome.Done -> Unit
        }
    }
}

@Composable
private fun reason(attempt: Attempt): String = when (attempt.reason) {
    "quota" -> stringResource(Res.string.summary_reason_quota)
    "key" -> stringResource(Res.string.summary_reason_key)
    "no_key" -> stringResource(Res.string.summary_reason_no_key)
    "payment" -> stringResource(Res.string.summary_reason_payment)
    "model" -> stringResource(Res.string.summary_reason_model)
    "too_long" -> stringResource(Res.string.summary_reason_too_long)
    "server" -> stringResource(Res.string.summary_reason_server)
    "unreachable" -> stringResource(Res.string.summary_reason_unreachable)
    "empty" -> stringResource(Res.string.summary_reason_empty)
    else -> stringResource(Res.string.summary_reason_other, attempt.reason)
}
