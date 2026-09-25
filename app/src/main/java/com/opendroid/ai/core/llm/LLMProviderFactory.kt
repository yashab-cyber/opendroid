package com.opendroid.ai.core.llm

import android.util.Log
import com.opendroid.ai.actions.ActionDispatcher
import com.opendroid.ai.core.agent.ActionSchema
import com.opendroid.ai.core.agent.DeviceStateProvider
import com.opendroid.ai.core.agent.IntentClassifier
import com.opendroid.ai.core.agent.QueryComplexity
import com.opendroid.ai.core.llm.prompts.SystemPrompts
import com.opendroid.ai.core.llm.error.LLMErrorMapper
import com.opendroid.ai.core.llm.error.SecretRegistry
import com.opendroid.ai.core.llm.providers.*
import com.opendroid.ai.core.llm.providers.HybridOnDeviceProvider
import com.opendroid.ai.core.llm.providers.LiteRTLMProvider
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.models.selectedModelFor
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlin.math.min
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class LLMProviderFactory @Inject constructor(
    private val claudeProvider: Provider<ClaudeProvider>,
    private val openAIProvider: Provider<OpenAIProvider>,
    private val geminiProvider: Provider<GeminiProvider>,
    private val mistralProvider: Provider<MistralProvider>,
    private val groqProvider: Provider<GroqProvider>,
    private val ollamaProvider: Provider<OllamaProvider>,
    private val openRouterProvider: Provider<OpenRouterProvider>,
    private val requestyProvider: Provider<RequestyProvider>,
    private val togetherAIProvider: Provider<TogetherAIProvider>,
    private val cohereProvider: Provider<CohereProvider>,
    private val deepSeekProvider: Provider<DeepSeekProvider>,
    private val copilotProvider: Provider<CopilotProvider>,
    private val customOpenAIProvider: Provider<CustomOpenAIProvider>,
    private val gemmaProvider: Provider<GemmaProvider>,
    private val liteRTLMProvider: Provider<LiteRTLMProvider>,
    private val hybridOnDeviceProvider: Provider<HybridOnDeviceProvider>,
    private val settingsRepository: SettingsRepository,
    private val onDeviceLatencyTracker: OnDeviceLatencyTracker,
    private val actionDispatcher: dagger.Lazy<ActionDispatcher>,
    private val intentClassifier: dagger.Lazy<IntentClassifier>,
    private val deviceStateProvider: DeviceStateProvider
) {

    fun getProviderByName(name: String): LLMProvider {
        val rawProvider = when (name) {
            "Anthropic Claude" -> claudeProvider.get()
            "OpenAI" -> openAIProvider.get()
            "Google Gemini" -> geminiProvider.get()
            "Mistral AI" -> mistralProvider.get()
            "Groq" -> groqProvider.get()
            "Ollama" -> ollamaProvider.get()
            "OpenRouter" -> openRouterProvider.get()
            "Requesty" -> requestyProvider.get()
            "Together AI" -> togetherAIProvider.get()
            "Cohere" -> cohereProvider.get()
            "DeepSeek" -> deepSeekProvider.get()
            "Copilot API" -> copilotProvider.get()
            "Custom OpenAI Compatible" -> customOpenAIProvider.get()
            // Hybrid on-device: both old and new names map here
            "On-Device AI",
            "Gemma 4 (On-device)" -> hybridOnDeviceProvider.get()
            // Direct backend access (for advanced users / testing)
            "LiteRT-LM (On-device)" -> liteRTLMProvider.get()
            else -> {
                Log.w(TAG, "Unknown LLM provider persisted; falling back to Google Gemini.")
                geminiProvider.get()
            }
        }
        return WrappedLLMProvider(
            delegate = rawProvider,
            configProvider = { settingsRepository.llmConfig.first() },
            requestRewriter = ::rewriteRequestIfNeeded
        )
    }

    suspend fun getActiveProvider(): LLMProvider {
        val config = settingsRepository.llmConfig.first()
        return getProviderByName(ProviderCatalog.canonicalName(config.activeProvider))
    }

    /**
     * Returns the active planner followed only by explicitly configured,
     * available fallbacks. Risk is evaluated before any alternate provider is
     * contacted; high-impact plans never cross a provider boundary silently.
     */
    suspend fun getPlanningProviders(actionNames: Collection<String>): List<LLMProvider> {
        val config = settingsRepository.llmConfig.first()
        val activeName = ProviderCatalog.canonicalName(config.activeProvider)
        val active = getProviderByName(activeName)
        val risk = ActionSchema.highestRisk(actionNames)
        val fallbackNames = ProviderSelectionPolicy.explicitFallbacks(
            activeProvider = activeName,
            configuredFallbacks = config.fallbackProviders,
            risk = risk
        )

        val availableFallbacks = fallbackNames.mapNotNull { providerName ->
            if (!isConfigured(config, providerName)) return@mapNotNull null
            val provider = getProviderByName(providerName)
            provider.takeIf { runCatching { it.isAvailable() }.getOrDefault(false) }
        }
        return listOf(active) + availableFallbacks
    }

    suspend fun recordPlanningLatency(response: LLMResponse): LatencyBudgetResult? =
        runCatching { onDeviceLatencyTracker.recordPlanning(response) }.getOrNull()

    private fun isConfigured(config: LLMConfig, providerName: String): Boolean {
        val provider = ProviderCatalog.canonicalName(providerName)
        if (ProviderCatalog.isOnDevice(provider)) return true
        return when (provider) {
            "Ollama" -> config.ollamaUrl.isNotBlank()
            "Copilot API" -> config.copilotUrl.isNotBlank()
            "Custom OpenAI Compatible" ->
                config.customEndpoints[provider].orEmpty().isNotBlank() &&
                    config.apiKeys[provider].orEmpty().isNotBlank()
            else -> {
                val model = config.selectedModelFor(provider)
                !ProviderCatalog.requiresApiKey(provider) ||
                    (provider == "Google Gemini" && model == "gemini-nano") ||
                    config.apiKeys[provider].orEmpty().isNotBlank()
            }
        }
    }

    private fun rewriteRequestIfNeeded(request: LLMRequest): LLMRequest {
        if (request.systemPrompt.contains("Planning Engine") || request.systemPrompt.contains("AVAILABLE ACTIONS")) {
            val userMessageText = request.messages.lastOrNull()?.text ?: ""
            val complexity = intentClassifier.get().classifyComplexity(userMessageText)
            val maxSteps = when (complexity) {
                QueryComplexity.SIMPLE -> 1
                QueryComplexity.MEDIUM -> 3
                QueryComplexity.COMPLEX -> 10
            }

            val memoryContext = if (request.systemPrompt.contains("Context about user and device:")) {
                request.systemPrompt.substringAfter("Context about user and device:").trim()
            } else {
                ""
            }

            val currentDateTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            val deviceState = deviceStateProvider.getFullStateString()
            val registeredActions = actionDispatcher.get().getAllRegisteredActions()

            return request.copy(
                systemPrompt = SystemPrompts.buildMainPrompt(
                    registeredActions = registeredActions,
                    memoryContext = memoryContext,
                    currentDateTime = currentDateTime,
                    deviceState = deviceState,
                    maxSteps = maxSteps
                )
            )
        }
        return request
    }

    private companion object {
        const val TAG = "LLMProviderFactory"
    }
}

