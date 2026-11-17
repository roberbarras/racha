package io.github.roberbarras.racha.ui.settings

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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.roberbarras.racha.application.ai.SummarizeWithAi
import io.github.roberbarras.racha.application.ai.SummaryProviders
import io.github.roberbarras.racha.application.sync.RemoteError
import io.github.roberbarras.racha.domain.ai.ProviderPreset
import io.github.roberbarras.racha.domain.ai.ProviderPresets
import io.github.roberbarras.racha.domain.ai.SummaryProvider
import io.github.roberbarras.racha.ui.app.RachaDependencies
import io.github.roberbarras.racha.ui.design.ButtonKind
import io.github.roberbarras.racha.ui.design.RButton
import io.github.roberbarras.racha.ui.design.RIconButton
import io.github.roberbarras.racha.ui.design.RSwitch
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.RTextField
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.design.Tag
import io.github.roberbarras.racha.ui.design.TagTone
import io.github.roberbarras.racha.ui.design.interactive
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.ai_key
import io.github.roberbarras.racha.ui.resources.ai_key_hint
import io.github.roberbarras.racha.ui.resources.ai_key_remove
import io.github.roberbarras.racha.ui.resources.ai_key_saved
import io.github.roberbarras.racha.ui.resources.provider_down
import io.github.roberbarras.racha.ui.resources.provider_edit
import io.github.roberbarras.racha.ui.resources.provider_enabled
import io.github.roberbarras.racha.ui.resources.provider_get_key
import io.github.roberbarras.racha.ui.resources.provider_model
import io.github.roberbarras.racha.ui.resources.provider_models_failed
import io.github.roberbarras.racha.ui.resources.provider_models_load
import io.github.roberbarras.racha.ui.resources.provider_name
import io.github.roberbarras.racha.ui.resources.provider_no_key
import io.github.roberbarras.racha.ui.resources.provider_remove
import io.github.roberbarras.racha.ui.resources.provider_remove_confirm
import io.github.roberbarras.racha.ui.resources.provider_test
import io.github.roberbarras.racha.ui.resources.provider_test_failed
import io.github.roberbarras.racha.ui.resources.provider_test_ok
import io.github.roberbarras.racha.ui.resources.provider_up
import io.github.roberbarras.racha.ui.resources.provider_url
import io.github.roberbarras.racha.ui.resources.save
import io.github.roberbarras.racha.ui.resources.section_summaries
import io.github.roberbarras.racha.ui.resources.summaries_add
import io.github.roberbarras.racha.ui.resources.summaries_free
import io.github.roberbarras.racha.ui.resources.summaries_intro
import io.github.roberbarras.racha.ui.resources.summaries_local
import io.github.roberbarras.racha.ui.resources.summaries_none
import io.github.roberbarras.racha.ui.resources.summaries_paid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** A provider check or model list in progress or done: null running, else the outcome. */
sealed interface ProviderCheck {
    data object Running : ProviderCheck
    data object Ok : ProviderCheck
    data class Failed(val reason: String) : ProviderCheck
}

class SummariesSettingsViewModel(private val providers: SummaryProviders, private val ai: SummarizeWithAi) : ViewModel() {
    val list: StateFlow<List<SummaryProvider>> = providers.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val keys = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val checks = MutableStateFlow<Map<String, ProviderCheck>>(emptyMap())
    val models = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    init {
        viewModelScope.launch { refreshKeys() }
    }

    private suspend fun refreshKeys() {
        keys.value = providers.list().associate { it.id to providers.hasKey(it.id) }
    }

    fun add(preset: ProviderPreset) = viewModelScope.launch { providers.add(preset); refreshKeys() }
    fun update(provider: SummaryProvider) = viewModelScope.launch { providers.update(provider) }
    fun move(id: String, by: Int) = viewModelScope.launch { providers.move(id, by) }
    fun remove(id: String) = viewModelScope.launch { providers.remove(id); refreshKeys() }
    fun setKey(id: String, key: String) = viewModelScope.launch { providers.setKey(id, key); refreshKeys() }

    fun test(provider: SummaryProvider) = viewModelScope.launch {
        checks.update { it + (provider.id to ProviderCheck.Running) }
        val reason = ai.test(provider)
        checks.update { it + (provider.id to if (reason == null) ProviderCheck.Ok else ProviderCheck.Failed(reason)) }
    }

