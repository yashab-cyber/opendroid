package com.opendroid.ai.core.llm.providers

import com.opendroid.ai.core.llm.PromptBudget
import kotlinx.coroutines.flow.Flow

/**
 * Collects an on-device model's response, passing each snapshot to [onText], with an
 * output cap of [maxTokens] estimated tokens.
 *
 * [snapshots] is cumulative: every emission is the whole response so far, which is how
 * [LiteRTLMProvider] reads the messages of LiteRT-LM's `Conversation.sendMessageAsync`.
 */
internal suspend fun collectCapped(
    snapshots: Flow<String>,
    maxTokens: Int,
    onText: suspend (String) -> Unit
) {
    snapshots.collect { text ->
        onText(text)
        // Returns from this lambda for this one snapshot only: collection, and native
        // decoding, carry on past the cap (#111).
        if (PromptBudget.estimateTokens(text) >= maxTokens) {
            return@collect
        }
    }
}
