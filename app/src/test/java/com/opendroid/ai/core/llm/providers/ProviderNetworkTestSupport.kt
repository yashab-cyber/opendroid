package com.opendroid.ai.core.llm.providers

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.opendroid.ai.core.llm.error.LLMException
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.repository.SettingsRepository
import com.opendroid.ai.di.AppModule
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.junit.Assert.assertFalse

/**
 * Shared plumbing for the cloud provider network tests.
 *
 * Every cloud provider hard-codes its HTTPS host, so the tests cannot point it
 * at a [MockWebServer] through configuration. [LoopbackRoute] instead hands
 * the provider the production client with one application interceptor added:
 * it remembers the URL the provider built and swaps only scheme, host and
 * port for the server's. Path and query reach the socket untouched, so
 * `RecordedRequest.target` is the path the provider really used.
 */
internal class LoopbackRoute(private val server: MockWebServer) {

    private val requested = CopyOnWriteArrayList<HttpUrl>()

    /** URLs exactly as the provider built them, before rerouting, in call order. */
    val requestedUrls: List<HttpUrl>
        get() = requested.toList()

    val client: OkHttpClient = AppModule.provideOkHttpClient().newBuilder()
        .addInterceptor(object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val original = chain.request()
                requested.add(original.url)
                val loopback = server.url("/")
                val routed = original.url.newBuilder()
                    .scheme(loopback.scheme)
                    .host(loopback.host)
                    .port(loopback.port)
                    .build()
                return chain.proceed(original.newBuilder().url(routed).build())
            }
        })
        .build()
}

/** A settings store with nothing in it: each request carries its own credential. */
internal fun newEmptySettingsRepository(): SettingsRepository = SettingsRepository(
    dataStore = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        produceFile = {
            Files.createTempDirectory("opendroid-provider-network-test")
                .resolve("settings.preferences_pb")
                .toFile()
        }
    ),
    providerCredentialStore = NoStoredProviderCredentials(),
    runStartupMigration = false
)

internal fun jsonResponse(
    code: Int,
    body: String,
    headers: Map<String, String> = emptyMap()
): MockResponse {
    val builder = MockResponse.Builder()
        .code(code)
        .setHeader("Content-Type", "application/json")
    headers.forEach { (name, value) -> builder.setHeader(name, value) }
    return builder.body(body).build()
}

internal fun RecordedRequest.jsonBody(): JsonObject =
    JsonParser.parseString(body?.utf8().orEmpty()).asJsonObject

/**
 * Asserts that nothing a caller could log or display carries [forbidden]
 * text. The stack trace rendering includes any cause chain. Failure messages
 * name only the index, so a leak is never echoed into the test report.
 */
internal fun assertNothingLeaks(failure: LLMException, vararg forbidden: String) {
    val rendered = listOf(
        failure.message.orEmpty(),
        failure.detail.toString(),
        failure.error.code,
        failure.provider,
        failure.model,
        failure.stackTraceToString()
    ).joinToString("\n")
    forbidden.forEachIndexed { index, text ->
        assertFalse("forbidden text #$index leaked into the failure", rendered.contains(text))
    }
}

private class NoStoredProviderCredentials : ProviderCredentialStore {
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
