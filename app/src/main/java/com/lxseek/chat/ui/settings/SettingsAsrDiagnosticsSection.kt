package com.lxseek.chat.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.speech.VoskTranscriber
import com.lxseek.chat.util.AppLog
import com.lxseek.chat.util.CrashReporter
import com.lxseek.chat.viewmodel.VoiceConversationController
import kotlinx.coroutines.delay

private val ASR_LOG_TAGS = setOf(
    "VoiceConvCtrl",
    "SpeechRecognizerMgr",
    "VoskTranscriber",
    "WhisperTranscriber",
    "AudioCaptureManager",
)

@Composable
fun SettingsAsrDiagnosticsSection(
    context: Context,
    asrEnginePref: String,
    controller: VoiceConversationController,
    voskTranscriber: VoskTranscriber,
) {
    val sessionState by controller.state.collectAsState()
    var logText by remember { mutableStateOf(AppLog.getFilteredText(ASR_LOG_TAGS, 30)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000L)
            logText = AppLog.getFilteredText(ASR_LOG_TAGS, 30)
        }
    }

    val engineLabel = when (asrEnginePref) {
        "system" -> stringResource(R.string.asr_engine_system)
        "vosk" -> stringResource(R.string.asr_engine_vosk)
        "whisper" -> stringResource(R.string.asr_engine_whisper)
        else -> stringResource(R.string.asr_engine_auto)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.asr_diagnostics_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.asr_engine_status, engineLabel, sessionState.name),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        val voskDiagnostic = voskTranscriber.getDiagnosticText()
        if (voskDiagnostic.isNotBlank()) {
            Text(
                text = voskDiagnostic,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = logText.ifBlank { stringResource(R.string.asr_no_logs_yet) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val payload = AppLog.getFilteredText(ASR_LOG_TAGS, 200) + "\n\n" + voskTranscriber.getDiagnosticText()
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ASR Log", payload))
            }) { Text(stringResource(R.string.asr_copy_log)) }
            TextButton(onClick = {
                val payload = "=== ASR Log ===\n" +
                    AppLog.getFilteredText(ASR_LOG_TAGS, 200) + "\n\n" +
                    voskTranscriber.getDiagnosticText()
                val name = CrashReporter.exportDiagnostics(context, payload)
                if (name != null) {
                    android.widget.Toast.makeText(context, "Saved to Downloads/Agora/$name", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(context, "Failed to save", android.widget.Toast.LENGTH_SHORT).show()
                }
            }) { Text(stringResource(R.string.asr_save_to_downloads)) }
        }
    }
}