package com.opendroid.ai.core.llm.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.opendroid.ai.core.llm.*
import com.opendroid.ai.core.llm.error.LLMErrorMapper
import com.opendroid.ai.core.llm.error.ProviderErrorDetail
import com.opendroid.ai.core.llm.error.toSafeProviderException
import com.opendroid.ai.data.repository.SettingsRepository
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
class GeminiProvider @Inject constructor(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) : LLMProvider {

    override val name: String = "Google Gemini"
    override val availableModels: List<String> = listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-pro", "gemini-nano")

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val config = settingsRepository.llmConfig.first()
        val selectedModel = request.model?.takeIf { it.isNotBlank() } ?: ProviderCatalog.defaultModel(name)

        // On-device Nano Mock fallback (to prevent crashes and support offline testing)
        if (selectedModel == "gemini-nano") {
            return executeNanoMock(request)
        }

        val apiKey = request.providerConfig?.apiKey?.takeIf { it.isNotBlank() }
            ?: config.apiKeys[name]
            ?: throw IllegalStateException("API Key for $name is not set.")
        val startTime = System.currentTimeMillis()

        // Map roles to user and model
        val contentsList = mutableListOf<Map<String, Any>>()
        request.messages.forEach { msg ->
            val role = if (msg.sender == com.opendroid.ai.data.models.ChatMessage.Sender.USER) "user" else "model"
            val partsList = mutableListOf<Map<String, Any>>()
            partsList.add(mapOf("text" to msg.text))
            if (msg.imageBase64 != null && role == "user") {
                partsList.add(
                    mapOf(
                        "inlineData" to mapOf(
                            "mimeType" to "image/jpeg",
                            "data" to msg.imageBase64
                        )
                    )
                )
            }
            contentsList.add(
                mapOf(
                    "role" to role,
                    "parts" to partsList
                )
            )
        }

        val requestBodyMap = mutableMapOf<String, Any>(
            "contents" to contentsList,
            "systemInstruction" to mapOf(
                "parts" to listOf(mapOf("text" to request.systemPrompt))
            )
        )

        val generationConfig = mutableMapOf<String, Any>(
            "temperature" to request.temperature,
            "maxOutputTokens" to request.maxTokens
        )
        if (request.responseFormat == ResponseFormat.JSON) {
            generationConfig["responseMimeType"] = "application/json"
        }
        requestBodyMap["generationConfig"] = generationConfig

        val bodyJson = gson.toJson(requestBodyMap)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$selectedModel:generateContent"
        val httpRequest = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .post(bodyJson.toRequestBody(mediaType))
            .build()

        return withContext(Dispatchers.IO) {
        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw response.toSafeProviderException(
                    provider = ProviderErrorDetail.Provider.GEMINI,
                    request = request,
                    knownSecrets = listOf(apiKey)
                )
            }
            val responseBody = response.body.string()
            if (responseBody.isBlank()) {
                throw IOException("Empty response body from Gemini")
            }
            val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
            val text = replyText(jsonResponse)
                ?: throw if (wasBlocked(jsonResponse)) {
                    LLMErrorMapper.requestInvalid(name, selectedModel)
                } else {
                    // e.g. finishReason MAX_TOKENS with no parts: on 2.5 models thinking
                    // counts against maxOutputTokens and can use up a small budget (#87).
                    LLMErrorMapper.malformed(name, selectedModel)
                }

            val usageMetadata = jsonResponse.get("usageMetadata")?.takeIf { it.isJsonObject }?.asJsonObject
            val totalTokens = usageMetadata?.get("totalTokenCount")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

            LLMResponse(
                content = text,
                tokensUsed = totalTokens,
                model = selectedModel,
                provider = name,
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
        } // withContext
    }

    /** The first candidate's text parts joined, or null when it has none. */
    private fun replyText(response: JsonObject): String? {
        val candidate = response.get("candidates")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val parts = candidate.get("content")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("parts")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        return parts
            .mapNotNull { part -> part.takeIf { it.isJsonObject }?.asJsonObject }
            .filterNot { part -> part.get("thought")?.takeIf { it.isJsonPrimitive }?.asBoolean == true }
            .mapNotNull { part -> part.get("text")?.takeIf { it.isJsonPrimitive }?.asString }
            .joinToString("")
            .ifEmpty { null }
    }

    /** Gemini refused the prompt, or stopped the candidate, for a content policy reason. */
    private fun wasBlocked(response: JsonObject): Boolean {
        val promptBlocked = response.get("promptFeedback")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.has("blockReason") == true
        val finishReason = response.get("candidates")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("finishReason")?.takeIf { it.isJsonPrimitive }?.asString
        return promptBlocked || finishReason in BLOCKED_FINISH_REASONS
    }

    private fun executeNanoMock(request: LLMRequest): LLMResponse {
        val startTime = System.currentTimeMillis()
        // If it's a planning query, return a mocked but structured simple plan response
        val mockContent = if (request.systemPrompt.contains("JSON") || request.responseFormat == ResponseFormat.JSON) {
            """
            {
              "speech": "I have set up a quick on-device task flow.",
              "type": "SIMPLE",
              "action": "WEB_SEARCH",
              "params": {"query": "OpenDroid on-device response"},
              "plan": null,
              "memoryUpdate": null,
              "confidence": 0.95,
              "needsClarification": false,
              "clarificationQuestion": null
            }
            """.trimIndent()
        } else {
            "OpenDroid on-device Gemini Nano mock completed successfully."
        }

        return LLMResponse(
            content = mockContent,
            tokensUsed = 120,
            model = "gemini-nano",
            provider = name,
            latencyMs = System.currentTimeMillis() - startTime
        )
    }

    override fun streamComplete(request: LLMRequest): Flow<String> = flow {
        val response = complete(request)
        val words = response.content.split(" ")
        for (word in words) {
            emit("$word ")
            kotlinx.coroutines.delay(50)
        }
    }

    override suspend fun isAvailable(): Boolean {
        val config = settingsRepository.llmConfig.first()
        if (config.activeModel == "gemini-nano") return true // always available locally
        return !config.apiKeys[name].isNullOrBlank()
    }
}

/** Gemini finish reasons that mean the candidate was withheld for policy reasons. */
private val BLOCKED_FINISH_REASONS = setOf(
    "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY"
)
