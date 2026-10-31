package com.opendroid.ai.core.llm

/**
 * Whether [LLMProviderFactory] replaces a planning request's system prompt with the full
 * [com.opendroid.ai.core.llm.prompts.SystemPrompts.buildMainPrompt], which carries the
 * live action registry, device state and memory context.
 */
internal object PlanningPromptRewrite {

    fun applies(providerName: String, systemPrompt: String): Boolean =
        systemPrompt.contains("Planning Engine") || systemPrompt.contains("AVAILABLE ACTIONS")
}
