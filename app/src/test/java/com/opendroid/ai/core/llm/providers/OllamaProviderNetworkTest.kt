package com.opendroid.ai.core.llm.providers

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ProviderRequestConfig
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.repository.SettingsRepository
import com.opendroid.ai.di.AppModule
import java.net.InetAddress
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [OllamaProvider] against a real socket, plus the per-call client that carries the
 * user-set timeout (issue 61: cold local model loads outlast 15 seconds).
 */
class OllamaProviderNetworkTest {

    private val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
    private val sharedClient = AppModule.provideOkHttpClient()
    private val provider = OllamaProvider(sharedClient, newSettingsRepository())

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `read and write timeouts follow the setting while connect keeps the shared limit`() {
        val client = provider.httpClientFor(LLMConfig(ollamaTimeoutSeconds = 300))
        assertEquals("read timeout", 300_000, client.readTimeoutMillis)
        assertEquals("write timeout", 300_000, client.writeTimeoutMillis)
        assertEquals("connect timeout", sharedClient.connectTimeoutMillis, client.connectTimeoutMillis)
    }

    @Test
    fun `the default config waits 120 seconds and out-of-range values are clamped`() {
        assertEquals(120_000, provider.httpClientFor(LLMConfig()).readTimeoutMillis)
        assertEquals(15_000, provider.httpClientFor(LLMConfig(ollamaTimeoutSeconds = 1)).readTimeoutMillis)
        assertEquals(600_000, provider.httpClientFor(LLMConfig(ollamaTimeoutSeconds = 9_999)).readTimeoutMillis)
    }

    @Test
    fun `a successful chat completion is parsed`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "application/json")
                .body("""{"message":{"content":"pong"},"prompt_eval_count":3,"eval_count":4}""")
                .build()
        )

        val response = provider.complete(
            LLMRequest(
                systemPrompt = "you are a test",
                messages = listOf(ChatMessage("1", "ping", ChatMessage.Sender.USER)),
                model = "gemma3:4b",
                providerConfig = ProviderRequestConfig(apiKey = "", endpoint = server.url("/").toString())
            )
        )

        assertEquals("pong", response.content)
        assertEquals(7, response.tokensUsed)
        assertEquals("/api/chat", server.takeRequest().target)
    }

    private fun newSettingsRepository() = SettingsRepository(
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = {
                Files.createTempDirectory("opendroid-network-test")
                    .resolve("settings.preferences_pb")
                    .toFile()
            }
        ),
        providerCredentialStore = EmptyProviderCredentialStore(),
        runStartupMigration = false
    )

    /** The request under test carries its own credential, so nothing is stored. */
    private class EmptyProviderCredentialStore : ProviderCredentialStore {
        override val recoveryState: StateFlow<ProviderCredentialRecoveryState> =
            MutableStateFlow(ProviderCredentialRecoveryState.Ready)

        override fun read(credential: ProviderCredentialId): CredentialStoreResult<String?> =
            CredentialStoreResult.Success(null)

        override fun readProviderApiKeys(): CredentialStoreResult<Map<String, String>> =
            CredentialStoreResult.Success(emptyMap())

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
