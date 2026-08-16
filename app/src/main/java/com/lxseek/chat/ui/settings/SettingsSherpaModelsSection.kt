package com.lxseek.chat.ui.settings

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.lxseek.chat.speech.SherpaModelManager
import com.lxseek.chat.speech.SherpaTtsEngine
import kotlinx.coroutines.launch

@Composable
fun SettingsSherpaModelsSection(
    context: android.content.Context,
) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val progress by SherpaModelManager.downloadProgress.collectAsState()
    val isDownloading by SherpaModelManager.isDownloading.collectAsState()
    val ttsAvailable by SherpaTtsEngine.isAvailable.collectAsState()
    val ttsModelLoaded by SherpaTtsEngine.isModelLoaded.collectAsState()

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.sherpa_models_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(4.dp))

        if (!ttsAvailable) {
            Text(
                text = stringResource(R.string.sherpa_models_native_not_loaded),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(8.dp))
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

        for (kind in SherpaModelManager.ModelKind.entries.filter { it.category == SherpaModelManager.Category.TTS }) {
            Spacer(modifier = Modifier.height(4.dp))
            SherpaModelRow(
                label = kind.displayName,
                description = kind.description,
                sizeHint = kind.sizeHint,
                present = run { refresh; SherpaModelManager.isModelPresent(context, kind) },
                progress = progress.keys.filter { it.startsWith("tts") }.mapNotNull { progress[it] }.firstOrNull { it < 1f },
                isDownloading = isDownloading,
                extraStatus = if (ttsModelLoaded) "Loaded" else null,
                onDownload = {
                    scope.launch {
                        if (SherpaModelManager.download(context, kind)) {
                            SherpaTtsEngine.init(context)
                            refresh++
                        }
                    }
                },
                onDelete = { SherpaModelManager.deleteModel(context, kind); refresh++ },
            )
        }
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
