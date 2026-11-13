package io.github.roberbarras.racha.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ButtonKind { Primary, Secondary, Ghost }

@Composable
fun RButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = Racha.colors
    val (background, content, border) = when (kind) {
        ButtonKind.Primary -> Triple(colors.accent, colors.onAccent, Color.Transparent)
        ButtonKind.Secondary -> Triple(colors.surface, colors.ink, colors.borderStrong)
        ButtonKind.Ghost -> Triple(Color.Transparent, colors.ink, Color.Transparent)
    }
    val shape = RoundedCornerShape(Racha.radius.pill)
    Row(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (enabled) background else colors.surfaceSunken)
            .border(1.dp, border, shape)
            .interactive(onClick, shape, enabled)
            .padding(horizontal = Racha.space.l, vertical = Racha.space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Racha.space.s, Alignment.CenterHorizontally),
    ) {
        if (icon != null) RIcon(icon, null, tint = if (enabled) content else colors.inkSubtle, size = 18.dp)
        RText(text, style = Racha.type.label, color = if (enabled) content else colors.inkSubtle, maxLines = 1)
    }
}

@Composable
fun RIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Racha.colors.ink) {
    Box(
        modifier = modifier.size(40.dp).interactive(onClick, CircleShape),
        contentAlignment = Alignment.Center,
    ) { RIcon(icon, contentDescription, tint = tint) }
}

/** Two or more mutually exclusive options in a pill track (e.g. Study / Leisure). */
@Composable
fun <T> SegmentedToggle(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val colors = Racha.colors
    val outer = RoundedCornerShape(Racha.radius.pill)
    Row(
        modifier = modifier.clip(outer).background(colors.surfaceSunken).border(1.dp, colors.border, outer).padding(Racha.space.xs),
        horizontalArrangement = Arrangement.spacedBy(Racha.space.xxs),
    ) {
        options.forEach { (label, value) ->
            val isSelected = value == selected
            Box(
                modifier = Modifier
                    .clip(outer)
                    .background(if (isSelected) colors.accent else Color.Transparent)
                    .interactive({ onSelect(value) }, outer, role = Role.Tab)
                    .semantics { this.selected = isSelected }
                    .padding(horizontal = Racha.space.l, vertical = Racha.space.s - 2.dp),
            ) {
                RText(label, style = Racha.type.label, color = if (isSelected) colors.onAccent else colors.inkMuted, maxLines = 1)
            }
        }
    }
}

/** Progress as a thin bar; fraction in 0..1. Completed items turn the success color. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 4.dp, completed: Boolean = false, label: String? = null) {
    val colors = Racha.colors
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(Racha.duration(Racha.motion.slow)))
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(Racha.radius.pill))
            .background(colors.progressTrack)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
                if (label != null) contentDescription = label
            },
    ) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(animated).clip(RoundedCornerShape(Racha.radius.pill))
                .background(if (completed) colors.success else colors.accent),
        )
    }
}

enum class TagTone { Neutral, Accent, Success }

@Composable
fun Tag(text: String, modifier: Modifier = Modifier, tone: TagTone = TagTone.Neutral) {
    val colors = Racha.colors
    val (background, content) = when (tone) {
        TagTone.Neutral -> colors.surfaceSunken to colors.inkMuted
        TagTone.Accent -> colors.accentSoft to colors.ink
        TagTone.Success -> colors.success.copy(alpha = 0.15f) to colors.success
    }
    RText(
        text = text,
        modifier = modifier.clip(RoundedCornerShape(Racha.radius.s)).background(background)
            .padding(horizontal = Racha.space.s, vertical = Racha.space.xxs),
        style = Racha.type.caption,
        color = content,
        maxLines = 1,
    )
}

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    shortcutHint: String? = null,
    onSubmit: () -> Unit = {},
) {
    val colors = Racha.colors
    val shape = RoundedCornerShape(Racha.radius.m)
    Row(
        modifier = modifier.heightIn(min = 44.dp).clip(shape).background(colors.surface).border(1.dp, colors.border, shape)
            .padding(horizontal = Racha.space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Racha.space.s),
    ) {
        RIcon(RachaIcons.Search, null, tint = colors.inkSubtle, size = 18.dp)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) RText(placeholder, style = Racha.type.body, color = colors.inkSubtle, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = Racha.type.body.merge(androidx.compose.ui.text.TextStyle(color = colors.ink)),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
            )
        }
        if (shortcutHint != null && value.isEmpty()) Tag(shortcutHint)
    }
}

/** Uppercase row header ("CONTINUE WATCHING") with optional trailing content. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RText(title.uppercase(), style = Racha.type.overline, color = Racha.colors.inkMuted, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Streak as a row of day pips: filled for active days in the current run, up to [goal]. */
@Composable
fun StreakMeter(days: Int, modifier: Modifier = Modifier, goal: Int = 7, onDark: Boolean = false) {
    val colors = Racha.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Racha.space.xs)) {
        repeat(goal) { index ->
            Box(
                Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(Racha.radius.pill))
                    .background(if (index < days.coerceAtMost(goal)) colors.accent else if (onDark) colors.onRailMuted.copy(alpha = 0.35f) else colors.progressTrack),
            )
        }
    }
}

@Composable
fun VSpace(height: Dp) = Spacer(Modifier.height(height))

@Composable
fun HSpace(width: Dp) = Spacer(Modifier.width(width))
