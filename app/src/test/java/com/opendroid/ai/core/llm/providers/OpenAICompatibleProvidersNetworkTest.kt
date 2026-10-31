package com.opendroid.ai.core.llm.providers

import com.opendroid.ai.core.llm.LLMProvider
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ProviderRequestConfig
import com.opendroid.ai.core.llm.ResponseFormat
import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.core.llm.error.LLMException
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.repository.SettingsRepository
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Network contract of the six cloud providers that speak OpenAI chat
 * completions to a hard-coded host. They are separate classes with
 * copy-pasted request and parsing code, so each one runs the whole contract.
 *
 * Each provider's real code runs against a socket; [LoopbackRoute] changes
 * only the scheme, host and port of the URL it built.
 */
@RunWith(Parameterized::class)
class OpenAICompatibleProvidersNetworkTest(private val case: Case) {

    class Case(
        val name: String,
        val host: String,
        val path: String,
        val model: String,
        val apiKey: String,
        val extraHeaders: Map<String, String> = emptyMap(),
        val create: (OkHttpClient, SettingsRepository) -> LLMProvider
    ) {
        override fun toString(): String = name
    }

    private val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
    private val route = LoopbackRoute(server)
    private val provider = case.create(route.client, newEmptySettingsRepository())

    private val systemPrompt = "the-system-prompt-must-not-leak"
    private val earlierQuestion = "an earlier question"
    private val earlierAnswer = "an earlier answer"
    private val prompt = "the-user-prompt-must-not-leak"

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `the request goes to the provider host with a bearer token and no key in the URL`() = runBlocking {
        server.enqueue(jsonResponse(200, SUCCESS_BODY))

        provider.complete(newRequest())

        val built = route.requestedUrls.single()
        assertEquals("https", built.scheme)
        assertEquals(case.host, built.host)
        assertEquals(case.path, built.encodedPath)
        assertNull(built.query)
        assertFalse(built.toString().contains(case.apiKey))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(case.path, recorded.target)
        assertEquals("Bearer ${case.apiKey}", recorded.headers["Authorization"])
        assertNull(recorded.headers["x-api-key"])
        assertTrue(recorded.headers["Content-Type"].orEmpty().startsWith("application/json"))
        case.extraHeaders.forEach { (header, value) ->
            assertEquals(header, value, recorded.headers[header])
        }
    }

    @Test
    fun `the body carries the model, the system prompt first and the mapped roles`() = runBlocking {
        server.enqueue(jsonResponse(200, SUCCESS_BODY))

        provider.complete(newRequest())

        val body = server.takeRequest().jsonBody()
        assertEquals(case.model, body.get("model").asString)
        val messages = body.getAsJsonArray("messages").map { it.asJsonObject }
        assertEquals(
            listOf("system", "user", "assistant", "user"),
            messages.map { it.get("role").asString }
        )
        assertEquals(
            listOf(systemPrompt, earlierQuestion, earlierAnswer, prompt),
            messages.map { it.get("content").asString }
        )
        assertEquals(0.7, body.get("temperature").asDouble, 1e-6)
        assertEquals(256, body.get("max_tokens").asInt)
        assertEquals("json_object", body.getAsJsonObject("response_format").get("type").asString)
    }

    @Test
    fun `text mode sends no response_format`() = runBlocking {
        server.enqueue(jsonResponse(200, SUCCESS_BODY))

        provider.complete(newRequest(responseFormat = ResponseFormat.TEXT))

        assertFalse(server.takeRequest().jsonBody().has("response_format"))
    }

    @Test
    fun `a successful completion is parsed`() = runBlocking {
        server.enqueue(jsonResponse(200, SUCCESS_BODY))

        val response = provider.complete(newRequest())

        assertEquals("pong", response.content)
        assertEquals(42, response.tokensUsed)
        assertEquals(case.model, response.model)
        assertEquals(case.name, response.provider)
        assertEquals(case.name, provider.name)
    }

    @Test
    fun `a missing usage block reports zero tokens`() = runBlocking {
        server.enqueue(jsonResponse(200, """{"choices":[{"message":{"role":"assistant","content":"pong"}}]}"""))

        val response = provider.complete(newRequest())

        assertEquals("pong", response.content)
        assertEquals(0, response.tokensUsed)
    }

    @Test
    fun `a refusal with null content is returned as the reply`() = runBlocking {
        // OpenAI answers a refused request with content null and the text in "refusal" (#88).
        server.enqueue(
            jsonResponse(
                200,
                """{"choices":[{"message":{"role":"assistant","content":null,"refusal":"I can't help with that."}}]}"""
            )
        )

        assertEquals("I can't help with that.", provider.complete(newRequest()).content)
    }

    @Test
    fun `null content without a refusal is a malformed response`() {
        server.enqueue(jsonResponse(200, """{"choices":[{"message":{"role":"assistant","content":null}}]}"""))

        assertEquals(LLMError.MalformedResponse, completeExpectingFailure().error)
    }

    @Test
    fun `a 200 error body without choices is a malformed response`() {
        server.enqueue(jsonResponse(200, """{"error":{"message":"upstream failed","code":502}}"""))

        assertEquals(LLMError.MalformedResponse, completeExpectingFailure().error)
    }

    @Test
    fun `null usage fields report zero tokens instead of discarding the reply`() = runBlocking {
        server.enqueue(jsonResponse(200, """{"choices":[{"message":{"role":"assistant","content":"pong"}}],"usage":null}"""))
        server.enqueue(
            jsonResponse(200, """{"choices":[{"message":{"role":"assistant","content":"pong"}}],"usage":{"total_tokens":null}}""")
        )

        assertEquals(0, provider.complete(newRequest()).tokensUsed)
        assertEquals(0, provider.complete(newRequest()).tokensUsed)
    }

