package com.lxseek.chat.ui.chat.bottombar

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.viewmodel.VoiceInputController

@Composable
internal fun VoiceInputButton(
    state: VoiceInputController.State,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isListening = state == VoiceInputController.State.LISTENING
    val isError = state == VoiceInputController.State.ERROR

    val transition = rememberInfiniteTransition(label = "voiceInputPulse")
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.2f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voiceInputScale",
    )

    val tint = when {
        isError -> MaterialTheme.colorScheme.error
        isListening -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(40.dp),
    ) {
        Crossfade(
            targetState = isListening,
            animationSpec = tween(200),
            label = "voiceInputIcon",
        ) { listening ->
            Icon(
                imageVector = if (listening) Icons.Default.Mic else Icons.Default.MicNone,
                contentDescription = stringResource(
                    if (isListening) R.string.voice_input_listening else R.string.voice_input_tap
                ),
                tint = tint,
                modifier = Modifier.size(22.dp).scale(pulseScale),
            )
        }
    }
}
