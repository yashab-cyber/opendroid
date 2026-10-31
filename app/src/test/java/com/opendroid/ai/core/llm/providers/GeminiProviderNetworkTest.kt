package com.opendroid.ai.core.llm.providers

import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ProviderRequestConfig
import com.opendroid.ai.core.llm.ResponseFormat
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
 * Network contract of [GeminiProvider] against `generateContent`: the key
 * travels in `x-goog-api-key` (never the `?key=` query), the system prompt
 * goes in `systemInstruction`, and assistant turns use the `model` role.
 */
class GeminiProviderNetworkTest {

    private val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
    private val route = LoopbackRoute(server)
    private val provider = GeminiProvider(route.client, newEmptySettingsRepository())

    /** Never logged or asserted on directly; only its absence from failures is asserted. */
    private val apiKey = "AIzaTest0123456789abcdef"
    private val model = "gemini-2.5-flash"
    private val systemPrompt = "the-system-prompt-must-not-leak"
    private val earlierQuestion = "an earlier question"
    private val earlierAnswer = "an earlier answer"
    private val prompt = "the-user-prompt-must-not-leak"

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `the request goes to generateContent with x-goog-api-key and no key in the URL`() = runBlocking {
        server.enqueue(jsonResponse(200, SUCCESS_BODY))

        provider.complete(newRequest())

        val built = route.requestedUrls.single()
        assertEquals("https", built.scheme)
        assertEquals("generativelanguage.googleapis.com", built.host)
        assertEquals("/v1beta/models/$model:generateContent", built.encodedPath)
        assertNull(built.query)
        assertNull(built.queryParameter("key"))
        assertFalse(built.toString().contains(apiKey))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1beta/models/$model:generateContent", recorded.target)
        assertEquals(apiKey, recorded.headers["x-goog-api-key"])
        assertNull(recorded.headers["Authorization"])
        assertTrue(recorded.headers["Content-Type"].orEmpty().startsWith("application/json"))
    }

    @Test
    fun `the body uses systemInstruction and maps assistant turns to the model role`() = runBlocking {
        server.enqueue(jsonResponse(200, SUCCESS_BODY))

        provider.complete(newRequest())

        val body = server.takeRequest().jsonBody()
        assertEquals(
            systemPrompt,
            body.getAsJsonObject("systemInstruction").getAsJsonArray("parts")[0].asJsonObject.get("text").asString
        )
        val contents = body.getAsJsonArray("contents").map { it.asJsonObject }
        assertEquals(listOf("user", "model", "user"), contents.map { it.get("role").asString })
        assertEquals(
            listOf(earlierQuestion, earlierAnswer, prompt),
            contents.map { it.getAsJsonArray("parts")[0].asJsonObject.get("text").asString }
        )
        assertFalse(body.getAsJsonArray("contents").toString().contains(systemPrompt))
        // The model is addressed by the URL path, not the body.
        assertFalse(body.has("model"))

        val generationConfig = body.getAsJsonObject("generationConfig")
        assertEquals(0.7, generationConfig.get("temperature").asDouble, 1e-6)
        assertEquals(256, generationConfig.get("maxOutputTokens").asInt)
        assertEquals("application/json", generationConfig.get("responseMimeType").asString)
    }

    @Test
    fun `text mode sends no responseMimeType`() = runBlocking {
        server.enqueue(jsonResponse(200, SUCCESS_BODY))

        provider.complete(newRequest(responseFormat = ResponseFormat.TEXT))

        val generationConfig = server.takeRequest().jsonBody().getAsJsonObject("generationConfig")
        assertFalse(generationConfig.has("responseMimeType"))
    }

    @Test
    fun `a successful generation is parsed`() = runBlocking {
        server.enqueue(jsonResponse(200, SUCCESS_BODY))

        val response = provider.complete(newRequest())

        assertEquals("pong", response.content)
        assertEquals(42, response.tokensUsed)
        assertEquals(model, response.model)
        assertEquals("Google Gemini", response.provider)
    }

    @Test
    fun `a missing usageMetadata block reports zero tokens`() = runBlocking {
        server.enqueue(
            jsonResponse(200, """{"candidates":[{"content":{"role":"model","parts":[{"text":"pong"}]}}]}""")
        )

        val response = provider.complete(newRequest())

        assertEquals("pong", response.content)
        assertEquals(0, response.tokensUsed)
    }

    @Test
    fun `a MAX_TOKENS candidate with no text parts fails as a malformed response`() {
        // 2.5 models count thinking against maxOutputTokens; a small budget can end
        // with no parts at all (#87). That used to throw a NullPointerException.
        server.enqueue(
            jsonResponse(
                200,
                """{"candidates":[{"content":{"role":"model"},"finishReason":"MAX_TOKENS","index":0}],
                   "usageMetadata":{"promptTokenCount":30,"totalTokenCount":286}}"""
            )
        )

        val failure = completeExpectingFailure()

        assertEquals(LLMError.MalformedResponse, failure.error)
        assertNothingLeaks(failure, apiKey, prompt, systemPrompt, server.hostName)
    }

    @Test
    fun `a blocked prompt fails as an invalid request`() {
        server.enqueue(jsonResponse(200, """{"promptFeedback":{"blockReason":"SAFETY"}}"""))

        val failure = completeExpectingFailure()

        assertEquals(LLMError.RequestInvalid, failure.error)
    }

