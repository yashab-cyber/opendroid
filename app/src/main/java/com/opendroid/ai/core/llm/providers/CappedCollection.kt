package com.opendroid.ai.core.llm.providers

import com.opendroid.ai.core.llm.PromptBudget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformWhile

/**
 * Collects an on-device model's response, passing each snapshot to [onText], and stops
 * once the response reaches [maxTokens] estimated tokens.
 *
 * [snapshots] is cumulative: every emission is the whole response so far, which is how
 * [LiteRTLMProvider] reads the messages of LiteRT-LM's `Conversation.sendMessageAsync`.
 * The snapshot that reaches the cap is still delivered. Then collection ends, which
 * cancels the upstream flow, so a model that never emits an end token stops decoding
 * instead of running until the KV cache is full.
 */
internal suspend fun collectCapped(
    snapshots: Flow<String>,
    maxTokens: Int,
    onText: suspend (String) -> Unit
) {
    snapshots
        .transformWhile { text ->
            emit(text)
            PromptBudget.estimateTokens(text) < maxTokens
        }
        .collect { text -> onText(text) }
}