    fun loadModels(provider: SummaryProvider) = viewModelScope.launch {
        checks.update { it + ("models:" + provider.id to ProviderCheck.Running) }
        val result = try {
            ai.models(provider).also { list -> models.update { it + (provider.id to list) } }
            ProviderCheck.Ok
        } catch (e: RemoteError) {
            ProviderCheck.Failed(SummarizeWithAi.reason(e))
        }
        checks.update { it + ("models:" + provider.id to result) }
    }
}

/** Settings → "Resúmenes con IA" (H17): the providers, tried in this order. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SummariesSection(deps: RachaDependencies) {
    val providers = deps.summaryProviders ?: return
    val ai = deps.summaries ?: return
    val vm = viewModel { SummariesSettingsViewModel(providers, ai) }
    val list by vm.list.collectAsStateWithLifecycle()
    val keys by vm.keys.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    SettingsSection(stringResource(Res.string.section_summaries), RachaIcons.Sparkles) {
        RText(stringResource(Res.string.summaries_intro), style = Racha.type.body, color = Racha.colors.inkMuted)
        if (list.isEmpty()) RText(stringResource(Res.string.summaries_none), style = Racha.type.bodyStrong)
        list.forEachIndexed { index, provider ->
            ProviderCard(vm, deps, provider, index, list.size, keys[provider.id] == true)
        }
        RButton(stringResource(Res.string.summaries_add), { adding = !adding }, kind = ButtonKind.Secondary, icon = RachaIcons.Plus)
        if (adding) {
            Column(verticalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
                ProviderPresets.all.forEach { preset ->
                    val shape = RoundedCornerShape(Racha.radius.m)
                    Row(
                        Modifier.fillMaxWidth().clip(shape).interactive({ vm.add(preset); adding = false }, shape).padding(Racha.space.m),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Racha.space.m),
                    ) {
                        RIconButton(RachaIcons.Plus, preset.name, { vm.add(preset); adding = false })
                        Column(Modifier.weight(1f)) {
                            RText(preset.name, style = Racha.type.bodyStrong)
                            if (preset.model.isNotEmpty()) RText(preset.model, style = Racha.type.caption, color = Racha.colors.inkMuted)
                        }
                        PresetTag(preset)
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetTag(preset: ProviderPreset) = when {
    !preset.needsKey -> Tag(stringResource(Res.string.summaries_local))
    preset.freeTier -> Tag(stringResource(Res.string.summaries_free), tone = TagTone.Success)
    preset.baseUrl.isNotEmpty() -> Tag(stringResource(Res.string.summaries_paid))
    else -> Unit
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderCard(vm: SummariesSettingsViewModel, deps: RachaDependencies, provider: SummaryProvider, index: Int, count: Int, hasKey: Boolean) {
    var open by remember(provider.id) { mutableStateOf(false) }
    val preset = ProviderPresets.byId(provider.presetId)
    val needsKey = preset?.needsKey != false
    val shape = RoundedCornerShape(Racha.radius.l)
    Column(
        Modifier.fillMaxWidth().clip(shape).border(1.dp, Racha.colors.border, shape).background(Racha.colors.surfaceSunken.copy(alpha = 0.4f))
            .padding(Racha.space.m),
        verticalArrangement = Arrangement.spacedBy(Racha.space.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.s)) {
            RText("${index + 1}", style = with(Racha.type) { label.numeric() }, color = Racha.colors.inkMuted)
            Column(Modifier.weight(1f)) {
                RText(provider.name, style = Racha.type.bodyStrong, maxLines = 1)
                RText(provider.model.ifEmpty { "—" }, style = Racha.type.caption, color = Racha.colors.inkMuted, maxLines = 1)
            }
            if (needsKey && !hasKey) Tag(stringResource(Res.string.provider_no_key))
            val enabledLabel = stringResource(Res.string.provider_enabled)
            RSwitch(provider.enabled, { vm.update(provider.copy(enabled = it)) }, Modifier.semantics { contentDescription = enabledLabel })
            if (index > 0) RIconButton(RachaIcons.ChevronDown, stringResource(Res.string.provider_up), { vm.move(provider.id, -1) }, Modifier.rotate(180f))
            if (index < count - 1) RIconButton(RachaIcons.ChevronDown, stringResource(Res.string.provider_down), { vm.move(provider.id, 1) })
            RIconButton(RachaIcons.Settings, stringResource(Res.string.provider_edit), { open = !open }, tint = if (open) Racha.colors.accent else Racha.colors.ink)
        }
        if (open) ProviderEditor(vm, deps, provider, preset, needsKey, hasKey)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderEditor(
    vm: SummariesSettingsViewModel, deps: RachaDependencies, provider: SummaryProvider, preset: ProviderPreset?, needsKey: Boolean, hasKey: Boolean,
) {
    val checks by vm.checks.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.m)) {
        TextRow(stringResource(Res.string.provider_name), provider.name, { vm.update(provider.copy(name = it.ifBlank { provider.name })) })
        TextRow(
            stringResource(Res.string.provider_url), provider.baseUrl, { vm.update(provider.copy(baseUrl = it)) }, null, KeyboardType.Uri,
            validate = { it.trim().startsWith("https://") || it.trim().startsWith("http://") },
        )
        TextRow(stringResource(Res.string.provider_model), provider.model, { vm.update(provider.copy(model = it)) })
        RButton(stringResource(Res.string.provider_models_load), { vm.loadModels(provider) }, kind = ButtonKind.Ghost, icon = RachaIcons.RefreshCw,
            enabled = checks["models:" + provider.id] != ProviderCheck.Running)
        (checks["models:" + provider.id] as? ProviderCheck.Failed)?.let {
            RText(stringResource(Res.string.provider_models_failed, it.reason), style = Racha.type.caption, color = Racha.colors.danger)
        }
        models[provider.id]?.takeIf { it.isNotEmpty() }?.let { list ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.xs), verticalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
                list.take(MAX_MODELS).forEach { model ->
                    val shape = RoundedCornerShape(Racha.radius.pill)
                    RText(
                        model,
                        Modifier.clip(shape).background(if (model == provider.model) Racha.colors.accentSoft else Racha.colors.surfaceSunken)
                            .interactive({ vm.update(provider.copy(model = model)) }, shape).padding(horizontal = Racha.space.m, vertical = Racha.space.xs),
                        style = Racha.type.caption,
                    )
                }
            }
        }
        if (needsKey) KeyEditor(vm, provider.id, hasKey)
        preset?.keysUrl?.let { url ->
            RButton(stringResource(Res.string.provider_get_key), { deps.platform.openUrl(url) }, kind = ButtonKind.Ghost, icon = RachaIcons.ExternalLink)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.m)) {
            val check = checks[provider.id]
            RButton(stringResource(Res.string.provider_test), { vm.test(provider) }, kind = ButtonKind.Secondary, enabled = check != ProviderCheck.Running && provider.usable)
            when (check) {
                ProviderCheck.Ok -> RText(stringResource(Res.string.provider_test_ok), style = Racha.type.body, color = Racha.colors.success)
                is ProviderCheck.Failed -> RText(stringResource(Res.string.provider_test_failed, check.reason), style = Racha.type.body, color = Racha.colors.danger)
                else -> Unit
            }
        }
        ActionRow(provider.name, null, stringResource(Res.string.provider_remove), { vm.remove(provider.id) },
            confirmText = stringResource(Res.string.provider_remove_confirm, provider.name), danger = true)
    }
}

/** The key goes to the keyring on "Save" and is never shown back: only whether there is one. */
@Composable
private fun KeyEditor(vm: SummariesSettingsViewModel, id: String, hasKey: Boolean) {
    var draft by remember(id) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Racha.space.m)) {
            RTextField(draft, { draft = it }, stringResource(Res.string.ai_key), Modifier.weight(1f), password = true)
            RButton(stringResource(Res.string.save), { vm.setKey(id, draft); draft = "" }, kind = ButtonKind.Secondary, enabled = draft.isNotBlank())
        }
        RText(stringResource(Res.string.ai_key_hint), style = Racha.type.caption, color = Racha.colors.inkMuted)
        if (hasKey) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.m)) {
                RText(stringResource(Res.string.ai_key_saved), style = Racha.type.body, color = Racha.colors.success, modifier = Modifier.weight(1f))
                RButton(stringResource(Res.string.ai_key_remove), { vm.setKey(id, "") }, kind = ButtonKind.Ghost)
            }
        }
    }
}

private const val MAX_MODELS = 60
