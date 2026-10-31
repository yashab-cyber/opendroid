package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.data.models.AutoMode
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.models.effectiveGrantedActions
import com.opendroid.ai.data.models.withActiveProvider
import com.opendroid.ai.data.models.withSelectedModel
import com.opendroid.ai.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import javax.inject.Inject

import com.opendroid.ai.core.llm.ClaudeModelCatalog
import com.opendroid.ai.core.llm.ConnectionTestPlanner
import com.opendroid.ai.core.llm.ConnectionTestState
import com.opendroid.ai.core.llm.ImportLocalModelResult
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ModelFetchOutcome
import com.opendroid.ai.core.llm.ProviderCatalog
import com.opendroid.ai.core.llm.ResponseFormat
import com.opendroid.ai.core.llm.RetryPolicy
import com.opendroid.ai.core.llm.error.SecretRegistry
import com.opendroid.ai.data.models.resolveClaudeModelOrNull
import com.opendroid.ai.data.models.selectedModelFor
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.core.settings.AppSettingsStore
import com.opendroid.ai.data.repository.ProviderCredentialPersistenceState
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import dagger.Lazy
import com.opendroid.ai.data.models.ChatMessage
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val settingsRepository: SettingsRepository,
    val notificationDao: com.opendroid.ai.data.db.dao.NotificationDao,
    private val llmProviderFactory: Lazy<com.opendroid.ai.core.llm.LLMProviderFactory>,
    private val modelFetcher: Lazy<com.opendroid.ai.core.llm.ModelFetcher>,
    val modelRepository: com.opendroid.ai.data.repository.ModelRepository,
    private val okHttpClient: OkHttpClient,
    private val providerCredentialStore: ProviderCredentialStore,
    // The verification timestamp says when a token was last checked, never what the token is,
    // so it lives with ordinary settings rather than in encrypted storage.
    private val appSettingsStore: AppSettingsStore
) : ViewModel() {

    private val _huggingFaceToken = MutableStateFlow("")
    val huggingFaceToken: StateFlow<String> = _huggingFaceToken

    private val _huggingFaceValidationStatus = MutableStateFlow("Token Required")
    val huggingFaceValidationStatus: StateFlow<String> = _huggingFaceValidationStatus

    private val _huggingFaceLastVerified = MutableStateFlow("Never")
    val huggingFaceLastVerified: StateFlow<String> = _huggingFaceLastVerified

    private val _localImportStatus = MutableStateFlow<String?>(null)
    val localImportStatus: StateFlow<String?> = _localImportStatus

    private val _llmConfig = MutableStateFlow(LLMConfig())
    val llmConfig: StateFlow<LLMConfig> = _llmConfig

    private val _modelsLoading = MutableStateFlow(false)
    val modelsLoading: StateFlow<Boolean> = _modelsLoading

    /**
     * Why the model list is not the provider's live catalog — a missing key, or
     * a failed lookup. Null once a list has been fetched successfully. The app
     * ships no hardcoded model names to fall back on, so this is what the user
     * sees instead of a list that silently went stale.
     */
    private val _modelFetchNotice = MutableStateFlow<String?>(null)
    val modelFetchNotice: StateFlow<String?> = _modelFetchNotice.asStateFlow()

    private val _connectionResults =
        MutableStateFlow<Map<String, ConnectionTestState>>(emptyMap())
    val connectionResults: StateFlow<Map<String, ConnectionTestState>> = _connectionResults.asStateFlow()

    private val _connectionBatchProgress = MutableStateFlow<ConnectionTestState.Testing?>(null)
    val connectionBatchProgress: StateFlow<ConnectionTestState.Testing?> =
        _connectionBatchProgress.asStateFlow()

    private var connectionTestJob: Job? = null
    private val apiKeyUpdateJobs = mutableMapOf<String, Job>()
    private var activeModelJob: Job? = null
    private var elevenLabsApiKeyJob: Job? = null
    private var elevenLabsVoiceIdJob: Job? = null
    private var ollamaUrlJob: Job? = null
    private var copilotUrlJob: Job? = null
    private var customEndpointJob: Job? = null

    private var isLoaded = false

    val providerCredentialRecoveryState = providerCredentialStore.recoveryState
    val providerCredentialPersistenceState = settingsRepository.providerCredentialPersistenceState

    init {
        viewModelScope.launch(Dispatchers.IO) {
            providerCredentialStore.migrateLegacyCredentials()
            val token = when (
                val result = providerCredentialStore.read(ProviderCredentialId.HuggingFaceToken)
            ) {
                is CredentialStoreResult.Success -> result.value.orEmpty()
                CredentialStoreResult.CredentialsMustBeReentered,
                CredentialStoreResult.StorageUnavailable -> ""
            }
            val lastVerified =
                appSettingsStore.huggingFaceLastVerified() ?: "Never"
            withContext(Dispatchers.Main.immediate) {
                _huggingFaceToken.value = token
                _huggingFaceLastVerified.value = lastVerified
                if (token.isNotBlank()) {
                    _huggingFaceValidationStatus.value = "Token Required"
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.llmConfig.collect { config ->
                if (!isLoaded) {
                    _llmConfig.value = config
                    isLoaded = true
                } else {
                    _llmConfig.value = config.copy(
                        apiKeys = _llmConfig.value.apiKeys,
                        customEndpoints = _llmConfig.value.customEndpoints,
                        elevenLabsApiKey = _llmConfig.value.elevenLabsApiKey,
                        elevenLabsVoiceId = _llmConfig.value.elevenLabsVoiceId,
                        ollamaUrl = _llmConfig.value.ollamaUrl,
                        copilotUrl = _llmConfig.value.copilotUrl
                    )
                }
            }
        }
        viewModelScope.launch {
            providerCredentialStore.recoveryState.collect { state ->
                if (state == ProviderCredentialRecoveryState.CredentialsMustBeReentered) {
                    // An already hydrated in-memory snapshot must not become a credential
                    // fallback after direct-store recovery begins.
                    _huggingFaceToken.value = ""
                    _llmConfig.value = _llmConfig.value.copy(
                        apiKeys = emptyMap(),
                        elevenLabsApiKey = ""
                    )
                }
            }
        }
        viewModelScope.launch {
            // Wait for initial config loading
            settingsRepository.llmConfig.first()
            refreshModels(force = false)
        }
    }

    fun updateHuggingFaceToken(token: String) {
        _huggingFaceToken.value = token
        _huggingFaceValidationStatus.value = "Token Required"
        viewModelScope.launch(Dispatchers.IO) {
            if (token.isBlank()) {
                providerCredentialStore.remove(ProviderCredentialId.HuggingFaceToken)
            } else {
                providerCredentialStore.write(ProviderCredentialId.HuggingFaceToken, token)
            }
        }
    }

    fun removeHuggingFaceToken() {
        _huggingFaceToken.value = ""
        _huggingFaceValidationStatus.value = "Token Required"
        _huggingFaceLastVerified.value = "Never"
        viewModelScope.launch(Dispatchers.IO) {
            providerCredentialStore.remove(ProviderCredentialId.HuggingFaceToken)
            clearHuggingFaceVerificationMetadata()
        }
    }

    /** Removes only unavailable provider credential records so the user can enter new values. */
    fun resetProviderCredentialsForReentry() {
        viewModelScope.launch(Dispatchers.IO) {
            if (settingsRepository.resetProviderCredentialsForReentry() is CredentialStoreResult.Success) {
                withContext(Dispatchers.Main.immediate) {
                    _huggingFaceToken.value = ""
                    _huggingFaceValidationStatus.value = "Token Required"
                    _llmConfig.value = _llmConfig.value.copy(
                        apiKeys = emptyMap(),
                        elevenLabsApiKey = ""
                    )
                }
            }
        }
    }

    fun validateHuggingFaceToken() {
        val token = _huggingFaceToken.value
        if (token.isBlank()) {
            _huggingFaceValidationStatus.value = "Token Required"
            return
        }

        _huggingFaceValidationStatus.value = "Verifying..."
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://huggingface.co/api/whoami-v2")
                .header("Authorization", "Bearer $token")
                .build()

            try {
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.code == 200) {
                        _huggingFaceValidationStatus.value = "Valid"
                        val sdf = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                        val dateStr = "Today " + sdf.format(java.util.Date())
                        _huggingFaceLastVerified.value = dateStr
                        if (!appSettingsStore.setHuggingFaceLastVerified(dateStr)) {
                            // The in-memory value still reflects this verification; only the
                            // persisted timestamp is stale, so surface it in the log rather than
                            // interrupting a successful token check.
                            android.util.Log.w(
                                "SettingsViewModel",
                                "Failed to persist Hugging Face verification timestamp"
                            )
                        }
                    } else if (response.code == 401) {
                        _huggingFaceValidationStatus.value = "Invalid"
                    } else {
                        _huggingFaceValidationStatus.value = "Unable to verify"
                    }
                }
            } catch (e: Exception) {
                _huggingFaceValidationStatus.value = "Unable to verify"
            }
        }
    }

    fun importLocalModel(modelId: String, uri: android.net.Uri) {
        _localImportStatus.value = "Importing..."
        viewModelScope.launch {
            // Repository already switches to Dispatchers.IO; yield so "Importing..." can paint first.
            when (val result = modelRepository.importLocalModel(modelId, uri)) {
                is ImportLocalModelResult.Success ->
                    _localImportStatus.value = "Success"
                is ImportLocalModelResult.Failure ->
                    _localImportStatus.value = result.reason
            }
        }
    }

    fun importCustomLocalModel(uri: android.net.Uri) {
        _localImportStatus.value = "Importing..."
        viewModelScope.launch {
            when (val result = modelRepository.importCustomLocalModel(uri)) {
                is ImportLocalModelResult.Success ->
                    _localImportStatus.value = "Success"
                is ImportLocalModelResult.Failure ->
                    _localImportStatus.value = result.reason
            }
        }
    }

    fun clearImportStatus() {
        _localImportStatus.value = null
    }

    fun refreshModels(force: Boolean = false) {
        viewModelScope.launch {
            try {
                val config = _llmConfig.value
                val provider = config.activeProvider

                // Migrate a legacy Claude selection regardless of cache state, so a
                // migratable ID is never left persisted or treated as absent below.
                // Live-fetched IDs (present in modelCache) are trusted the same as
                // catalog entries — see resolveClaudeModelOrNull.
                val isClaude = provider == "Anthropic Claude"
                val claudeResolved = if (isClaude) config.resolveClaudeModelOrNull(config.activeModel) else null
                val activeModel = if (isClaude) {
                    if (claudeResolved != null && claudeResolved != config.activeModel) {
                        updateActiveModel(claudeResolved)
                    }
                    claudeResolved ?: config.activeModel
                } else {
                    config.activeModel
                }
                // Catalog+cache reject this ID: re-fetch so auto-selection can move
                // onto a live model (or the catalog default) rather than leave a
                // hand-edited / attacker-controlled string persisted.
                val unsupportedClaudeModel = isClaude && config.activeModel.isNotBlank() && claudeResolved == null

                // Check cache time limit (1 hour) unless forced
                val lastFetch = config.lastModelFetch[provider] ?: 0L
                val cacheExists = config.modelCache[provider]?.isNotEmpty() == true
                val cacheExpired = System.currentTimeMillis() - lastFetch > 60 * 60 * 1000

                if (force || !cacheExists || cacheExpired || unsupportedClaudeModel) {
                    _modelsLoading.value = true
                    when (val outcome = modelFetcher.get().fetchModels(provider)) {
                        is ModelFetchOutcome.Success -> {
                            _modelFetchNotice.value = null
                            val models = outcome.models
                            try {
                                settingsRepository.saveModelCache(provider, models)
                            } catch (e: Exception) {
                                android.util.Log.e("SettingsViewModel", "Failed to save model cache: ${e.message}", e)
                            }
                            // Local state must include the fresh list before any
                            // withSelectedModel call: selection trusts modelCache,
                            // and the DataStore collect may lag behind this coroutine.
                            _llmConfig.value = _llmConfig.value.copy(
                                modelCache = _llmConfig.value.modelCache + (provider to models),
                                lastModelFetch = _llmConfig.value.lastModelFetch +
                                    (provider to System.currentTimeMillis())
                            )

                            // The live list is authoritative: a selection the provider
                            // no longer serves is replaced. A previously untrusted ID
                            // that now appears in the fetch is kept (it came from
                            // Anthropic), not forced onto the catalog default.
                            val modelExists = models.any { it.id == activeModel }
                            if (!modelExists || activeModel.isBlank()) {
                                val providerDefault = if (provider == "Anthropic Claude") {
                                    models.find { it.id == ClaudeModelCatalog.defaultModelId }
                                } else {
                                    null
                                }
                                val recommended = providerDefault
                                    ?: models.find { it.isRecommended }
                                    ?: models.firstOrNull()
                                recommended?.let {
                                    updateActiveModel(it.id)
                                }
                            }
                        }
                        // Neither non-success state writes the cache: no timestamp is
                        // stamped, so the next visit retries instead of treating an
                        // empty or unverified list as fresh.
                        is ModelFetchOutcome.NeedsCredentials -> {
                            _modelFetchNotice.value = outcome.message
                        }
                        is ModelFetchOutcome.Failed -> {
                            android.util.Log.e("SettingsViewModel", "Failed to fetch models for $provider: ${outcome.message}")
                            _modelFetchNotice.value = outcome.message
                        }
                    }
                    _modelsLoading.value = false
                }
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "Failed to refresh models: ${e.message}", e)
                _modelsLoading.value = false
            }
        }
    }

    fun updateActiveProvider(provider: String) {
        val updated = _llmConfig.value.withActiveProvider(provider)
        _llmConfig.value = updated
        viewModelScope.launch {
            try {
                settingsRepository.updateConfig { current ->
                    current.withActiveProvider(provider)
                }
                refreshModels(force = false)
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "Failed to update active provider: ${e.message}", e)
            }
        }
    }

    fun updateActiveModel(model: String) {
        val provider = _llmConfig.value.activeProvider
        val updated = _llmConfig.value.withSelectedModel(provider, model)
        _llmConfig.value = updated
        activeModelJob?.cancel()
        activeModelJob = viewModelScope.launch {
            try {
                delay(1000)
                // Prefer this session's in-memory Claude/live list for the active
                // provider if DataStore has not absorbed refreshModels yet.
                val memoryModels = _llmConfig.value.modelCache[provider]
                settingsRepository.updateConfig { current ->
                    val config = if (!memoryModels.isNullOrEmpty()) {
                        current.copy(modelCache = current.modelCache + (provider to memoryModels))
                    } else {
                        current
                    }
                    config.withSelectedModel(current.activeProvider, model)
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    android.util.Log.e("SettingsViewModel", "Failed to update active model: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Stores an explicit planning fallback allowlist. No provider is inferred
     * from credentials alone; the list is the user's authorization boundary.
     */
    fun updateFallbackProvider(providerName: String, enabled: Boolean) {
        val provider = ProviderCatalog.canonicalName(providerName)
        val updated = _llmConfig.value.fallbackProviders.toMutableList().apply {
            if (enabled) add(provider) else removeAll { it == provider }
        }.distinct()
        _llmConfig.value = _llmConfig.value.copy(fallbackProviders = updated)
        viewModelScope.launch {
            settingsRepository.updateConfig { current ->
                current.copy(fallbackProviders = updated)
            }
        }
    }

    fun updateApiKey(providerName: String, key: String) {
        val keys = _llmConfig.value.apiKeys.toMutableMap()
        keys[providerName] = key
        _llmConfig.value = _llmConfig.value.copy(apiKeys = keys)
        
        apiKeyUpdateJobs[providerName]?.cancel()
        apiKeyUpdateJobs[providerName] = viewModelScope.launch {
            try {
                delay(1000)
                settingsRepository.updateConfig { current ->
                    val currentKeys = current.apiKeys.toMutableMap()
                    currentKeys[providerName] = key
                    current.copy(apiKeys = currentKeys)
                }
                if (providerName == _llmConfig.value.activeProvider) {
                    refreshModels(force = true)
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    android.util.Log.e("SettingsViewModel", "Failed to update API Key: ${e.message}", e)
                }
            }
        }
    }

    fun updateElevenLabsApiKey(key: String) {
        _llmConfig.value = _llmConfig.value.copy(elevenLabsApiKey = key)
        elevenLabsApiKeyJob?.cancel()
        elevenLabsApiKeyJob = viewModelScope.launch {
            try {
                delay(1000)
                settingsRepository.updateConfig { current ->
                    current.copy(elevenLabsApiKey = key)
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    android.util.Log.e("SettingsViewModel", "Failed to update ElevenLabs API Key: ${e.message}", e)
                }
            }
        }
    }

    fun updateElevenLabsVoiceId(voiceId: String) {
        _llmConfig.value = _llmConfig.value.copy(elevenLabsVoiceId = voiceId)
        elevenLabsVoiceIdJob?.cancel()
        elevenLabsVoiceIdJob = viewModelScope.launch {
            try {
                delay(1000)
                settingsRepository.updateConfig { current ->
                    current.copy(elevenLabsVoiceId = voiceId)
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    android.util.Log.e("SettingsViewModel", "Failed to update ElevenLabs Voice ID: ${e.message}", e)
                }
            }
        }
    }

    fun updateOllamaUrl(url: String) {
        _llmConfig.value = _llmConfig.value.copy(ollamaUrl = url)
        ollamaUrlJob?.cancel()
        ollamaUrlJob = viewModelScope.launch {
            try {
                delay(1000)
                settingsRepository.updateConfig { current ->
                    current.copy(ollamaUrl = url)
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    android.util.Log.e("SettingsViewModel", "Failed to update Ollama URL: ${e.message}", e)
                }
            }
        }
    }

    fun updateCopilotUrl(url: String) {
        _llmConfig.value = _llmConfig.value.copy(copilotUrl = url)
        copilotUrlJob?.cancel()
        copilotUrlJob = viewModelScope.launch {
            try {
                delay(1000)
                settingsRepository.updateConfig { current ->
                    current.copy(copilotUrl = url)
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    android.util.Log.e("SettingsViewModel", "Failed to update Copilot URL: ${e.message}", e)
                }
            }
        }
    }

    fun updateCustomEndpoint(providerName: String, url: String) {
        val endpoints = _llmConfig.value.customEndpoints.toMutableMap()
        endpoints[providerName] = url
        _llmConfig.value = _llmConfig.value.copy(customEndpoints = endpoints)
        
        customEndpointJob?.cancel()
        customEndpointJob = viewModelScope.launch {
            try {
                delay(1000)
                settingsRepository.updateConfig { current ->
                    val currentEndpoints = current.customEndpoints.toMutableMap()
                    currentEndpoints[providerName] = url
                    current.copy(customEndpoints = currentEndpoints)
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    android.util.Log.e("SettingsViewModel", "Failed to update custom endpoint: ${e.message}", e)
                }
            }
        }
    }

    fun testConnection(providerName: String) {
        connectionTestJob?.cancel()
        clearInFlightConnectionState()
        connectionTestJob = viewModelScope.launch {
            runConnectionTest(providerName, index = 1, total = 1)
        }
    }

    fun testAllConfigured() {
        connectionTestJob?.cancel()
        clearInFlightConnectionState()
        connectionTestJob = viewModelScope.launch {
            val snapshot = _llmConfig.value
            val providers = ConnectionTestPlanner.configuredProviders(snapshot)
            providers.forEachIndexed { index, providerName ->
                if (!coroutineContext.isActive) return@launch
                _connectionBatchProgress.value = ConnectionTestState.Testing(
                    provider = providerName,
                    index = index + 1,
                    total = providers.size
                )
                runConnectionTest(providerName, index = index + 1, total = providers.size)
            }
            _connectionBatchProgress.value = null
        }
    }

    fun cancelConnectionTests() {
        connectionTestJob?.cancel()
        connectionTestJob = null
        clearInFlightConnectionState()
    }

    /**
     * Resets everything a cancelled test run would otherwise leave dangling: the batch
     * progress banner ("Testing X of Y") and any provider row still stuck at Testing.
     * Cancelled in-flight providers return to their terminal not-tested presentation
     * rather than being mislabeled as failures.
     */
    private fun clearInFlightConnectionState() {
        _connectionBatchProgress.value = null
        val results = _connectionResults.value
        if (results.values.any { it is ConnectionTestState.Testing }) {
            _connectionResults.value = results.filterValues { it !is ConnectionTestState.Testing }
        }
    }

    private suspend fun runConnectionTest(providerName: String, index: Int, total: Int) {
        val provider = ProviderCatalog.canonicalName(providerName)
        val snapshot = _llmConfig.value
        val model = snapshot.selectedModelFor(provider)
        val now = System.currentTimeMillis()
        val gap = ConnectionTestPlanner.configurationGap(snapshot, provider)
        if (gap != null) {
            publishConnectionResult(ConnectionTestPlanner.stamp(gap, now))
            return
        }

        publishConnectionResult(ConnectionTestState.Testing(provider, index, total))
        val candidateKey = snapshot.apiKeys[provider].orEmpty()
        val candidateEndpoint = when (provider) {
            "Ollama" -> snapshot.ollamaUrl
            "Copilot API" -> snapshot.copilotUrl
            else -> snapshot.customEndpoints[provider].orEmpty()
        }
        val registrations = buildList {
            if (candidateKey.isNotBlank()) add(SecretRegistry.register(candidateKey))
            if (candidateEndpoint.isNotBlank()) add(SecretRegistry.register(candidateEndpoint))
        }
        try {
            val factory = llmProviderFactory.get()
            val llmProvider = factory.getProviderByName(provider)
            val request = LLMRequest(
                systemPrompt = "You are a speed test server. Respond with 'pong'.",
                messages = listOf(
                    ChatMessage(id = "1", text = "ping", sender = ChatMessage.Sender.USER)
                ),
                responseFormat = ResponseFormat.TEXT,
                retryPolicy = RetryPolicy.NONE
            )
            val response = llmProvider.complete(request)
            val connected = ConnectionTestPlanner.success(
                provider = provider,
                model = response.model.ifBlank { model },
                latencyMs = response.latencyMs,
                testedAtMillis = System.currentTimeMillis()
            )
            publishConnectionResult(connected)
            val updatedBenchmarks = _llmConfig.value.latencyBenchmarks.toMutableMap()
            updatedBenchmarks[provider] = connected.latencyMs
            _llmConfig.value = _llmConfig.value.copy(latencyBenchmarks = updatedBenchmarks)
            settingsRepository.updateConfig { current ->
                current.copy(
                    latencyBenchmarks = current.latencyBenchmarks + (provider to connected.latencyMs)
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            publishConnectionResult(
                ConnectionTestPlanner.fromException(
                    provider = provider,
                    model = model,
                    throwable = e,
                    testedAtMillis = System.currentTimeMillis()
                )
            )
        } finally {
            registrations.asReversed().forEach(AutoCloseable::close)
        }
    }

    private fun publishConnectionResult(state: ConnectionTestState) {
        val provider = when (state) {
            is ConnectionTestState.Idle -> return
            is ConnectionTestState.Testing -> state.provider
            is ConnectionTestState.Connected -> state.provider
            is ConnectionTestState.Failed -> state.provider
            is ConnectionTestState.ConfigMissing -> state.provider
        }
        _connectionResults.value = _connectionResults.value + (provider to state)
    }

    fun setAutoMode(mode: AutoMode) {
        _llmConfig.value = _llmConfig.value.copy(autoMode = mode, autoConfirmPlans = mode == AutoMode.YOLO)
        viewModelScope.launch {
            settingsRepository.updateConfig { current ->
                current.copy(autoMode = mode, autoConfirmPlans = mode == AutoMode.YOLO)
            }
        }
    }

    /** Removes one grant. Writes the RESOLVED map minus the action, so the
     *  first revoke also materializes the seeded defaults (a revoked default
     *  must never come back on the next read). */
    fun revokeGrant(action: String) {
        val updated = _llmConfig.value.effectiveGrantedActions() - action
        _llmConfig.value = _llmConfig.value.copy(grantedActions = updated)
        viewModelScope.launch {
            settingsRepository.updateConfig { current ->
                current.copy(grantedActions = current.effectiveGrantedActions() - action)
            }
        }
    }

    fun updateMultiAgentMode(enabled: Boolean) {
        _llmConfig.value = _llmConfig.value.copy(multiAgentModeEnabled = enabled)
        viewModelScope.launch {
            settingsRepository.updateConfig { current ->
                current.copy(multiAgentModeEnabled = enabled)
            }
        }
    }

    fun updateShowFloatingButton(enabled: Boolean) {
        _llmConfig.value = _llmConfig.value.copy(showFloatingButton = enabled)
        viewModelScope.launch {
            settingsRepository.updateConfig { current ->
                current.copy(showFloatingButton = enabled)
            }
        }
    }

    fun updateDarkMode(enabled: Boolean) {
        _llmConfig.value = _llmConfig.value.copy(isDarkMode = enabled)
        viewModelScope.launch {
            settingsRepository.updateConfig { current ->
                current.copy(isDarkMode = enabled)
            }
        }
    }

    // ── On-Device Model Lifecycle Management ──

    val allModels = modelRepository.allModelsFlow.stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val storageInfo = modelRepository.getStorageInfoFlow().stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        initialValue = com.opendroid.ai.data.repository.ModelRepository.StorageInfo(0L, 0L, 0L)
    )

    fun isCellularNetwork(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                connectivityManager.isActiveNetworkMetered
        } catch (_: Exception) {
            false
        }
    }

    fun downloadModel(modelId: String) {
        viewModelScope.launch {
            val spec = com.opendroid.ai.core.llm.OnDeviceModelRegistry.findById(modelId)
            spec?.let {
                modelRepository.startDownload(it)
            }
        }
    }

    fun pauseDownload(modelId: String) {
        viewModelScope.launch {
            val spec = com.opendroid.ai.core.llm.OnDeviceModelRegistry.findById(modelId)
            spec?.let {
                modelRepository.pauseDownload(it)
            }
        }
    }

    fun resumeDownload(modelId: String) {
        viewModelScope.launch {
            val spec = com.opendroid.ai.core.llm.OnDeviceModelRegistry.findById(modelId)
            spec?.let {
                modelRepository.resumeDownload(it)
            }
        }
    }

    fun cancelDownload(modelId: String) {
        viewModelScope.launch {
            val catalog = com.opendroid.ai.core.llm.OnDeviceModelRegistry.findById(modelId)
            if (catalog != null) {
                modelRepository.cancelDownload(catalog)
            } else if (com.opendroid.ai.core.llm.OnDeviceModelRegistry.isCustomId(modelId)) {
                modelRepository.cancelDownload(
                    com.opendroid.ai.core.llm.OnDeviceModelRegistry.customSpec(
                        id = modelId,
                        displayName = modelId,
                        modelFilename = "model.litertlm"
                    )
                )
            }
        }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            val catalog = com.opendroid.ai.core.llm.OnDeviceModelRegistry.findById(modelId)
            if (catalog != null) {
                modelRepository.delete(catalog)
            } else if (com.opendroid.ai.core.llm.OnDeviceModelRegistry.isCustomId(modelId)) {
                modelRepository.delete(
                    com.opendroid.ai.core.llm.OnDeviceModelRegistry.customSpec(
                        id = modelId,
                        displayName = modelId,
                        modelFilename = "model.litertlm"
                    )
                )
            }
        }
    }

    fun loadModel(modelId: String) {
        viewModelScope.launch {
            val catalog = com.opendroid.ai.core.llm.OnDeviceModelRegistry.findById(modelId)
            val spec = catalog
                ?: modelRepository.resolveLiteRTSpec(modelId)
            spec?.let {
                modelRepository.load(it)
                // load() already switches the active on-device provider + model
            }
        }
    }

    fun deleteUnusedModels() {
        viewModelScope.launch {
            modelRepository.deleteUnusedModels()
        }
    }

    private fun clearHuggingFaceVerificationMetadata() {
        // Invoked only from the IO dispatcher because the commit is synchronous.
        if (!appSettingsStore.setHuggingFaceLastVerified(null)) {
            // The in-memory value is already reset to "Never"; only the persisted timestamp
            // survives, so surface it in the log rather than failing the token removal.
            android.util.Log.w(
                "SettingsViewModel",
                "Failed to clear persisted Hugging Face verification timestamp"
            )
        }
    }
}
