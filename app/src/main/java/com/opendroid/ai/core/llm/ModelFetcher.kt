package com.opendroid.ai.core.llm

import android.util.Log
import com.opendroid.ai.core.llm.OnDeviceModelRegistry
import com.opendroid.ai.core.util.UrlUtils
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
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

/**
 * The outcome of a model list lookup.
 *
 * Model lists are never hardcoded: every remote provider is asked what it
 * currently serves, so a model the provider retires disappears from the picker
 * without an app release. That leaves two non-success states the caller has to
 * be able to tell apart from an empty provider, which is why this type exists
 * instead of a bare list:
 *
 * - [NeedsCredentials]: nothing was requested because the provider cannot be
 *   listed anonymously and no key/endpoint is configured yet.
 * - [Failed]: the provider was asked and did not answer usably. The previous
 *   cached list stays in place; it is not replaced with stale bundled names.
 */
sealed interface ModelFetchOutcome {
    data class Success(val models: List<AIModel>) : ModelFetchOutcome
    data class NeedsCredentials(val message: String) : ModelFetchOutcome
    data class Failed(val message: String) : ModelFetchOutcome
}

@Singleton
class ModelFetcher @Inject constructor(
    private val httpClient: OkHttpClient,
    private val settingsRepository: SettingsRepository
) {

    private val tag = "ModelFetcher"

    suspend fun fetchModels(provider: String): ModelFetchOutcome = withContext(Dispatchers.IO) {
        val config = settingsRepository.llmConfig.first()
        val apiKey = config.apiKeys[provider]?.takeIf { it.isNotBlank() }

        try {
            when (ProviderCatalog.canonicalName(provider)) {
                "Anthropic Claude" -> {
                    apiKey ?: return@withContext needsKey(provider)
                    val page = getJson(
                        url = "https://api.anthropic.com/v1/models?limit=1000",
                        headers = mapOf(
                            "x-api-key" to apiKey,
                            "anthropic-version" to "2023-06-01"
                        )
                    )
                    ModelFetchOutcome.Success(ModelListParsers.anthropic(page, provider))
                }

                "Google Gemini" -> {
                    apiKey ?: return@withContext needsKey(provider)
                    ModelFetchOutcome.Success(fetchGemini(apiKey, provider))
                }

                "OpenAI" -> {
                    apiKey ?: return@withContext needsKey(provider)
                    ModelFetchOutcome.Success(
                        openAiStyle("https://api.openai.com/v1/models", apiKey, provider)
                    )
                }

                "Groq" -> {
                    apiKey ?: return@withContext needsKey(provider)
                    ModelFetchOutcome.Success(
                        openAiStyle("https://api.groq.com/openai/v1/models", apiKey, provider)
                    )
                }

                "Mistral AI" -> {
                    apiKey ?: return@withContext needsKey(provider)
                    ModelFetchOutcome.Success(
                        openAiStyle("https://api.mistral.ai/v1/models", apiKey, provider)
                    )
                }

                "Together AI" -> {
                    apiKey ?: return@withContext needsKey(provider)
                    ModelFetchOutcome.Success(
                        openAiStyle("https://api.together.xyz/v1/models", apiKey, provider)
                    )
                }

                "DeepSeek" -> {
                    apiKey ?: return@withContext needsKey(provider)
                    ModelFetchOutcome.Success(
                        openAiStyle("https://api.deepseek.com/models", apiKey, provider)
                    )
                }

                "OpenRouter" -> {
                    // OpenRouter lists its catalog anonymously; a key only personalizes it.
                    val page = getJson(
                        url = "https://openrouter.ai/api/v1/models",
                        headers = apiKey?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
                    )
                    ModelFetchOutcome.Success(ModelListParsers.openRouter(page, provider))
                }

                "Requesty" -> {
                    // Managed policies are Requesty's curated routing chains and are
                    // listed anonymously. The full catalog is merged in so any
                    // vendor/model id stays selectable; a key narrows it to the
                    // models the organization allows.
                    val managed = runCatching {
                        getJson(url = "https://router.requesty.ai/v1/models/managed", headers = emptyMap())
                    }
                    val catalog = runCatching {
                        getJson(
                            url = "https://router.requesty.ai/v1/models",
                            headers = apiKey?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
                        )
                    }
                    if (managed.isFailure && catalog.isFailure) throw catalog.exceptionOrNull()!!
                    ModelFetchOutcome.Success(
                        ModelListParsers.requesty(managed.getOrNull(), catalog.getOrNull(), provider)
                    )
                }

                "Cohere" -> {
                    apiKey ?: return@withContext needsKey(provider)
                    val page = getJson(
                        url = "https://api.cohere.com/v1/models?page_size=1000",
                        headers = mapOf("Authorization" to "Bearer $apiKey")
                    )
                    ModelFetchOutcome.Success(ModelListParsers.cohere(page, provider))
                }

                "Ollama" -> {
                    val baseUrl = UrlUtils.formatBaseUrl(config.ollamaUrl, "")
                    if (baseUrl.isEmpty()) {
                        return@withContext ModelFetchOutcome.NeedsCredentials(
                            "Set the Ollama server URL to load its installed models."
                        )
                    }
                    val page = getJson(url = "$baseUrl/api/tags", headers = emptyMap())
                    ModelFetchOutcome.Success(ModelListParsers.ollama(page, provider))
                }

                "Copilot API" -> {
                    val baseUrl = UrlUtils.formatBaseUrl(config.copilotUrl, "")
                    if (baseUrl.isEmpty()) {
                        return@withContext ModelFetchOutcome.NeedsCredentials(
                            "Set the Copilot API endpoint to load its models."
                        )
                    }
                    ModelFetchOutcome.Success(openAiStyle(modelsUrl(baseUrl), apiKey, provider))
                }

                "Custom OpenAI Compatible" -> {
                    val customUrl = config.customEndpoints[provider]?.trim().orEmpty()
                    if (customUrl.isEmpty()) {
                        return@withContext ModelFetchOutcome.NeedsCredentials(
                            "Set the endpoint URL to load models from your OpenAI-compatible server."
                        )
                    }
                    val baseUrl = UrlUtils.formatBaseUrl(customUrl, "")
                    ModelFetchOutcome.Success(openAiStyle(modelsUrl(baseUrl), apiKey, provider))
                }

                ProviderCatalog.ON_DEVICE -> {
                    // On-device models ship with the app, so the registry — not a
                    // remote endpoint — is their authoritative list.
                    ModelFetchOutcome.Success(
                        OnDeviceModelRegistry.allModels.map { spec ->
                            AIModel(
                                id = spec.id,
                                displayName = spec.displayName,
                                provider = provider,
                                isFree = true,
                                isRecommended = spec.isRecommended
                            )
                        }
                    )
                }

                else -> ModelFetchOutcome.Success(emptyList())
            }
        } catch (e: Exception) {
            // Messages here reach the UI, so they carry the provider and status
            // only — never the request URL (it can hold the API key) or the
            // response body.
            Log.e(tag, "Failed to fetch models for provider $provider: ${e.localizedMessage}", e)
            ModelFetchOutcome.Failed(
                "Could not load models from $provider: ${e.localizedMessage ?: "network error"}"
            )
        }
    }

    private fun needsKey(provider: String) = ModelFetchOutcome.NeedsCredentials(
        "Add an API key to load the models $provider currently serves."
    )

    /** `/v1/models` is the shape every OpenAI-compatible provider agrees on. */
    private fun openAiStyle(url: String, apiKey: String?, provider: String): List<AIModel> {
        val page = getJson(
            url = url,
            headers = apiKey?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
        )
        return ModelListParsers.openAiStyle(page, provider)
    }

    private fun modelsUrl(baseUrl: String): String =
        if (baseUrl.endsWith("/v1")) "$baseUrl/models" else "$baseUrl/v1/models"

    /**
     * Gemini pages its model list, and the page a model lands on shifts as
     * Google adds and retires models — so every page is walked rather than
     * trusting the first one to hold what the user picked.
     */
    private fun fetchGemini(apiKey: String, provider: String): List<AIModel> {
        val models = mutableListOf<AIModel>()
        var pageToken: String? = null
        var pagesFetched = 0
        do {
            val url = "https://generativelanguage.googleapis.com/v1beta/models".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("key", apiKey)
                ?.addQueryParameter("pageSize", "200")
                ?.apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                ?.build()
                ?: throw IOException("Could not build the Gemini models request.")
            val page = getJson(url = url.toString(), headers = emptyMap())
            models += ModelListParsers.gemini(page, provider)
            pageToken = page.optString("nextPageToken").takeIf { it.isNotBlank() }
            pagesFetched++
        } while (pageToken != null && pagesFetched < MAX_PAGES)
        return models.sortedBy { it.displayName }
    }

    /**
     * Neither the URL (it can carry the API key as a query parameter) nor the
     * response body may appear in a thrown message: these are logged and shown
     * to the user.
     */
    private fun getJson(url: String, headers: Map<String, String>): JSONObject {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (name, value) -> builder.header(name, value) }
        httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val body = response.body.string()
            if (body.isBlank()) throw IOException("The models endpoint returned an empty response.")
            return try {
                JSONObject(body)
            } catch (e: JSONException) {
                throw IOException("The models endpoint returned an unexpected response shape.")
            }
        }
    }

    private companion object {
        /** Bounds a provider that keeps handing back a page token. */
        const val MAX_PAGES = 10
    }
}

