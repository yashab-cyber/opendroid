package com.opendroid.ai.core.llm

import android.util.Log
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class AIModel(
    val id: String,
    val displayName: String,
    val provider: String,
    val contextWindow: Int? = null,
    val isRecommended: Boolean = false,
    val isPremium: Boolean = false,
    val isFree: Boolean = false
)

@Singleton
class ModelFetcher @Inject constructor(
    private val httpClient: OkHttpClient,
    private val settingsRepository: SettingsRepository
) {

    private val tag = "ModelFetcher"

    suspend fun fetchModels(provider: String): Result<List<AIModel>> = withContext(Dispatchers.IO) {
        val config = settingsRepository.llmConfig.first()
        val apiKey = config.apiKeys[provider]

        try {
            when (provider) {
                "Anthropic Claude" -> {
                    if (apiKey.isNullOrBlank()) return@withContext Result.success(getAnthropicFallback())
                    val request = Request.Builder()
                        .url("https://api.anthropic.com/v1/models")
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", "2023-06-01")
                        .get()
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                        val json = JSONObject(response.body?.string() ?: "")
                        val dataArray = json.getJSONArray("data")
                        val list = mutableListOf<AIModel>()
                        for (i in 0 until dataArray.length()) {
                            val obj = dataArray.getJSONObject(i)
                            val id = obj.getString("id")
                            list.add(
                                AIModel(
                                    id = id,
                                    displayName = formatModelName(id),
                                    provider = provider,
                                    isRecommended = id.contains("sonnet"),
                                    isPremium = id.contains("opus")
                                )
                            )
                        }
                        Result.success(list.sortedBy { it.displayName })
                    }
                }
                "OpenAI" -> {
                    if (apiKey.isNullOrBlank()) return@withContext Result.success(getOpenAIFallback())
                    val request = Request.Builder()
                        .url("https://api.openai.com/v1/models")
                        .header("Authorization", "Bearer $apiKey")
                        .get()
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                        val json = JSONObject(response.body?.string() ?: "")
                        val dataArray = json.getJSONArray("data")
                        val list = mutableListOf<AIModel>()
                        for (i in 0 until dataArray.length()) {
                            val obj = dataArray.getJSONObject(i)
                            val id = obj.getString("id")
                            // Filter only common chat models
                            if (id.startsWith("gpt-") || id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4")) {
                                list.add(
                                    AIModel(
                                        id = id,
                                        displayName = formatModelName(id),
                                        provider = provider,
                                        isRecommended = id == "gpt-4o",
                                        isPremium = id.contains("o1") || id.contains("o3")
                                    )
                                )
                            }
                        }
                        Result.success(list.sortedBy { it.displayName })
                    }
                }
                "OpenRouter" -> {
                    // OpenRouter model listing doesn't strictly require API key
                    val requestBuilder = Request.Builder()
                        .url("https://openrouter.ai/api/v1/models")
                        .get()
                    if (!apiKey.isNullOrBlank()) {
                        requestBuilder.header("Authorization", "Bearer $apiKey")
                    }
                    httpClient.newCall(requestBuilder.build()).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                        val json = JSONObject(response.body?.string() ?: "")
                        val dataArray = json.getJSONArray("data")
                        val list = mutableListOf<AIModel>()
                        for (i in 0 until dataArray.length()) {
                            val obj = dataArray.getJSONObject(i)
                            val id = obj.getString("id")
                            val name = obj.optString("name", id)
                            val contextLength = if (obj.has("context_length")) obj.getInt("context_length") else null
                            list.add(
                                AIModel(
                                    id = id,
                                    displayName = name,
                                    provider = provider,
                                    contextWindow = contextLength,
                                    isFree = id.contains(":free"),
                                    isRecommended = id.contains("gemini-2.0-flash-exp:free") || id.contains("llama-3-8b-instruct:free")
                                )
                            )
                        }
                        // Sort free models first, then alphabetically
                        Result.success(list.sortedWith(compareByDescending<AIModel> { it.isFree }.thenBy { it.displayName }))
                    }
                }
                "Groq" -> {
                    if (apiKey.isNullOrBlank()) return@withContext Result.success(getGroqFallback())
                    val request = Request.Builder()
                        .url("https://api.groq.com/openai/v1/models")
                        .header("Authorization", "Bearer $apiKey")
                        .get()
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                        val json = JSONObject(response.body?.string() ?: "")
                        val dataArray = json.getJSONArray("data")
                        val list = mutableListOf<AIModel>()
                        for (i in 0 until dataArray.length()) {
                            val obj = dataArray.getJSONObject(i)
                            val id = obj.getString("id")
                            list.add(
                                AIModel(
                                    id = id,
                                    displayName = formatModelName(id),
                                    provider = provider,
                                    isFree = true,
                                    isRecommended = id.contains("llama-3.3")
                                )
                            )
                        }
                        Result.success(list.sortedBy { it.displayName })
                    }
                }
                "Mistral AI" -> {
                    if (apiKey.isNullOrBlank()) return@withContext Result.success(getMistralFallback())
                    val request = Request.Builder()
                        .url("https://api.mistral.ai/v1/models")
                        .header("Authorization", "Bearer $apiKey")
                        .get()
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                        val json = JSONObject(response.body?.string() ?: "")
                        val dataArray = json.getJSONArray("data")
                        val list = mutableListOf<AIModel>()
                        for (i in 0 until dataArray.length()) {
                            val obj = dataArray.getJSONObject(i)
                            val id = obj.getString("id")
                            list.add(
                                AIModel(
                                    id = id,
                                    displayName = formatModelName(id),
                                    provider = provider,
                                    isRecommended = id.contains("large")
                                )
                            )
                        }
                        Result.success(list.sortedBy { it.displayName })
                    }
                }
                "Together AI" -> {
                    if (apiKey.isNullOrBlank()) return@withContext Result.success(getTogetherFallback())
                    val request = Request.Builder()
                        .url("https://api.together.xyz/v1/models")
                        .header("Authorization", "Bearer $apiKey")
                        .get()
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                        val json = JSONObject(response.body?.string() ?: "")
                        val dataArray = json.getJSONArray("data")
                        val list = mutableListOf<AIModel>()
                        for (i in 0 until dataArray.length()) {
                            val obj = dataArray.getJSONObject(i)
                            val id = obj.getString("id")
                            list.add(
                                AIModel(
                                    id = id,
                                    displayName = formatModelName(id),
                                    provider = provider,
                                    isRecommended = id.contains("Llama-3-70b")
                                )
                            )
                        }
                        Result.success(list.sortedBy { it.displayName })
                    }
                }
                "DeepSeek" -> {
                    if (apiKey.isNullOrBlank()) return@withContext Result.success(getDeepSeekFallback())
                    val request = Request.Builder()
                        .url("https://api.deepseek.com/models")
                        .header("Authorization", "Bearer $apiKey")
                        .get()
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                        val json = JSONObject(response.body?.string() ?: "")
                        val dataArray = json.getJSONArray("data")
                        val list = mutableListOf<AIModel>()
                        for (i in 0 until dataArray.length()) {
                            val obj = dataArray.getJSONObject(i)
                            val id = obj.getString("id")
                            list.add(
                                AIModel(
                                    id = id,
                                    displayName = formatModelName(id),
                                    provider = provider,
                                    isRecommended = id == "deepseek-chat"
                                )
                            )
                        }
                        Result.success(list.sortedBy { it.displayName })
                    }
                }
                "Cohere" -> {
                    if (apiKey.isNullOrBlank()) return@withContext Result.success(getCohereFallback())
                    val request = Request.Builder()
                        .url("https://api.cohere.com/v1/models")
                        .header("Authorization", "Bearer $apiKey")
                        .get()
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                        val json = JSONObject(response.body?.string() ?: "")
                        val dataArray = json.getJSONArray("models")
                        val list = mutableListOf<AIModel>()
                        for (i in 0 until dataArray.length()) {
                            val obj = dataArray.getJSONObject(i)
                            val id = obj.getString("name")
                            list.add(
                                AIModel(
                                    id = id,
                                    displayName = formatModelName(id),
                                    provider = provider,
                                    isRecommended = id.contains("command-r-plus")
                                )
                            )
                        }
                        Result.success(list.sortedBy { it.displayName })
                    }
                }
                "Ollama" -> {
                    val rawUrl = config.ollamaUrl.trim()
                    val baseUrl = if (rawUrl.isNotEmpty()) rawUrl else "http://10.0.2.2:11434"
                    val request = Request.Builder()
                        .url("$baseUrl/api/tags")
                        .get()
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                        val json = JSONObject(response.body?.string() ?: "")
                        val models = json.getJSONArray("models")
                        val list = mutableListOf<AIModel>()
                        for (i in 0 until models.length()) {
                            val obj = models.getJSONObject(i)
                            val name = obj.getString("name")
                            list.add(
                                AIModel(
                                    id = name,
                                    displayName = name,
                                    provider = provider,
                                    isFree = true,
                                    isRecommended = name.contains("llama3")
                                )
                            )
                        }
                        Result.success(list.sortedBy { it.displayName })
                    }
                }
                "Google Gemini" -> {
                    Result.success(getGeminiFallback())
                }
                "Copilot API" -> {
                    val rawUrl = config.copilotUrl.trim()
                    val baseUrl = if (rawUrl.isNotEmpty()) rawUrl else "http://10.0.2.2:4141"
                    val requestBuilder = Request.Builder()
                        .url(if (baseUrl.endsWith("/v1")) "$baseUrl/models" else "$baseUrl/v1/models")
                        .get()
                    if (!apiKey.isNullOrBlank()) {
                        requestBuilder.header("Authorization", "Bearer $apiKey")
                    }
                    try {
                        httpClient.newCall(requestBuilder.build()).execute().use { response ->
                            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                            val json = JSONObject(response.body?.string() ?: "")
                            val dataArray = json.getJSONArray("data")
                            val list = mutableListOf<AIModel>()
                            for (i in 0 until dataArray.length()) {
                                val obj = dataArray.getJSONObject(i)
                                val id = obj.getString("id")
                                list.add(
                                    AIModel(
                                        id = id,
                                        displayName = formatModelName(id),
                                        provider = provider,
                                        isRecommended = id.contains("gpt-4o")
                                    )
                                )
                            }
                            Result.success(list.sortedBy { it.displayName })
                        }
                    } catch (e: Exception) {
                        Result.success(getCopilotFallback())
                    }
                }
                "Custom OpenAI Compatible" -> {
                    val customUrl = config.customEndpoints[provider]?.trim() ?: ""
                    if (customUrl.isEmpty()) return@withContext Result.success(emptyList())
                    val requestBuilder = Request.Builder()
                        .url(if (customUrl.endsWith("/v1")) "$customUrl/models" else "$customUrl/v1/models")
                        .get()
                    if (!apiKey.isNullOrBlank()) {
                        requestBuilder.header("Authorization", "Bearer $apiKey")
                    }
                    try {
                        httpClient.newCall(requestBuilder.build()).execute().use { response ->
                            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                            val json = JSONObject(response.body?.string() ?: "")
                            val dataArray = json.getJSONArray("data")
                            val list = mutableListOf<AIModel>()
                            for (i in 0 until dataArray.length()) {
                                val obj = dataArray.getJSONObject(i)
                                val id = obj.getString("id")
                                list.add(
                                    AIModel(
                                        id = id,
                                        displayName = formatModelName(id),
                                        provider = provider
                                    )
                                )
                            }
                            Result.success(list.sortedBy { it.displayName })
                        }
                    } catch (e: Exception) {
                        Result.success(emptyList())
                    }
                }
                else -> Result.success(emptyList())
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to fetch models for provider $provider: ${e.localizedMessage}", e)
            // Fallback strategy if call fails but credentials might exist
            val fallback = when (provider) {
                "Anthropic Claude" -> getAnthropicFallback()
                "OpenAI" -> getOpenAIFallback()
                "Groq" -> getGroqFallback()
                "Mistral AI" -> getMistralFallback()
                "Together AI" -> getTogetherFallback()
                "DeepSeek" -> getDeepSeekFallback()
                "Cohere" -> getCohereFallback()
                "Google Gemini" -> getGeminiFallback()
                "Copilot API" -> getCopilotFallback()
                else -> emptyList()
            }
            Result.success(fallback)
        }
    }

    private fun formatModelName(id: String): String {
        return id
            .substringAfterLast("/")
            .replace("-", " ")
            .replace("_", " ")
            .split(" ")
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                when (word.lowercase()) {
                    "gpt" -> "GPT"
                    "llm" -> "LLM"
                    "ai" -> "AI"
                    "it" -> "IT"
                    "instruct" -> "Instruct"
                    else -> word.replaceFirstChar { if (it.isLowerCase()) it.uppercase() else it.toString() }
                }
            }
    }

    private fun getAnthropicFallback() = listOf(
        AIModel("claude-sonnet-4-6", "Claude Sonnet 4.6", "Anthropic Claude", isRecommended = true),
        AIModel("claude-haiku-4-5", "Claude Haiku 4.5", "Anthropic Claude", isFree = true),
        AIModel("claude-opus-4-8", "Claude Opus 4.8", "Anthropic Claude", isPremium = true)
    )

    private fun getOpenAIFallback() = listOf(
        AIModel("gpt-4o", "GPT-4o", "OpenAI", isRecommended = true),
        AIModel("gpt-4o-mini", "GPT-4o Mini", "OpenAI", isFree = true),
        AIModel("gpt-4-turbo", "GPT-4 Turbo", "OpenAI"),
        AIModel("o1-preview", "O1 Preview", "OpenAI", isPremium = true),
        AIModel("o3-mini", "O3 Mini", "OpenAI", isPremium = true)
    )

    private fun getGroqFallback() = listOf(
        AIModel("llama-3.3-70b-specdec", "Llama 3.3 70B SpecDec", "Groq", isFree = true, isRecommended = true),
        AIModel("llama3-70b-8192", "Llama 3 70B", "Groq", isFree = true),
        AIModel("gemma2-9b-it", "Gemma 2 9B IT", "Groq", isFree = true),
        AIModel("mixtral-8x7b-32768", "Mixtral 8x7B", "Groq", isFree = true)
    )

    private fun getMistralFallback() = listOf(
        AIModel("mistral-large-latest", "Mistral Large Latest", "Mistral AI", isRecommended = true),
        AIModel("mistral-medium", "Mistral Medium", "Mistral AI"),
        AIModel("mistral-small", "Mistral Small", "Mistral AI"),
        AIModel("open-mixtral-8x7b", "Open Mixtral 8x7B", "Mistral AI")
    )

    private fun getTogetherFallback() = listOf(
        AIModel("meta-llama/Llama-3-70b-chat-hf", "Llama 3 70B Chat HF", "Together AI", isRecommended = true),
        AIModel("meta-llama/Llama-3-8b-chat-hf", "Llama 3 8B Chat HF", "Together AI"),
        AIModel("Qwen/Qwen1.5-72B-Chat", "Qwen 1.5 72B Chat", "Together AI")
    )

    private fun getDeepSeekFallback() = listOf(
        AIModel("deepseek-chat", "DeepSeek Chat", "DeepSeek", isRecommended = true),
        AIModel("deepseek-reasoner", "DeepSeek Reasoner", "DeepSeek", isPremium = true)
    )

    private fun getCohereFallback() = listOf(
        AIModel("command-r-plus", "Command R Plus", "Cohere", isRecommended = true),
        AIModel("command-r", "Command R", "Cohere")
    )

    private fun getGeminiFallback() = listOf(
        AIModel("gemini-2.0-flash", "Gemini 2.0 Flash", "Google Gemini", isRecommended = true, isFree = true),
        AIModel("gemini-1.5-pro", "Gemini 1.5 Pro", "Google Gemini", isPremium = true),
        AIModel("gemini-1.5-flash", "Gemini 1.5 Flash", "Google Gemini", isFree = true),
        AIModel("gemini-nano", "Gemini Nano (On-device Mock)", "Google Gemini")
    )

    private fun getCopilotFallback() = listOf(
        AIModel("gpt-4o", "GPT-4o", "Copilot API", isRecommended = true),
        AIModel("gpt-4-turbo", "GPT-4 Turbo", "Copilot API"),
        AIModel("gpt-3.5-turbo", "GPT-3.5 Turbo", "Copilot API")
    )
}
