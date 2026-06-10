package com.opendroid.ai.core.llm.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.opendroid.ai.core.llm.*
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
    override val availableModels: List<String> = listOf("gemini-2.0-flash", "gemini-1.5-pro", "gemini-nano")

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val config = settingsRepository.llmConfig.first()
        val activeModel = config.activeModel

        // On-device Nano Mock fallback (to prevent crashes and support offline testing)
        if (activeModel == "gemini-nano") {
            return executeNanoMock(request)
        }

        val apiKey = config.apiKeys[name] ?: throw IllegalStateException("API Key for $name is not set.")
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
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$activeModel:generateContent?key=$apiKey"
        val httpRequest = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody(mediaType))
            .build()

        return withContext(Dispatchers.IO) {
        client.newCall(httpRequest).execute().use { response ->
            val responseBody = response.body?.string()
            if (!response.isSuccessful) {
                throw IOException("Gemini request failed: Code ${response.code} - $responseBody")
            }
            if (responseBody == null) {
                throw IOException("Empty response body from Gemini")
            }
            val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
            val candidates = jsonResponse.getAsJsonArray("candidates")
            val firstCandidate = candidates[0].asJsonObject
            val contentObj = firstCandidate.getAsJsonObject("content")
            val parts = contentObj.getAsJsonArray("parts")
            val text = parts[0].asJsonObject.get("text").asString

            val usageMetadata = jsonResponse.getAsJsonObject("usageMetadata")
            val totalTokens = usageMetadata?.get("totalTokenCount")?.asInt ?: 0

            LLMResponse(
                content = text,
                tokensUsed = totalTokens,
                model = activeModel,
                provider = name,
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
        } // withContext
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
        try {
            val response = complete(request)
            val words = response.content.split(" ")
            for (word in words) {
                emit("$word ")
                kotlinx.coroutines.delay(50)
            }
        } catch (e: Exception) {
            emit("Error streaming Gemini: ${e.localizedMessage}")
        }
    }

    override suspend fun isAvailable(): Boolean {
        val config = settingsRepository.llmConfig.first()
        if (config.activeModel == "gemini-nano") return true // always available locally
        return !config.apiKeys[name].isNullOrBlank()
    }
}
