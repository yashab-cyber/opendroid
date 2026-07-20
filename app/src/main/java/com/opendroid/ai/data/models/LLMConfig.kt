package com.opendroid.ai.data.models

import kotlinx.serialization.Serializable
import com.opendroid.ai.core.llm.AIModel

@Serializable
data class LLMConfig(
    val activeProvider: String = "Google Gemini",
    val activeModel: String = "gemini-2.0-flash",
    val apiKeys: Map<String, String> = emptyMap(), // Provider -> API Key
    val customEndpoints: Map<String, String> = emptyMap(), // Provider -> URL
    // Off by default: LLM-generated plans must be confirmed by the user before
    // executing device actions (calls, messages, settings changes).
    val autoConfirmPlans: Boolean = false,
    val latencyBenchmarks: Map<String, Long> = emptyMap(), // Provider -> latency Ms
    val elevenLabsApiKey: String = "",
    val elevenLabsVoiceId: String = "",
    val ollamaUrl: String = "",
    val copilotUrl: String = "",
    val multiAgentModeEnabled: Boolean = false,
    val showFloatingButton: Boolean = true,
    val isDarkMode: Boolean = true,
    val lastModelFetch: Map<String, Long> = emptyMap(), // Provider -> last fetch timestamp
    val modelCache: Map<String, List<AIModel>> = emptyMap() // Provider -> cached AIModels list
)

