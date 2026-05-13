package com.sukisu.ultra.ui.screen.ai.data

import androidx.compose.runtime.Immutable

enum class ChatRole { System, User, Assistant }

@Immutable
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val id: Long = System.nanoTime(),
    val attachmentLabel: String? = null,
)

enum class AiMode(val key: String) {
    ReverseAnalyze("analyze"),
    GenerateKpm("generate"),
    Freeform("freeform");

    companion object {
        fun from(key: String?): AiMode = entries.firstOrNull { it.key == key } ?: ReverseAnalyze
    }
}
