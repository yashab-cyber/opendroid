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
class OllamaProvider @Inject constructor(
    private val client: OkHttpClient,
    private val settingsRepository: SettingsRepository
) : LLMProvider {

    override val name: String = "Ollama"
    override val availableModels: List<String> = listOf("llama3", "mistral", "phi3", "gemma", "codellama")

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val config = settingsRepository.llmConfig.first()
        val customUrl = config.customEndpoints[name]?.trim()
        val baseUrl = if (!customUrl.isNullOrBlank()) customUrl else "http://10.0.2.2:11434"
        val endpoint = "$baseUrl/api/chat"

        val startTime = System.currentTimeMillis()

        val messagesList = request.messages.toOpenAIMessages(request.systemPrompt)

        val requestBodyMap = mutableMapOf<String, Any>(
            "model" to config.activeModel,
            "messages" to messagesList,
            "stream" to false,
            "options" to mapOf(
                "temperature" to request.temperature,
                "num_predict" to request.maxTokens
            )
        )

        val bodyJson = gson.toJson(requestBodyMap)
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(bodyJson.toRequestBody(mediaType))

        // Add optional authorization if a bearer token key is configured
        val apiKey = config.apiKeys[name]
        if (!apiKey.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        return withContext(Dispatchers.IO) {
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Ollama request failed: Code ${response.code} - ${response.body?.string()}")
            }
            val responseBody = response.body?.string() ?: throw IOException("Empty response body from Ollama")
            val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
            val messageObj = jsonResponse.getAsJsonObject("message")
            val content = messageObj.get("content").asString

            val promptEvalCount = jsonResponse.get("prompt_eval_count")?.asInt ?: 0
            val evalCount = jsonResponse.get("eval_count")?.asInt ?: 0

            LLMResponse(
                content = content,
                tokensUsed = promptEvalCount + evalCount,
                model = config.activeModel,
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
        } catch (e: Exception) {
            emit("Error streaming Ollama: ${e.localizedMessage}")
        }
    }

    override suspend fun isAvailable(): Boolean {
        // Ollama runs locally, so it doesn't strict check API keys unless users want.
        // It is considered always available if active or setup.
        return true
    }
}
