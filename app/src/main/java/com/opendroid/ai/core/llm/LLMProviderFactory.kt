package com.opendroid.ai.core.llm

import com.opendroid.ai.actions.ActionDispatcher
import com.opendroid.ai.core.agent.ActionSchema
import com.opendroid.ai.core.agent.DeviceStateProvider
import com.opendroid.ai.core.agent.IntentClassifier
import com.opendroid.ai.core.agent.QueryComplexity
import com.opendroid.ai.core.llm.prompts.SystemPrompts
import com.opendroid.ai.core.llm.providers.*
import com.opendroid.ai.core.llm.providers.HybridOnDeviceProvider
import com.opendroid.ai.core.llm.providers.LiteRTLMProvider
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    private val togetherAIProvider: Provider<TogetherAIProvider>,
    private val cohereProvider: Provider<CohereProvider>,
    private val deepSeekProvider: Provider<DeepSeekProvider>,
    private val copilotProvider: Provider<CopilotProvider>,
    private val customOpenAIProvider: Provider<CustomOpenAIProvider>,
    private val gemmaProvider: Provider<GemmaProvider>,
    private val liteRTLMProvider: Provider<LiteRTLMProvider>,
    private val hybridOnDeviceProvider: Provider<HybridOnDeviceProvider>,
    private val settingsRepository: SettingsRepository,
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
            else -> geminiProvider.get()
        }
        return WrappedLLMProvider(rawProvider, actionDispatcher, intentClassifier, deviceStateProvider)
    }

    private fun getFallbackChain(primaryName: String): List<LLMProvider> {
        val providersList = listOf(
            "Google Gemini",
            "OpenAI",
            "Anthropic Claude",
            "Groq",
            "Mistral AI",
            "OpenRouter",
            "Together AI",
            "Cohere",
            "DeepSeek",
            "Copilot API",
            "Custom OpenAI Compatible",
            "Ollama",
            "On-Device AI"
        )
        // Normalize legacy name
        val normalizedPrimary = if (primaryName == "Gemma 4 (On-device)") "On-Device AI" else primaryName
        val orderedNames = mutableListOf<String>()
        orderedNames.add(normalizedPrimary)
        providersList.forEach { name ->
            if (name != normalizedPrimary) orderedNames.add(name)
        }
        return orderedNames.map { getProviderByName(it) }
    }

    suspend fun getActiveProvider(): LLMProvider {
        val config = settingsRepository.llmConfig.first()
        val chain = getFallbackChain(config.activeProvider)
        for (provider in chain) {
            if (provider.isAvailable()) {
                return provider
            }
        }
        // If nothing is configured, default to Gemini (it has Nano offline mock fallback)
        return getProviderByName("Google Gemini")
    }

    suspend fun executeWithFallback(request: LLMRequest): LLMResponse {
        val config = settingsRepository.llmConfig.first()
        val chain = getFallbackChain(config.activeProvider)
        val errors = mutableListOf<String>()

        for (provider in chain) {
            if (provider.isAvailable()) {
                try {
                    val response = provider.complete(request)
                    // Benchmark successfully executed provider in settings background
                    updateLatencyBenchmark(provider.name, response.latencyMs)
                    return response
                } catch (e: Exception) {
                    errors.add("${provider.name}: ${e.localizedMessage}")
                }
            }
        }
        throw IllegalStateException("All available LLM providers failed execution:\n" + errors.joinToString("\n"))
    }

    private suspend fun updateLatencyBenchmark(providerName: String, latency: Long) {
        settingsRepository.updateConfig { current ->
            val updatedBenchmarks = current.latencyBenchmarks.toMutableMap()
            updatedBenchmarks[providerName] = latency
            current.copy(latencyBenchmarks = updatedBenchmarks)
        }
    }
}

class WrappedLLMProvider(
    private val delegate: LLMProvider,
    private val actionDispatcher: dagger.Lazy<ActionDispatcher>,
    private val intentClassifier: dagger.Lazy<IntentClassifier>,
    private val deviceStateProvider: DeviceStateProvider
) : LLMProvider {
    override val name: String get() = delegate.name
    override val availableModels: List<String> get() = delegate.availableModels

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val rewrittenRequest = rewriteRequestIfNeeded(request)
        return delegate.complete(rewrittenRequest)
    }

    override fun streamComplete(request: LLMRequest): Flow<String> {
        val rewrittenRequest = rewriteRequestIfNeeded(request)
        return delegate.streamComplete(rewrittenRequest)
    }

    override suspend fun isAvailable(): Boolean = delegate.isAvailable()

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

            val newSystemPrompt = SystemPrompts.buildMainPrompt(
                registeredActions = registeredActions,
                memoryContext = memoryContext,
                currentDateTime = currentDateTime,
                deviceState = deviceState,
                maxSteps = maxSteps
            )

            return request.copy(systemPrompt = newSystemPrompt)
        }
        return request
    }
}
