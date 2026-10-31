package com.opendroid.ai.core.llm.providers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.opendroid.ai.core.llm.*
import com.opendroid.ai.core.llm.error.ProviderErrorDetail
import com.opendroid.ai.core.llm.error.toSafeProviderException
import com.opendroid.ai.core.util.NetworkErrorFormatter
import com.opendroid.ai.core.util.UrlUtils
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.models.resolvedOllamaTimeoutSeconds
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
import java.util.concurrent.TimeUnit
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
        val selectedModel = request.model?.takeIf { it.isNotBlank() } ?: ProviderCatalog.defaultModel(name)
        val baseUrl = UrlUtils.formatBaseUrl(
            request.providerConfig?.endpoint?.takeIf { it.isNotBlank() } ?: config.ollamaUrl,
            ""
        )
        if (baseUrl.isEmpty()) {
            throw IllegalStateException("Ollama server URL is not configured. Set it in Settings.")
        }
        val endpoint = "$baseUrl/api/chat"

        val startTime = System.currentTimeMillis()

        val messagesList = request.messages.toOpenAIMessages(request.systemPrompt)

        val requestBodyMap = mutableMapOf<String, Any>(
            "model" to selectedModel,
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
        val apiKey = request.providerConfig?.apiKey?.takeIf { it.isNotBlank() } ?: config.apiKeys[name]
        if (!apiKey.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        return withContext(Dispatchers.IO) {
        httpClientFor(config).newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw response.toSafeProviderException(
                    provider = ProviderErrorDetail.Provider.OLLAMA,
                    request = request,
                    knownSecrets = listOfNotNull(apiKey)
                )
            }
            val responseBody = response.body.string()
            if (responseBody.isBlank()) throw IOException("Empty response body from Ollama")
            val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
            val messageObj = jsonResponse.getAsJsonObject("message")
            val content = messageObj.get("content").asString

            val promptEvalCount = jsonResponse.get("prompt_eval_count")?.asInt ?: 0
            val evalCount = jsonResponse.get("eval_count")?.asInt ?: 0

            LLMResponse(
                content = content,
                tokensUsed = promptEvalCount + evalCount,
                model = selectedModel,
                provider = name,
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
        } // withContext
    }

    /**
     * The shared client's 15-second limits suit cloud APIs, but a local server can spend
     * longer than that loading a model before the first byte. Connect stays at the shared
     * limit; only read/write follow the user's setting. newBuilder() shares the pool.
     */
    internal fun httpClientFor(config: LLMConfig): OkHttpClient {
        val seconds = config.resolvedOllamaTimeoutSeconds().toLong()
        return client.newBuilder()
            .readTimeout(seconds, TimeUnit.SECONDS)
            .writeTimeout(seconds, TimeUnit.SECONDS)
            .build()
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
            emit("Error streaming Ollama: ${NetworkErrorFormatter.toUserMessage(e)}")
        }
    }

    override suspend fun isAvailable(): Boolean {
        val config = settingsRepository.llmConfig.first()
        return config.ollamaUrl.trim().isNotEmpty()
    }

}
