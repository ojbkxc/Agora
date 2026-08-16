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
import com.lxseek.chat.speech.SpeechRecognitionManager
import com.lxseek.chat.speech.VoskModelManager
import kotlinx.coroutines.launch

@Composable
fun SettingsVoskModelsSection(
    context: android.content.Context,
) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val voskEngine = SpeechRecognitionManager.voskEngine
    val voskAvailable by voskEngine.isAvailable.collectAsState()
    val modelLoaded by voskEngine.isModelLoaded.collectAsState()
    val lastError by voskEngine.lastError.collectAsState()
    val progressMap by VoskModelManager.downloadProgress.collectAsState()

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.vosk_models_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(4.dp))

        if (voskAvailable) {
            Text(
                text = "Native: OK | Model: ${if (modelLoaded) "loaded" else "not loaded"}",
                style = MaterialTheme.typography.labelSmall,
                color = if (modelLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (lastError != null) {
                Text(
                    text = "Error: $lastError",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            Text(
                text = "Native: not loaded",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        val downloaded = run { refresh; VoskModelManager.getDownloadedModels(context) }
        for (model in VoskModelManager.AVAILABLE_MODELS) {
            Spacer(modifier = Modifier.height(4.dp))
            VoskModelRow(
                label = model.displayName,
                sizeHint = "${model.sizeBytes / 1_000_000}MB",
                isDownloaded = downloaded.contains(model.code),
                progress = progressMap[model.code],
                onDownload = {
                    scope.launch {
                        if (VoskModelManager.downloadModel(context, model.code)) {
                            voskEngine.init(context)
                            refresh++
                        }
                    }
                },
                onDelete = {
                    VoskModelManager.deleteModel(context, model.code)
                    voskEngine.init(context)
                    refresh++
                },
            )
        }
    }
}

@Composable
private fun VoskModelRow(
    label: String,
    sizeHint: String,
    isDownloaded: Boolean,
    progress: Int?,
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
                text = sizeHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (progress != null && progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (isDownloaded) {
            OutlinedButton(onClick = onDelete) {
                Text(stringResource(R.string.vosk_model_delete))
            }
        } else {
            OutlinedButton(onClick = onDownload, enabled = progress == null) {
                Text(
                    if (progress != null && progress == -1) stringResource(R.string.vosk_model_extracting)
                    else if (progress != null && progress in 1..99) stringResource(R.string.vosk_model_downloading, progress)
                    else stringResource(R.string.vosk_model_download)
                )
            }
        }
    }
}
