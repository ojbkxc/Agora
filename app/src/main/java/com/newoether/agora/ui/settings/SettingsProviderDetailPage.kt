package com.newoether.agora.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.ApiKeyEntry

import com.newoether.agora.ui.components.CustomEndpointProtocolSelector
import com.newoether.agora.ui.components.GradientButton
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.theme.LocalAgoraColors
import com.newoether.agora.util.Constants
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsProviderDetailPage(
    providerName: String,
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val apiKeys by viewModel.settings.apiKeys.collectAsState()
    val activeApiKeyIds by viewModel.settings.activeApiKeyIds.collectAsState()
    val providerBaseUrls by viewModel.settings.providerBaseUrls.collectAsState()
    val customProviders by viewModel.settings.customProviders.collectAsState()

    val isLocal = providerName == Constants.PROVIDER_LOCAL
    val customConfig = customProviders.firstOrNull { it.name == providerName }
    val isCustom = customConfig != null


    // Dialogs
    var showKeyDialog by remember { mutableStateOf<ApiKeyEntry?>(null) }
    var showDeleteKeyConfirm by remember { mutableStateOf<ApiKeyEntry?>(null) }
    var showRenameProvider by remember { mutableStateOf(false) }
    var showDeleteProvider by remember { mutableStateOf(false) }
    var providerMenuExpanded by remember { mutableStateOf(false) }


    CollapsingSettingsScaffold(
        title = if (isLocal) stringResource(R.string.local_title) else providerName,
        onBack = onBack,
        actions = {
            if (isCustom) {
                Box {
                    IconButton(onClick = { providerMenuExpanded = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.options)) }
                    DropdownMenu(expanded = providerMenuExpanded, onDismissRequest = { providerMenuExpanded = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 16.dp, shape = RoundedCornerShape(12.dp)) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { providerMenuExpanded = false; showRenameProvider = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { providerMenuExpanded = false; showDeleteProvider = true })
                    }
                }
            }
        }
    ) {
            SettingsGroupColumn {
                // Base URL (non-Local only)
                if (!isLocal) {
                // Nullable: after deleting a custom provider this page recomposes once more
                // before navigation pops it 鈥?render with an empty placeholder, don't crash.
                val providerInstance = viewModel.getProviderInstanceOrNull(providerName)
                val savedUrl = providerBaseUrls[providerName]
                // Don't key remember on savedUrl 鈥?that causes TextFieldState to be recreated
                // every time the debounced save writes back to DataStore, overwriting user input.
                val baseUrlState = remember { TextFieldState(savedUrl ?: "") }
                // Sync external changes (e.g. import) back into the text field.
                LaunchedEffect(savedUrl) {
                    val ext = savedUrl ?: ""
                    val cur = baseUrlState.text.toString()
                    if (ext.isNotEmpty() && ext != cur) {
                        baseUrlState.edit { replace(0, length, ext) }
                    }
                }
                // Save user input with 500ms debounce 鈥?but only on a *real* edit.
                // On first composition the field is initialized to `savedUrl ?: ""`, and a DataStore
                // cold load can deliver savedUrl=null momentarily; writing that "" back would poison
                // the persisted map (see SettingsManager.saveProviderBaseUrl). Skipping when the text
                // already equals the persisted (or absent) value eliminates that race and also avoids
                // re-writing the same URL the LaunchedEffect(savedUrl) sync just applied.
                LaunchedEffect(baseUrlState.text) {
                    delay(500)
                    val text = baseUrlState.text.toString()
                    val stored = providerBaseUrls[providerName] ?: ""
                    if (text != stored) {
                        viewModel.settings.setProviderBaseUrl(providerName, text)
                    }
                }
                SettingsGroup(
                    title = stringResource(
                        if (customConfig != null) R.string.provider_connection
                        else R.string.provider_base_url
                    ),
                    items = buildList {
                        add {
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                    Icon(painterResource(R.drawable.link_24), null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(R.string.provider_base_url), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
                                        Box(modifier = Modifier.noOpBringIntoView().padding(top = 8.dp)) {
                                            // Glass-style input: translucent background + 12dp corners.
                                            // Focus colour stays the theme primary (indigo) which harmonises
                                            // with the cf-ai-gw purple accent.
                                            OutlinedTextField(
                                                state = baseUrlState,
                                                placeholder = { Text(providerInstance?.defaultBaseUrl ?: "", style = MaterialTheme.typography.bodyMedium) },
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(LocalAgoraColors.current.inputBg, RoundedCornerShape(12.dp)),
                                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        customConfig?.let { config ->
                            add {
                                SettingsIconContent(icon = Icons.Default.DataObject) {
                                    Text(
                                        stringResource(R.string.custom_provider_protocol_label),
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    CustomEndpointProtocolSelector(
                                        selected = config.protocol,
                                        onSelected = { protocol ->
                                            if (protocol != config.protocol) {
                                                viewModel.updateCustomProviderProtocol(providerName, protocol)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }

            // 拉取模型按钮 — 单供应商模型同步
            if (!isLocal) {
                val isSyncing by viewModel.isSyncingModels.collectAsState()
                SettingsGroup(
                    title = stringResource(R.string.models_available),
                    items = buildList {
                        add {
                            SettingsItem(
                                headlineContent = { Text(stringResource(R.string.models_sync)) },
                                supportingContent = { Text(stringResource(R.string.models_sync_desc)) },
                                leadingContent = {
                                    if (isSyncing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    } else {
                                        Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                modifier = Modifier.clickable(enabled = !isSyncing) {
                                    viewModel.fetchAvailableModels()
                                }
                            )
                        }
                    },
                )
            }

            // API Keys (non-Local)
            if (!isLocal) {
                val providerKeys = apiKeys.filter { it.provider == providerName }
                if (providerKeys.isEmpty()) {
                    SettingsGroup(
                        title = stringResource(R.string.provider_api_keys),
                        items = buildList {
                            add {
                                SettingsItem(
                                    headlineContent = { Text(stringResource(R.string.provider_no_keys, providerName), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    leadingContent = { Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
                                    modifier = Modifier.heightIn(min = 64.dp)
                                )
                            }
                            add {
                                // Gradient "add key" CTA (cf-ai-gw primary-gradient).
                                GradientButton(
                                    text = stringResource(R.string.provider_add_key),
                                    onClick = { showKeyDialog = ApiKeyEntry(name = "", key = "", provider = providerName) },
                                    icon = {
                                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp), tint = Color.White)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    )
                } else {
                    SettingsGroup(
                        title = stringResource(R.string.provider_api_keys),
                        items = buildList {
                            providerKeys.forEach { entry ->
                                var showMenu by remember { mutableStateOf(false) }
                                val isCurrentActive = entry.id == activeApiKeyIds[providerName]
                                add {
                                    SettingsItem(
                                        headlineContent = { Text(entry.name, fontWeight = FontWeight.Medium) },
                                        supportingContent = { Text(entry.key.take(4) + "••••" + entry.key.takeLast(4)) },
                                        leadingContent = { Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) { RadioButton(selected = isCurrentActive, onClick = { viewModel.settings.setActiveApiKey(providerName, entry.id) }, modifier = Modifier.size(20.dp)) } },
                                        trailingContent = {
                                            Box {
                                                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.MoreVert, stringResource(R.string.options), modifier = Modifier.size(18.dp)) }
                                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 16.dp, shape = RoundedCornerShape(12.dp)) {
                                                    DropdownMenuItem(text = { Text(stringResource(R.string.provider_edit)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { showMenu = false; showKeyDialog = entry })
                                                    DropdownMenuItem(text = { Text(stringResource(R.string.provider_delete), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; showDeleteKeyConfirm = entry })
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .clickable { viewModel.settings.setActiveApiKey(providerName, entry.id) }
                                    )
                                }
                            }
                            add {
                                // Gradient "add key" CTA (cf-ai-gw primary-gradient).
                                GradientButton(
                                    text = stringResource(R.string.provider_add_key),
                                    onClick = { showKeyDialog = ApiKeyEntry(name = "", key = "", provider = providerName) },
                                    icon = {
                                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp), tint = Color.White)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    )
                }
            }
            }
    }

    // --- Dialogs ---
    // (Local model add/edit/delete dialogs removed — on-device GGUF chat models are
    // no longer supported after the llama.cpp native layer removal.)

    // API Key dialog
    showKeyDialog?.let { entry ->
        var name by remember { mutableStateOf(entry.name) }; var key by remember { mutableStateOf(entry.key) }
        val isEdit = apiKeys.any { it.id == entry.id }
        AlertDialog(modifier = Modifier.clearFocusOnTap(), containerColor = MaterialTheme.colorScheme.surfaceContainer, onDismissRequest = { showKeyDialog = null }, title = { Text(if (isEdit) stringResource(R.string.provider_edit_key) else stringResource(R.string.provider_add_key_title), fontWeight = FontWeight.Bold) }, text = {
            Column(Modifier.fillMaxWidth()) {
                // Glass-style inputs: translucent background + 12dp corners.
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.provider_key_name_hint)) }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().noOpBringIntoView().background(LocalAgoraColors.current.inputBg, RoundedCornerShape(12.dp)))
                Spacer(modifier = Modifier.height(8.dp))
                Box(modifier = Modifier.noOpBringIntoView()) { OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("${providerName} API Key") }, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().background(LocalAgoraColors.current.inputBg, RoundedCornerShape(12.dp))) }
            }
        }, confirmButton = {
            // Gradient save/add button (cf-ai-gw primary-gradient CTA).
            GradientButton(
                text = if (isEdit) stringResource(R.string.provider_save) else stringResource(R.string.provider_add),
                onClick = { if (name.isNotBlank() && key.isNotBlank()) { if (isEdit) viewModel.settings.updateApiKey(entry.id, name, key) else viewModel.settings.addApiKey(name, key, providerName); showKeyDialog = null } },
            )
        }, dismissButton = { TextButton(onClick = { showKeyDialog = null }) { Text(stringResource(R.string.cancel)) } })
    }

    // Delete key confirm
    showDeleteKeyConfirm?.let { entry ->
        AlertDialog(containerColor = MaterialTheme.colorScheme.surfaceContainer, onDismissRequest = { showDeleteKeyConfirm = null }, title = { Text(stringResource(R.string.provider_delete_key_title), fontWeight = FontWeight.Bold) }, text = { Text(stringResource(R.string.provider_delete_key_text, entry.name)) }, confirmButton = { TextButton(onClick = { viewModel.settings.deleteApiKey(entry.id); showDeleteKeyConfirm = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.provider_delete)) } }, dismissButton = { TextButton(onClick = { showDeleteKeyConfirm = null }) { Text(stringResource(R.string.cancel)) } })
    }

    // Rename custom provider
    if (showRenameProvider) {
        var renameValue by remember { mutableStateOf(providerName) }
        var renameError by remember { mutableStateOf(false) }
        val allNames = listOf(Constants.PROVIDER_GOOGLE, Constants.PROVIDER_OPENAI, Constants.PROVIDER_ANTHROPIC, Constants.PROVIDER_DEEPSEEK, Constants.PROVIDER_QWEN, Constants.PROVIDER_GROQ, Constants.PROVIDER_OLLAMA, Constants.PROVIDER_OPEN_ROUTER) + customProviders.map { it.name }
        AlertDialog(modifier = Modifier.clearFocusOnTap(), containerColor = MaterialTheme.colorScheme.surfaceContainer, onDismissRequest = { showRenameProvider = false }, title = { Text(stringResource(R.string.custom_provider_rename_title), fontWeight = FontWeight.Bold) }, text = {
            OutlinedTextField(value = renameValue, onValueChange = { renameValue = it; renameError = false }, label = { Text(stringResource(R.string.custom_provider_name_label)) }, isError = renameError, supportingText = if (renameError) {{ Text(stringResource(R.string.custom_provider_name_error)) }} else null, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), singleLine = true)
        }, confirmButton = { TextButton(onClick = {
            val trimmed = renameValue.trim()
            renameError = trimmed.isBlank() || (trimmed != providerName && trimmed in allNames)
            if (!renameError) { viewModel.renameCustomProvider(providerName, trimmed); showRenameProvider = false; onBack() }
        }) { Text(stringResource(R.string.custom_provider_rename)) } }, dismissButton = { TextButton(onClick = { showRenameProvider = false }) { Text(stringResource(R.string.cancel)) } })
    }

    // Delete custom provider
    if (showDeleteProvider) {
        AlertDialog(containerColor = MaterialTheme.colorScheme.surfaceContainer, onDismissRequest = { showDeleteProvider = false }, title = { Text(stringResource(R.string.custom_provider_delete_title), fontWeight = FontWeight.Bold) }, text = { Text(stringResource(R.string.custom_provider_delete_text, providerName)) }, confirmButton = { TextButton(onClick = { viewModel.deleteCustomProvider(providerName); showDeleteProvider = false; onBack() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.provider_delete)) } }, dismissButton = { TextButton(onClick = { showDeleteProvider = false }) { Text(stringResource(R.string.cancel)) } })
    }
}
