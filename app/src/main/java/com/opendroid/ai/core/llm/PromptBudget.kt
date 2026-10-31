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

    /** Smallest output allocation worth running inference for. */
    const val MIN_OUTPUT_TOKENS = 64

    /**
     * Tokens the runtime adds around the prompt text, which [estimateTokens] never sees:
     * the model's chat-template turn markers (Qwen wraps a turn in `<|im_start|>user` …
     * `<|im_end|>` `<|im_start|>assistant`). Providers reserve it when fitting messages,
     * and [outputBudget] reserves it again when checking the finished prompt.
     */
    const val PROMPT_OVERHEAD_TOKENS = 20

    /** [estimateTokens] counts in sixths of a token so every weight below stays an integer. */
    private const val SIXTHS_PER_TOKEN = 6

    /**
     * Code point ranges Qwen's vocabulary merges well, so one token per code point is an
     * upper bound: Cyrillic, Hebrew and Arabic, CJK punctuation/kana/ideographs, Hangul
     * syllables and full-width forms (measured 0.45–0.9 tokens per code point).
     */
    private val DENSE_SCRIPTS = listOf(
        0x0400..0x052F,
        0x0590..0x06FF,
        0x3000..0x9FFF,
        0xAC00..0xD7AF,
        0xFF00..0xFFEF
    )

    /**
     * Estimates the token count of [text], as an upper bound. Undercounting is the
     * dangerous direction: a prompt that looks like it fits but does not overflows the
     * KV cache and aborts the process.
     *
     * Tuned against Qwen2.x's byte-level BPE (`qwen.tiktoken`), the tokenizer of the
     * smallest-context catalog model:
     * - ASCII words: about 3 letters per token, and at least 1 token per word. Common
     *   English words merge further, so English prose comes out at ~1.3–1.5× its real count.
     * - ASCII digits: 1 token each (Qwen splits numbers into single digits). A space or
     *   punctuation mark next to a digit is a token of its own, as it cannot merge with it.
     * - A space between words is free (it merges into the next word); any other ASCII
     *   punctuation or whitespace costs half a token.
     * - Other characters: 1 token per code point for [DENSE_SCRIPTS], 2 for everything
     *   else (Devanagari and the other Indic scripts, accented Latin, Greek, Thai, emoji,
     *   …), whose characters BPE often splits into several byte tokens.
     */
    fun estimateTokens(text: String): Int {
        var sixths = 0
        var wordLength = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code) {
                wordLength++
            } else {
                sixths += wordSixths(wordLength)
                wordLength = 0
                sixths += when {
                    text[i].isAsciiDigit() -> 6
                    cp < 0x80 && (text.getOrNull(i - 1).isAsciiDigit() || text.getOrNull(i + 1).isAsciiDigit()) -> 6
                    cp == ' '.code -> 0
                    cp < 0x80 -> 3
                    DENSE_SCRIPTS.any { cp in it } -> 6
                    else -> 12
                }
            }
            i += Character.charCount(cp)
        }
        sixths += wordSixths(wordLength)
        return (sixths + SIXTHS_PER_TOKEN - 1) / SIXTHS_PER_TOKEN
    }

    /** A run of [length] ASCII letters: a third of a token per letter, at least one token. */
    private fun wordSixths(length: Int): Int = if (length == 0) 0 else maxOf(6, 2 * length)

    private fun Char?.isAsciiDigit(): Boolean = this != null && this in '0'..'9'

    /**
     * Returns how many output tokens a request may use once [prompt] — plus the
     * [PROMPT_OVERHEAD_TOKENS] of chat template the runtime wraps around it — is loaded
     * into a [contextWindow]-token context: [requestedOutputTokens] when there is room,
     * clamped down to the remaining space otherwise, or `null` when fewer than
     * [MIN_OUTPUT_TOKENS] would remain — i.e. the prompt is too long to run.
     */
    fun outputBudget(prompt: String, contextWindow: Int, requestedOutputTokens: Int): Int? {
        val remaining = contextWindow - estimateTokens(prompt) - PROMPT_OVERHEAD_TOKENS
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
            latestMessage.copy(text = takeLastWithinTokens(latestMessage.text, availableBudget))
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

    /** The longest suffix of [text] whose [estimateTokens] is at most [maxTokens]. */
    private fun takeLastWithinTokens(text: String, maxTokens: Int): String {
        // Dropping characters from the front never raises the estimate, so binary-search
        // the earliest start index that fits.
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high) ushr 1
            if (estimateTokens(text.substring(mid)) <= maxTokens) high = mid else low = mid + 1
        }
        // Don't start on the second half of a surrogate pair.
        if (low < text.length && text[low].isLowSurrogate()) low++
        return text.substring(low)
    }
}
