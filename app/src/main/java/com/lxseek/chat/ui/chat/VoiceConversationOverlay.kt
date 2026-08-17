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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.viewmodel.VoiceConversationController

/**
 * Full-screen overlay for the real-time voice conversation. Replaces the chat
 * surface with a dark backdrop, a central 200dp pulsing orb that visualises the
 * listening/speaking state, and a top-corner exit button.
 */
@Composable
internal fun VoiceConversationOverlay(
    state: VoiceConversationController.State,
    partialTranscript: String,
    amplitude: Float,
    onExit: () -> Unit,
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
    val stateColor = when (state) {
        VoiceConversationController.State.LISTENING -> MaterialTheme.colorScheme.error
        VoiceConversationController.State.TRANSCRIBING -> MaterialTheme.colorScheme.tertiary
        VoiceConversationController.State.PROCESSING -> MaterialTheme.colorScheme.tertiary
        VoiceConversationController.State.SPEAKING -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    val stateIcon = when (state) {
        VoiceConversationController.State.LISTENING -> Icons.Default.Mic
        VoiceConversationController.State.TRANSCRIBING -> Icons.Default.GraphicEq
        VoiceConversationController.State.PROCESSING -> Icons.Default.Lightbulb
        VoiceConversationController.State.SPEAKING -> Icons.Default.VolumeUp
        else -> Icons.Default.Mic
    }

    val transition = rememberInfiniteTransition(label = "voiceOverlayPulse")
    val ringScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "voiceOverlayRingScale",
    )
    val ringAlpha by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "voiceOverlayRingAlpha",
    )
    val orbScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (state == VoiceConversationController.State.LISTENING) 1.04f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voiceOverlayOrbScale",
    )

    AnimatedVisibility(
        visible = isActive,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = onExit,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp)
                    .size(44.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.voice_conversation_exit),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(200.dp),
                ) {
                    if (state == VoiceConversationController.State.LISTENING) {
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .scale(ringScale)
                                .alpha(ringAlpha),
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = stateColor,
                                modifier = Modifier.fillMaxSize(),
                            ) {}
                        }
                    }
                    Surface(
                        shape = CircleShape,
                        color = stateColor,
                        modifier = Modifier
                            .size(160.dp)
                            .scale(orbScale),
                        tonalElevation = 6.dp,
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            if (state == VoiceConversationController.State.LISTENING ||
                                state == VoiceConversationController.State.TRANSCRIBING) {
                                VoiceWaveformIndicator(
                                    amplitude = if (state == VoiceConversationController.State.LISTENING) amplitude else 0.2f,
                                    color = Color.White,
                                    modifier = Modifier.scale(2.2f),
                                )
                            } else {
                                Icon(
                                    imageVector = stateIcon,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(56.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = stateText,
                    style = MaterialTheme.typography.titleMedium,
                    color = stateColor,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
                if (state == VoiceConversationController.State.LISTENING && partialTranscript.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "\u201C$partialTranscript\u201D",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}