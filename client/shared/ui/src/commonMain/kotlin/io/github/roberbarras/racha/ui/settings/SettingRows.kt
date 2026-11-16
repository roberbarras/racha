package io.github.roberbarras.racha.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.roberbarras.racha.ui.common.IconBadge
import io.github.roberbarras.racha.ui.design.ButtonKind
import io.github.roberbarras.racha.ui.design.RButton
import io.github.roberbarras.racha.ui.design.RCard
import io.github.roberbarras.racha.ui.design.RIconButton
import io.github.roberbarras.racha.ui.design.RSwitch
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.RTextField
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.design.SegmentedToggle
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.cancel
import io.github.roberbarras.racha.ui.resources.confirm
import io.github.roberbarras.racha.ui.resources.decrease
import io.github.roberbarras.racha.ui.resources.increase
import io.github.roberbarras.racha.ui.resources.save
import org.jetbrains.compose.resources.stringResource

/** One section of Settings: a card with an icon, a title and its rows separated by hairlines. */
@Composable
fun SettingsSection(title: String, icon: ImageVector, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    RCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(Racha.space.l), verticalArrangement = Arrangement.spacedBy(Racha.space.l)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.m)) {
                IconBadge(icon)
                RText(title, style = Racha.type.heading)
            }
            content()
        }
    }
}

@Composable
fun Hairline() = Box(Modifier.fillMaxWidth().height(1.dp).background(Racha.colors.border))

@Composable
private fun Label(title: String, description: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Racha.space.xxs)) {
        RText(title, style = Racha.type.bodyStrong)
        description?.let { RText(it, style = Racha.type.caption, color = Racha.colors.inkMuted) }
    }
}

/** A setting that is on or off; the whole row toggles it. */
@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, description: String? = null, enabled: Boolean = true) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(Racha.radius.m)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .semantics(mergeDescendants = true) { contentDescription = title }
            .padding(vertical = Racha.space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Racha.space.m),
    ) {
        Label(title, description, Modifier.weight(1f))
        RSwitch(checked, null, enabled = enabled)
    }
}

/** A setting with a few named values. Wraps under the label when the row is narrow. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceRow(title: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit, description: String? = null) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Racha.space.m),
        verticalArrangement = Arrangement.spacedBy(Racha.space.s),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Label(title, description, Modifier.weight(1f).widthIn(min = 200.dp))
        SegmentedToggle(options, selected, onSelect)
    }
}

/** A number changed in steps: − value +. */
@Composable
fun StepperRow(title: String, value: String, onMinus: (() -> Unit)?, onPlus: (() -> Unit)?, description: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.s)) {
        Label(title, description, Modifier.weight(1f))
        RIconButton(RachaIcons.ChevronLeft, stringResource(Res.string.decrease) + " · " + title, { onMinus?.invoke() },
            tint = if (onMinus != null) Racha.colors.ink else Racha.colors.inkSubtle)
        RText(value, style = Racha.type.bodyStrong, modifier = Modifier.widthIn(min = 64.dp).semantics { stateDescription = value }, maxLines = 1)
        RIconButton(RachaIcons.ChevronRight, stringResource(Res.string.increase) + " · " + title, { onPlus?.invoke() },
            tint = if (onPlus != null) Racha.colors.ink else Racha.colors.inkSubtle)
    }
}

/** Free text saved with "Save" or the keyboard's done key (endpoints, proxy…), not on every keystroke. */
@Composable
fun TextRow(
    title: String,
    value: String,
    onSave: (String) -> Unit,
    description: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    validate: (String) -> Boolean = { true },
) {
    var draft by remember(value) { mutableStateOf(value) }
    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Racha.space.m)) {
            RTextField(
                draft, { draft = it }, title, Modifier.weight(1f), keyboardType,
                imeAction = ImeAction.Done, onSubmit = { if (validate(draft)) onSave(draft.trim()) },
            )
            RButton(stringResource(Res.string.save), { onSave(draft.trim()) }, kind = ButtonKind.Secondary, enabled = draft != value && validate(draft))
        }
        description?.let { RText(it, style = Racha.type.caption, color = Racha.colors.inkMuted) }
    }
}

/** An action (clear, export…) with an optional result line; [confirmText] asks once more before acting. */
@Composable
fun ActionRow(
    title: String,
    description: String?,
    button: String,
    onClick: () -> Unit,
    result: String? = null,
    confirmText: String? = null,
    danger: Boolean = false,
) {
    var asking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Racha.space.m)) {
            Label(title, description, Modifier.weight(1f))
            if (!asking) RButton(button, { if (confirmText != null) asking = true else onClick() }, kind = ButtonKind.Secondary)
        }
        if (asking && confirmText != null) ConfirmBox(confirmText, onConfirm = { asking = false; onClick() }, onCancel = { asking = false }, danger = danger)
        result?.let { RText(it, style = Racha.type.caption, color = Racha.colors.success) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfirmBox(text: String, onConfirm: () -> Unit, onCancel: () -> Unit, danger: Boolean = false) {
    Column(
        Modifier.fillMaxWidth().background(if (danger) Racha.colors.danger.copy(alpha = 0.10f) else Racha.colors.accentSoft, androidx.compose.foundation.shape.RoundedCornerShape(Racha.radius.m))
            .padding(Racha.space.m),
        verticalArrangement = Arrangement.spacedBy(Racha.space.s),
    ) {
        RText(text, style = Racha.type.body)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.s), verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
            RButton(stringResource(Res.string.confirm), onConfirm)
            RButton(stringResource(Res.string.cancel), onCancel, kind = ButtonKind.Secondary)
        }
    }
}
