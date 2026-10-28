package com.opendroid.ai.core.llm

import com.opendroid.ai.data.models.ChatMessage

/**
 * Token budgeting for on-device models with small, fixed context windows.
 *
 * LiteRT-LM engines abort natively (SIGABRT in liblitertlm_jni) when the prompt
 * exceeds the engine's `maxNumTokens` / the model's KV-cache size — that kills
 * the whole process as a force close, with no catchable exception. This object
 * lets providers check the prompt against the model's context window *before*
 * handing it to the native runtime, so oversized prompts surface as a normal
 * error message instead of a crash.
 */
object PromptBudget {

    /** Rough chars-per-token heuristic; deliberately conservative for safety. */
    private const val CHARS_PER_TOKEN = 4

    /** Smallest output allocation worth running inference for. */
    const val MIN_OUTPUT_TOKENS = 64

    /** Estimates the token count of [text], rounding up. */
    fun estimateTokens(text: String): Int =
        (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    /**
     * Returns how many output tokens a request may use once [prompt] is loaded
     * into a [contextWindow]-token context: [requestedOutputTokens] when there is
     * room, clamped down to the remaining space otherwise, or `null` when fewer
     * than [MIN_OUTPUT_TOKENS] would remain — i.e. the prompt is too long to run.
     */
    fun outputBudget(prompt: String, contextWindow: Int, requestedOutputTokens: Int): Int? {
        val remaining = contextWindow - estimateTokens(prompt)
        if (remaining < MIN_OUTPUT_TOKENS) return null
        return minOf(requestedOutputTokens, remaining)
    }

    /**
     * Fits a list of conversation messages into a prompt budget within [contextWindow] tokens,
     * reserving [reservedOutputTokens] for generation.
     *
     * Ensures:
     * 1. The latest message is always retained (truncated if extraordinarily large).
     * 2. System prompt and tool schemas are respected via [overheadTokens].
     * 3. Prior messages are included from newest to oldest up to available budget.
     */
    fun fitMessagesToBudget(
        messages: List<ChatMessage>,
        contextWindow: Int,
        reservedOutputTokens: Int = MIN_OUTPUT_TOKENS * 2,
        overheadTokens: Int = 0
    ): List<ChatMessage> {
        if (messages.isEmpty()) return emptyList()

        val availableBudget = (contextWindow - reservedOutputTokens - overheadTokens).coerceAtLeast(MIN_OUTPUT_TOKENS)
        val latestMessage = messages.last()
        val latestTokens = estimateTokens(latestMessage.text)

        val trimmedLatest = if (latestTokens > availableBudget) {
            val maxChars = availableBudget * CHARS_PER_TOKEN
            latestMessage.copy(text = latestMessage.text.takeLast(maxChars))
        } else {
            latestMessage
        }

        var remainingTokens = availableBudget - estimateTokens(trimmedLatest.text)
        val selectedPriorMessages = mutableListOf<ChatMessage>()

        for (i in messages.size - 2 downTo 0) {
            val msg = messages[i]
            val msgTokens = estimateTokens(msg.text) + 4 // ~4 tokens for message prefix/newline
            if (remainingTokens >= msgTokens) {
                selectedPriorMessages.add(msg)
                remainingTokens -= msgTokens
            } else {
                break
            }
        }

        return selectedPriorMessages.reversed() + trimmedLatest
    }
}
