package com.opendroid.ai.core.llm.providers

import com.opendroid.ai.core.llm.ClaudeModelCatalog
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Network contract of [ClaudeProvider] against the Anthropic Messages API:
 * `x-api-key` auth, a top-level system prompt, catalog-gated model IDs and
 * sampling parameters, and redacted error mapping.
 */
class ClaudeProviderNetworkTest {

    private val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
    private val route = LoopbackRoute(server)
    private val provider = ClaudeProvider(route.client, newEmptySettingsRepository())

    /** Never logged or asserted on directly; only its absence from failures is asserted. */
    private val apiKey = "sk-ant-test-0123456789abcdef"
    private val systemPrompt = "the-system-prompt-must-not-leak"
    private val earlierQuestion = "an earlier question"
    private val earlierAnswer = "an earlier answer"
    private val prompt = "the-user-prompt-must-not-leak"

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `the request goes to the Messages API with x-api-key and no key in the URL`() = runBlocking {
        server.enqueue(jsonResponse(200, SUCCESS_BODY))

        provider.complete(newRequest())

        val built = route.requestedUrls.single()
        assertEquals("https", built.scheme)
        assertEquals("api.anthropic.com", built.host)
        assertEquals("/v1/messages", built.encodedPath)
        assertNull(built.query)
        assertFalse(built.toString().contains(apiKey))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/messages", recorded.target)
        assertEquals(apiKey, recorded.headers["x-api-key"])
        assertEquals("2023-06-01", recorded.headers["anthropic-version"])
        assertNull(recorded.headers["Authorization"])
        assertTrue(recorded.headers["Content-Type"].orEmpty().startsWith("application/json"))
    }

