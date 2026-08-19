package com.lxseek.chat.ui.settings

import android.content.Context
import android.speech.SpeechRecognizer
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
import androidx.compose.ui.graphics.Color
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
    asrUseRemote: Boolean = false,
    asrRemoteBaseUrl: String = "",
    asrRemoteApiKey: String = "",
    asrRemoteModel: String = "",
    voiceLanguage: String = "en",
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

        // Strict engine readiness checks (hardcoded English diagnostic text).
        val voskReady = voskTranscriber.isReady()
        val voskCurrentLang = voskTranscriber.getCurrentLanguage()
        val downloadedLangs = voskTranscriber.getDownloadedLanguages()
        val voiceBaseLang = VoskTranscriber.getBaseLanguageCode(voiceLanguage)
        val modelForVoiceLang = downloadedLangs.any { VoskTranscriber.getBaseLanguageCode(it) == voiceBaseLang }
        val systemAvailable = try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (_: Throwable) {
            false
        }
        val whisperConfigured = asrUseRemote &&
            asrRemoteBaseUrl.isNotBlank() &&
            asrRemoteApiKey.isNotBlank() &&
            asrRemoteModel.isNotBlank()

        val readinessLines = buildList {
            add("Engine readiness:")
            when (asrEnginePref) {
                "auto" -> {
                    val candidates = buildList {
                        if (voskReady) add("vosk: ready")
                        if (whisperConfigured) add("whisper: configured")
                        if (systemAvailable) add("system: available")
                    }
                    if (candidates.isEmpty()) {
                        add("  ⚠ NO engine available!")
                    } else {
                        candidates.forEach { add("  • $it") }
                    }
                }
                "vosk" -> {
                    add("  vosk.isReady() = $voskReady")
                    add("  loadedLang = ${voskCurrentLang.ifBlank { "(none)" }}")
                    add("  downloadedLangs = ${downloadedLangs.joinToString(", ").ifBlank { "(none)" }}")
                    add("  modelForVoiceLang($voiceBaseLang) = $modelForVoiceLang")
                    if (!voskReady) add("  ⚠ Vosk model not loaded")
                    if (voskReady && !modelForVoiceLang) add("  ⚠ No model downloaded for voice language '$voiceBaseLang'")
                }
                "whisper" -> {
                    add("  useRemote = $asrUseRemote")
                    add("  baseUrl = ${if (asrRemoteBaseUrl.isNotBlank()) "set" else "MISSING"}")
                    add("  apiKey = ${if (asrRemoteApiKey.isNotBlank()) "set" else "MISSING"}")
                    add("  model = ${if (asrRemoteModel.isNotBlank()) "set" else "MISSING"}")
                    if (!whisperConfigured) add("  ⚠ Whisper not fully configured")
                }
                "system" -> {
                    add("  SpeechRecognizer.isRecognitionAvailable = $systemAvailable")
                    if (!systemAvailable) add("  ⚠ System speech recognition not available")
                }
                else -> {
                    add("  Unknown engine pref: '$asrEnginePref'")
                }
            }
        }
        Text(
            text = readinessLines.joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
            maxLines = 12,
            overflow = TextOverflow.Ellipsis,
        )

        // Language mismatch warning: selected voice language has no downloaded Vosk model.
        if (voiceBaseLang.isNotBlank() && !modelForVoiceLang) {
            Text(
                text = "⚠ Voice language '$voiceBaseLang' has no downloaded Vosk model. ASR may fall back or fail.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFE53935),
                modifier = Modifier.padding(top = 4.dp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

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
            TextButton(onClick = {
                AppLog.clear()
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.asr_log_cleared),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }) { Text(stringResource(R.string.asr_clear_log)) }
        }
    }
}
