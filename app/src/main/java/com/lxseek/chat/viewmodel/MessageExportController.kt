package com.lxseek.chat.viewmodel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.lxseek.chat.R
import com.lxseek.chat.util.SnackbarEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class MessageExportController(
    private val service: ConversationForkShareService,
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val emitSnackbar: suspend (SnackbarEvent) -> Unit,
) {
    fun copyMessagesAsPlainText(conversationId: String?, messageIds: Set<String>) = scope.launch {
        if (messageIds.isEmpty() || conversationId == null) return@launch
        when (val result = service.buildPlainText(
            conversationId = conversationId,
            messageIds = messageIds,
            userLabel = appContext.getString(R.string.share_label_user),
            aiLabel = appContext.getString(R.string.share_label_ai),
        )) {
            is ConversationForkShareService.ShareResult.Success -> {
                val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return@launch
                clipboard.setPrimaryClip(ClipData.newPlainText("agora", result.text))
                emitSnackbar(SnackbarEvent(appContext.getString(R.string.copied_to_clipboard)))
            }
            is ConversationForkShareService.ShareResult.Failure -> {
                emitSnackbar(SnackbarEvent(result.reason))
            }
        }
    }

    fun shareMessagesAsLongImage(
        conversationId: String?,
        messageIds: Set<String>,
        title: String,
    ) = scope.launch {
        if (messageIds.isEmpty() || conversationId == null) return@launch
        when (val result = service.buildPlainText(
            conversationId = conversationId,
            messageIds = messageIds,
            userLabel = appContext.getString(R.string.share_label_user),
            aiLabel = appContext.getString(R.string.share_label_ai),
        )) {
            is ConversationForkShareService.ShareResult.Success -> {
                val file = MessageLongImageRenderer.renderToCacheFile(appContext, title, result.text)
                if (file == null) {
                    emitSnackbar(SnackbarEvent(appContext.getString(R.string.share_failed)))
                    return@launch
                }
                val uri: Uri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    file,
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, appContext.getString(R.string.share_long_image))
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(chooser)
            }
            is ConversationForkShareService.ShareResult.Failure -> {
                emitSnackbar(SnackbarEvent(result.reason))
            }
        }
    }
}
