package com.lxseek.chat.ui.chat.bottombar

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.viewmodel.VoiceConversationController

/**
 * Mic FAB for continuous voice conversation. Pulsates while listening,
 * shows a distinct color while the assistant is processing/speaking.
 */
@Composable
internal fun VoiceMicButton(
    state: VoiceConversationController.State,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActive = state != VoiceConversationController.State.IDLE
    val isListening = state == VoiceConversationController.State.LISTENING

    val pulseScale by if (isListening) {
        rememberInfiniteTransition(label = "micPulse").animateFloat(
            initialValue = 1f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "micPulseScale",
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(1f) }
    }

    val containerColor = when (state) {
        VoiceConversationController.State.IDLE -> MaterialTheme.colorScheme.surfaceVariant
        VoiceConversationController.State.LISTENING -> MaterialTheme.colorScheme.error
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

    FloatingActionButton(
        onClick = onClick,
        containerColor = containerColor,
        contentColor = contentColor,
        modifier = modifier.size(46.dp).scale(pulseScale),
        shape = CircleShape,
        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
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