    @Test
    fun `the body has a top-level system prompt and user and assistant roles only`() = runBlocking {
        server.enqueue(jsonResponse(200, SUCCESS_BODY))

        provider.complete(newRequest())

        val body = server.takeRequest().jsonBody()
        assertEquals(SAMPLING_MODEL, body.get("model").asString)
        assertEquals(systemPrompt, body.get("system").asString)
        val messages = body.getAsJsonArray("messages").map { it.asJsonObject }
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.get("role").asString })
        assertEquals(
            listOf(earlierQuestion, earlierAnswer, prompt),
            messages.map { it.get("content").asString }
        )
        assertEquals(256, body.get("max_tokens").asInt)
        assertEquals(0.7, body.get("temperature").asDouble, 1e-6)
        // Anthropic has no response_format field; JSON mode must not add one.
        assertFalse(body.has("response_format"))
    }

    @Test
    fun `a model that rejects sampling parameters is sent without temperature`() = runBlocking {
        server.enqueue(jsonResponse(200, SUCCESS_BODY))

        provider.complete(newRequest(model = NO_SAMPLING_MODEL))

        val body = server.takeRequest().jsonBody()
        assertEquals(NO_SAMPLING_MODEL, body.get("model").asString)
        assertFalse(body.has("temperature"))
        assertEquals(256, body.get("max_tokens").asInt)
    }

    @Test
    fun `no model id selects the catalog default`() = runBlocking {
        server.enqueue(jsonResponse(200, SUCCESS_BODY))

        val response = provider.complete(newRequest(model = null))

        assertEquals(ClaudeModelCatalog.defaultModelId, server.takeRequest().jsonBody().get("model").asString)
        assertEquals(ClaudeModelCatalog.defaultModelId, response.model)
    }

    @Test
    fun `a retired model id is sent as its catalog replacement`() = runBlocking {
        val retired = "claude-3-5-sonnet-20241022"
        val replacement = ClaudeModelCatalog.resolve(retired)
        assertNotNull(replacement)
        assertNotEquals(retired, replacement)
        server.enqueue(jsonResponse(200, SUCCESS_BODY))

        val response = provider.complete(newRequest(model = retired))

        assertEquals(replacement, server.takeRequest().jsonBody().get("model").asString)
        assertEquals(replacement, response.model)
    }

    @Test
    fun `an unsupported model id is rejected before any request is sent`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { provider.complete(newRequest(model = "gpt-4o")) }
        }

        assertTrue(route.requestedUrls.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a successful message is parsed and input and output tokens are summed`() = runBlocking {
        server.enqueue(jsonResponse(200, SUCCESS_BODY))

        val response = provider.complete(newRequest())

        assertEquals("pong", response.content)
        assertEquals(42, response.tokensUsed)
        assertEquals(SAMPLING_MODEL, response.model)
        assertEquals("Anthropic Claude", response.provider)
    }

    @Test
    fun `a missing usage block reports zero tokens`() = runBlocking {
        server.enqueue(
            jsonResponse(200, """{"type":"message","role":"assistant","content":[{"type":"text","text":"pong"}]}""")
        )

        val response = provider.complete(newRequest())

        assertEquals("pong", response.content)
        assertEquals(0, response.tokensUsed)
    }

    @Test
    fun `text blocks are joined and other block types skipped`() = runBlocking {
        server.enqueue(
            jsonResponse(
                200,
                """{"content":[{"type":"thinking","thinking":"hmm"},{"type":"text","text":"po"},{"type":"text","text":"ng"}],
                   "usage":{"input_tokens":3,"output_tokens":4}}"""
            )
        )

        assertEquals("pong", provider.complete(newRequest()).content)
    }

    @Test
    fun `a reply without a text block is a malformed response`() {
        for (body in listOf("""{"content":[]}""", """{"content":null}""", """{"type":"message"}""",
                            """{"content":[{"type":"tool_use","id":"t1","name":"x","input":{}}]}""")) {
            server.enqueue(jsonResponse(200, body))
            assertEquals(body, LLMError.MalformedResponse, completeExpectingFailure().error)
        }
    }

    @Test
    fun `null usage reports zero tokens`() = runBlocking {
        server.enqueue(jsonResponse(200, """{"content":[{"type":"text","text":"pong"}],"usage":null}"""))

        assertEquals(0, provider.complete(newRequest()).tokensUsed)
    }

    @Test
    fun `a non-JSON 200 while streaming fails as malformed, not as a network error`() {
        // streamComplete wrapped every parse failure in IOException, which maps to NETWORK (#88).
        server.enqueue(jsonResponse(200, "<html>gateway</html>"))

        val failure = assertThrows(LLMException::class.java) {
            runBlocking { provider.streamComplete(newRequest()).collect {} }
        }

        assertEquals(LLMError.MalformedResponse, failure.error)
    }

    @Test
    fun `a 401 becomes a redacted AuthInvalid`() {
        server.enqueue(
            jsonResponse(
                401,
                """
                {"type":"error","error":{"type":"authentication_error",
                 "message":"invalid x-api-key $apiKey for prompt $prompt. System: $systemPrompt"}}
                """.trimIndent()
            )
        )

        val failure = completeExpectingFailure()

        assertEquals(LLMError.AuthInvalid, failure.error)
        assertEquals(401, failure.status)
        assertFalse(failure.retryable)
        assertEquals("Anthropic Claude", failure.provider)
        assertEquals("authentication_error", failure.detail?.vendorType)
        assertNull(failure.detail?.vendorCode)
        assertNothingLeaks(failure, apiKey, prompt, systemPrompt, server.hostName, "invalid x-api-key")
    }

    @Test
    fun `a 429 becomes a retryable RateLimited that honours Retry-After`() {
        server.enqueue(
            jsonResponse(
                429,
                """
                {"type":"error","error":{"type":"rate_limit_error",
                 "message":"Number of requests has exceeded your rate limit for $apiKey on $prompt"}}
                """.trimIndent(),
                headers = mapOf("Retry-After" to "7")
            )
        )

        val failure = completeExpectingFailure()

        assertEquals(LLMError.RateLimited, failure.error)
        assertEquals(429, failure.status)
        assertTrue(failure.retryable)
        assertEquals(7_000L, failure.retryAfterMillis)
        assertEquals("rate_limit_error", failure.detail?.vendorType)
        assertNothingLeaks(failure, apiKey, prompt, systemPrompt, server.hostName, "exceeded your rate limit")
    }

    @Test
    fun `a 500 becomes a retryable ServerError`() {
        server.enqueue(
            jsonResponse(
                500,
                """{"type":"error","error":{"type":"api_error","message":"Internal server error on $prompt"}}"""
            )
        )

        val failure = completeExpectingFailure()

        assertEquals(LLMError.ServerError, failure.error)
        assertEquals(500, failure.status)
        assertTrue(failure.retryable)
        assertNull(failure.retryAfterMillis)
        assertEquals("api_error", failure.detail?.vendorType)
        assertNothingLeaks(failure, apiKey, prompt, systemPrompt, server.hostName, "Internal server error")
    }

    @Test
    fun `a 529 overload becomes a retryable ServerError`() {
        server.enqueue(
            jsonResponse(529, """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        )

        val failure = completeExpectingFailure()

        assertEquals(LLMError.ServerError, failure.error)
        assertEquals(529, failure.status)
        assertTrue(failure.retryable)
        assertEquals("overloaded_error", failure.detail?.vendorType)
    }

    private fun completeExpectingFailure(): LLMException =
        assertThrows(LLMException::class.java) {
            runBlocking { provider.complete(newRequest()) }
        }

    private fun newRequest(model: String? = SAMPLING_MODEL) = LLMRequest(
        systemPrompt = systemPrompt,
        messages = listOf(
            ChatMessage("1", earlierQuestion, ChatMessage.Sender.USER),
            ChatMessage("2", earlierAnswer, ChatMessage.Sender.AGENT),
            ChatMessage("3", prompt, ChatMessage.Sender.USER)
        ),
        model = model,
        maxTokens = 256,
        providerConfig = ProviderRequestConfig(apiKey = apiKey, endpoint = "")
    )

    private companion object {
        /** Picked from the catalog, so a model release or retirement cannot invalidate the test. */
        val SAMPLING_MODEL = ClaudeModelCatalog.models.first { it.acceptsSamplingParameters }.id
        val NO_SAMPLING_MODEL = ClaudeModelCatalog.models.first { !it.acceptsSamplingParameters }.id

        val SUCCESS_BODY = """
            {
              "id": "msg_test",
              "type": "message",
              "role": "assistant",
              "content": [{"type": "text", "text": "pong"}],
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 30, "output_tokens": 12}
            }
        """.trimIndent()
    }
}
