package com.lxseek.chat.viewmodel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.lxseek.chat.R
import com.lxseek.chat.util.SnackbarEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Bridges the share-selection UI with the clipboard for "copy as plain text".
 *
 * Kept as a small dedicated controller (rather than inlined in [ChatViewModel]) so the
 * ViewModel stays under the 999-line source-size cap. The Markdown export path still
 * flows through [ConversationForkShareController] / [ConversationForkShareService] because
 * it reuses the `_conversationShareText` SharedFlow → `Intent.ACTION_SEND` pipeline.
 */
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
}
