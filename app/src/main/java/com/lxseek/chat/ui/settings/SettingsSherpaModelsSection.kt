package com.lxseek.chat.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
    vadThreshold: Float,
    vadMinSilence: Float,
    vadMaxSpeech: Float,
    onVadThresholdChange: (Float) -> Unit,
    onVadMinSilenceChange: (Float) -> Unit,
    onVadMaxSpeechChange: (Float) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val progress by SherpaModelManager.downloadProgress.collectAsState()
    val isDownloading by SherpaModelManager.isDownloading.collectAsState()
    val sherpaAvailable by sherpaEngine.isAvailable.collectAsState()
    val asrModelLoaded by sherpaEngine.isModelLoaded.collectAsState()
    val asrError by sherpaEngine.lastError.collectAsState()
    val ttsAvailable by SherpaTtsEngine.isAvailable.collectAsState()
    val ttsModelLoaded by SherpaTtsEngine.isModelLoaded.collectAsState()

    LaunchedEffect(vadThreshold, vadMinSilence, vadMaxSpeech) {
        com.lxseek.chat.util.VoiceRecorder.vadThreshold = vadThreshold
        com.lxseek.chat.util.VoiceRecorder.vadMinSilence = vadMinSilence
        com.lxseek.chat.util.VoiceRecorder.vadMaxSpeech = vadMaxSpeech
    }

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

        if (sherpaAvailable) {
            Text(
                text = "Native: OK | ASR model: ${if (asrModelLoaded) "loaded" else "not loaded"}",
                style = MaterialTheme.typography.labelSmall,
                color = if (asrModelLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (asrError != null) {
                Text(
                    text = "ASR Error: $asrError",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (ttsAvailable) {
            Text(
                text = "TTS model: ${if (ttsModelLoaded) "loaded" else "not loaded"}",
                style = MaterialTheme.typography.labelSmall,
                color = if (ttsModelLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val ttsError = SherpaTtsEngine.lastError.collectAsState().value
            if (ttsError != null) {
                Text(
                    text = "TTS Error: $ttsError",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        for (category in SherpaModelManager.Category.entries) {
            val models = SherpaModelManager.ModelKind.entries.filter { it.category == category }
            if (models.isEmpty()) continue
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = category.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (kind in models) {
                Spacer(modifier = Modifier.height(4.dp))
                SherpaModelRow(
                    label = kind.displayName,
                    description = kind.description,
                    sizeHint = kind.sizeHint,
                    present = run { refresh; SherpaModelManager.isModelPresent(context, kind) },
                    progress = progressForKind(kind, progress),
                    isDownloading = isDownloading,
                    extraStatus = extraStatusForKind(kind, asrModelLoaded, ttsModelLoaded),
                    onDownload = {
                        scope.launch {
                            if (SherpaModelManager.download(context, kind)) {
                                when (kind.category) {
                                    SherpaModelManager.Category.ASR -> sherpaEngine.init(context)
                                    SherpaModelManager.Category.TTS -> SherpaTtsEngine.init(context)
                                    else -> {}
                                }
                                refresh++
                            }
                        }
                    },
                    onDelete = { SherpaModelManager.deleteModel(context, kind); refresh++ },
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.sherpa_vad_params),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        VadParamSlider(
            label = stringResource(R.string.sherpa_vad_threshold),
            value = vadThreshold,
            range = 0.1f..0.9f,
            steps = 15,
            onChange = onVadThresholdChange,
        )
        VadParamSlider(
            label = stringResource(R.string.sherpa_vad_min_silence),
            value = vadMinSilence,
            range = 0.1f..2.0f,
            steps = 18,
            onChange = onVadMinSilenceChange,
        )
        VadParamSlider(
            label = stringResource(R.string.sherpa_vad_max_speech),
            value = vadMaxSpeech,
            range = 5f..60f,
            steps = 54,
            onChange = onVadMaxSpeechChange,
        )
    }
}

private fun progressForKind(kind: SherpaModelManager.ModelKind, progress: Map<String, Float>): Float? {
    val keys = when (kind.category) {
        SherpaModelManager.Category.VAD -> listOf("vad")
        SherpaModelManager.Category.ASR -> progress.keys.filter { it.startsWith("asr") }
        SherpaModelManager.Category.TTS -> listOf("tts_tar")
    }
    return keys.mapNotNull { progress[it] }.firstOrNull { it < 1f }
}

private fun extraStatusForKind(kind: SherpaModelManager.ModelKind, asrLoaded: Boolean, ttsLoaded: Boolean): String? = when {
    kind.category == SherpaModelManager.Category.ASR && asrLoaded -> "Loaded"
    kind.category == SherpaModelManager.Category.TTS && ttsLoaded -> "Loaded"
    else -> null
}

@Composable
private fun VadParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, style = MaterialTheme.typography.bodySmall)
            Text(text = "%.2f".format(value), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SherpaModelRow(
    label: String,
    description: String,
    sizeHint: String,
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
            Text(
                text = "$sizeHint — $description",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                val pct = ((progress ?: 0f) * 100).toInt()
                Text(if (isDownloading && progress != null && progress > 0f && progress < 1f) stringResource(R.string.sherpa_model_downloading, pct) else stringResource(R.string.sherpa_model_download))
            }
        }
    }
}
