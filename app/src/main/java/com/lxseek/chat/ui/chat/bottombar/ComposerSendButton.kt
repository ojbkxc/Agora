package com.lxseek.chat.ui.chat.bottombar

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import com.lxseek.chat.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.lxseek.chat.R
import com.lxseek.chat.model.SelectedAttachment
import com.lxseek.chat.ui.common.LocalAgoraHaptics
import com.lxseek.chat.ui.chat.message.COMPOSER_ICON_CROSSFADE_DURATION_MS
import com.lxseek.chat.viewmodel.SendAcceptance
import com.lxseek.chat.viewmodel.VoiceConversationController
import kotlinx.coroutines.launch

private enum class ComposerActionIcon {
    STOPPING,
    PENDING,
    STOP,
    SEND,
    MIC,
    VOICE_STOP,
}

/**
 * Unified composer action FAB (48dp circle). ChatGPT-style: when the composer is
 * empty the button is a mic that starts the real-time voice conversation overlay;
 * when there is text (or single ASR is recording) it becomes a send button; while
 * the LLM is generating or a voice conversation is active it becomes a stop button.
 */
@Composable
internal fun ComposerSendButton(
    textFieldState: TextFieldState,
    composer: ChatComposerState,
    isLoading: Boolean,
    isCompacting: Boolean = false,
    isSwitching: Boolean,
    isStopping: Boolean = false,
    isModelValid: Boolean,
    voiceConversationState: VoiceConversationController.State = VoiceConversationController.State.IDLE,
    voiceConversationEnabled: Boolean = false,
    voiceConversationActive: Boolean = false,
    singleAsrRecording: Boolean = false,
    onSendMessage: suspend (
        String,
        List<SelectedAttachment>,
        suspend () -> Unit,
    ) -> SendAcceptance?,
    onStopGeneration: () -> Unit,
    onCollapse: () -> Unit,
    onVoiceConversationToggle: () -> Unit = {},
    onStopSingleAsr: () -> Unit = {},
) {
    val haptics = LocalAgoraHaptics.current
    val submitScope = rememberCoroutineScope()
    var isSubmitting by remember { mutableStateOf(false) }
    val anyProcessing = composer.processingStates.isNotEmpty()

    suspend fun submit(
        submittedText: String,
        submittedAttachments: List<SelectedAttachment>,
    ) {
        val submittedAttachmentIds = submittedAttachments.map { it.localId }
        isSubmitting = true
        try {
            onSendMessage(
                submittedText,
                submittedAttachments,
            ) {
                if (composer.selectedAttachments.map { it.localId } == submittedAttachmentIds) {
                    composer.clearAttachments()
                }
                if (textFieldState.text.toString() == submittedText) {
                    textFieldState.edit { replace(0, length, "") }
                }
                composer.pendingSend = false
                isSubmitting = false
                onCollapse()
            }
        } finally {
            isSubmitting = false
        }
    }

    LaunchedEffect(composer.pendingSend, anyProcessing) {
        if (composer.pendingSend && !anyProcessing) {
            val submittedText = textFieldState.text.toString()
            val submittedAttachments = composer.selectedAttachments.toList()
            submit(submittedText, submittedAttachments)
            composer.pendingSend = false
        }
    }
    val textIsEmpty = textFieldState.text.isBlank()
    val attachmentsIsEmpty = composer.selectedAttachments.isEmpty()
    val showStop = isLoading && !isStopping && textIsEmpty && attachmentsIsEmpty

    val canSend = (textFieldState.text.isNotBlank() || composer.selectedAttachments.isNotEmpty()) && isModelValid && !isSwitching && !isStopping && !isCompacting && !isSubmitting
            && composer.selectedAttachments.none { it.localPath == null && (it.type == "image" || it.type == "file") }
    val isBusy = isStopping || isCompacting || isSubmitting || composer.pendingSend
    val isActionable = (isLoading || canSend || voiceConversationActive || singleAsrRecording || voiceConversationEnabled) && !isSwitching && !isBusy

    val fabIcon = when {
        isStopping || isCompacting || isSubmitting -> ComposerActionIcon.STOPPING
        composer.pendingSend -> ComposerActionIcon.PENDING
        showStop -> ComposerActionIcon.STOP
        voiceConversationActive -> ComposerActionIcon.VOICE_STOP
        singleAsrRecording -> ComposerActionIcon.SEND
        canSend -> ComposerActionIcon.SEND
        else -> ComposerActionIcon.MIC
    }

    val containerColor by animateColorAsState(
        targetValue = when (fabIcon) {
            ComposerActionIcon.MIC -> MaterialTheme.colorScheme.surfaceVariant
            ComposerActionIcon.STOPPING, ComposerActionIcon.PENDING -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(durationMillis = 400),
        label = "fabContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = when (fabIcon) {
            ComposerActionIcon.MIC -> MaterialTheme.colorScheme.onSurfaceVariant
            ComposerActionIcon.STOPPING, ComposerActionIcon.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onPrimary
        },
        animationSpec = tween(durationMillis = 400),
        label = "fabContent",
    )
    FloatingActionButton(
        onClick = {
            if (isSwitching || isStopping) return@FloatingActionButton
            when (fabIcon) {
                ComposerActionIcon.STOPPING, ComposerActionIcon.PENDING -> {}
                ComposerActionIcon.STOP -> onStopGeneration()
                ComposerActionIcon.VOICE_STOP -> onVoiceConversationToggle()
                ComposerActionIcon.MIC -> onVoiceConversationToggle()
                ComposerActionIcon.SEND -> {
                    if (singleAsrRecording) {
                        onStopSingleAsr()
                        return@FloatingActionButton
                    }
                    if (composer.pendingSend) {
                        haptics.selection()
                        composer.pendingSend = false
                        return@FloatingActionButton
                    }
                    if (canSend) {
                        if (anyProcessing) {
                            composer.pendingSend = true
                        } else {
                            val submittedText = textFieldState.text.toString()
                            val submittedAttachments = composer.selectedAttachments.toList()
                            submitScope.launch {
                                submit(submittedText, submittedAttachments)
                            }
                        }
                    }
                }
            }
        },
        containerColor = containerColor,
        contentColor = contentColor,
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = if (fabIcon == ComposerActionIcon.MIC) 0.dp else 2.dp,
            pressedElevation = 2.dp,
            focusedElevation = 2.dp,
            hoveredElevation = 2.dp,
        ),
    ) {
        Crossfade(
            targetState = fabIcon,
            animationSpec = tween(
                durationMillis = COMPOSER_ICON_CROSSFADE_DURATION_MS,
                easing = LinearEasing,
            ),
            label = "composerActionIcon",
        ) { icon ->
            when (icon) {
                ComposerActionIcon.STOPPING -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ComposerActionIcon.PENDING -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ComposerActionIcon.STOP -> Icon(
                    Icons.Default.Stop,
                    stringResource(R.string.action),
                    modifier = Modifier.size(24.dp),
                )
                ComposerActionIcon.VOICE_STOP -> Icon(
                    Icons.Default.Stop,
                    stringResource(R.string.voice_conversation_tap_to_stop),
                    modifier = Modifier.size(24.dp),
                )
                ComposerActionIcon.SEND -> Icon(
                    Icons.Default.ArrowUpward,
                    stringResource(R.string.action),
                    modifier = Modifier.size(24.dp),
                )
                ComposerActionIcon.MIC -> Icon(
                    Icons.Default.Mic,
                    stringResource(R.string.voice_conversation_tap_to_speak),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