/**
 * Pure JSON-to-[AIModel] translation, split out from the network layer so the
 * filtering rules that decide what reaches the picker are unit-testable.
 *
 * No parser matches on specific model names: a provider's list is taken as
 * given, minus entries whose *capability* makes them unusable for chat.
 */
internal object ModelListParsers {

    /**
     * Capability markers, not model names. A provider's `/models` response mixes
     * embedding, speech, and image endpoints in with chat models, and only the
     * chat ones can serve a completion. Matching on capability words keeps
     * models the app has never heard of listed the moment a provider ships them.
     */
    private val NON_CHAT_MARKERS = listOf(
        "embed", "embedding", "rerank", "moderation",
        "whisper", "tts", "transcribe", "speech", "voice",
        "dall-e", "imagen", "image-generation", "stable-diffusion", "veo", "sora",
        "aqa", "guard"
    )

    fun isChatCapableId(id: String): Boolean {
        val normalized = id.lowercase()
        return NON_CHAT_MARKERS.none { normalized.contains(it) }
    }

    /** OpenAI, Groq, Mistral, Together, DeepSeek, Copilot, custom endpoints. */
    fun openAiStyle(json: JSONObject, provider: String): List<AIModel> {
        val data = json.optJSONArray("data") ?: return emptyList()
        val list = mutableListOf<AIModel>()
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            if (!isChatCapableId(id)) continue
            list.add(
                AIModel(
                    id = id,
                    displayName = formatModelName(id),
                    provider = provider,
                    contextWindow = obj.optInt("context_length").takeIf { it > 0 }
                )
            )
        }
        return list.sortedBy { it.displayName }
    }

    /**
     * Anthropic's list is authoritative for *which* models exist; the catalog
     * only supplies display names and badges for the ones OpenDroid knows, so a
     * model released today is still listed and selectable.
     */
    fun anthropic(json: JSONObject, provider: String): List<AIModel> {
        val data = json.optJSONArray("data") ?: return emptyList()
        val list = mutableListOf<AIModel>()
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            val spec = ClaudeModelCatalog.specFor(id)
            list.add(
                AIModel(
                    id = id,
                    displayName = spec?.displayName ?: obj.optString("display_name").takeIf { it.isNotBlank() }
                        ?: formatModelName(id),
                    provider = provider,
                    isRecommended = spec?.isRecommended ?: false,
                    isPremium = spec?.isPremium ?: false,
                    isFree = spec?.isFree ?: false
                )
            )
        }
        return list.sortedBy { it.displayName }
    }

    /**
     * Gemini reports per-model capabilities, so chat models are identified by
     * `generateContent` support rather than by a name pattern — the check that
     * would have kept a retired `gemini-1.5-flash` out of the picker.
     */
    fun gemini(json: JSONObject, provider: String): List<AIModel> {
        val models = json.optJSONArray("models") ?: return emptyList()
        val list = mutableListOf<AIModel>()
        for (i in 0 until models.length()) {
            val obj = models.optJSONObject(i) ?: continue
            val methods = obj.optJSONArray("supportedGenerationMethods")
            val supportsChat = (0 until (methods?.length() ?: 0))
                .any { methods?.optString(it) == "generateContent" }
            if (!supportsChat) continue
            val id = obj.optString("name").removePrefix("models/").takeIf { it.isNotBlank() } ?: continue
            if (!isChatCapableId(id)) continue
            list.add(
                AIModel(
                    id = id,
                    displayName = obj.optString("displayName").takeIf { it.isNotBlank() }
                        ?: formatModelName(id),
                    provider = provider,
                    contextWindow = obj.optInt("inputTokenLimit").takeIf { it > 0 }
                )
            )
        }
        return list
    }

    fun openRouter(json: JSONObject, provider: String): List<AIModel> {
        val data = json.optJSONArray("data") ?: return emptyList()
        val list = mutableListOf<AIModel>()
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            if (!isChatCapableId(id)) continue
            list.add(
                AIModel(
                    id = id,
                    displayName = obj.optString("name").takeIf { it.isNotBlank() } ?: id,
                    provider = provider,
                    contextWindow = obj.optInt("context_length").takeIf { it > 0 },
                    isFree = id.endsWith(":free")
                )
            )
        }
        return list.sortedWith(compareByDescending<AIModel> { it.isFree }.thenBy { it.displayName })
    }

    /**
     * Managed policies come first and are marked recommended, so the picker
     * lands on one when the seed model is not served. Catalog entries follow;
     * both lists share one model shape and report their own `api` type.
     */
    fun requesty(managed: JSONObject?, catalog: JSONObject?, provider: String): List<AIModel> {
        fun parse(json: JSONObject?, recommended: Boolean): List<AIModel> {
            val data = json?.optJSONArray("data") ?: return emptyList()
            val list = mutableListOf<AIModel>()
            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                val api = obj.opt("api") as? String ?: continue
                if (api != "chat" || !isChatCapableId(id)) continue
                list.add(
                    AIModel(
                        id = id,
                        displayName = id,
                        provider = provider,
                        contextWindow = obj.optInt("context_window").takeIf { it > 0 },
                        isRecommended = recommended
                    )
                )
            }
            return list.sortedBy { it.displayName }
        }
        return (parse(managed, recommended = true) + parse(catalog, recommended = false))
            .distinctBy { it.id }
    }

    fun cohere(json: JSONObject, provider: String): List<AIModel> {
        val models = json.optJSONArray("models") ?: return emptyList()
        val list = mutableListOf<AIModel>()
        for (i in 0 until models.length()) {
            val obj = models.optJSONObject(i) ?: continue
            val id = obj.optString("name").takeIf { it.isNotBlank() } ?: continue
            // Cohere states each model's endpoints; only chat models can serve a completion.
            val endpoints = obj.optJSONArray("endpoints")
            val supportsChat = endpoints == null ||
                (0 until endpoints.length()).any { endpoints.optString(it) == "chat" }
            if (!supportsChat || !isChatCapableId(id)) continue
            list.add(
                AIModel(
                    id = id,
                    displayName = formatModelName(id),
                    provider = provider,
                    contextWindow = obj.optInt("context_length").takeIf { it > 0 }
                )
            )
        }
        return list.sortedBy { it.displayName }
    }

    fun ollama(json: JSONObject, provider: String): List<AIModel> {
        val models = json.optJSONArray("models") ?: return emptyList()
        val list = mutableListOf<AIModel>()
        for (i in 0 until models.length()) {
            val obj = models.optJSONObject(i) ?: continue
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: continue
            list.add(AIModel(id = name, displayName = name, provider = provider, isFree = true))
        }
        return list.sortedBy { it.displayName }
    }

    fun formatModelName(id: String): String {
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
}
