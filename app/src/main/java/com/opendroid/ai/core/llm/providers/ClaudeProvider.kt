package com.opendroid.ai.core.llm.providers

import com.google.gson.Gson
import com.opendroid.ai.core.llm.*
import com.opendroid.ai.core.llm.error.LLMException
import com.opendroid.ai.core.llm.error.LLMErrorMapper
import com.opendroid.ai.core.llm.error.ProviderErrorDetail
import com.opendroid.ai.core.llm.error.toSafeProviderException
import com.opendroid.ai.data.models.resolveClaudeModelOrNull
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClaudeProvider @Inject constructor(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) : LLMProvider {

    override val name: String = "Anthropic Claude"
    override val availableModels: List<String> = ClaudeModelCatalog.models.map { it.id }

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val config = settingsRepository.llmConfig.first()
        val apiKey = request.providerConfig?.apiKey?.takeIf { it.isNotBlank() }
            ?: config.apiKeys[name]
            ?: throw IllegalStateException("API Key for $name is not set.")

        val startTime = System.currentTimeMillis()

        // Model IDs are untrusted input: accept catalog entries (incl. legacy
        // aliases) and IDs Anthropic listed via /v1/models (cached on LLMConfig),
        // never an arbitrary persisted string.
        val requestedModel = request.model?.takeIf { it.isNotBlank() }
        val selectedModel = if (requestedModel == null) {
            ClaudeModelCatalog.defaultModelId
        } else {
            config.resolveClaudeModelOrNull(requestedModel)
                ?: throw IllegalStateException(
                    "The selected Claude model \"$requestedModel\" is no longer supported. " +
                        "Please pick another model in Settings."
                )
        }

        val messagesList = mutableListOf<Map<String, Any>>()
        request.messages.forEach { msg ->
            val role = if (msg.sender == com.opendroid.ai.data.models.ChatMessage.Sender.USER) "user" else "assistant"
            if (msg.imageBase64 != null && role == "user") {
                messagesList.add(
                    mapOf(
                        "role" to role,
                        "content" to listOf(
                            mapOf("type" to "text", "text" to msg.text),
                            mapOf(
                                "type" to "image",
                                "source" to mapOf(
                                    "type" to "base64",
                                    "media_type" to "image/jpeg",
                                    "data" to msg.imageBase64
                                )
                            )
                        )
                    )
                )
            } else {
                messagesList.add(mapOf("role" to role, "content" to msg.text))
            }
        }

        val requestBodyMap = mutableMapOf<String, Any>(
            "model" to selectedModel,
            "system" to request.systemPrompt,
            "messages" to messagesList,
            "max_tokens" to request.maxTokens
        )
        // Current Opus-tier models reject sampling parameters with HTTP 400.
        if (ClaudeModelCatalog.acceptsSamplingParameters(selectedModel)) {
            requestBodyMap["temperature"] = request.temperature
        }

        val bodyJson = gson.toJson(requestBodyMap)
        val httpRequest = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(bodyJson.toRequestBody(mediaType))
            .build()

        return withContext(Dispatchers.IO) {
        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw response.toSafeProviderException(
                    provider = ProviderErrorDetail.Provider.CLAUDE,
                    request = request,
                    knownSecrets = listOf(apiKey)
                )
            }
            val responseBody = response.body.string()
            if (responseBody.isBlank()) throw IOException("Empty response body from Claude")
            val jsonResponse = parseJsonObject(responseBody, name, selectedModel)
            // Joins every text block; thinking and tool_use blocks carry no reply text.
            val content = textOf(jsonResponse.get("content"))
                ?: throw LLMErrorMapper.malformed(name, selectedModel)

            val usage = jsonResponse.objectOrNull("usage")
            val inputTokens = usage?.intOrNull("input_tokens") ?: 0
            val outputTokens = usage?.intOrNull("output_tokens") ?: 0

            LLMResponse(
                content = content,
                tokensUsed = inputTokens + outputTokens,
                model = selectedModel,
                provider = name,
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
        } // withContext
    }

    override fun streamComplete(request: LLMRequest): Flow<String> = flow {
        try {
            val response = complete(request)
            val words = response.content.split(" ")
            for (word in words) {
                emit("$word ")
                kotlinx.coroutines.delay(50)
            }
        } catch (e: CancellationException) {
            // Never convert cancellation into a failure: it must propagate so the
            // collecting coroutine unwinds normally.
            throw e
        } catch (e: LLMException) {
            // Typed API errors (rate limit, invalid key, server errors) must reach
            // AgentLoop's LLMException handlers unchanged; wrapping them in IOException
            // would bypass publishChatError and the structured error card.
            throw e
        } catch (e: IllegalStateException) {
            // Configuration failures belong in AgentLoop's Error state, not in
            // persisted assistant content that looks like a Claude response.
            throw e
        } catch (e: Exception) {
            throw IOException("Claude streaming failed.", e)
        }
    }

    override suspend fun isAvailable(): Boolean {
        val config = settingsRepository.llmConfig.first()
        return !config.apiKeys[name].isNullOrBlank()
    }
}
