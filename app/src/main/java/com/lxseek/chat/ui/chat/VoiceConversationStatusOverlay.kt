package com.lxseek.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.viewmodel.VoiceConversationController

@Composable
internal fun VoiceConversationStatusOverlay(
    state: VoiceConversationController.State,
    partialTranscript: String,
    amplitude: Float,
    modifier: Modifier = Modifier,
) {
    val isActive = state != VoiceConversationController.State.IDLE
    if (!isActive) return

    val stateText = when (state) {
        VoiceConversationController.State.LISTENING -> stringResource(R.string.voice_conversation_listening)
        VoiceConversationController.State.TRANSCRIBING -> stringResource(R.string.asr_remote_transcribing)
        VoiceConversationController.State.PROCESSING -> stringResource(R.string.voice_conversation_processing)
        VoiceConversationController.State.SPEAKING -> stringResource(R.string.voice_conversation_speaking)
        else -> ""
    }
    val stateIcon = when (state) {
        VoiceConversationController.State.LISTENING -> Icons.Default.GraphicEq
        VoiceConversationController.State.TRANSCRIBING -> Icons.Default.GraphicEq
        VoiceConversationController.State.PROCESSING -> Icons.Default.Lightbulb
        VoiceConversationController.State.SPEAKING -> Icons.Default.VolumeUp
        else -> Icons.Default.RecordVoiceOver
    }
    val stateColor = when (state) {
        VoiceConversationController.State.LISTENING -> MaterialTheme.colorScheme.error
        VoiceConversationController.State.TRANSCRIBING -> MaterialTheme.colorScheme.tertiary
        VoiceConversationController.State.PROCESSING -> MaterialTheme.colorScheme.tertiary
        VoiceConversationController.State.SPEAKING -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }

    val transition = rememberInfiniteTransition(label = "voiceStatusPulse")
    val iconAlpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voiceStatusIconAlpha",
    )

    AnimatedVisibility(
        visible = isActive,
        enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it },
        exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it },
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = stateIcon,
                        contentDescription = null,
                        tint = stateColor,
                        modifier = Modifier.size(20.dp).alpha(iconAlpha),
                    )
                    Text(
                        text = stateText,
                        style = MaterialTheme.typography.labelLarge,
                        color = stateColor,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    )
                }
                if (state == VoiceConversationController.State.LISTENING ||
                    state == VoiceConversationController.State.TRANSCRIBING) {
                    VoiceWaveformIndicator(
                        amplitude = if (state == VoiceConversationController.State.LISTENING) amplitude else 0.15f,
                        color = stateColor,
                    )
                }
                if (state == VoiceConversationController.State.LISTENING && partialTranscript.isNotBlank()) {
                    Text(
                        text = "\u201C$partialTranscript\u201D",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 28.dp),
                    )
                }
            }
        }
    }
}
