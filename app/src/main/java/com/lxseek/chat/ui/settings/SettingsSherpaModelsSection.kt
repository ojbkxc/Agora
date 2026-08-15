package com.lxseek.chat.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.speech.SherpaAsrEngine
import com.lxseek.chat.speech.SherpaModelManager
import com.lxseek.chat.speech.SherpaTtsEngine
import kotlinx.coroutines.launch

@Composable
fun SettingsSherpaModelsSection(
    context: android.content.Context,
    sherpaEngine: SherpaAsrEngine,
) {
    val scope = rememberCoroutineScope()
    val progress by SherpaModelManager.downloadProgress.collectAsState()
    val isDownloading by SherpaModelManager.isDownloading.collectAsState()
    val sherpaAvailable by sherpaEngine.isAvailable.collectAsState()
    val asrModelLoaded by sherpaEngine.isModelLoaded.collectAsState()
    val ttsAvailable by SherpaTtsEngine.isAvailable.collectAsState()
    val ttsModelLoaded by SherpaTtsEngine.isModelLoaded.collectAsState()

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.sherpa_models_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (!sherpaAvailable && !ttsAvailable) {
            Text(
                text = stringResource(R.string.sherpa_models_native_not_loaded),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        SherpaModelRow(
            label = stringResource(R.string.sherpa_model_vad),
            present = SherpaModelManager.isModelPresent(context, SherpaModelManager.ModelKind.VAD),
            progress = progress["vad"],
            isDownloading = isDownloading,
            onDownload = {
                scope.launch {
                    if (SherpaModelManager.downloadVad(context)) {
                        com.lxseek.chat.util.VoiceRecorder().initSileroVad(
                            "${SherpaModelManager.modelDir(context, SherpaModelManager.ModelKind.VAD).absolutePath}/silero_vad.onnx",
                        )
                    }
                }
            },
            onDelete = { SherpaModelManager.deleteModel(context, SherpaModelManager.ModelKind.VAD) },
        )

        Spacer(modifier = Modifier.height(8.dp))

        SherpaModelRow(
            label = stringResource(R.string.sherpa_model_asr),
            present = SherpaModelManager.isModelPresent(context, SherpaModelManager.ModelKind.ASR_ZIPFORMER_BILINGUAL),
            progress = progress.values.filter { it < 1f && progress.keys.any { k -> k.startsWith("asr_") } }.firstOrNull(),
            isDownloading = isDownloading,
            extraStatus = if (asrModelLoaded) stringResource(R.string.sherpa_model_loaded) else null,
            onDownload = {
                scope.launch {
                    if (SherpaModelManager.downloadAsrZipformerBilingual(context)) {
                        sherpaEngine.loadModel(
                            SherpaModelManager.modelDir(context, SherpaModelManager.ModelKind.ASR_ZIPFORMER_BILINGUAL).absolutePath,
                        )
                    }
                }
            },
            onDelete = { SherpaModelManager.deleteModel(context, SherpaModelManager.ModelKind.ASR_ZIPFORMER_BILINGUAL) },
        )

        Spacer(modifier = Modifier.height(8.dp))

        SherpaModelRow(
            label = stringResource(R.string.sherpa_model_tts),
            present = SherpaModelManager.isModelPresent(context, SherpaModelManager.ModelKind.TTS_KOKORO),
            progress = progress.values.filter { it < 1f && progress.keys.any { k -> k.startsWith("tts_") } }.firstOrNull(),
            isDownloading = isDownloading,
            extraStatus = if (ttsModelLoaded) stringResource(R.string.sherpa_model_loaded) else null,
            onDownload = {
                scope.launch {
                    if (SherpaModelManager.downloadTtsKokoro(context)) {
                        SherpaTtsEngine.loadModel(
                            SherpaModelManager.modelDir(context, SherpaModelManager.ModelKind.TTS_KOKORO).absolutePath,
                            SherpaTtsEngine.ModelType.KOKORO,
                        )
                    }
                }
            },
            onDelete = { SherpaModelManager.deleteModel(context, SherpaModelManager.ModelKind.TTS_KOKORO) },
        )
    }
}

@Composable
private fun SherpaModelRow(
    label: String,
    present: Boolean,
    progress: Float?,
    isDownloading: Boolean,
    extraStatus: String? = null,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            if (extraStatus != null) {
                Text(
                    text = extraStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (progress != null && progress < 1f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (present) {
            OutlinedButton(onClick = onDelete, enabled = !isDownloading) {
                Text(stringResource(R.string.sherpa_model_delete))
            }
        } else {
            OutlinedButton(onClick = onDownload, enabled = !isDownloading) {
                Text(if (isDownloading) stringResource(R.string.sherpa_model_downloading, 0) else stringResource(R.string.sherpa_model_download))
            }
        }
    }
}
