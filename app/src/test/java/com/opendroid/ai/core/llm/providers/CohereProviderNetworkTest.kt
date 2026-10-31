package com.opendroid.ai.core.llm.providers

import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ProviderRequestConfig
import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.core.llm.error.LLMException
import com.opendroid.ai.data.models.ChatMessage
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Network contract of [CohereProvider] against the v2 Chat API: bearer auth,
 * OpenAI-style messages with a leading system turn, and a response whose text
 * lives in `message.content[0].text`.
 */
class CohereProviderNetworkTest {

    private val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
    private val route = LoopbackRoute(server)
    private val provider = CohereProvider(route.client, newEmptySettingsRepository())

    /** Never logged or asserted on directly; only its absence from failures is asserted. */
    private val apiKey = "co-test-0123456789abcdef"
    private val model = "command-r-plus"
    private val systemPrompt = "the-system-prompt-must-not-leak"
    private val earlierQuestion = "an earlier question"
    private val earlierAnswer = "an earlier answer"
    private val prompt = "the-user-prompt-must-not-leak"

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `the request goes to v2 chat with a bearer token and no key in the URL`() = runBlocking {
        server.enqueue(jsonResponse(200, SUCCESS_BODY))

        provider.complete(newRequest())

        val built = route.requestedUrls.single()
        assertEquals("https", built.scheme)
        assertEquals("api.cohere.ai", built.host)
        assertEquals("/v2/chat", built.encodedPath)
        assertNull(built.query)
        assertFalse(built.toString().contains(apiKey))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v2/chat", recorded.target)
        assertEquals("Bearer $apiKey", recorded.headers["Authorization"])
        assertNull(recorded.headers["x-api-key"])
        assertTrue(recorded.headers["Content-Type"].orEmpty().startsWith("application/json"))
    }

    @Test
    fun `the body carries the model, the system prompt first and the mapped roles`() = runBlocking {
        server.enqueue(jsonResponse(200, SUCCESS_BODY))

        provider.complete(newRequest())

        val body = server.takeRequest().jsonBody()
        assertEquals(model, body.get("model").asString)
        val messages = body.getAsJsonArray("messages").map { it.asJsonObject }
        assertEquals(
            listOf("system", "user", "assistant", "user"),
            messages.map { it.get("role").asString }
        )
        assertEquals(
            listOf(systemPrompt, earlierQuestion, earlierAnswer, prompt),
            messages.map { it.get("content").asString }
        )
    }

    @Test
    fun `a successful chat response is parsed`() = runBlocking {
        server.enqueue(jsonResponse(200, SUCCESS_BODY))

        val response = provider.complete(newRequest())

        assertEquals("pong", response.content)
        assertEquals(model, response.model)
        assertEquals("Cohere", response.provider)
        // tokensUsed is deliberately not pinned: the provider reads
        // usage.tokens.total_tokens, which the documented v2 shape does not carry.
    }

    @Test
    fun `a missing usage block reports zero tokens`() = runBlocking {
        server.enqueue(
            jsonResponse(200, """{"message":{"role":"assistant","content":[{"type":"text","text":"pong"}]}}""")
        )

        val response = provider.complete(newRequest())

        assertEquals("pong", response.content)
        assertEquals(0, response.tokensUsed)
    }

    @Test
    fun `a 401 becomes a redacted AuthInvalid`() {
        server.enqueue(
            jsonResponse(
                401,
                """{"id":"err-1","message":"invalid api token $apiKey for prompt $prompt. System: $systemPrompt"}"""
            )
        )

        val failure = completeExpectingFailure()

        assertEquals(LLMError.AuthInvalid, failure.error)
        assertEquals(401, failure.status)
        assertFalse(failure.retryable)
        assertEquals("Cohere", failure.provider)
        assertNothingLeaks(failure, apiKey, prompt, systemPrompt, server.hostName, "invalid api token")
    }

    @Test
    fun `a 429 becomes a retryable RateLimited that honours Retry-After`() {
        server.enqueue(
            jsonResponse(
                429,
                """{"id":"err-2","message":"You are using a Trial key ($apiKey), limited to 10 calls a minute. $prompt"}""",
                headers = mapOf("Retry-After" to "7")
            )
        )

        val failure = completeExpectingFailure()

        assertEquals(LLMError.RateLimited, failure.error)
        assertEquals(429, failure.status)
        assertTrue(failure.retryable)
        assertEquals(7_000L, failure.retryAfterMillis)
        assertNothingLeaks(failure, apiKey, prompt, systemPrompt, server.hostName, "Trial key")
    }

    @Test
    fun `a 500 becomes a retryable ServerError`() {
        server.enqueue(
            jsonResponse(500, """{"id":"err-3","message":"internal server error while handling $prompt"}""")
        )

        val failure = completeExpectingFailure()

        assertEquals(LLMError.ServerError, failure.error)
        assertEquals(500, failure.status)
        assertTrue(failure.retryable)
        assertNull(failure.retryAfterMillis)
        assertNothingLeaks(failure, apiKey, prompt, systemPrompt, server.hostName, "internal server error")
    }

    private fun completeExpectingFailure(): LLMException =
        assertThrows(LLMException::class.java) {
            runBlocking { provider.complete(newRequest()) }
        }

    private fun newRequest() = LLMRequest(
        systemPrompt = systemPrompt,
        messages = listOf(
            ChatMessage("1", earlierQuestion, ChatMessage.Sender.USER),
            ChatMessage("2", earlierAnswer, ChatMessage.Sender.AGENT),
            ChatMessage("3", prompt, ChatMessage.Sender.USER)
        ),
        model = model,
        providerConfig = ProviderRequestConfig(apiKey = apiKey, endpoint = "")
    )

    private companion object {
        /** The documented v2 shape: usage reports input and output tokens, with no total. */
        val SUCCESS_BODY = """
            {
              "id": "chat-test",
              "finish_reason": "COMPLETE",
              "message": {"role": "assistant", "content": [{"type": "text", "text": "pong"}]},
              "usage": {
                "billed_units": {"input_tokens": 30, "output_tokens": 12},
                "tokens": {"input_tokens": 30, "output_tokens": 12}
              }
            }
        """.trimIndent()
    }
}
