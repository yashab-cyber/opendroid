package com.opendroid.ai.core.llm

/**
 * Stable provider identity and defaults used by Settings, request resolution,
 * connection tests, and providers. Provider display strings are persisted, so
 * aliases are normalized here instead of being interpreted ad hoc.
 */
object ProviderCatalog {
    data class ProviderSpec(
        val displayName: String,
        val defaultModel: String,
        val canonicalName: String = displayName
    )

    const val ON_DEVICE = "On-Device AI"
    const val LEGACY_ON_DEVICE = "Gemma 4 (On-device)"

    /**
     * The model a provider starts on before its live list has been fetched.
     * These are seeds, not a catalog: [com.opendroid.ai.core.llm.ModelFetcher]
     * asks each provider what it currently serves and the picker replaces a
     * seed that the provider no longer lists. Prefer IDs that track a family
     * rather than a dated snapshot, so a seed stays valid between releases.
     */
    val providers: List<ProviderSpec> = listOf(
        ProviderSpec("Google Gemini", "gemini-2.5-flash"),
        ProviderSpec("OpenAI", "gpt-4o"),
        ProviderSpec("Anthropic Claude", ClaudeModelCatalog.defaultModelId),
        ProviderSpec("Mistral AI", "mistral-large-latest"),
        ProviderSpec("Groq", "llama-3.3-70b-versatile"),
        // OpenRouter's auto-router always resolves to a live model, so this seed
        // cannot be retired out from under the user.
        ProviderSpec("OpenRouter", "openrouter/auto"),
        ProviderSpec("Together AI", "meta-llama/Llama-3-70b-chat-hf"),
        ProviderSpec("Cohere", "command-r-plus"),
        ProviderSpec("DeepSeek", "deepseek-chat"),
        ProviderSpec("Copilot API", "gpt-4o"),
        ProviderSpec("Opencode-Go", "beam-free"),
        ProviderSpec("Custom OpenAI Compatible", "custom-model"),
        ProviderSpec("Ollama", "llama3"),
        ProviderSpec(ON_DEVICE, "gemma-4-on-device"),
        ProviderSpec("LiteRT-LM (On-device)", "qwen-2.5-0.5b-it-litert"),
        // Compatibility entry for the directly addressable AI Core backend.
        // Its persisted key is normalized to the unified on-device provider.
        ProviderSpec(LEGACY_ON_DEVICE, "gemma-4-on-device", ON_DEVICE)
    )

    private val byExternalName = providers.associateBy { it.displayName }
    private val byCanonicalName = providers.associateBy { it.canonicalName }

    fun canonicalName(providerName: String): String =
        byExternalName[providerName.trim()]?.canonicalName ?: providerName.trim()

    fun isKnown(providerName: String): Boolean {
        val normalized = canonicalName(providerName)
        return byCanonicalName.containsKey(normalized)
    }

    fun defaultModel(providerName: String): String {
        val normalized = canonicalName(providerName)
        return requireNotNull(byCanonicalName[normalized]) {
            "Unknown LLM provider."
        }.defaultModel
    }

    fun requiresApiKey(providerName: String): Boolean = when (canonicalName(providerName)) {
        "Google Gemini",
        "OpenAI",
        "Anthropic Claude",
        "Mistral AI",
        "Groq",
        "OpenRouter",
        "Together AI",
        "Cohere",
        "DeepSeek",
        "Opencode-Go",
        "Custom OpenAI Compatible" -> true
        else -> false
    }

    fun isOnDevice(providerName: String): Boolean = when (canonicalName(providerName)) {
        ON_DEVICE, "LiteRT-LM (On-device)" -> true
        else -> false
    }
}
