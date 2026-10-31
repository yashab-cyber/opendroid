package com.opendroid.ai.core.llm

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.opendroid.ai.actions.ActionDispatcher
import com.opendroid.ai.core.agent.DeviceStateProvider
import com.opendroid.ai.core.agent.IntentClassifier
import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.core.llm.error.LLMException
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.repository.SettingsRepository
import java.nio.file.Files
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A saved provider name this build doesn't know (an auto-backup restored onto an
 * older version, a renamed provider) must fail as a configuration error. It used
 * to fall back to Google Gemini with the user's Gemini key, a vendor they never
 * chose (#82).
 */
class LLMProviderFactoryUnknownProviderTest {

    private var geminiRequests = 0

    @Test
    fun `an unknown saved provider fails as misconfigured and never reaches Gemini`() {
        val factory = newFactory(savedActiveProvider = "Requesty")

        val failure = runCatching {
            runBlocking { factory.getActiveProvider().complete(request()) }
        }.exceptionOrNull()

        assertEquals(0, geminiRequests)
        assertTrue("Expected an LLMException, got $failure", failure is LLMException)
        assertEquals(LLMError.RequestInvalid, (failure as LLMException).error)
    }

    @Test
    fun `an unknown saved provider cannot stream either`() {
        val factory = newFactory(savedActiveProvider = "Requesty")

        val failure = runCatching {
            runBlocking { factory.getActiveProvider().streamComplete(request()).collect {} }
        }.exceptionOrNull()

        assertEquals(0, geminiRequests)
        assertTrue("Expected an LLMException, got $failure", failure is LLMException)
        assertEquals(LLMError.RequestInvalid, (failure as LLMException).error)
    }

    private fun newFactory(savedActiveProvider: String): LLMProviderFactory {
        val settings = newSettingsRepository()
        runBlocking {
            // copy(), not withActiveProvider(): this is data written by another build.
            settings.updateConfig { it.copy(activeProvider = savedActiveProvider) }
        }
        return LLMProviderFactory(
            claudeProvider = unused("Claude"),
            openAIProvider = unused("OpenAI"),
            geminiProvider = Provider {
                geminiRequests++
                throw AssertionError("Gemini must not be used for an unknown provider")
            },
            mistralProvider = unused("Mistral"),
            groqProvider = unused("Groq"),
            ollamaProvider = unused("Ollama"),
            openRouterProvider = unused("OpenRouter"),
            togetherAIProvider = unused("Together"),
            cohereProvider = unused("Cohere"),
            deepSeekProvider = unused("DeepSeek"),
            copilotProvider = unused("Copilot"),
            opencodeGoProvider = unused("Opencode-Go"),
            customOpenAIProvider = unused("Custom"),
            gemmaProvider = unused("Gemma"),
            liteRTLMProvider = unused("LiteRT-LM"),
            hybridOnDeviceProvider = unused("Hybrid"),
            settingsRepository = settings,
            onDeviceLatencyTracker = OnDeviceLatencyTracker(settings),
            actionDispatcher = dagger.Lazy<ActionDispatcher> { throw AssertionError("unused") },
            intentClassifier = dagger.Lazy<IntentClassifier> { throw AssertionError("unused") },
            // Only used to rewrite planning prompts, which this test never sends.
            deviceStateProvider = DeviceStateProvider(ContextWrapper(null))
        )
    }

    private fun <T> unused(name: String): Provider<T> =
        Provider { throw AssertionError("$name must not be used") }

    private fun request() = LLMRequest(
        systemPrompt = "constant",
        messages = listOf(ChatMessage("1", "ping", ChatMessage.Sender.USER)),
        responseFormat = ResponseFormat.TEXT
    )

    private fun newSettingsRepository() = SettingsRepository(
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = {
                Files.createTempDirectory("opendroid-factory-test")
                    .resolve("settings.preferences_pb")
                    .toFile()
            }
        ),
        providerCredentialStore = FixedKeyCredentialStore(mapOf("Google Gemini" to "gemini-test-key")),
        runStartupMigration = false
    )

    /** The user has a Gemini key: exactly the situation where the old fallback leaked. */
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
}
