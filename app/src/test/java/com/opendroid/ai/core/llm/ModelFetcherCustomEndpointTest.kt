package com.opendroid.ai.core.llm

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
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
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Where the model list is requested for a "Custom OpenAI Compatible" endpoint (#60).
 *
 * The user enters the endpoint the way OpenAI-compatible services document it: a
 * base URL that already carries the API version (`…/v1`, `…/v2`, `…/v1beta/openai`).
 * Chat completions go to `<base>/chat/completions`, so the model list has to come
 * from `<base>/models` on the same base. A base of `https://api.deutschlandgpt.de/v2`
 * used to be listed at `/v2/v1/models`, which 404s, so the picker showed
 * "Could not load models … HTTP 404" and no model could be chosen.
 *
 * Robolectric supplies a real `org.json`, which the model list parser needs.
 */
@RunWith(RobolectricTestRunner::class)
class ModelFetcherCustomEndpointTest {

    private val provider = "Custom OpenAI Compatible"
    private val apiKey = "sk-test-0123456789abcdef"

    private val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `a base URL ending in v2 lists models from v2 models`() {
        val outcome = fetchWithBasePath("/v2", servedModelsPath = "/v2/models")

        assertSuccess(outcome)
        assertEquals("/v2/models", server.takeRequest().target)
    }

    @Test
    fun `a base URL ending in v1 still lists models from v1 models`() {
        val outcome = fetchWithBasePath("/v1", servedModelsPath = "/v1/models")

        assertSuccess(outcome)
        assertEquals("/v1/models", server.takeRequest().target)
    }

    @Test
    fun `a base URL with a non-version path lists models under that path`() {
        val outcome = fetchWithBasePath("/v1beta/openai", servedModelsPath = "/v1beta/openai/models")

        assertSuccess(outcome)
        assertEquals("/v1beta/openai/models", server.takeRequest().target)
    }

    @Test
    fun `a bare host still lists models from v1 models`() {
        val outcome = fetchWithBasePath("", servedModelsPath = "/v1/models")

        assertSuccess(outcome)
        assertEquals("/v1/models", server.takeRequest().target)
    }

    @Test
    fun `the model list request carries the configured key`() {
        fetchWithBasePath("/v2", servedModelsPath = "/v2/models")

        assertEquals("Bearer $apiKey", server.takeRequest().headers["Authorization"])
    }

    /**
     * Serves a one-model list at [servedModelsPath] only and 404s every other path,
     * the way a real server answers a wrong URL.
     */
    private fun fetchWithBasePath(basePath: String, servedModelsPath: String): ModelFetchOutcome {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.target == servedModelsPath) {
                    MockResponse.Builder()
                        .code(200)
                        .setHeader("Content-Type", "application/json")
                        .body("""{"object":"list","data":[{"id":"served-chat-model","object":"model"}]}""")
                        .build()
                } else {
                    MockResponse.Builder().code(404).build()
                }
        }
        val baseUrl = server.url("/").toString().trimEnd('/') + basePath
        val settingsRepository = newSettingsRepository()
        return runBlocking {
            settingsRepository.updateConfig { config ->
                config.copy(customEndpoints = config.customEndpoints + (provider to baseUrl))
            }
            ModelFetcher(AppModule.provideOkHttpClient(), settingsRepository).fetchModels(provider)
        }
    }

    private fun assertSuccess(outcome: ModelFetchOutcome) {
        assertTrue("Expected the model list to load, got $outcome", outcome is ModelFetchOutcome.Success)
        assertEquals(
            listOf("served-chat-model"),
            (outcome as ModelFetchOutcome.Success).models.map { it.id }
        )
    }

    private fun newSettingsRepository() = SettingsRepository(
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = {
                Files.createTempDirectory("opendroid-model-fetch-test")
                    .resolve("settings.preferences_pb")
                    .toFile()
            }
        ),
        providerCredentialStore = FixedKeyCredentialStore(mapOf(provider to apiKey)),
        runStartupMigration = false
    )

    /** Hands back a fixed key for each provider and accepts writes without storing them. */
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
