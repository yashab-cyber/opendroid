package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.data.models.LLMConfig
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

import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ResponseFormat
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import dagger.Lazy
import com.opendroid.ai.data.models.ChatMessage

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val settingsRepository: SettingsRepository,
    val notificationDao: com.opendroid.ai.data.db.dao.NotificationDao,
    private val llmProviderFactory: Lazy<com.opendroid.ai.core.llm.LLMProviderFactory>,
    private val modelFetcher: Lazy<com.opendroid.ai.core.llm.ModelFetcher>,
    val modelRepository: com.opendroid.ai.data.repository.ModelRepository,
    private val okHttpClient: OkHttpClient
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

    private val apiKeyUpdateJobs = mutableMapOf<String, Job>()
    private var activeModelJob: Job? = null
    private var elevenLabsApiKeyJob: Job? = null
    private var elevenLabsVoiceIdJob: Job? = null
    private var ollamaUrlJob: Job? = null
    private var copilotUrlJob: Job? = null
    private var customEndpointJob: Job? = null

    private var isLoaded = false

    init {
        val prefs = com.opendroid.ai.core.security.SecurePrefs.get(context)
        _huggingFaceToken.value = prefs.getString("huggingface_token", "") ?: ""
        _huggingFaceLastVerified.value = prefs.getString("huggingface_last_verified", "Never") ?: "Never"
        if (_huggingFaceToken.value.isNotBlank()) {
            _huggingFaceValidationStatus.value = "Token Required"
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
            // Wait for initial config loading
            settingsRepository.llmConfig.first()
            refreshModels(force = false)
        }
    }

    fun updateHuggingFaceToken(token: String) {
        _huggingFaceToken.value = token
        _huggingFaceValidationStatus.value = "Token Required"
        com.opendroid.ai.core.security.SecurePrefs.get(context)
            .edit()
            .putString("huggingface_token", token)
            .apply()
    }

    fun removeHuggingFaceToken() {
        _huggingFaceToken.value = ""
        _huggingFaceValidationStatus.value = "Token Required"
        _huggingFaceLastVerified.value = "Never"
        com.opendroid.ai.core.security.SecurePrefs.get(context)
            .edit()
            .remove("huggingface_token")
            .remove("huggingface_last_verified")
            .apply()
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
                        com.opendroid.ai.core.security.SecurePrefs.get(context)
                            .edit()
                            .putString("huggingface_last_verified", dateStr)
                            .apply()
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
            val success = modelRepository.importLocalModel(modelId, uri)
            _localImportStatus.value = if (success) "Success" else "Failed"
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
                
                // Check cache time limit (1 hour) unless forced
                val lastFetch = config.lastModelFetch[provider] ?: 0L
                val cacheExists = config.modelCache[provider]?.isNotEmpty() == true
                val cacheExpired = System.currentTimeMillis() - lastFetch > 60 * 60 * 1000
                
                if (force || !cacheExists || cacheExpired) {
                    _modelsLoading.value = true
                    val result = modelFetcher.get().fetchModels(provider)
                    result.onSuccess { models ->
                        try {
                            settingsRepository.saveModelCache(provider, models)
                        } catch (e: Exception) {
                            android.util.Log.e("SettingsViewModel", "Failed to save model cache: ${e.message}", e)
                        }
                        
                        // Auto-select recommended model if current model is blank or not in fetched list
                        val currentModel = config.activeModel
                        val modelExists = models.any { it.id == currentModel }
                        if (!modelExists || currentModel.isBlank()) {
                            val recommended = models.find { it.isRecommended } ?: models.firstOrNull()
                            recommended?.let {
                                updateActiveModel(it.id)
                            }
                        }
                    }
                    result.onFailure { error ->
                        android.util.Log.e("SettingsViewModel", "Failed to fetch models for $provider: ${error.message}", error)
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
        val defaultModel = when (provider) {
            "Google Gemini" -> "gemini-2.0-flash"
            "OpenAI" -> "gpt-4o"
            "Anthropic Claude" -> "claude-sonnet-4-6"
            "OpenRouter" -> "google/gemini-2.0-flash-exp:free"
            "Groq" -> "llama-3.3-70b-specdec"
            "Together AI" -> "meta-llama/Llama-3-70b-chat-hf"
            "DeepSeek" -> "deepseek-chat"
            "Cohere" -> "command-r-plus"
            "Ollama" -> "llama3"
            "Copilot API" -> "gpt-4o"
            "Custom OpenAI Compatible" -> "gpt-4o"
            "On-Device AI",
            "Gemma 4 (On-device)" -> "gemma-4-on-device"
            "Mistral AI" -> "mistral-large-latest"
            else -> "gemini-2.0-flash"
        }
        // Normalize legacy name to the new unified name
        val normalizedProvider = if (provider == "Gemma 4 (On-device)") "On-Device AI" else provider
        _llmConfig.value = _llmConfig.value.copy(activeProvider = normalizedProvider, activeModel = defaultModel)
        viewModelScope.launch {
            try {
                settingsRepository.updateConfig { current ->
                    current.copy(activeProvider = normalizedProvider, activeModel = defaultModel)
                }
                refreshModels(force = false)
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "Failed to update active provider: ${e.message}", e)
            }
        }
    }

    fun updateActiveModel(model: String) {
        _llmConfig.value = _llmConfig.value.copy(activeModel = model)
        activeModelJob?.cancel()
        activeModelJob = viewModelScope.launch {
            try {
                delay(500)
                settingsRepository.updateConfig { current ->
                    current.copy(activeModel = model)
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    android.util.Log.e("SettingsViewModel", "Failed to update active model: ${e.message}", e)
                }
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
                delay(500)
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
                delay(500)
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
                delay(500)
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
                delay(500)
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
                delay(500)
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
                delay(500)
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

    fun testProviderLatency(providerName: String) {
        viewModelScope.launch {
            try {
                val factory = llmProviderFactory.get()
                val provider = factory.getProviderByName(providerName)
                if (provider.isAvailable()) {
                    val request = LLMRequest(
                        systemPrompt = "You are a speed test server. Respond with 'pong'.",
                        messages = listOf(ChatMessage(id = "1", text = "ping", sender = ChatMessage.Sender.USER)),
                        responseFormat = ResponseFormat.TEXT
                    )
                    val response = provider.complete(request)
                    val updatedBenchmarks = _llmConfig.value.latencyBenchmarks.toMutableMap()
                    updatedBenchmarks[providerName] = response.latencyMs
                    _llmConfig.value = _llmConfig.value.copy(latencyBenchmarks = updatedBenchmarks)
                    settingsRepository.updateConfig { current ->
                        val currentBenchmarks = current.latencyBenchmarks.toMutableMap()
                        currentBenchmarks[providerName] = response.latencyMs
                        current.copy(latencyBenchmarks = currentBenchmarks)
                    }
                }
            } catch (e: Exception) {
                // Keep the record but fail with high number
                val updatedBenchmarks = _llmConfig.value.latencyBenchmarks.toMutableMap()
                updatedBenchmarks[providerName] = 9999L
                _llmConfig.value = _llmConfig.value.copy(latencyBenchmarks = updatedBenchmarks)
                settingsRepository.updateConfig { current ->
                    val currentBenchmarks = current.latencyBenchmarks.toMutableMap()
                    currentBenchmarks[providerName] = 9999L
                    current.copy(latencyBenchmarks = currentBenchmarks)
                }
            }
        }
    }

    fun updateAutoConfirmPlans(enabled: Boolean) {
        _llmConfig.value = _llmConfig.value.copy(autoConfirmPlans = enabled)
        viewModelScope.launch {
            settingsRepository.updateConfig { current ->
                current.copy(autoConfirmPlans = enabled)
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

    fun downloadModel(modelId: String, simulate: Boolean = false) {
        viewModelScope.launch {
            val spec = com.opendroid.ai.core.llm.OnDeviceModelRegistry.findById(modelId)
            spec?.let {
                modelRepository.startDownload(it, simulate)
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
            val spec = com.opendroid.ai.core.llm.OnDeviceModelRegistry.findById(modelId)
            spec?.let {
                modelRepository.cancelDownload(it)
            }
        }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            val spec = com.opendroid.ai.core.llm.OnDeviceModelRegistry.findById(modelId)
            spec?.let {
                modelRepository.delete(it)
            }
        }
    }

    fun loadModel(modelId: String) {
        viewModelScope.launch {
            val spec = com.opendroid.ai.core.llm.OnDeviceModelRegistry.findById(modelId)
            spec?.let {
                modelRepository.load(it)
                updateActiveModel(it.id)
            }
        }
    }

    fun deleteUnusedModels() {
        viewModelScope.launch {
            modelRepository.deleteUnusedModels()
        }
    }
}
