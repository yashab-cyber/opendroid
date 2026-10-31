package com.opendroid.ai.core.llm.error

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.opendroid.ai.core.llm.ProviderCatalog
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

sealed class LLMError(val code: String) {
    data object AuthMissing : LLMError("AUTH_MISSING")
    data object AuthInvalid : LLMError("AUTH_INVALID")
    data object QuotaExhausted : LLMError("QUOTA_EXHAUSTED")
    data object RateLimited : LLMError("RATE_LIMITED")
    data object ModelUnavailable : LLMError("MODEL_UNAVAILABLE")
    data object RequestInvalid : LLMError("REQUEST_INVALID")
    data object Network : LLMError("NETWORK")
    data object ServerError : LLMError("SERVER_ERROR")
    data object MalformedResponse : LLMError("MALFORMED_RESPONSE")
    data object SafeFallbackUnavailable : LLMError("SAFE_FALLBACK_UNAVAILABLE")
    data object Unknown : LLMError("UNKNOWN")
}

/**
 * Allowlisted diagnostic detail. No raw body, endpoint, prompt, key, or cause is
 * representable by this type.
 */
class RedactedDetail private constructor(
    val vendorType: String?,
    val vendorCode: String?
) {
    override fun toString(): String = buildList {
        vendorType?.let { add("type=$it") }
        vendorCode?.let { add("code=$it") }
    }.joinToString()

    companion object {
        internal fun fromProviderDetail(detail: ProviderErrorDetail): RedactedDetail =
            RedactedDetail(
                vendorType = detail.vendorType,
                vendorCode = detail.vendorCode
            )
    }
}

class LLMException internal constructor(
    val error: LLMError,
    provider: String,
    model: String,
    val status: Int? = null,
    val retryable: Boolean = false,
    val retryAfterMillis: Long? = null,
    val detail: RedactedDetail? = null,
    internal val transientMalformedResponse: Boolean = false
) : IOException(safeMessage(error, provider, status)) {
    val provider: String = provider
        .takeIf(ProviderCatalog::isKnown)
        ?.let(ProviderCatalog::canonicalName)
        ?: "Unknown provider"
    val model: String = model
        .trim()
        .filter { character -> character.code in 0x20..0x7e }
        .take(128)

    companion object {
        private fun safeMessage(error: LLMError, provider: String, status: Int?): String =
            buildString {
                append(error.code)
                append(" from ")
                append(
                    provider
                        .takeIf(ProviderCatalog::isKnown)
                        ?.let(ProviderCatalog::canonicalName)
                        ?: "Unknown provider"
                )
                status?.let {
                    append(" (HTTP ")
                    append(it)
                    append(')')
                }
            }
    }
}

/**
 * Process-local scrub scope for credentials that are not yet persisted, such
 * as a candidate key being tested from Settings.
 */
object SecretRegistry {
    private val registrations = mutableMapOf<String, Int>()

    fun register(secret: String): AutoCloseable {
        val normalized = secret.trim()
        if (normalized.length < 4) return AutoCloseable {}
        synchronized(registrations) {
            registrations[normalized] = (registrations[normalized] ?: 0) + 1
        }
        return AutoCloseable {
            synchronized(registrations) {
                val remaining = (registrations[normalized] ?: 1) - 1
                if (remaining <= 0) registrations.remove(normalized)
                else registrations[normalized] = remaining
            }
        }
    }

    fun snapshot(): Set<String> = synchronized(registrations) { registrations.keys.toSet() }
}

object LLMErrorMapper {
    private const val MAX_ERROR_BODY_CHARS = 32_768
    private val GO_DURATION_PART = Regex("""(\d+(?:\.\d+)?)(ms|h|m|s)""")
    /** delay-seconds, plus the fractional form some providers send ("1.25"). */
    private val RETRY_AFTER_SECONDS = Regex("""\d+(?:\.\d+)?""")

    fun fromHttpFailure(
        provider: ProviderErrorDetail.Provider,
        model: String,
        httpStatus: Int,
        headers: Map<String, String> = emptyMap(),
        rawBody: String?,
        knownSecrets: Iterable<String> = emptyList(),
        forbiddenText: Iterable<String> = emptyList()
    ): LLMException {
        val registeredSecrets = SecretRegistry.snapshot()
        val allSecrets = knownSecrets + registeredSecrets
        val boundedBody = rawBody?.takeIf { it.length <= MAX_ERROR_BODY_CHARS }
        val root = parseObject(boundedBody)
        val errorElement = root?.get("error")
        val errorObject = errorElement?.takeIf(JsonElement::isJsonObject)?.asJsonObject
        val evidence = buildList {
            addJsonEvidence(errorElement)
            addJsonEvidence(errorObject?.get("type"))
            addJsonEvidence(errorObject?.get("code"))
            addJsonEvidence(errorObject?.get("status"))
            addJsonEvidence(errorObject?.get("message"))
            addJsonEvidence(root?.get("type"))
            addJsonEvidence(root?.get("code"))
            addJsonEvidence(root?.get("status"))
            addJsonEvidence(root?.get("message"))
        }.joinToString(" ").lowercase(Locale.ROOT)

        val category = classify(provider, httpStatus, evidence)
        val providerDetail = ProviderErrorDetail.fromHttpFailure(
            provider = provider,
            httpStatus = httpStatus,
            rawBody = boundedBody,
            knownSecrets = allSecrets,
            forbiddenText = forbiddenText
        )
        return LLMException(
            error = category,
            provider = provider.displayName,
            model = model,
            status = httpStatus,
            retryable = category == LLMError.RateLimited ||
                category == LLMError.ServerError ||
                httpStatus == 408,
            retryAfterMillis = parseRetryAfter(
                headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value
            ),
            detail = RedactedDetail.fromProviderDetail(providerDetail)
        )
    }