data class RetryRuntime(
    val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    val delayMillis: suspend (Long) -> Unit = { delay(it) },
    val jitterMillis: (Long) -> Long = { upperBound ->
        if (upperBound <= 0L) 0L else Random.nextLong(upperBound + 1L)
    }
)

class WrappedLLMProvider(
    private val delegate: LLMProvider,
    private val configProvider: suspend () -> LLMConfig,
    private val requestRewriter: (LLMRequest) -> LLMRequest = { it },
    private val retryRuntime: RetryRuntime = RetryRuntime()
) : LLMProvider {
    override val name: String get() = delegate.name
    override val availableModels: List<String> get() = delegate.availableModels

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val resolved = resolveRequest(request)
        val registrations = registerSecrets(resolved)
        return try {
            executeWithRetry(resolved) { delegate.complete(it) }
        } finally {
            registrations.asReversed().forEach(AutoCloseable::close)
        }
    }

    override fun streamComplete(request: LLMRequest): Flow<String> = flow {
        val resolved = resolveRequest(request)
        val registrations = registerSecrets(resolved)
        var attempt = 1
        var emitted = false
        val startedAt = retryRuntime.nowMillis()
        try {
            while (true) {
                try {
                    delegate.streamComplete(resolved).collect { chunk ->
                        if (chunk.isNotEmpty()) {
                            emitted = true
                            emit(chunk)
                        }
                    }
                    if (!emitted) {
                        throw LLMErrorMapper.malformed(name, resolved.model.orEmpty(), transient = true)
                    }
                    break
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    val failure = LLMErrorMapper.fromThrowable(name, resolved.model.orEmpty(), throwable)
                    val delayMillis = proposedDelayMillis(failure, attempt)
                    if (emitted || !shouldRetry(resolved, failure, attempt, startedAt, delayMillis)) throw failure
                    retryRuntime.delayMillis(delayMillis)
                    attempt++
                }
            }
        } finally {
            registrations.asReversed().forEach(AutoCloseable::close)
        }
    }

    override suspend fun isAvailable(): Boolean = delegate.isAvailable()

    private suspend fun resolveRequest(request: LLMRequest): LLMRequest {
        val config = configProvider()
        val provider = ProviderCatalog.canonicalName(name)
        val model = config.selectedModelFor(provider)
        val endpoint = when (provider) {
            "Custom OpenAI Compatible" -> config.customEndpoints[provider].orEmpty().trim()
            "Copilot API" -> config.copilotUrl.trim()
            "Ollama" -> config.ollamaUrl.trim()
            else -> config.customEndpoints[provider].orEmpty().trim()
        }
        val apiKey = config.apiKeys[provider].orEmpty()

        if (ProviderCatalog.requiresApiKey(provider) &&
            !(provider == "Google Gemini" && model == "gemini-nano") &&
            apiKey.isBlank()
        ) {
            throw LLMErrorMapper.authMissing(provider, model)
        }
        if (provider in setOf("Custom OpenAI Compatible", "Copilot API", "Ollama") && endpoint.isBlank()) {
            throw LLMErrorMapper.requestInvalid(provider, model)
        }

        return requestRewriter(request).copy(
            model = model,
            providerConfig = ProviderRequestConfig(apiKey = apiKey, endpoint = endpoint)
        )
    }

    private suspend fun <T> executeWithRetry(
        request: LLMRequest,
        operation: suspend (LLMRequest) -> T
    ): T {
        var attempt = 1
        val startedAt = retryRuntime.nowMillis()
        while (true) {
            try {
                return operation(request)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                val failure = LLMErrorMapper.fromThrowable(name, request.model.orEmpty(), throwable)
                val delayMillis = proposedDelayMillis(failure, attempt)
                if (!shouldRetry(request, failure, attempt, startedAt, delayMillis)) throw failure
                retryRuntime.delayMillis(delayMillis)
                attempt++
            }
        }
    }

    private fun shouldRetry(
        request: LLMRequest,
        failure: com.opendroid.ai.core.llm.error.LLMException,
        attempt: Int,
        startedAt: Long,
        proposedDelay: Long
    ): Boolean {
        if (request.retryPolicy == RetryPolicy.NONE || !failure.retryable || attempt >= MAX_ATTEMPTS) {
            return false
        }
        val elapsed = (retryRuntime.nowMillis() - startedAt).coerceAtLeast(0L)
        if (elapsed >= RETRY_WINDOW_MILLIS) return false
        return proposedDelay <= RETRY_WINDOW_MILLIS - elapsed
    }

    private fun proposedDelayMillis(
        failure: com.opendroid.ai.core.llm.error.LLMException,
        attempt: Int
    ): Long {
        failure.retryAfterMillis?.let { retryAfter ->
            return retryAfter + retryRuntime.jitterMillis(250L).coerceIn(0L, 250L)
        }
        val exponentialCap = min(500L shl (attempt - 1), 4_000L)
        return 250L + retryRuntime.jitterMillis(exponentialCap).coerceIn(0L, exponentialCap)
    }

    private fun registerSecrets(request: LLMRequest): List<AutoCloseable> = buildList {
        request.providerConfig?.apiKey?.takeIf(String::isNotBlank)?.let {
            add(SecretRegistry.register(it))
        }
        request.providerConfig?.endpoint?.takeIf(String::isNotBlank)?.let {
            add(SecretRegistry.register(it))
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val RETRY_WINDOW_MILLIS = 30_000L
    }
}
