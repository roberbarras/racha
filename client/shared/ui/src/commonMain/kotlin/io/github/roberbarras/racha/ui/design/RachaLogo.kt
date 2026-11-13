package io.github.roberbarras.racha.ui.design

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** App mark (same geometry as the Android adaptive icon): ink "R" on an ember rounded square. */
val RachaLogo: ImageVector by lazy {
    ImageVector.Builder("racha", 108.dp, 108.dp, 108f, 108f).apply {
        addPath(addPathNodes("M24 8h60a16 16 0 0 1 16 16v60a16 16 0 0 1-16 16h-60a16 16 0 0 1-16-16v-60a16 16 0 0 1 16-16z"), fill = SolidColor(Tokens.Light.accent))
        addPath(
            addPathNodes("M42 32L42 76M42 32L58 32A12 12 0 0 1 58 56L42 56M55 56L68 76"),
            stroke = SolidColor(Tokens.Light.ink),
            strokeLineWidth = 8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()
}
