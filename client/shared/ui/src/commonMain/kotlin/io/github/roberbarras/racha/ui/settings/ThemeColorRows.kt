package io.github.roberbarras.racha.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.roberbarras.racha.ui.common.parseColor
import io.github.roberbarras.racha.ui.design.ButtonKind
import io.github.roberbarras.racha.ui.design.RButton
import io.github.roberbarras.racha.ui.design.RIcon
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.RTextField
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaIcons
import io.github.roberbarras.racha.ui.design.interactive
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.color_apply
import io.github.roberbarras.racha.ui.resources.color_custom
import io.github.roberbarras.racha.ui.resources.color_default
import io.github.roberbarras.racha.ui.resources.color_hex
import org.jetbrains.compose.resources.stringResource

private val HEX = Regex("#[0-9A-Fa-f]{6}")

/** A swatch: [value] is what is stored ("" for Racha's own colour), [shown] what is painted. */
data class Swatch(val value: String, val shown: String)

/** Accents (the first one is Racha's). */
val ACCENT_SWATCHES = listOf("#E8812A", "#E05A47", "#D65DB1", "#7B61FF", "#3A86FF", "#2A9D8F", "#2D6A4F", "#C9A227").map { Swatch(it, it) }

/** Background tones: Racha's warm cream, then cool, blue, green, lavender, rose, sand and neutral grey. */
val BACKGROUND_SWATCHES = listOf(Swatch("", "#F6F1E9")) +
    listOf("#E6EBF1", "#DCE7F6", "#DFEFE2", "#E9E3F5", "#F5E1E8", "#F0E6D2", "#ECECEC").map { Swatch(it, it) }

/** Bar colours: Racha's night blue, then black, forest, wine, plum, petrol and coffee. */
val RAIL_SWATCHES = listOf(Swatch("", "#1B2437")) +
    listOf("#121212", "#14301F", "#3A1222", "#2A1B45", "#0E2F3A", "#2E2016").map { Swatch(it, it) }

/**
 * One of the theme's colours (Settings → General): swatches, the first being Racha's, and any other colour as
 * `#RRGGBB`. [value] "" is Racha's own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorRow(title: String, description: String?, swatches: List<Swatch>, value: String, onSelect: (String) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    val isPreset = swatches.any { it.value.equals(value, ignoreCase = true) }
    Column(verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Racha.space.m), verticalArrangement = Arrangement.spacedBy(Racha.space.s),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).widthIn(min = 160.dp), verticalArrangement = Arrangement.spacedBy(Racha.space.xxs)) {
                RText(title, style = Racha.type.bodyStrong)
                description?.let { RText(it, style = Racha.type.caption, color = Racha.colors.inkMuted) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Racha.space.s), verticalArrangement = Arrangement.spacedBy(Racha.space.s)) {
                val defaultName = stringResource(Res.string.color_default)
                swatches.forEach { swatch ->
                    Dot(swatch.shown, swatch.value.equals(value, ignoreCase = true), if (swatch.value.isEmpty()) defaultName else swatch.value) { onSelect(swatch.value) }
                }
                // A colour typed by hand shows as one more swatch, selected.
                if (!isPreset && HEX.matches(value)) Dot(value, true, value) { custom = true }
                val customName = stringResource(Res.string.color_custom)
                Box(
                    Modifier.size(32.dp).clip(CircleShape).border(1.dp, Racha.colors.borderStrong, CircleShape)
                        .interactive({ custom = !custom }, CircleShape).semantics { contentDescription = customName },
                    contentAlignment = Alignment.Center,
                ) { RIcon(RachaIcons.Plus, null, tint = Racha.colors.inkMuted, size = 16.dp) }
            }
        }
        if (custom) HexField(value.takeIf { HEX.matches(it) } ?: "#") { onSelect(it.uppercase()); custom = false }
    }
}

@Composable
private fun Dot(hex: String, selected: Boolean, name: String, onClick: () -> Unit) {
    // The chosen colour gets a ring in the text colour, so it is clear without relying on size alone.
    Box(
        Modifier.size(32.dp).clip(CircleShape)
            .then(if (selected) Modifier.border(2.dp, Racha.colors.ink, CircleShape) else Modifier.border(1.dp, Racha.colors.border, CircleShape))
            .padding(if (selected) 4.dp else 2.dp).clip(CircleShape)
            .background(parseColor(hex))
            .interactive(onClick, CircleShape)
            .semantics { contentDescription = name; this.selected = selected },
    )
}

@Composable
private fun HexField(initial: String, onApply: (String) -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    val valid = HEX.matches(draft.trim())
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Racha.space.m)) {
        if (valid) Box(Modifier.size(32.dp).clip(CircleShape).background(parseColor(draft.trim())))
        RTextField(
            draft, { draft = it.take(7) }, stringResource(Res.string.color_hex), Modifier.weight(1f),
            imeAction = ImeAction.Done, onSubmit = { if (valid) onApply(draft.trim()) },
        )
        RButton(stringResource(Res.string.color_apply), { onApply(draft.trim()) }, kind = ButtonKind.Secondary, enabled = valid)
    }
}
