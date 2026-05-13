package com.sukisu.ultra.ui.screen.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.navigation3.LocalNavigator
import com.sukisu.ultra.ui.navigation3.Route
import com.sukisu.ultra.ui.screen.ai.components.InstalledAppPickerDialog
import com.sukisu.ultra.ui.screen.ai.data.AiMode
import com.sukisu.ultra.ui.screen.ai.data.ChatMessage
import com.sukisu.ultra.ui.screen.ai.data.ChatRole

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAssistantScreen() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val vm = viewModel<AiAssistantViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()

    // Reload config every time we land back on this screen, in case the user just
    // changed it.
    LaunchedEffect(Unit) { vm.refreshProviderConfig() }

    var menuOpen by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }

    val apkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.attachApkFile(uri)
    }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = vm.exportKpmProject(uri)
        val msg = if (ok) {
            context.getString(R.string.ai_assistant_export_success, uri.toString())
        } else context.getString(R.string.ai_assistant_export_failed)
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    // Surface one-off errors as toasts so the chat log stays clean.
    LaunchedEffect(state.lastError) {
        val err = state.lastError ?: return@LaunchedEffect
        val message = when (err) {
            "not_configured" -> context.getString(R.string.ai_assistant_configure_first)
            "invalid" -> context.getString(R.string.ai_attachment_invalid)
            else -> context.getString(R.string.ai_assistant_error, err)
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        vm.consumeError()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_assistant_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { navigator.push(Route.AiProviderConfig) }) {
                        Icon(Icons.Filled.Settings, contentDescription = null)
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ai_assistant_new_chat)) },
                            onClick = { vm.clearChat(); menuOpen = false }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ai_assistant_export_kpm)) },
                            onClick = {
                                menuOpen = false
                                val files = vm.extractLatestKpmProject()
                                if (files.isEmpty()) {
                                    Toast.makeText(
                                        context,
                                        R.string.ai_assistant_no_generated_kpm,
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    exportLauncher.launch("kpm_project.zip")
                                }
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
        ) {
            ModeSelector(
                selected = state.mode,
                onSelected = vm::setMode,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )

            ChatList(
                messages = state.messages,
                sending = state.sending,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            AttachmentBar(
                attachmentLabel = state.attachment?.label,
                loading = state.attachmentLoading,
                onDetach = vm::clearAttachment,
            )

            Composer(
                input = state.input,
                sending = state.sending,
                onInputChange = vm::setInput,
                onSend = vm::send,
                onStop = vm::stop,
                onAttachInstalledApp = { showAppPicker = true },
                onAttachApkFile = { apkPickerLauncher.launch(arrayOf("application/vnd.android.package-archive", "*/*")) },
            )
        }
    }

    if (showAppPicker) {
        InstalledAppPickerDialog(
            onDismiss = { showAppPicker = false },
            onPicked = { app ->
                showAppPicker = false
                vm.attachInstalledApp(app.packageName)
            }
        )
    }
}

@Composable
private fun ModeSelector(
    selected: AiMode,
    onSelected: (AiMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val items = listOf(
            AiMode.ReverseAnalyze to R.string.ai_assistant_mode_analyze,
            AiMode.GenerateKpm to R.string.ai_assistant_mode_generate,
            AiMode.Freeform to R.string.ai_assistant_mode_freeform,
        )
        items.forEach { (mode, res) ->
            FilterChip(
                selected = mode == selected,
                onClick = { onSelected(mode) },
                label = { Text(stringResource(res)) }
            )
        }
    }
}

@Composable
private fun ChatList(
    messages: List<ChatMessage>,
    sending: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, sending) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1 + if (sending) 1 else 0)
        }
    }

    if (messages.isEmpty() && !sending) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                Text(
                    text = stringResource(R.string.ai_assistant_empty_hint),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.ai_assistant_disclaimer),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(messages.size, key = { messages[it].id }) { idx ->
            MessageBubble(messages[idx])
        }
        if (sending) {
            item(key = "thinking") {
                ThinkingBubble()
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val context = LocalContext.current
    val isUser = message.role == ChatRole.User
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            modifier = Modifier.widthIn(max = 520.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(
                        if (isUser) R.string.ai_assistant_role_user else R.string.ai_assistant_role_assistant
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor.copy(alpha = 0.7f),
                )
                if (message.attachmentLabel != null) {
                    Text(
                        text = stringResource(R.string.ai_assistant_attached, message.attachmentLabel),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f),
                    )
                }
                Spacer(Modifier.height(4.dp))
                // We render the text verbatim. Rich markdown rendering would be nice
                // but it is not required for functionality and KPM code stays readable.
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    fontFamily = if (message.role == ChatRole.Assistant) FontFamily.Default else FontFamily.Default,
                )
                if (message.role == ChatRole.Assistant) {
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("assistant", message.content))
                            Toast.makeText(context, R.string.ai_assistant_copied, Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.ai_assistant_copy_code))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingBubble() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ai_assistant_thinking), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AttachmentBar(
    attachmentLabel: String?,
    loading: Boolean,
    onDetach: () -> Unit,
) {
    if (!loading && attachmentLabel == null) return
    Surface(
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ai_attachment_analyzing), style = MaterialTheme.typography.bodySmall)
            } else if (attachmentLabel != null) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.ai_assistant_attached, attachmentLabel),
                    style = MaterialTheme.typography.bodySmall,
                )
                IconButton(onClick = onDetach) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.ai_assistant_detach))
                }
            }
        }
    }
}

@Composable
private fun Composer(
    input: String,
    sending: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttachInstalledApp: () -> Unit,
    onAttachApkFile: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = onAttachInstalledApp,
                    label = { Text(stringResource(R.string.ai_assistant_attach_installed_app)) },
                    leadingIcon = { Icon(Icons.Filled.Apps, contentDescription = null) }
                )
                AssistChip(
                    onClick = onAttachApkFile,
                    label = { Text(stringResource(R.string.ai_assistant_attach_apk_file)) },
                    leadingIcon = { Icon(Icons.Filled.Archive, contentDescription = null) }
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.ai_assistant_input_hint)) },
                    maxLines = 6,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = if (sending) onStop else onSend,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (sending) Icons.Filled.Stop else Icons.Filled.Send,
                        contentDescription = stringResource(if (sending) R.string.ai_assistant_stop else R.string.ai_assistant_send),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
