package com.opendroid.ai.core.llm

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.opendroid.ai.actions.ActionDispatcher
import com.opendroid.ai.core.agent.DeviceStateProvider
import com.opendroid.ai.core.agent.IntentClassifier
import com.opendroid.ai.core.llm.providers.ClaudeProvider
import com.opendroid.ai.core.llm.providers.CohereProvider
import com.opendroid.ai.core.llm.providers.CopilotProvider
import com.opendroid.ai.core.llm.providers.CustomOpenAIProvider
import com.opendroid.ai.core.llm.providers.DeepSeekProvider
import com.opendroid.ai.core.llm.providers.GeminiProvider
import com.opendroid.ai.core.llm.providers.GroqProvider
import com.opendroid.ai.core.llm.providers.MistralProvider
import com.opendroid.ai.core.llm.providers.OllamaProvider
import com.opendroid.ai.core.llm.providers.OpenAIProvider
import com.opendroid.ai.core.llm.providers.OpenRouterProvider
import com.opendroid.ai.core.llm.providers.OpencodeGoProvider
import com.opendroid.ai.core.llm.providers.TogetherAIProvider
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.repository.SettingsRepository
import com.opendroid.ai.di.AppModule
import java.nio.file.Files
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Provider selection through the real [LLMProviderFactory], as documented in
 * docs/agents/on-device-inference.md: the active provider is the one the user
 * picked, and a fallback is used only when the user allowlisted it, it is
 * configured and available, and the plan is low-risk. A key on its own is never
 * permission to send a prompt to that vendor.
 */
class LLMProviderFactorySelectionTest {

    @Test
    fun `every cloud provider name resolves to that provider`() {
        val factory = newFactory(keys = emptyMap())

        for (name in CLOUD_PROVIDERS) {
            assertEquals(name, factory.getProviderByName(name).name)
        }
    }

    @Test
    fun `planning uses only allowlisted fallbacks that are configured and available`() = runBlocking {
        val factory = newFactory(
            keys = mapOf(
                "OpenAI" to "sk-openai",
                "Groq" to "gsk-groq",
                "Custom OpenAI Compatible" to "sk-custom"
            )
        ) { config ->
            config.copy(
                activeProvider = "OpenAI",
                // Mistral has no key, Custom has no endpoint, Ollama has no URL.
                fallbackProviders = listOf("Groq", "Mistral AI", "Custom OpenAI Compatible", "Ollama")
            )
        }

        val providers = factory.getPlanningProviders(actionNames = emptyList())

        assertEquals(listOf("OpenAI", "Groq"), providers.map { it.name })
    }

    @Test
    fun `a configured key alone never makes a provider a fallback`() = runBlocking {
        val factory = newFactory(
            keys = mapOf("OpenAI" to "sk-openai", "Groq" to "gsk-groq", "Anthropic Claude" to "sk-ant")
        ) { config -> config.copy(activeProvider = "OpenAI", fallbackProviders = emptyList()) }

        val providers = factory.getPlanningProviders(actionNames = emptyList())

        assertEquals(listOf("OpenAI"), providers.map { it.name })
    }

    @Test
    fun `a plan with an unknown or high-risk action never crosses to a fallback`() = runBlocking {
        val factory = newFactory(keys = mapOf("OpenAI" to "sk-openai", "Groq" to "gsk-groq")) { config ->
            config.copy(activeProvider = "OpenAI", fallbackProviders = listOf("Groq"))
        }

        // Unknown action names are treated as SENSITIVE.
        val providers = factory.getPlanningProviders(actionNames = listOf("NOT_A_REAL_ACTION"))

        assertEquals(listOf("OpenAI"), providers.map { it.name })
    }

    @Test
    fun `the active provider is never repeated as its own fallback`() = runBlocking {
        val factory = newFactory(keys = mapOf("OpenAI" to "sk-openai", "Groq" to "gsk-groq")) { config ->
            config.copy(activeProvider = "OpenAI", fallbackProviders = listOf("OpenAI", "Groq", "Groq"))
        }

        val providers = factory.getPlanningProviders(actionNames = emptyList())

        assertEquals(listOf("OpenAI", "Groq"), providers.map { it.name })
    }

    private fun newFactory(
        keys: Map<String, String>,
        configure: (LLMConfig) -> LLMConfig = { it }
    ): LLMProviderFactory {
        val settings = newSettingsRepository(keys)
        runBlocking { settings.updateConfig(configure) }
        val client = AppModule.provideOkHttpClient()
        return LLMProviderFactory(
            claudeProvider = Provider { ClaudeProvider(client, settings) },
            openAIProvider = Provider { OpenAIProvider(client, settings) },
            geminiProvider = Provider { GeminiProvider(client, settings) },
            mistralProvider = Provider { MistralProvider(client, settings) },
            groqProvider = Provider { GroqProvider(client, settings) },
            ollamaProvider = Provider { OllamaProvider(client, settings) },
            openRouterProvider = Provider { OpenRouterProvider(client, settings) },
            togetherAIProvider = Provider { TogetherAIProvider(client, settings) },
            cohereProvider = Provider { CohereProvider(client, settings) },
            deepSeekProvider = Provider { DeepSeekProvider(client, settings) },
            copilotProvider = Provider { CopilotProvider(client, settings) },
            opencodeGoProvider = Provider { OpencodeGoProvider(client, settings) },
            customOpenAIProvider = Provider { CustomOpenAIProvider(client, settings) },
            // On-device providers need a real Context and aren't exercised here.
            gemmaProvider = unused("Gemma"),
            liteRTLMProvider = unused("LiteRT-LM"),
            hybridOnDeviceProvider = unused("Hybrid"),
            settingsRepository = settings,
            onDeviceLatencyTracker = OnDeviceLatencyTracker(settings),
            actionDispatcher = dagger.Lazy<ActionDispatcher> { throw AssertionError("unused") },
            intentClassifier = dagger.Lazy<IntentClassifier> { throw AssertionError("unused") },
            // Only used to rewrite planning prompts, which these tests never send.
            deviceStateProvider = DeviceStateProvider(ContextWrapper(null))
        )
    }

    private fun <T> unused(name: String): Provider<T> =
        Provider { throw AssertionError("$name must not be used") }

    private fun newSettingsRepository(keys: Map<String, String>) = SettingsRepository(
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = {
                Files.createTempDirectory("opendroid-factory-selection-test")
                    .resolve("settings.preferences_pb")
                    .toFile()
            }
        ),
        providerCredentialStore = FixedKeyCredentialStore(keys),
        runStartupMigration = false
    )

    private class FixedKeyCredentialStore(
        private val keys: Map<String, String>
    ) : ProviderCredentialStore {
        override val recoveryState: StateFlow<ProviderCredentialRecoveryState> =
            MutableStateFlow(ProviderCredentialRecoveryState.Ready)

        override fun read(credential: ProviderCredentialId): CredentialStoreResult<String?> =
            CredentialStoreResult.Success(null)

        override fun readProviderApiKeys(): CredentialStoreResult<Map<String, String>> =
            CredentialStoreResult.Success(keys)

        override fun write(credential: ProviderCredentialId, value: String): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun remove(credential: ProviderCredentialId): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun migrateLegacyCredentials(): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun resetForReentry(): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)
    }

    private companion object {
        val CLOUD_PROVIDERS = listOf(
            "Anthropic Claude", "OpenAI", "Google Gemini", "Mistral AI", "Groq", "Ollama",
            "OpenRouter", "Together AI", "Cohere", "DeepSeek", "Copilot API", "Opencode-Go",
            "Custom OpenAI Compatible"
        )
    }
}
