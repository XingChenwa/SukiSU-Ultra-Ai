package com.sukisu.ultra.ui.screen.ai

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sukisu.ultra.ui.screen.ai.data.AiClient
import com.sukisu.ultra.ui.screen.ai.data.AiMode
import com.sukisu.ultra.ui.screen.ai.data.AiProviderConfig
import com.sukisu.ultra.ui.screen.ai.data.AiProviderConfigStore
import com.sukisu.ultra.ui.screen.ai.data.ApkAttachmentSummary
import com.sukisu.ultra.ui.screen.ai.data.ApkInspector
import com.sukisu.ultra.ui.screen.ai.data.ChatMessage
import com.sukisu.ultra.ui.screen.ai.data.ChatRole
import com.sukisu.ultra.ui.screen.ai.data.KpmProjectExporter
import com.sukisu.ultra.ui.screen.ai.data.Prompts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class AiAssistantUiState(
    val providerConfig: AiProviderConfig = AiProviderConfig(),
    val mode: AiMode = AiMode.ReverseAnalyze,
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val attachment: ApkAttachmentSummary? = null,
    val attachmentLoading: Boolean = false,
    val sending: Boolean = false,
    val lastError: String? = null,
)

class AiAssistantViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(
        AiAssistantUiState(providerConfig = AiProviderConfigStore.load(app))
    )
    val state: StateFlow<AiAssistantUiState> = _state.asStateFlow()

    private val client = AiClient()
    private var inFlight: Job? = null

    fun refreshProviderConfig() {
        _state.update { it.copy(providerConfig = AiProviderConfigStore.load(getApplication())) }
    }

    fun setMode(mode: AiMode) {
        _state.update { it.copy(mode = mode) }
    }

    fun setInput(text: String) {
        _state.update { it.copy(input = text) }
    }

    fun clearChat() {
        inFlight?.cancel()
        _state.update {
            it.copy(
                messages = emptyList(),
                input = "",
                attachment = null,
                sending = false,
                lastError = null,
            )
        }
    }

    fun clearAttachment() {
        _state.update { it.copy(attachment = null) }
    }

    fun attachInstalledApp(packageName: String) {
        viewModelScope.launch {
            _state.update { it.copy(attachmentLoading = true, lastError = null) }
            val summary = withContext(Dispatchers.IO) {
                ApkInspector.fromInstalled(getApplication(), packageName)
            }
            _state.update {
                if (summary == null) {
                    it.copy(attachmentLoading = false, lastError = "invalid")
                } else {
                    it.copy(attachmentLoading = false, attachment = summary)
                }
            }
        }
    }

    fun attachApkFile(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(attachmentLoading = true, lastError = null) }
            val summary = withContext(Dispatchers.IO) {
                ApkInspector.fromApkUri(getApplication(), uri)
            }
            _state.update {
                if (summary == null) {
                    it.copy(attachmentLoading = false, lastError = "invalid")
                } else {
                    it.copy(attachmentLoading = false, attachment = summary)
                }
            }
        }
    }

    fun send() {
        val current = _state.value
        if (current.sending) return
        val trimmed = current.input.trim()
        if (trimmed.isEmpty() && current.attachment == null) return
        val config = current.providerConfig
        if (!config.isConfigured) {
            _state.update { it.copy(lastError = "not_configured") }
            return
        }

        val attachmentBlock = current.attachment?.toPromptBlock()
        val composedUser = buildString {
            if (!attachmentBlock.isNullOrBlank()) {
                append(attachmentBlock)
                append("\n\n")
            }
            append(trimmed.ifEmpty { "Please analyse the attachment above." })
        }

        val userMsg = ChatMessage(
            role = ChatRole.User,
            content = composedUser,
            attachmentLabel = current.attachment?.label,
        )
        val history = current.messages + userMsg
        _state.update {
            it.copy(
                messages = history,
                input = "",
                sending = true,
                lastError = null,
                // Attachment is considered consumed once folded into a user message.
                attachment = null,
            )
        }

        val systemMsg = ChatMessage(
            role = ChatRole.System,
            content = buildSystemPrompt(current.mode, config),
        )
        inFlight = viewModelScope.launch {
            val reply = runCatching {
                client.chat(config, listOf(systemMsg) + history)
            }
            reply.onSuccess { text ->
                _state.update {
                    it.copy(
                        sending = false,
                        messages = it.messages + ChatMessage(ChatRole.Assistant, text),
                    )
                }
            }.onFailure { t ->
                _state.update {
                    it.copy(sending = false, lastError = t.message ?: t.javaClass.simpleName)
                }
            }
        }
    }

    fun stop() {
        inFlight?.cancel()
        _state.update { it.copy(sending = false) }
    }

    /**
     * Walk backwards through the conversation until we find the most recent
     * assistant reply and extract KPM project files from it.
     */
    fun extractLatestKpmProject(): List<KpmProjectExporter.ProjectFile> {
        val last = _state.value.messages.lastOrNull { it.role == ChatRole.Assistant }
            ?: return emptyList()
        return KpmProjectExporter.extract(last.content)
    }

    fun exportKpmProject(target: Uri): Boolean {
        val files = extractLatestKpmProject()
        if (files.isEmpty()) return false
        return KpmProjectExporter.writeZip(getApplication(), target, files)
    }

    fun consumeError() {
        _state.update { it.copy(lastError = null) }
    }

    private fun buildSystemPrompt(mode: AiMode, config: AiProviderConfig): String {
        val base = Prompts.forMode(mode)
        val extra = config.extraSystemPrompt.trim()
        return if (extra.isEmpty()) base else base + "\n\nUser overrides:\n" + extra
    }
}
