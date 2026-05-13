package com.sukisu.ultra.ui.screen.ai

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.navigation3.LocalNavigator
import com.sukisu.ultra.ui.screen.ai.data.AiClient
import com.sukisu.ultra.ui.screen.ai.data.AiProviderConfig
import com.sukisu.ultra.ui.screen.ai.data.AiProviderConfigStore
import com.sukisu.ultra.ui.screen.ai.data.AiProviderKind
import com.sukisu.ultra.ui.screen.ai.data.ChatMessage
import com.sukisu.ultra.ui.screen.ai.data.ChatRole
import com.sukisu.ultra.ui.screen.ai.data.defaultBaseUrl
import com.sukisu.ultra.ui.screen.ai.data.defaultModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiProviderConfigScreen() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()

    val initial = remember { AiProviderConfigStore.load(context) }
    var kind by remember { mutableStateOf(initial.kind) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var temperature by remember { mutableStateOf(initial.temperature) }
    var maxTokens by remember { mutableStateOf(initial.maxTokens.toString()) }
    var timeout by remember { mutableStateOf(initial.timeoutSeconds.toString()) }
    var extraPrompt by remember { mutableStateOf(initial.extraSystemPrompt) }
    var testing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_assistant_config_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.ai_assistant_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = stringResource(R.string.ai_provider),
                style = MaterialTheme.typography.titleSmall
            )
            ProviderChips(
                selected = kind,
                onSelected = { new ->
                    // When the user changes providers we reset base url / model to
                    // the sane defaults so they don't accidentally send OpenAI paths
                    // to Anthropic and vice-versa.
                    if (kind != new) {
                        if (baseUrl == defaultBaseUrl(kind)) baseUrl = defaultBaseUrl(new)
                        if (model == defaultModel(kind)) model = defaultModel(new)
                        kind = new
                    }
                }
            )

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ai_provider_base_url)) },
                singleLine = true
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ai_provider_api_key)) },
                supportingText = { Text(stringResource(R.string.ai_provider_api_key_hint)) },
                singleLine = true
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ai_provider_model)) },
                supportingText = { Text(stringResource(R.string.ai_provider_model_hint)) },
                singleLine = true
            )

            Text(
                text = stringResource(R.string.ai_provider_temperature) + "  " + "%.2f".format(temperature),
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = temperature,
                onValueChange = { temperature = it },
                valueRange = 0f..2f,
                steps = 19,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = maxTokens,
                    onValueChange = { v -> maxTokens = v.filter { it.isDigit() } },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.ai_provider_max_tokens)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = timeout,
                    onValueChange = { v -> timeout = v.filter { it.isDigit() } },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.ai_provider_timeout)) },
                    singleLine = true
                )
            }

            OutlinedTextField(
                value = extraPrompt,
                onValueChange = { extraPrompt = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                label = { Text(stringResource(R.string.ai_provider_system_prompt)) },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = !testing,
                    onClick = {
                        testing = true
                        val config = buildConfig(
                            kind, baseUrl, apiKey, model, temperature, maxTokens, timeout, extraPrompt
                        )
                        scope.launch {
                            val outcome = withContext(Dispatchers.IO) {
                                runCatching {
                                    AiClient().chat(
                                        config,
                                        listOf(
                                            ChatMessage(ChatRole.System, "ping"),
                                            ChatMessage(ChatRole.User, "Reply with the single word OK."),
                                        )
                                    )
                                }
                            }
                            testing = false
                            val message = outcome.fold(
                                onSuccess = { context.getString(R.string.ai_provider_test_ok) },
                                onFailure = { context.getString(R.string.ai_provider_test_fail, it.message ?: "unknown") }
                            )
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    }
                ) {
                    Text(stringResource(R.string.ai_provider_test))
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val config = buildConfig(
                            kind, baseUrl, apiKey, model, temperature, maxTokens, timeout, extraPrompt
                        )
                        AiProviderConfigStore.save(context, config)
                        Toast.makeText(context, R.string.ai_provider_saved, Toast.LENGTH_SHORT).show()
                        navigator.pop()
                    }
                ) {
                    Text(stringResource(R.string.ai_provider_save))
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ProviderChips(
    selected: AiProviderKind,
    onSelected: (AiProviderKind) -> Unit,
) {
    val choices = remember {
        listOf(
            AiProviderKind.OpenAiCompatible to R.string.ai_provider_openai,
            AiProviderKind.Anthropic to R.string.ai_provider_anthropic,
            AiProviderKind.Gemini to R.string.ai_provider_gemini,
        )
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        choices.forEach { (k, res) ->
            AssistChip(
                onClick = { onSelected(k) },
                label = { Text(stringResource(res)) },
                colors = if (k == selected) {
                    androidx.compose.material3.AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    androidx.compose.material3.AssistChipDefaults.assistChipColors()
                }
            )
        }
    }
}

private fun buildConfig(
    kind: AiProviderKind,
    baseUrl: String,
    apiKey: String,
    model: String,
    temperature: Float,
    maxTokens: String,
    timeout: String,
    extraPrompt: String,
): AiProviderConfig = AiProviderConfig(
    kind = kind,
    baseUrl = baseUrl.ifBlank { defaultBaseUrl(kind) },
    apiKey = apiKey,
    model = model.ifBlank { defaultModel(kind) },
    temperature = temperature,
    maxTokens = maxTokens.toIntOrNull() ?: 4096,
    timeoutSeconds = timeout.toIntOrNull() ?: 120,
    extraSystemPrompt = extraPrompt,
)