    @Test
    fun `a candidate stopped for safety without content fails as an invalid request`() {
        server.enqueue(jsonResponse(200, """{"candidates":[{"finishReason":"SAFETY","index":0}]}"""))

        val failure = completeExpectingFailure()

        assertEquals(LLMError.RequestInvalid, failure.error)
    }

    @Test
    fun `every text part of the reply is kept`() = runBlocking {
        server.enqueue(
            jsonResponse(
                200,
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"po"},{"text":"ng"}]}}]}"""
            )
        )

        assertEquals("pong", provider.complete(newRequest()).content)
    }

    @Test
    fun `a null usageMetadata reports zero tokens`() = runBlocking {
        server.enqueue(
            jsonResponse(
                200,
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"pong"}]}}],"usageMetadata":null}"""
            )
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
                """
                {"error":{"code":401,"status":"UNAUTHENTICATED",
                 "message":"Request had invalid authentication credentials: $apiKey. Prompt: $prompt. System: $systemPrompt"}}
                """.trimIndent()
            )
        )

        val failure = completeExpectingFailure()

        assertEquals(LLMError.AuthInvalid, failure.error)
        assertEquals(401, failure.status)
        assertFalse(failure.retryable)
        assertEquals("Google Gemini", failure.provider)
        assertNull(failure.detail?.vendorType)
        assertEquals("UNAUTHENTICATED", failure.detail?.vendorCode)
        assertNothingLeaks(failure, apiKey, prompt, systemPrompt, server.hostName, "invalid authentication credentials")
    }

    @Test
    fun `a 400 for an invalid key, as Gemini reports it, becomes AuthInvalid`() {
        server.enqueue(
            jsonResponse(
                400,
                """
                {"error":{"code":400,"status":"INVALID_ARGUMENT",
                 "message":"API key not valid. Please pass a valid API key.",
                 "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID"}]}}
                """.trimIndent()
            )
        )

        val failure = completeExpectingFailure()

        assertEquals(LLMError.AuthInvalid, failure.error)
        assertEquals(400, failure.status)
        assertEquals("INVALID_ARGUMENT", failure.detail?.vendorCode)
        assertNothingLeaks(failure, apiKey, prompt, systemPrompt, server.hostName, "API key not valid")
    }

    @Test
    fun `a 429 becomes a retryable RateLimited that honours Retry-After`() {
        server.enqueue(
            jsonResponse(
                429,
                """
                {"error":{"code":429,"status":"RESOURCE_EXHAUSTED",
                 "message":"Resource has been exhausted (e.g. check quota). Key $apiKey, prompt $prompt"}}
                """.trimIndent(),
                headers = mapOf("Retry-After" to "7")
            )
        )

        val failure = completeExpectingFailure()

        // Generic "quota" wording on a 429 stays a retryable rate limit.
        assertEquals(LLMError.RateLimited, failure.error)
        assertEquals(429, failure.status)
        assertTrue(failure.retryable)
        assertEquals(7_000L, failure.retryAfterMillis)
        assertEquals("RESOURCE_EXHAUSTED", failure.detail?.vendorCode)
        assertNothingLeaks(failure, apiKey, prompt, systemPrompt, server.hostName, "Resource has been exhausted")
    }

    @Test
    fun `a 500 becomes a retryable ServerError`() {
        server.enqueue(
            jsonResponse(
                500,
                """{"error":{"code":500,"status":"INTERNAL","message":"An internal error has occurred for $prompt"}}"""
            )
        )

        val failure = completeExpectingFailure()

        assertEquals(LLMError.ServerError, failure.error)
        assertEquals(500, failure.status)
        assertTrue(failure.retryable)
        assertNull(failure.retryAfterMillis)
        assertEquals("INTERNAL", failure.detail?.vendorCode)
        assertNothingLeaks(failure, apiKey, prompt, systemPrompt, server.hostName, "An internal error has occurred")
    }

    private fun completeExpectingFailure(): LLMException =
        assertThrows(LLMException::class.java) {
            runBlocking { provider.complete(newRequest()) }
        }

    private fun newRequest(responseFormat: ResponseFormat = ResponseFormat.JSON) = LLMRequest(
        systemPrompt = systemPrompt,
        messages = listOf(
            ChatMessage("1", earlierQuestion, ChatMessage.Sender.USER),
            ChatMessage("2", earlierAnswer, ChatMessage.Sender.AGENT),
            ChatMessage("3", prompt, ChatMessage.Sender.USER)
        ),
        model = model,
        maxTokens = 256,
        responseFormat = responseFormat,
        providerConfig = ProviderRequestConfig(apiKey = apiKey, endpoint = "")
    )

    private companion object {
        val SUCCESS_BODY = """
            {
              "candidates": [
                {
                  "content": {"role": "model", "parts": [{"text": "pong"}]},
                  "finishReason": "STOP",
                  "index": 0
                }
              ],
              "usageMetadata": {"promptTokenCount": 30, "candidatesTokenCount": 12, "totalTokenCount": 42},
              "modelVersion": "gemini-2.5-flash"
            }
        """.trimIndent()
    }
}
