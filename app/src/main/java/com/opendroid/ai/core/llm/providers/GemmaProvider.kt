package com.opendroid.ai.core.llm.providers

import android.content.Context
import com.google.mlkit.genai.prompt.*
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.DownloadStatus
import com.opendroid.ai.core.llm.*
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GemmaProvider @Inject constructor(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) : LLMProvider {

    override val name: String = "Gemma 4 (On-device)"
    override val availableModels: List<String> = listOf("gemma-4-on-device", "gemma-3n-multimodal")

    private fun getActiveModelName(): String {
        return "gemma-on-device"
    }

    /**
     * Returns the appropriate GenerativeModel client based on the selected model.
     * - gemma-4-on-device: Uses default (STABLE) config
     * - gemma-3n-multimodal: Uses PREVIEW stage with FAST preference for multimodal support
     */
    private fun getClientForModel(modelId: String): GenerativeModel {
        return if (modelId == "gemma-3n-multimodal") {
            val config = generationConfig {
                modelConfig = modelConfig {
                    releaseStage = ModelReleaseStage.PREVIEW
                    preference = ModelPreference.FAST
                }
            }
            Generation.getClient(config)
        } else {
            Generation.getClient()
        }
    }

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val startTime = System.currentTimeMillis()
        val config = settingsRepository.llmConfig.first()
        val selectedModel = config.activeModel
        val systemPrompt = request.systemPrompt
        val messages = request.messages
        val prompt = buildPrompt(systemPrompt, messages, request.tools?.map { ToolDefinition(it.name, it.description, it.parameters) } ?: emptyList())

        return withContext(Dispatchers.IO) {
            try {
                val generativeModel = getClientForModel(selectedModel)
                val status = generativeModel.checkStatus()
                
                when (status) {
                    0 -> throw IllegalStateException("This device does not support Google AI Core. Try selecting a different model.")
                    1, 2 -> throw IllegalStateException("On-device model is downloading or not installed. Please try again later.")
                    3 -> { /* Ready */ }
                }

                val contentRequest = generateContentRequest(TextPart(prompt)) {
                    maxOutputTokens = request.maxTokens
                }

                val response = generativeModel.generateContent(contentRequest)
                val outputText = response.candidates.firstOrNull()?.text ?: ""

                LLMResponse(
                    content = outputText,
                    tokensUsed = outputText.length / 4,
                    model = selectedModel,
                    provider = name,
                    latencyMs = System.currentTimeMillis() - startTime
                )
            } catch (e: Exception) {
                throw handleException(e)
            }
        }
    }

    override fun streamComplete(request: LLMRequest): Flow<String> = flow {
        try {
            val config = settingsRepository.llmConfig.first()
            val selectedModel = config.activeModel
            val generativeModel = getClientForModel(selectedModel)
            val status = generativeModel.checkStatus()
            
            when (status) {
                0 -> {
                    emit("Error: This device does not support Google AI Core. Try selecting a different model.")
                    return@flow
                }
                1, 2 -> {
                    emit("Error: On-device model is downloading or not installed. Please try again later.")
                    return@flow
                }
                3 -> { /* Ready */ }
            }

            val systemPrompt = request.systemPrompt
            val messages = request.messages
            val prompt = buildPrompt(systemPrompt, messages, request.tools?.map { ToolDefinition(it.name, it.description, it.parameters) } ?: emptyList())

            val contentRequest = generateContentRequest(TextPart(prompt)) {
                maxOutputTokens = request.maxTokens
            }

            generativeModel.generateContentStream(contentRequest).collect { chunk ->
                emit(chunk.candidates.firstOrNull()?.text ?: "")
            }
        } catch (e: Exception) {
            emit("Error streaming on-device model: ${handleException(e).localizedMessage}")
        }
    }

    override suspend fun generate(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>
    ): Flow<StreamChunk> = flow {
        try {
            val config = settingsRepository.llmConfig.first()
            val selectedModel = config.activeModel
            val generativeModel = getClientForModel(selectedModel)
            val status = generativeModel.checkStatus()
            
            when (status) {
                0 -> {
                    emit(StreamChunk.Content("Error: This device does not support Google AI Core. Try selecting a different model."))
                    return@flow
                }
                1, 2 -> {
                    emit(StreamChunk.Content("Error: On-device model is downloading or not installed. Please try again later."))
                    return@flow
                }
                3 -> { /* Ready */ }
            }

            val systemPrompt = "You are an autonomous AI agent for Android."
            val prompt = buildPrompt(systemPrompt, messages, tools)

            val contentRequest = generateContentRequest(TextPart(prompt)) {
                maxOutputTokens = 2000
            }

            var accumulatedText = ""
            generativeModel.generateContentStream(contentRequest).collect { chunk ->
                val txt = chunk.candidates.firstOrNull()?.text ?: ""
                accumulatedText += txt
                emit(StreamChunk.Content(txt))
            }

            try {
                val cleaned = accumulatedText.trim()
                if (cleaned.startsWith("{") && cleaned.endsWith("}")) {
                    val jsonObj = JSONObject(cleaned)
                    if (jsonObj.has("toolCall")) {
                        val toolCallObj = jsonObj.getJSONObject("toolCall")
                        val toolName = toolCallObj.getString("name")
                        val argsObj = toolCallObj.optJSONObject("arguments") ?: JSONObject()
                        emit(StreamChunk.ToolCall(toolName, argsObj.toString()))
                    }
                }
            } catch (jsonEx: Exception) {
                // Ignore and treat as plain text if JSON parsing fails
            }
        } catch (e: Exception) {
            emit(StreamChunk.Content("Error: ${handleException(e).localizedMessage}"))
        }
    }

    override suspend fun isAvailable(): Boolean {
        return try {
            // Check if any on-device model variant is available
            val defaultClient = Generation.getClient()
            if (defaultClient.checkStatus() == 3) return true
            
            // Also check PREVIEW variant (Gemma 3n)
            val previewConfig = generationConfig {
                modelConfig = modelConfig {
                    releaseStage = ModelReleaseStage.PREVIEW
                    preference = ModelPreference.FAST
                }
            }
            val previewClient = Generation.getClient(previewConfig)
            previewClient.checkStatus() == 3
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getFeatureStatus(): Int {
        return try {
            val generativeModel = Generation.getClient()
            generativeModel.checkStatus()
        } catch (e: Exception) {
            0
        }
    }

    suspend fun getFeatureStatusForModel(modelId: String): Int {
        return try {
            val generativeModel = getClientForModel(modelId)
            generativeModel.checkStatus()
        } catch (e: Exception) {
            0
        }
    }

    fun triggerModelDownload(): Flow<DownloadStatus> {
        return try {
            val generativeModel = Generation.getClient()
            generativeModel.download()
        } catch (e: Exception) {
            flow { 
                emit(DownloadStatus.DownloadFailed(
                    if (e is com.google.mlkit.genai.common.GenAiException) e 
                    else com.google.mlkit.genai.common.GenAiException(e.localizedMessage ?: "Download failed", e, 1)
                )) 
            }
        }
    }

    fun triggerModelDownloadForModel(modelId: String): Flow<DownloadStatus> {
        return try {
            val generativeModel = getClientForModel(modelId)
            generativeModel.download()
        } catch (e: Exception) {
            flow { 
                emit(DownloadStatus.DownloadFailed(
                    if (e is com.google.mlkit.genai.common.GenAiException) e 
                    else com.google.mlkit.genai.common.GenAiException(e.localizedMessage ?: "Download failed", e, 1)
                )) 
            }
        }
    }

    private fun buildPrompt(systemPrompt: String, messages: List<ChatMessage>, tools: List<ToolDefinition>): String {
        val sb = StringBuilder()
        if (systemPrompt.isNotEmpty()) {
            sb.append("System Instructions:\n").append(systemPrompt).append("\n\n")
        }

        if (tools.isNotEmpty()) {
            sb.append("Available tools you can call:\n")
            tools.forEach { tool ->
                sb.append("- Tool: ").append(tool.name).append("\n")
                sb.append("  Description: ").append(tool.description).append("\n")
                sb.append("  Parameters schema: ").append(tool.parameters).append("\n\n")
            }
            sb.append("If you need to call a tool, respond ONLY with a JSON object conforming exactly to this format:\n")
            sb.append("{\n")
            sb.append("  \"toolCall\": {\n")
            sb.append("    \"name\": \"TOOL_NAME\",\n")
            sb.append("    \"arguments\": { ... }\n")
            sb.append("  }\n")
            sb.append("}\n")
            sb.append("Do not add markdown formatting or backticks around the JSON. Output only the raw JSON. If no tool is needed, respond with standard text.\n\n")
        }

        sb.append("Conversation History:\n")
        messages.forEach { msg ->
            val sender = if (msg.sender == ChatMessage.Sender.USER) "User" else "Model"
            sb.append(sender).append(": ").append(msg.text).append("\n")
        }
        sb.append("Model:")
        return sb.toString()
    }

    private fun handleException(e: Exception): Exception {
        return when (e) {
            is TimeoutException -> IOException("Connection timed out while generating response.", e)
            is IllegalStateException -> e
            else -> {
                val msg = e.localizedMessage ?: ""
                if (msg.contains("Core", ignoreCase = true) || msg.contains("Service", ignoreCase = true)) {
                    IOException("Google AI Core is not supported or not installed.", e)
                } else if (msg.contains("permission", ignoreCase = true)) {
                    IOException("Permission denied to access AI Core.", e)
                } else if (msg.contains("cancel", ignoreCase = true)) {
                    IOException("Generation cancelled.", e)
                } else {
                    IOException("Error generating content: $msg", e)
                }
            }
        }
    }
}
