package com.sukisu.ultra.ui.screen.ai.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.core.content.edit

/**
 * Supported providers. The OpenAI branch also covers Ollama and any other OpenAI
 * compatible endpoint (DeepSeek, SiliconFlow, LM Studio, vLLM, ...). Base URL is
 * user-editable and the wire format follows /v1/chat/completions.
 */
enum class AiProviderKind(val id: String) {
    OpenAiCompatible("openai"),
    Anthropic("anthropic"),
    Gemini("gemini");

    companion object {
        fun fromId(id: String?): AiProviderKind =
            entries.firstOrNull { it.id == id } ?: OpenAiCompatible
    }
}

@Immutable
data class AiProviderConfig(
    val kind: AiProviderKind = AiProviderKind.OpenAiCompatible,
    val baseUrl: String = defaultBaseUrl(AiProviderKind.OpenAiCompatible),
    val apiKey: String = "",
    val model: String = defaultModel(AiProviderKind.OpenAiCompatible),
    val temperature: Float = 0.3f,
    val maxTokens: Int = 4096,
    val timeoutSeconds: Int = 120,
    val extraSystemPrompt: String = "",
) {
    val isConfigured: Boolean
        get() = model.isNotBlank() && baseUrl.isNotBlank() && (apiKey.isNotBlank() || kind == AiProviderKind.OpenAiCompatible)
}

fun defaultBaseUrl(kind: AiProviderKind): String = when (kind) {
    AiProviderKind.OpenAiCompatible -> "https://api.openai.com/v1"
    AiProviderKind.Anthropic -> "https://api.anthropic.com"
    AiProviderKind.Gemini -> "https://generativelanguage.googleapis.com"
}

fun defaultModel(kind: AiProviderKind): String = when (kind) {
    AiProviderKind.OpenAiCompatible -> "gpt-4o-mini"
    AiProviderKind.Anthropic -> "claude-3-5-sonnet-latest"
    AiProviderKind.Gemini -> "gemini-2.0-flash"
}

/**
 * Lightweight persistence. We intentionally keep the API key in plain SharedPreferences
 * rather than a third-party secure store to avoid taking on new dependencies; it lives
 * in the app's private data dir, which is the same trust boundary as the rest of the
 * manager state.
 */
object AiProviderConfigStore {
    private const val PREFS = "ai_provider_config"
    private const val K_KIND = "kind"
    private const val K_BASE_URL = "base_url"
    private const val K_API_KEY = "api_key"
    private const val K_MODEL = "model"
    private const val K_TEMPERATURE = "temperature"
    private const val K_MAX_TOKENS = "max_tokens"
    private const val K_TIMEOUT = "timeout_seconds"
    private const val K_EXTRA_PROMPT = "extra_system_prompt"

    fun load(context: Context): AiProviderConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val kind = AiProviderKind.fromId(prefs.getString(K_KIND, null))
        return AiProviderConfig(
            kind = kind,
            baseUrl = prefs.getString(K_BASE_URL, defaultBaseUrl(kind)) ?: defaultBaseUrl(kind),
            apiKey = prefs.getString(K_API_KEY, "") ?: "",
            model = prefs.getString(K_MODEL, defaultModel(kind)) ?: defaultModel(kind),
            temperature = prefs.getFloat(K_TEMPERATURE, 0.3f),
            maxTokens = prefs.getInt(K_MAX_TOKENS, 4096),
            timeoutSeconds = prefs.getInt(K_TIMEOUT, 120),
            extraSystemPrompt = prefs.getString(K_EXTRA_PROMPT, "") ?: ""
        )
    }

    fun save(context: Context, config: AiProviderConfig) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit {
            putString(K_KIND, config.kind.id)
            putString(K_BASE_URL, config.baseUrl.trim())
            putString(K_API_KEY, config.apiKey.trim())
            putString(K_MODEL, config.model.trim())
            putFloat(K_TEMPERATURE, config.temperature.coerceIn(0f, 2f))
            putInt(K_MAX_TOKENS, config.maxTokens.coerceIn(256, 32_768))
            putInt(K_TIMEOUT, config.timeoutSeconds.coerceIn(15, 600))
            putString(K_EXTRA_PROMPT, config.extraSystemPrompt)
        }
    }
}
