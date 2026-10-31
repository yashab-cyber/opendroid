package com.opendroid.ai.core.llm

/**
 * Whether [LLMProviderFactory] replaces a planning request's system prompt with the full
 * [com.opendroid.ai.core.llm.prompts.SystemPrompts.buildMainPrompt], which carries the
 * live action registry, device state and memory context.
 *
 * Never for an on-device provider. Those get the compact
 * [com.opendroid.ai.core.llm.prompts.PlanningPrompts.buildOnDevicePlanningPrompt], sized
 * for a 1280-token window. The full prompt overflowed it, and LiteRT cut it off
 * mid-schema, before the JSON format and the rules (#113).
 */
internal object PlanningPromptRewrite {

    fun applies(providerName: String, systemPrompt: String): Boolean =
        !ProviderCatalog.isOnDevice(providerName) &&
            (systemPrompt.contains("Planning Engine") || systemPrompt.contains("AVAILABLE ACTIONS"))
}
