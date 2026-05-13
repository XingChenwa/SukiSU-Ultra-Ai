package com.sukisu.ultra.ui.screen.ai.data

import com.sukisu.ultra.ksuApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper over OkHttp that normalises calls across supported providers into a
 * single `chat(messages, config) -> assistant text` contract.
 *
 * We deliberately use non-streaming requests; the manager UI is not latency-sensitive
 * and skipping SSE keeps this file small and robust.
 */
class AiClient {

    private fun client(timeoutSeconds: Int): OkHttpClient =
        ksuApp.okhttpClient.newBuilder()
            .callTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()

    /**
     * Send a full chat turn and return the assistant's textual reply.
     * Throws on network / parse / HTTP failures so the caller can surface a message.
     */
    suspend fun chat(
        config: AiProviderConfig,
        messages: List<ChatMessage>,
    ): String = withContext(Dispatchers.IO) {
        when (config.kind) {
            AiProviderKind.OpenAiCompatible -> chatOpenAi(config, messages)
            AiProviderKind.Anthropic -> chatAnthropic(config, messages)
            AiProviderKind.Gemini -> chatGemini(config, messages)
        }
    }

    // -- OpenAI / Ollama / DeepSeek / SiliconFlow / LM Studio -----------------------

    private fun chatOpenAi(config: AiProviderConfig, messages: List<ChatMessage>): String {
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val body = JSONObject().apply {
            put("model", config.model)
            put("temperature", config.temperature.toDouble())
            put("max_tokens", config.maxTokens)
            put("stream", false)
            put("messages", JSONArray().also { arr ->
                messages.forEach { msg ->
                    arr.put(JSONObject().apply {
                        put("role", when (msg.role) {
                            ChatRole.System -> "system"
                            ChatRole.User -> "user"
                            ChatRole.Assistant -> "assistant"
                        })
                        put("content", msg.content)
                    })
                }
            })
        }
        val requestBuilder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
        if (config.apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${config.apiKey}")
        }
        return execute(config, requestBuilder.build()) { json ->
            val choices = json.optJSONArray("choices") ?: error("missing choices in response")
            val first = choices.optJSONObject(0) ?: error("empty choices")
            val message = first.optJSONObject("message") ?: first.optJSONObject("delta") ?: error("missing message")
            message.optString("content").ifBlank {
                // Some providers (e.g. OpenAI tool calls) return null content; fall back
                // to anything textual we can find.
                first.optString("text")
            }
        }
    }

    // -- Anthropic Claude ------------------------------------------------------------

    private fun chatAnthropic(config: AiProviderConfig, messages: List<ChatMessage>): String {
        val url = config.baseUrl.trimEnd('/') + "/v1/messages"
        val systemCombined = messages.filter { it.role == ChatRole.System }
            .joinToString("\n\n") { it.content }
        val convo = messages.filter { it.role != ChatRole.System }
        val body = JSONObject().apply {
            put("model", config.model)
            put("max_tokens", config.maxTokens)
            put("temperature", config.temperature.toDouble())
            if (systemCombined.isNotBlank()) put("system", systemCombined)
            put("messages", JSONArray().also { arr ->
                convo.forEach { msg ->
                    arr.put(JSONObject().apply {
                        put("role", if (msg.role == ChatRole.User) "user" else "assistant")
                        put("content", msg.content)
                    })
                }
            })
        }
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", "2023-06-01")
            .build()
        return execute(config, request) { json ->
            val content = json.optJSONArray("content") ?: error("missing content")
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                if (part.optString("type") == "text") {
                    sb.append(part.optString("text"))
                }
            }
            sb.toString()
        }
    }

    // -- Google Gemini ---------------------------------------------------------------

    private fun chatGemini(config: AiProviderConfig, messages: List<ChatMessage>): String {
        val base = config.baseUrl.trimEnd('/')
        val url = "$base/v1beta/models/${config.model}:generateContent?key=${config.apiKey}"
        val systemCombined = messages.filter { it.role == ChatRole.System }
            .joinToString("\n\n") { it.content }
        val convo = messages.filter { it.role != ChatRole.System }
        val body = JSONObject().apply {
            if (systemCombined.isNotBlank()) {
                put("systemInstruction", JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", systemCombined)))
                })
            }
            put("generationConfig", JSONObject().apply {
                put("temperature", config.temperature.toDouble())
                put("maxOutputTokens", config.maxTokens)
            })
            put("contents", JSONArray().also { arr ->
                convo.forEach { msg ->
                    arr.put(JSONObject().apply {
                        put("role", if (msg.role == ChatRole.User) "user" else "model")
                        put("parts", JSONArray().put(JSONObject().put("text", msg.content)))
                    })
                }
            })
        }
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .build()
        return execute(config, request) { json ->
            val candidates = json.optJSONArray("candidates") ?: error("missing candidates")
            val first = candidates.optJSONObject(0) ?: error("empty candidates")
            val parts = first.optJSONObject("content")?.optJSONArray("parts") ?: return@execute ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                sb.append(parts.optJSONObject(i)?.optString("text").orEmpty())
            }
            sb.toString()
        }
    }

    private inline fun execute(
        config: AiProviderConfig,
        request: Request,
        parse: (JSONObject) -> String,
    ): String {
        val call = client(config.timeoutSeconds).newCall(request)
        call.execute().use { response ->
            val raw = response.body.string()
            if (!response.isSuccessful) {
                // Try to extract a useful error message from the provider instead of
                // raising the raw HTML/HTTP code.
                val pretty = runCatching {
                    val obj = JSONObject(raw)
                    obj.optJSONObject("error")?.optString("message")
                        ?: obj.optString("error").takeIf { it.isNotBlank() }
                        ?: raw.take(300)
                }.getOrDefault(raw.take(300))
                throw IllegalStateException("HTTP ${response.code}: $pretty")
            }
            val json = runCatching { JSONObject(raw) }.getOrElse {
                throw IllegalStateException("Malformed JSON from provider")
            }
            return parse(json).ifBlank {
                throw IllegalStateException("Provider returned empty content")
            }
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
