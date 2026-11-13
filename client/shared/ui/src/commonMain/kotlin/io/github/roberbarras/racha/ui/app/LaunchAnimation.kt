package io.github.roberbarras.racha.ui.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import io.github.roberbarras.racha.ui.design.RText
import io.github.roberbarras.racha.ui.design.Racha
import io.github.roberbarras.racha.ui.design.RachaFlame
import io.github.roberbarras.racha.ui.resources.Res
import io.github.roberbarras.racha.ui.resources.app_name
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource

/** Brand background of the app icon, shared with the Android system splash (`racha_navy`). */
private val SplashNavy = Color(0xFF212A41)

/**
 * Cold-start animation (~2 s) that continues the system splash: the emblem settles, a light sweeps over the flame,
 * the name and a loading line appear. The app keeps loading underneath; the overlay leaves once content is ready
 * (waiting at most 1.5 s more), and collapses to a short fade when the system asks to reduce motion.
 */
@Composable
fun LaunchAnimation(contentReady: Boolean, onFinished: () -> Unit) {
    val reduce = Racha.reduceMotion
    val ready by rememberUpdatedState(contentReady)
    val intro = remember { Animatable(0f) }
    val sweep = remember { Animatable(-0.6f) }
    val loading = remember { Animatable(0f) }
    val exit = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        if (!reduce) {
            launch { sweep.animateTo(1.6f, tween(1100, delayMillis = 350)) }
            launch { loading.animateTo(1f, tween(1600, easing = FastOutSlowInEasing)) }
            intro.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
            kotlinx.coroutines.delay(700)
        } else {
            intro.snapTo(1f)
            loading.snapTo(1f)
        }
        withTimeoutOrNull(1_500) { snapshotFlow { ready }.first { it } }
        exit.animateTo(0f, tween(if (reduce) 150 else 350))
        onFinished()
    }
    val t = intro.value
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = exit.value }.background(SplashNavy), contentAlignment = Alignment.Center) {
        Image(
            painter = rememberVectorPainter(RachaFlame),
            contentDescription = null,
            modifier = Modifier
                // Android 12+ draws an icon without background at 288 dp, the emblem filling 66/108 of it (~176 dp),
                // centred in the window: start exactly there so the hand-over from the system splash is invisible.
                .size(176.dp)
                .graphicsLayer {
                    val scale = 1f - 0.3f * t
                    scaleX = scale
                    scaleY = scale
                    translationY = -56.dp.toPx() * t
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    val x = size.width * sweep.value
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent),
                            start = Offset(x - size.width * 0.3f, 0f),
                            end = Offset(x + size.width * 0.3f, size.height),
                        ),
                        blendMode = BlendMode.SrcAtop,
                    )
                },
        )
        Column(
            Modifier.offset(y = 88.dp).graphicsLayer {
                alpha = ((t - 0.35f) / 0.65f).coerceIn(0f, 1f)
                translationY = 12.dp.toPx() * (1f - t)
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RText(stringResource(Res.string.app_name), style = Racha.type.display, color = Color(0xFFF2EDE4))
            Box(Modifier.width(96.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(loading.value).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFE8812A)))
            }
        }
    }
}
