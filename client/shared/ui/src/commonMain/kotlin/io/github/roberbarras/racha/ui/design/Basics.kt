package io.github.roberbarras.racha.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Text in the design system: style from the type scale, ink color by default, ellipsis on overflow. */
@Composable
fun RText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Racha.type.body,
    color: Color = Racha.colors.ink,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.merge(TextStyle(color = color, textAlign = textAlign ?: TextAlign.Unspecified)),
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines,
    )
}

/** A Lucide stroke icon tinted with [tint]. [contentDescription] null for decorative icons. */
@Composable
fun RIcon(icon: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = Racha.colors.ink, size: Dp = 20.dp) {
    Image(
        painter = rememberVectorPainter(icon),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

/**
 * Click handling shared by every interactive element: a subtle ink overlay on hover/press (instead of a ripple)
 * and a visible focus ring for keyboard navigation.
 */
@Composable
fun Modifier.interactive(
    onClick: () -> Unit,
    shape: Shape = RectangleShape,
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    overlay: Color = Racha.colors.ink,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val tint by animateColorAsState(
        when {
            !enabled -> Color.Transparent
            pressed -> overlay.copy(alpha = 0.10f)
            hovered -> overlay.copy(alpha = 0.05f)
            else -> Color.Transparent
        },
        tween(Racha.duration(Racha.motion.fast)),
    )
    val ring = Racha.colors.focus
    return this
        .clip(shape)
        .then(if (focused) Modifier.border(2.dp, ring, shape) else Modifier)
        .hoverable(source, enabled)
        .clickable(source, indication = null, enabled = enabled, onClickLabel = onClickLabel, role = role, onClick = onClick)
        .drawWithContent {
            drawContent()
            drawRect(tint)
        }
}
