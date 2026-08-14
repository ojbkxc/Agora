package com.lxseek.chat.ui.chat.bottombar

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.viewmodel.VoiceConversationController

@Composable
internal fun VoiceMicButton(
    state: VoiceConversationController.State,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActive = state != VoiceConversationController.State.IDLE
    val isListening = state == VoiceConversationController.State.LISTENING ||
        state == VoiceConversationController.State.TRANSCRIBING

    val transition = rememberInfiniteTransition(label = "micPulse")
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "micPulseScale",
    )

    val haloAlpha by transition.animateFloat(
        initialValue = if (isListening) 0.4f else 0f,
        targetValue = if (isListening) 0f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "micHaloAlpha",
    )

    val haloScale by transition.animateFloat(
        initialValue = if (isListening) 1f else 1f,
        targetValue = if (isListening) 1.8f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "micHaloScale",
    )

    val containerColor = when (state) {
        VoiceConversationController.State.IDLE -> MaterialTheme.colorScheme.surfaceVariant
        VoiceConversationController.State.LISTENING -> MaterialTheme.colorScheme.error
        VoiceConversationController.State.TRANSCRIBING -> MaterialTheme.colorScheme.tertiary
        VoiceConversationController.State.PROCESSING -> MaterialTheme.colorScheme.tertiary
        VoiceConversationController.State.SPEAKING -> MaterialTheme.colorScheme.secondary
    }
    val contentColor = if (isActive) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val contentDescription = if (isActive) {
        stringResource(R.string.voice_conversation_tap_to_stop)
    } else {
        stringResource(R.string.voice_conversation_tap_to_speak)
    }

    Box(
        modifier = modifier.size(56.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (isListening) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .scale(haloScale)
                    .graphicsLayer { alpha = haloAlpha },
            ) {
                androidx.compose.foundation.Canvas(modifier = Modifier.size(46.dp)) {
                    drawCircle(color = Color.Red.copy(alpha = 0.3f))
                }
            }
        }
        FloatingActionButton(
            onClick = onClick,
            containerColor = containerColor,
            contentColor = contentColor,
            modifier = Modifier.size(46.dp).scale(pulseScale),
            shape = CircleShape,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = if (isActive) 8.dp else 2.dp,
                pressedElevation = 4.dp,
                focusedElevation = 4.dp,
                hoveredElevation = 4.dp,
            ),
        ) {
            Crossfade(
                targetState = isActive,
                animationSpec = tween(durationMillis = 200, easing = LinearEasing),
                label = "micIcon",
            ) { active ->
                Icon(
                    imageVector = if (active) Icons.Default.Mic else Icons.Default.MicNone,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
