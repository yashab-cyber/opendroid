package com.opendroid.ai.core.llm.providers

import com.opendroid.ai.core.llm.PromptBudget
import java.io.IOException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CappedCollectionTest {

    private val cap = 50

    /** How many snapshots "word word …" takes to reach [cap] estimated tokens. */
    private val snapshotsToReachCap = generateSequence(1) { it + 1 }
        .first { PromptBudget.estimateTokens("word ".repeat(it)) >= cap }

    private var upstreamEmissions = 0

    /**
     * A model that never emits an end token. Like LiteRT-LM's sendMessageAsync, each
     * emission is the whole response so far.
     */
    private val endlessResponse = flow {
        val text = StringBuilder()
        repeat(10_000) {
            upstreamEmissions++
            text.append("word ")
            emit(text.toString())
        }
    }

    @Test
    fun `collectCapped stops the upstream once the cap is reached`() = runBlocking {
        collectCapped(endlessResponse, cap) {}

        assertEquals(snapshotsToReachCap, upstreamEmissions)
    }

    @Test
    fun `collectCapped delivers the text up to the cap`() = runBlocking {
        var deliveries = 0
        var lastText = ""
        collectCapped(endlessResponse, cap) { text ->
            deliveries++
            lastText = text
        }

        assertEquals(snapshotsToReachCap, deliveries)
        assertEquals("word ".repeat(snapshotsToReachCap), lastText)
    }

    @Test
    fun `collectCapped delivers every snapshot of a response that ends before the cap`() = runBlocking {
        val delivered = mutableListOf<String>()
        collectCapped(flowOf("Hi", "Hi there", "Hi there!"), cap) { delivered += it }

        assertEquals(listOf("Hi", "Hi there", "Hi there!"), delivered)
    }

    @Test
    fun `collectCapped rethrows a failure from the upstream`() {
        val failing = flow {
            emit("partial")
            throw IOException("native decode failed")
        }

        val thrown = assertThrows(IOException::class.java) {
            runBlocking { collectCapped(failing, cap) {} }
        }
        assertEquals("native decode failed", thrown.message)
    }
}
