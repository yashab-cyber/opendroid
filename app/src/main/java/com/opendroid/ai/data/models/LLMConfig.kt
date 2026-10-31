package com.opendroid.ai.data.models

import android.util.Log
import kotlinx.serialization.Serializable
import com.opendroid.ai.core.llm.AIModel
import com.opendroid.ai.core.llm.ClaudeModelCatalog
import com.opendroid.ai.core.llm.ProviderCatalog
import com.opendroid.ai.core.llm.OnDeviceLatencyProfile

private const val TAG = "LLMConfig"

// android.util.Log is unavailable in plain-JVM unit tests; the warning is
// best-effort signal, never worth failing model resolution over.
private fun warnCoercion() = runCatching {
    Log.w(TAG, "Unresolvable Claude model selection; coercing to ${ClaudeModelCatalog.defaultModelId}.")
}

@Serializable
data class LLMConfig(
    val activeProvider: String = "Google Gemini",
    // Read from the catalog rather than repeated here, so one seed cannot drift
    // from the other. It is replaced by the provider's live list on first fetch.
    val activeModel: String = ProviderCatalog.defaultModel("Google Gemini"),
    /**
     * Provider/model pairs. `null` means the setting predates this field and is
     * resolved lazily from [activeProvider]/[activeModel] without an upgrade
     * write. An explicit empty map is a valid migrated value.
     */
    val selectedModels: Map<String, String>? = null,
    val apiKeys: Map<String, String> = emptyMap(), // Provider -> API Key
    val customEndpoints: Map<String, String> = emptyMap(), // Provider -> URL
    // Off by default: LLM-generated plans must be confirmed by the user before
    // executing device actions (calls, messages, settings changes).
    val autoConfirmPlans: Boolean = false,
    // Auto mode (see docs: upstream issue 18 spec). null = never set; resolvedAutoMode()
    // migrates the legacy autoConfirmPlans flag (true behaved like YOLO).
    val autoMode: AutoMode? = null,
    // Action name -> grant timestamp (epoch millis; 0L = seeded default).
    // null = never seeded; effectiveGrantedActions() falls back to defaults.
    // An explicit empty map means "user revoked everything" and stays empty.
    val grantedActions: Map<String, Long>? = null,
    val latencyBenchmarks: Map<String, Long> = emptyMap(), // Provider -> latency Ms
    /** Per-device, per-model-tier local planning measurements. */
    val onDeviceLatencyProfiles: Map<String, OnDeviceLatencyProfile> = emptyMap(),
    /** Provider names explicitly allowed as planning fallbacks; empty means none. */
    val fallbackProviders: List<String> = emptyList(),
    val elevenLabsApiKey: String = "",
    val elevenLabsVoiceId: String = "",
    val ollamaUrl: String = "",
    // Read via resolvedOllamaTimeoutSeconds(); a cold local model load routinely
    // outlasts the shared client's 15-second limit.
    val ollamaTimeoutSeconds: Int = OllamaTimeout.DEFAULT_SECONDS,
    val copilotUrl: String = "",
    val multiAgentModeEnabled: Boolean = false,
    val showFloatingButton: Boolean = true,
    val isDarkMode: Boolean = true,
    val lastModelFetch: Map<String, Long> = emptyMap(), // Provider -> last fetch timestamp
    val modelCache: Map<String, List<AIModel>> = emptyMap() // Provider -> cached AIModels list
)

/** Bounds for the user-set Ollama read/write timeout, in seconds. */
object OllamaTimeout {
    const val DEFAULT_SECONDS = 120
    const val MIN_SECONDS = 15
    const val MAX_SECONDS = 600

    fun clamp(seconds: Int): Int = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
}

fun LLMConfig.resolvedOllamaTimeoutSeconds(): Int = OllamaTimeout.clamp(ollamaTimeoutSeconds)

fun LLMConfig.resolvedAutoMode(): AutoMode =
    autoMode ?: if (autoConfirmPlans) AutoMode.YOLO else AutoMode.OFF

fun LLMConfig.effectiveGrantedActions(): Map<String, Long> =
    grantedActions ?: AutoMode.DEFAULT_GRANTS.associateWith { 0L }

data class ApprovalSettings(
    val mode: AutoMode,
    val grantedActions: Set<String>
)

fun LLMConfig.approvalSettings(): ApprovalSettings = ApprovalSettings(
    mode = resolvedAutoMode(),
    grantedActions = effectiveGrantedActions().keys
)

/**
 * Resolves a Claude model ID for outbound use without coercing to the default.
 *
 * Order: catalog (current IDs + legacy aliases), then any ID Anthropic returned
 * via `/v1/models` in [modelCache] this session. Returns `null` when neither
 * trusts the selection — callers then decide whether to warn and fall back.
 */
fun LLMConfig.resolveClaudeModelOrNull(selected: String): String? {
    ClaudeModelCatalog.resolve(selected)?.let { return it }
    val trimmed = selected.trim()
    if (trimmed.isEmpty()) return null
    val provider = ProviderCatalog.canonicalName("Anthropic Claude")
    val trusted = modelCache[provider].orEmpty()
    return if (trusted.any { it.id == trimmed }) trimmed else null
}

/** Like [resolveClaudeModelOrNull], coercing untrusted IDs to the catalog default. */
fun LLMConfig.resolveClaudeModelId(selected: String): String =
    resolveClaudeModelOrNull(selected) ?: run {
        warnCoercion()
        ClaudeModelCatalog.defaultModelId
    }

fun LLMConfig.selectedModelFor(providerName: String): String {
    val provider = ProviderCatalog.canonicalName(providerName)
    val migratedPairs = selectedModels
        ?.entries
        ?.associate { (key, value) -> ProviderCatalog.canonicalName(key) to value }
    val legacySelection = activeModel.takeIf {
        ProviderCatalog.canonicalName(activeProvider) == provider && it.isNotBlank()
    }
    val selected = migratedPairs?.get(provider)?.takeIf(String::isNotBlank)
        ?: (if (selectedModels == null) legacySelection else null)
        ?: ProviderCatalog.defaultModel(provider)

    return if (provider == "Anthropic Claude") {
        resolveClaudeModelId(selected)
    } else {
        selected.trim()
    }
}

fun LLMConfig.withSelectedModel(providerName: String, model: String): LLMConfig {
    val provider = ProviderCatalog.canonicalName(providerName)
    require(ProviderCatalog.isKnown(provider)) { "Unknown LLM provider." }
    val safeModel = if (provider == "Anthropic Claude") {
        resolveClaudeModelId(model)
    } else {
        model.trim().ifBlank { ProviderCatalog.defaultModel(provider) }
    }
    val pairs = buildMap {
        selectedModels?.forEach { (key, value) ->
            put(ProviderCatalog.canonicalName(key), value)
        }
        if (selectedModels == null && activeModel.isNotBlank()) {
            put(ProviderCatalog.canonicalName(activeProvider), activeModel)
        }
        put(provider, safeModel)
    }
    return copy(
        activeModel = if (ProviderCatalog.canonicalName(activeProvider) == provider) safeModel else activeModel,
        selectedModels = pairs
    )
}

fun LLMConfig.withActiveProvider(providerName: String): LLMConfig {
    val provider = ProviderCatalog.canonicalName(providerName)
    require(ProviderCatalog.isKnown(provider)) { "Unknown LLM provider." }
    return copy(
        activeProvider = provider,
        activeModel = selectedModelFor(provider)
    )
}
