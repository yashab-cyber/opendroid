package com.opendroid.ai.core.llm.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class LLMErrorMapperTest {

    @Test
    fun `maps provider dialect evidence into all non-local HTTP categories`() {
        val cases = listOf(
            Case(401, """{"error":{"type":"authentication_error"}}""", LLMError.AuthInvalid),
            Case(402, """{"error":{"code":"billing_required"}}""", LLMError.QuotaExhausted),
            Case(429, """{"error":{"code":"insufficient_quota"}}""", LLMError.QuotaExhausted),
            Case(429, """{"error":{"type":"rate_limit_error"}}""", LLMError.RateLimited),
            Case(429, """{"error":{"message":"quota of credit-based requests exceeded"}}""", LLMError.RateLimited),
            Case(404, """{"error":{"code":"model_not_found"}}""", LLMError.ModelUnavailable),
            Case(400, """{"error":{"type":"invalid_request_error"}}""", LLMError.RequestInvalid),
            Case(529, """{"error":{"type":"overloaded_error"}}""", LLMError.ServerError),
            Case(418, """{"error":{"type":"unexpected"}}""", LLMError.Unknown)
        )

        cases.forEach { case ->
            val exception = LLMErrorMapper.fromHttpFailure(
                provider = ProviderErrorDetail.Provider.CLAUDE,
                model = "claude-sonnet-5",
                httpStatus = case.status,
                rawBody = case.body
            )
            assertEquals("status=${case.status}", case.error, exception.error)
        }
    }

    @Test
    fun `Gemini invalid key 400 and context 5xx use dialect evidence instead of status alone`() {
        val invalidKey = LLMErrorMapper.fromHttpFailure(
            provider = ProviderErrorDetail.Provider.GEMINI,
            model = "gemini-2.0-flash",
            httpStatus = 400,
            rawBody = """{"error":{"status":"INVALID_ARGUMENT","message":"API key not valid"}}"""
        )
        val tooLong = LLMErrorMapper.fromHttpFailure(
            provider = ProviderErrorDetail.Provider.GEMINI,
            model = "gemini-2.0-flash",
            httpStatus = 504,
            rawBody = """{"error":{"status":"DEADLINE_EXCEEDED","message":"input context length exceeded"}}"""
        )

        assertEquals(LLMError.AuthInvalid, invalidKey.error)
        assertEquals(LLMError.RequestInvalid, tooLong.error)
        assertFalse(tooLong.retryable)
    }

    @Test
    fun `retry after accepts fractional seconds and Go duration syntax`() {
        val fractional = LLMErrorMapper.fromHttpFailure(
            provider = ProviderErrorDetail.Provider.OPENAI,
            model = "gpt-4o",
            httpStatus = 429,
            headers = mapOf("Retry-After" to "1.25"),
            rawBody = """{"error":{"type":"rate_limit_error"}}"""
        )
        val goDuration = LLMErrorMapper.fromHttpFailure(
            provider = ProviderErrorDetail.Provider.CLAUDE,
            model = "claude-sonnet-5",
            httpStatus = 529,
            headers = mapOf("retry-after" to "2m59.56s"),
            rawBody = """{"error":{"type":"overloaded_error"}}"""
        )

        assertEquals(1_250L, fractional.retryAfterMillis)
        assertEquals(179_560L, goDuration.retryAfterMillis)
    }

    @Test
    fun `retry after ignores values that are not a delay in seconds`() {
        // Kotlin's toDoubleOrNull accepts these, but none is a Retry-After delay (#79).
        for (value in listOf("Infinity", "1e30", "NaN", "-5", "0x1p3")) {
            val failure = LLMErrorMapper.fromHttpFailure(
                provider = ProviderErrorDetail.Provider.OPENAI,
                model = "gpt-4o",
                httpStatus = 429,
                headers = mapOf("Retry-After" to value),
                rawBody = """{"error":{"type":"rate_limit_error"}}"""
            )
            assertEquals("Retry-After: $value", null, failure.retryAfterMillis)
        }
    }

    @Test
    fun `malformed non-json error retains no body text or secrets`() {
        val key = "sk-secret-value-ABCD6789"
        val raw = "<html>Bearer $key at https://user:password@example.test?key=$key</html>"

        val exception = LLMErrorMapper.fromHttpFailure(
            provider = ProviderErrorDetail.Provider.OPENAI,
            model = "gpt-4o",
            httpStatus = 500,
            rawBody = raw,
            knownSecrets = listOf(key)
        )

        val observable = listOf(exception.message, exception.detail?.toString(), exception.toString())
            .joinToString()
        assertEquals(LLMError.ServerError, exception.error)
        assertFalse(observable.contains(raw))
        assertFalse(observable.contains(key))
        assertFalse(observable.contains("ABCD"))
        assertFalse(observable.contains("6789"))
        assertFalse(observable.contains("password"))
    }

    @Test
    fun `transport errors map to network without retaining hostile cause`() {
        val cause = SocketTimeoutException("Bearer sk-do-not-retain at secret.example")

        val exception = LLMErrorMapper.fromThrowable("OpenAI", "gpt-4o", cause)

        assertEquals(LLMError.Network, exception.error)
        assertNull(exception.cause)
        assertFalse(exception.toString().contains("sk-do-not-retain"))
        assertFalse(exception.toString().contains("secret.example"))
    }

    @Test
    fun `only connect-phase network failures are retryable`() {
        val refused = LLMErrorMapper.fromThrowable("OpenAI", "gpt-4o", ConnectException("refused"))
        val unknownHost = LLMErrorMapper.fromThrowable("OpenAI", "gpt-4o", UnknownHostException("api.test"))
        val timeout = LLMErrorMapper.fromThrowable("OpenAI", "gpt-4o", SocketTimeoutException("read timed out"))
        val io = LLMErrorMapper.fromThrowable("OpenAI", "gpt-4o", java.io.IOException("stream reset"))

        assertEquals(LLMError.Network, refused.error)
        assertTrue(refused.retryable)
        assertTrue(unknownHost.retryable)
        assertEquals(LLMError.Network, timeout.error)
        assertFalse(timeout.retryable)
        assertEquals(LLMError.Network, io.error)
        assertFalse(io.retryable)
    }

    @Test
    fun `missing auth and malformed responses cover local non-HTTP failures`() {
        val missing = LLMErrorMapper.fromThrowable(
            "OpenAI",
            "gpt-4o",
            IllegalStateException("API Key for OpenAI is not set.")
        )
        val malformed = LLMErrorMapper.fromThrowable(
            "OpenAI",
            "gpt-4o",
            IllegalStateException("Empty response body from OpenAI")
        )

        assertEquals(LLMError.AuthMissing, missing.error)
        assertEquals(LLMError.MalformedResponse, malformed.error)
        assertFalse(missing.retryable)
        assertFalse(malformed.retryable)
        assertNull(missing.cause)
        assertNull(malformed.cause)
    }

    @Test
    fun `provider boundary refuses oversized bodies before classification`() {
        val response = Response.Builder()
            .request(Request.Builder().url("https://example.test").build())
            .protocol(Protocol.HTTP_1_1)
            .code(500)
            .message("Server error")
            .body("""{"error":{"type":"${"x".repeat(40_000)}"}}""".toResponseBody())
            .build()

        response.use {
            assertNull(it.consumeBoundedErrorBody())
        }
    }

    @Test
    fun `model metadata strips controls before it can reach UI or audit`() {
        val exception = LLMErrorMapper.fromHttpFailure(
            provider = ProviderErrorDetail.Provider.OPENAI,
            model = "gpt-4o\r\nInjected: secret",
            httpStatus = 500,
            rawBody = null
        )

        assertEquals("gpt-4oInjected: secret", exception.model)
    }

    @Test
    fun `secret registry scrubs candidate credentials for the duration of a connection test`() {
        val candidate = "candidate-credential-12345678"
        val registration = SecretRegistry.register(candidate)
        val exception = try {
            LLMErrorMapper.fromHttpFailure(
                provider = ProviderErrorDetail.Provider.COHERE,
                model = "command-r-plus",
                httpStatus = 401,
                rawBody = """{"error":{"type":"${candidate.take(8)}","code":"${candidate.takeLast(4)}"}}"""
            )
        } finally {
            registration.close()
        }

        assertNull(exception.detail?.vendorType)
        assertNull(exception.detail?.vendorCode)
        assertFalse(SecretRegistry.snapshot().contains(candidate))
    }

    private data class Case(val status: Int, val body: String, val error: LLMError)
}
