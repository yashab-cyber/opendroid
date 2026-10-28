package com.opendroid.ai.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBudgetTest {

    @Test
    fun `estimateTokens uses 4 chars per token rounded up`() {
        assertEquals(0, PromptBudget.estimateTokens(""))
        assertEquals(1, PromptBudget.estimateTokens("hi"))
        assertEquals(1, PromptBudget.estimateTokens("abcd"))
        assertEquals(2, PromptBudget.estimateTokens("abcde"))
        assertEquals(25, PromptBudget.estimateTokens("x".repeat(100)))
    }

    @Test
    fun `outputBudget returns requested tokens when prompt leaves room`() {
        // 100 chars ~= 25 tokens; context 1280 leaves plenty for 500 output tokens
        val budget = PromptBudget.outputBudget(
            prompt = "x".repeat(100),
            contextWindow = 1280,
            requestedOutputTokens = 500
        )
        assertEquals(500, budget)
    }

    @Test
    fun `outputBudget clamps requested tokens to remaining context`() {
        // 4000 chars ~= 1000 tokens; context 1280 leaves 280 for output
        val budget = PromptBudget.outputBudget(
            prompt = "x".repeat(4000),
            contextWindow = 1280,
            requestedOutputTokens = 1500
        )
        assertEquals(280, budget)
    }

    @Test
    fun `outputBudget returns null when prompt fills the context window`() {
        // 6000 chars ~= 1500 tokens > 1280 context: nothing left for output
        val budget = PromptBudget.outputBudget(
            prompt = "x".repeat(6000),
            contextWindow = 1280,
            requestedOutputTokens = 500
        )
        assertNull(budget)
    }

    @Test
    fun `outputBudget returns null when remaining space is below the minimum useful output`() {
        // 5000 chars ~= 1250 tokens; only 30 tokens left, below MIN_OUTPUT_TOKENS
        val budget = PromptBudget.outputBudget(
            prompt = "x".repeat(5000),
            contextWindow = 1280,
            requestedOutputTokens = 500
        )
        assertNull(budget)
    }

    @Test
    fun `registry qwen spec carries its ekv1280 context window`() {
        val qwen = OnDeviceModelRegistry.findById("qwen-2.5-0.5b-it-litert")!!
        assertEquals(1280, qwen.contextWindow)
    }

    @Test
    fun `fitMessagesToBudget retains latest message and prunes oldest when budget exceeded`() {
        val msg1 = com.opendroid.ai.data.models.ChatMessage(
            id = "1",
            text = "Oldest message ".repeat(50), // ~750 chars ~= 188 tokens
            sender = com.opendroid.ai.data.models.ChatMessage.Sender.USER
        )
        val msg2 = com.opendroid.ai.data.models.ChatMessage(
            id = "2",
            text = "Middle message ".repeat(50), // ~750 chars ~= 188 tokens
            sender = com.opendroid.ai.data.models.ChatMessage.Sender.AGENT
        )
        val msg3 = com.opendroid.ai.data.models.ChatMessage(
            id = "3",
            text = "Latest user query",
            sender = com.opendroid.ai.data.models.ChatMessage.Sender.USER
        )

        // Context window 500, overhead 100, reserved output 128 -> Available for messages = 272 tokens
        // msg3 (~5 tokens) + msg2 (~192 tokens) = ~197 tokens -> fits!
        // msg1 cannot fit in remaining 75 tokens -> pruned!
        val fitted = PromptBudget.fitMessagesToBudget(
            messages = listOf(msg1, msg2, msg3),
            contextWindow = 500,
            reservedOutputTokens = 128,
            overheadTokens = 100
        )

        assertEquals(2, fitted.size)
        assertEquals("2", fitted[0].id)
        assertEquals("3", fitted[1].id)
    }

    @Test
    fun `fitMessagesToBudget truncates latest message if it alone exceeds budget`() {
        val massiveLatest = com.opendroid.ai.data.models.ChatMessage(
            id = "1",
            text = "word ".repeat(1000), // ~5000 chars ~= 1250 tokens
            sender = com.opendroid.ai.data.models.ChatMessage.Sender.USER
        )

        val fitted = PromptBudget.fitMessagesToBudget(
            messages = listOf(massiveLatest),
            contextWindow = 500,
            reservedOutputTokens = 128,
            overheadTokens = 100
        )

        assertEquals(1, fitted.size)
        // Available budget is (500 - 128 - 100) = 272 tokens ~= 1088 chars
        assertTrue("Expected message to be truncated to fit budget", fitted[0].text.length <= 272 * 4)
    }
}
