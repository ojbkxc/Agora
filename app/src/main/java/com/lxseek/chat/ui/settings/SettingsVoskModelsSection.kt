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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.speech.VoskTranscriber
import kotlinx.coroutines.launch

@Composable
fun SettingsVoskModelsSection(
    context: android.content.Context,
    voskTranscriber: VoskTranscriber,
) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val downloadProgress by voskTranscriber.downloadProgress.collectAsState()
    val downloadingFor = remember { mutableStateMapOf<String, String>() }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.vosk_models_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Ready: ${voskTranscriber.isReady()} | Lang: ${voskTranscriber.getCurrentLanguage()}",
            style = MaterialTheme.typography.labelSmall,
            color = if (voskTranscriber.isReady()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        val downloaded = run { refresh; voskTranscriber.getDownloadedLanguages() }
        for (model in VoskTranscriber.AVAILABLE_LANGUAGES) {
            Spacer(modifier = Modifier.height(4.dp))
            val isDownloaded = downloaded.contains(model.code)
            val isDownloading = downloadingFor.containsKey(model.code)
            VoskModelRow(
                label = model.displayName,
                sizeHint = "${model.sizeBytes / 1_000_000}MB",
                isDownloaded = isDownloaded,
                isDownloading = isDownloading,
                progress = if (isDownloading) downloadProgress else null,
                onDownload = {
                    scope.launch {
                        downloadingFor[model.code] = "downloading"
                        voskTranscriber.downloadModel(model.code).collect { state ->
                            when (state) {
                                is VoskTranscriber.DownloadState.Downloading -> {
                                    downloadingFor[model.code] = "downloading"
                                }
                                is VoskTranscriber.DownloadState.Extracting -> {
                                    downloadingFor[model.code] = "extracting"
                                }
                                is VoskTranscriber.DownloadState.Complete -> {
                                    downloadingFor.remove(model.code)
                                    refresh++
                                }
                                is VoskTranscriber.DownloadState.Error -> {
                                    downloadingFor.remove(model.code)
                                    refresh++
                                }
                                else -> {}
                            }
                        }
                    }
                },
                onDelete = {
                    voskTranscriber.deleteModel(model.code)
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
    isDownloading: Boolean,
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
            if (isDownloading && progress != null && progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (isDownloaded) {
            OutlinedButton(onClick = onDelete, enabled = !isDownloading) {
                Text(stringResource(R.string.vosk_model_delete))
            }
        } else {
            OutlinedButton(onClick = onDownload, enabled = !isDownloading) {
                Text(
                    if (isDownloading && progress != null && progress in 1..99)
                        stringResource(R.string.vosk_model_downloading, progress)
                    else if (isDownloading)
                        stringResource(R.string.vosk_model_extracting)
                    else
                        stringResource(R.string.vosk_model_download)
                )
            }
        }
    }
}
