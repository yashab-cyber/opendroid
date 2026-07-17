package com.opendroid.ai.core.llm.providers

import android.util.Log
import com.opendroid.ai.core.llm.*
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hybrid on-device provider that orchestrates between AI Core and LiteRT-LM.
 *
 * Strategy:
 * 1. If the user has explicitly selected an AI Core model → use [GemmaProvider].
 * 2. If the user has explicitly selected a LiteRT-LM model → use [LiteRTLMProvider].
 * 3. If the selected model is ambiguous or the preferred backend is unavailable,
 *    try AI Core first, then fall back to LiteRT-LM.
 *
 * This provider is registered in [LLMProviderFactory] under the name "On-Device AI"
 * and replaces the old "Gemma 4 (On-device)" entry as the single on-device option
 * visible to the user.
 */
@Singleton
class HybridOnDeviceProvider @Inject constructor(
    private val gemmaProvider: GemmaProvider,
    private val liteRTLMProvider: LiteRTLMProvider,
    private val settingsRepository: SettingsRepository
) : LLMProvider {

    companion object {
        private const val TAG = "HybridOnDeviceProvider"
        const val PROVIDER_NAME = "On-Device AI"
    }

    override val name: String = PROVIDER_NAME

    override val availableModels: List<String> =
        OnDeviceModelRegistry.allModels.map { it.id }

    /**
     * Determines which backend should handle the given model ID.
     */
    private fun resolveBackend(modelId: String): OnDeviceBackend {
        val spec = OnDeviceModelRegistry.findById(modelId)
        return spec?.backend ?: OnDeviceBackend.AI_CORE // default to AI Core for legacy model IDs
    }

    /**
     * Returns the delegate provider for the given backend.
     */
    private fun delegateFor(backend: OnDeviceBackend): LLMProvider = when (backend) {
        OnDeviceBackend.AI_CORE -> gemmaProvider
        OnDeviceBackend.LITERT_LM -> liteRTLMProvider
    }

    /**
     * Returns the fallback provider (the other backend).
     */
    private fun fallbackFor(backend: OnDeviceBackend): LLMProvider = when (backend) {
        OnDeviceBackend.AI_CORE -> liteRTLMProvider
        OnDeviceBackend.LITERT_LM -> gemmaProvider
    }

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val config = settingsRepository.llmConfig.first()
        val backend = resolveBackend(config.activeModel)
        val primary = delegateFor(backend)
        val fallback = fallbackFor(backend)

        return try {
            if (primary.isAvailable()) {
                Log.d(TAG, "Using primary backend: ${primary.name}")
                primary.complete(request)
            } else {
                Log.d(TAG, "Primary backend ${primary.name} unavailable, trying fallback: ${fallback.name}")
                if (fallback.isAvailable()) {
                    fallback.complete(request)
                } else {
                    throw IllegalStateException(
                        "No on-device AI backend is available. " +
                        "AI Core is not supported on this device and no LiteRT-LM models are downloaded."
                    )
                }
            }
        } catch (e: Exception) {
            // If primary threw (not just unavailable), try fallback
            if (primary.isAvailable()) {
                Log.w(TAG, "Primary backend ${primary.name} failed: ${e.message}, trying fallback")
                try {
                    if (fallback.isAvailable()) {
                        return fallback.complete(request)
                    }
                } catch (fallbackEx: Exception) {
                    Log.e(TAG, "Fallback also failed: ${fallbackEx.message}")
                }
            }
            throw e
        }
    }

    override fun streamComplete(request: LLMRequest): Flow<String> = flow {
        val config = settingsRepository.llmConfig.first()
        val backend = resolveBackend(config.activeModel)
        val primary = delegateFor(backend)
        val fallback = fallbackFor(backend)

        val activeProvider = when {
            primary.isAvailable() -> {
                Log.d(TAG, "Streaming via primary: ${primary.name}")
                primary
            }
            fallback.isAvailable() -> {
                Log.d(TAG, "Streaming via fallback: ${fallback.name}")
                fallback
            }
            else -> {
                emit("Error: No on-device AI backend is available.")
                return@flow
            }
        }

        try {
            activeProvider.streamComplete(request).collect { chunk ->
                emit(chunk)
            }
        } catch (e: Exception) {
            // Try fallback on stream failure
            if (activeProvider == primary && fallback.isAvailable()) {
                Log.w(TAG, "Stream failed on ${primary.name}, retrying with ${fallback.name}")
                emit("\n[Switched to ${fallback.name}]\n")
                fallback.streamComplete(request).collect { chunk ->
                    emit(chunk)
                }
            } else {
                emit("Error: ${e.localizedMessage}")
            }
        }
    }

    override suspend fun generate(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>
    ): Flow<StreamChunk> = flow {
        val config = settingsRepository.llmConfig.first()
        val backend = resolveBackend(config.activeModel)
        val primary = delegateFor(backend)
        val fallback = fallbackFor(backend)

        val activeProvider = when {
            primary.isAvailable() -> primary
            fallback.isAvailable() -> fallback
            else -> {
                emit(StreamChunk.Content("Error: No on-device AI backend is available."))
                return@flow
            }
        }

        try {
            activeProvider.generate(messages, tools).collect { chunk ->
                emit(chunk)
            }
        } catch (e: Exception) {
            if (activeProvider == primary && fallback.isAvailable()) {
                Log.w(TAG, "Generate failed on ${primary.name}, retrying with ${fallback.name}")
                fallback.generate(messages, tools).collect { chunk ->
                    emit(chunk)
                }
            } else {
                emit(StreamChunk.Content("Error: ${e.localizedMessage}"))
            }
        }
    }

    override suspend fun isAvailable(): Boolean {
        return gemmaProvider.isAvailable() || liteRTLMProvider.isAvailable()
    }

    /**
     * Returns which backend is currently active / would be used for inference.
     * Useful for the Settings UI to show the active backend indicator.
     */
    suspend fun getActiveBackendName(): String {
        val config = settingsRepository.llmConfig.first()
        val backend = resolveBackend(config.activeModel)
        val primary = delegateFor(backend)
        val fallback = fallbackFor(backend)

        return when {
            primary.isAvailable() -> primary.name
            fallback.isAvailable() -> "${fallback.name} (fallback)"
            else -> "None available"
        }
    }

    /**
     * Returns availability status for each backend.
     */
    suspend fun getBackendStatuses(): Map<OnDeviceBackend, Boolean> {
        return mapOf(
            OnDeviceBackend.AI_CORE to gemmaProvider.isAvailable(),
            OnDeviceBackend.LITERT_LM to liteRTLMProvider.isAvailable()
        )
    }
}