    fun fromThrowable(provider: String, model: String, throwable: Throwable): LLMException {
        if (throwable is CancellationException) throw throwable
        if (throwable is LLMException) return throwable

        val safeMessage = throwable.message.orEmpty()
        val isMalformed = safeMessage.startsWith("Empty response body") ||
            throwable is com.google.gson.JsonParseException ||
            throwable is IndexOutOfBoundsException ||
            throwable is NoSuchElementException
        val isAuthMissing = throwable is IllegalStateException &&
            safeMessage.contains("API Key", ignoreCase = true) &&
            (safeMessage.contains("not set", ignoreCase = true) ||
                safeMessage.contains("not configured", ignoreCase = true))
        val isNetwork = throwable is SocketTimeoutException ||
            throwable is ConnectException ||
            throwable is UnknownHostException ||
            throwable is IOException
        // Only connect-phase failures are safe to retry: once the request may
        // have reached the server, re-POSTing risks duplicate processing.
        val isConnectFailure = throwable is ConnectException ||
            throwable is UnknownHostException

        val error = when {
            isMalformed -> LLMError.MalformedResponse
            isAuthMissing -> LLMError.AuthMissing
            isNetwork -> LLMError.Network
            else -> LLMError.Unknown
        }
        return LLMException(
            error = error,
            provider = provider,
            model = model,
            retryable = error == LLMError.Network && isConnectFailure
        )
    }

    fun malformed(
        provider: String,
        model: String,
        transient: Boolean = false
    ): LLMException = LLMException(
        error = LLMError.MalformedResponse,
        provider = provider,
        model = model,
        retryable = transient,
        transientMalformedResponse = transient
    )

    fun authMissing(provider: String, model: String): LLMException = LLMException(
        error = LLMError.AuthMissing,
        provider = provider,
        model = model
    )

    fun requestInvalid(provider: String, model: String): LLMException = LLMException(
        error = LLMError.RequestInvalid,
        provider = provider,
        model = model
    )

    fun safeFallbackUnavailable(provider: String, model: String): LLMException = LLMException(
        error = LLMError.SafeFallbackUnavailable,
        provider = provider,
        model = model
    )

    private fun classify(
        provider: ProviderErrorDetail.Provider,
        status: Int,
        evidence: String
    ): LLMError {
        val quota = evidence.containsAny("quota", "billing", "credit", "insufficient_quota")
        val invalidKey = evidence.containsAny(
            "invalid_api_key",
            "api_key_invalid",
            "api key not valid",
            "authentication_error",
            "invalid x-api-key"
        )
        val contextTooLong = evidence.containsAny(
            "context length",
            "context_length",
            "too many tokens",
            "token limit",
            "input too long"
        )
        val modelUnavailable = evidence.containsAny(
            "model_not_found",
            "unknown_model",
            "model unavailable",
            "model_deprecated",
            "not_found_error"
        )

        return when {
            invalidKey && (status == 400 || status == 401 || status == 403) -> LLMError.AuthInvalid
            status == 401 || status == 403 -> LLMError.AuthInvalid
            // A 429 is retryable rate limiting unless the body specifically
            // reports exhausted credit; generic "quota" wording stays retryable.
            status == 429 ->
                if (evidence.containsAny("insufficient_quota", "billing")) LLMError.QuotaExhausted
                else LLMError.RateLimited
            status == 402 || quota -> LLMError.QuotaExhausted
            modelUnavailable || status == 404 -> LLMError.ModelUnavailable
            provider == ProviderErrorDetail.Provider.GEMINI &&
                status in setOf(500, 504) && contextTooLong -> LLMError.RequestInvalid
            status in setOf(400, 413, 422) -> LLMError.RequestInvalid
            status == 408 -> LLMError.Network
            status >= 500 -> LLMError.ServerError
            else -> LLMError.Unknown
        }
    }

    private fun parseObject(body: String?): JsonObject? {
        if (body.isNullOrBlank()) return null
        return try {
            JsonParser.parseString(body).takeIf(JsonElement::isJsonObject)?.asJsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun MutableList<String>.addJsonEvidence(element: JsonElement?) {
        if (element == null || !element.isJsonPrimitive) return
        runCatching { element.asString.take(512) }.getOrNull()?.let(::add)
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { contains(it) }

    private fun parseRetryAfter(value: String?): Long? {
        val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        // Not toDoubleOrNull alone: it also accepts "Infinity", "NaN", "1e30" and hex floats.
        if (RETRY_AFTER_SECONDS.matches(text)) {
            return (text.toDouble() * 1_000.0).roundToLong()
        }

        val parts = GO_DURATION_PART.findAll(text).toList()
        if (parts.isNotEmpty() && parts.joinToString("") { it.value } == text) {
            val millis = parts.sumOf { match ->
                val amount = match.groupValues[1].toDouble()
                val multiplier = when (match.groupValues[2]) {
                    "h" -> 3_600_000.0
                    "m" -> 60_000.0
                    "s" -> 1_000.0
                    else -> 1.0
                }
                amount * multiplier
            }
            return millis.roundToLong().takeIf { it >= 0L }
        }

        return runCatching {
            val retryAt = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            (retryAt.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0L)
        }.getOrNull()
    }
}
