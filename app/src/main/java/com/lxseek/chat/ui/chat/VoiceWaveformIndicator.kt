package com.lxseek.chat.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.sin

@Composable
internal fun VoiceWaveformIndicator(
    amplitude: Float,
    color: Color,
    modifier: Modifier = Modifier,
    ballSize: Float = 6.8f,
    maxJump: Float = 14f,
    gap: Float = 20f,
) {
    val transition = rememberInfiniteTransition(label = "waveformTime")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(16000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "waveformT",
    )

    val smoothAmp = remember { Animatable(0f) }
    LaunchedEffect(amplitude) {
        smoothAmp.animateTo(
            targetValue = amplitude.coerceIn(0f, 1f),
            animationSpec = tween(150),
        )
    }

    val density = LocalDensity.current
    val timeAxis = t * 0.06f
    val idle = 0.16f + 0.06f * sin(timeAxis.toDouble()).toFloat()
    val speak = (smoothAmp.value * 1.25f).coerceIn(0f, 1f)

    val p1 = idle + speak * wave01(timeAxis + 0.0f)
    val p2 = idle + speak * wave01(timeAxis + 0.9f)
    val p3 = idle + speak * wave01(timeAxis + 1.8f)

    Canvas(modifier = modifier.size(width = (gap * 2 + ballSize * 2).dp, height = (maxJump * 2 + ballSize * 2).dp)) {
        val cy = size.height / 2f
        val cx = size.width / 2f
        val gapPx = with(density) { gap.dp.toPx() }
        val baseRPx = with(density) { ballSize.dp.toPx() }
        val maxJumpPx = with(density) { maxJump.dp.toPx() }

        val x1 = cx - gapPx
        val x2 = cx
        val x3 = cx + gapPx

        val y1 = cy - bezierArcY(p1.coerceIn(0f, 1f)) * maxJumpPx
        val y2 = cy - bezierArcY(p2.coerceIn(0f, 1f)) * maxJumpPx
        val y3 = cy - bezierArcY(p3.coerceIn(0f, 1f)) * maxJumpPx

        val r1 = baseRPx * (1f + 0.18f * p1)
        val r2 = baseRPx * (1f + 0.18f * p2)
        val r3 = baseRPx * (1f + 0.18f * p3)

        drawCircle(color = color, radius = r1, center = Offset(x1, y1))
        drawCircle(color = color, radius = r2, center = Offset(x2, y2))
        drawCircle(color = color, radius = r3, center = Offset(x3, y3))
    }
}

private fun wave01(t: Float): Float {
    return ((sin(t.toDouble()) + 1.0) * 0.5).toFloat()
}

private fun bezierArcY(t: Float): Float {
    val u = 1f - t
    return 2f * u * t
}