    @Test
    fun `content given as typed parts keeps only the text parts`() = runBlocking {
        server.enqueue(
            jsonResponse(
                200,
                """{"choices":[{"message":{"role":"assistant","content":[
                   {"type":"thinking","thinking":"hmm"},{"type":"text","text":"po"},{"type":"text","text":"ng"}]}}]}"""
            )
        )

        assertEquals("pong", provider.complete(newRequest()).content)
    }

    @Test
    fun `a 401 becomes a redacted AuthInvalid`() {
        server.enqueue(
            jsonResponse(
                401,
                """
                {"error":{"message":"Incorrect API key provided: ${case.apiKey}. Prompt was: $prompt. System: $systemPrompt",
                 "type":"invalid_request_error","param":null,"code":"invalid_api_key"}}
                """.trimIndent()
            )
        )

        val failure = completeExpectingFailure()

        assertEquals(LLMError.AuthInvalid, failure.error)
        assertEquals(401, failure.status)
        assertFalse(failure.retryable)
        assertNull(failure.retryAfterMillis)
        assertEquals(case.name, failure.provider)
        assertEquals("invalid_request_error", failure.detail?.vendorType)
        assertEquals("invalid_api_key", failure.detail?.vendorCode)
        assertNothingLeaks(failure, case.apiKey, prompt, systemPrompt, server.hostName, "Incorrect API key provided")
    }

    @Test
    fun `a 429 becomes a retryable RateLimited that honours Retry-After`() {
        server.enqueue(
            jsonResponse(
                429,
                """
                {"error":{"message":"Rate limit reached for ${case.apiKey} while answering $prompt",
                 "type":"requests","param":null,"code":"rate_limit_exceeded"}}
                """.trimIndent(),
                headers = mapOf("Retry-After" to "7")
            )
        )

        val failure = completeExpectingFailure()

        assertEquals(LLMError.RateLimited, failure.error)
        assertEquals(429, failure.status)
        assertTrue(failure.retryable)
        assertEquals(7_000L, failure.retryAfterMillis)
        assertEquals("rate_limit_exceeded", failure.detail?.vendorCode)
        assertNothingLeaks(failure, case.apiKey, prompt, systemPrompt, server.hostName, "Rate limit reached")
    }

    @Test
    fun `a 500 becomes a retryable ServerError`() {
        server.enqueue(
            jsonResponse(
                500,
                """
                {"error":{"message":"The server had an error processing $prompt for ${case.apiKey}",
                 "type":"server_error","param":null,"code":null}}
                """.trimIndent()
            )
        )

        val failure = completeExpectingFailure()

        assertEquals(LLMError.ServerError, failure.error)
        assertEquals(500, failure.status)
        assertTrue(failure.retryable)
        assertNull(failure.retryAfterMillis)
        assertEquals("server_error", failure.detail?.vendorType)
        assertNull(failure.detail?.vendorCode)
        assertNothingLeaks(failure, case.apiKey, prompt, systemPrompt, server.hostName, "The server had an error")
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
        model = case.model,
        maxTokens = 256,
        responseFormat = responseFormat,
        providerConfig = ProviderRequestConfig(apiKey = case.apiKey, endpoint = "")
    )

    companion object {
        private val SUCCESS_BODY = """
            {
              "id": "chatcmpl-test",
              "object": "chat.completion",
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "pong"}, "finish_reason": "stop"}
              ],
              "usage": {"prompt_tokens": 30, "completion_tokens": 12, "total_tokens": 42}
            }
        """.trimIndent()

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Case> = listOf(
            Case(
                name = "OpenAI",
                host = "api.openai.com",
                path = "/v1/chat/completions",
                model = "gpt-4o-mini",
                apiKey = "sk-test-openai-0123456789abcdef",
                create = ::OpenAIProvider
            ),
            Case(
                name = "Groq",
                host = "api.groq.com",
                path = "/openai/v1/chat/completions",
                model = "llama-3.3-70b-versatile",
                apiKey = "gsk_test_groq_0123456789abcdef",
                create = ::GroqProvider
            ),
            Case(
                name = "Mistral AI",
                host = "api.mistral.ai",
                path = "/v1/chat/completions",
                model = "mistral-large-latest",
                apiKey = "mistral-test-0123456789abcdef",
                create = ::MistralProvider
            ),
            Case(
                name = "Together AI",
                host = "api.together.xyz",
                path = "/v1/chat/completions",
                model = "meta-llama/Llama-3-70b-chat-hf",
                apiKey = "together-test-0123456789abcdef",
                create = ::TogetherAIProvider
            ),
            Case(
                name = "DeepSeek",
                host = "api.deepseek.com",
                path = "/chat/completions",
                model = "deepseek-chat",
                apiKey = "sk-test-deepseek-0123456789abcdef",
                create = ::DeepSeekProvider
            ),
            Case(
                name = "OpenRouter",
                host = "openrouter.ai",
                path = "/api/v1/chat/completions",
                model = "openrouter/auto",
                apiKey = "sk-or-test-0123456789abcdef",
                extraHeaders = mapOf(
                    "HTTP-Referer" to "https://opendroid.ai",
                    "X-Title" to "OpenDroid"
                ),
                create = ::OpenRouterProvider
            )
        )
    }
}
