package com.opendroid.ai.core.llm.error

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.opendroid.ai.core.llm.LLMRequest
import okhttp3.Response
import java.io.IOException

private const val MAX_PROVIDER_ERROR_BODY_CHARS = 32_768

/**
 * A structurally safe summary of a cloud provider HTTP failure.
 *
 * The raw response body is accepted only at the factory boundary, parsed for a
 * small allowlist of vendor identifiers, and never retained. Consequently,
 * [message] and [toString] cannot expose provider messages, request content, or
 * endpoint URLs.
 */
class ProviderErrorDetail private constructor(
    val provider: Provider,
    val httpStatus: Int,
    val vendorType: String?,
    val vendorCode: String?
) {

    val message: String = buildString {
        append(provider.displayName)
        append(" request failed with HTTP ")
        append(httpStatus)

        val attributes = buildList {
            vendorType?.let { add("type=$it") }
            vendorCode?.takeUnless { it == vendorType }?.let { add("code=$it") }
        }
        if (attributes.isNotEmpty()) {
            append(" (")
            append(attributes.joinToString())
            append(')')
        }
        append('.')
    }

    override fun toString(): String = message

    enum class Provider(val displayName: String) {
        OPENAI("OpenAI"),
        CLAUDE("Anthropic Claude"),
        GEMINI("Google Gemini"),
        GROQ("Groq"),
        MISTRAL("Mistral AI"),
        COHERE("Cohere"),
        DEEPSEEK("DeepSeek"),
        OPENROUTER("OpenRouter"),
        REQUESTY("Requesty"),
        TOGETHER_AI("Together AI"),
        CUSTOM_OPENAI("Custom OpenAI Compatible"),
        COPILOT("Copilot API"),
        OLLAMA("Ollama")
    }

    companion object {
        private val SAFE_VENDOR_TOKEN = Regex("""[A-Za-z][A-Za-z0-9._-]{0,63}""")
        private val CREDENTIAL_PREFIXES = listOf(
            "sk-",
            "gsk_",
            "AIza",
            "hf_",
            "xai-",
            "csk-",
            "r8_"
        )

        fun fromHttpFailure(
            provider: Provider,
            httpStatus: Int,
            rawBody: String?,
            knownSecrets: Iterable<String>,
            forbiddenText: Iterable<String>
        ): ProviderErrorDetail {
            require(httpStatus in 100..599) { "HTTP status must be between 100 and 599." }

            val forbiddenFragments = knownSecretFragments(knownSecrets)
            val forbiddenValues = forbiddenText.filterTo(mutableSetOf()) { it.isNotBlank() }
            val root = parseBody(rawBody)
            val error = root?.get("error")?.takeIf { it.isJsonObject }?.asJsonObject

            val vendorType = firstSafeToken(
                candidates = listOf(error?.get("type"), root?.get("type")),
                forbiddenFragments = forbiddenFragments,
                forbiddenValues = forbiddenValues
            )
            val vendorCode = firstSafeToken(
                candidates = listOf(
                    error?.get("code"),
                    error?.get("status"),
                    root?.get("code"),
                    root?.get("status")
                ),
                forbiddenFragments = forbiddenFragments,
                forbiddenValues = forbiddenValues
            )

            return ProviderErrorDetail(
                provider = provider,
                httpStatus = httpStatus,
                vendorType = vendorType,
                vendorCode = vendorCode
            )
        }

        private fun parseBody(rawBody: String?): JsonObject? {
            if (rawBody.isNullOrBlank() || rawBody.length > MAX_PROVIDER_ERROR_BODY_CHARS) return null

            return try {
                JsonParser.parseString(rawBody).takeIf { it.isJsonObject }?.asJsonObject
            } catch (_: Exception) {
                null
            }
        }

        private fun firstSafeToken(
            candidates: List<com.google.gson.JsonElement?>,
            forbiddenFragments: Set<String>,
            forbiddenValues: Set<String>
        ): String? = candidates.firstNotNullOfOrNull { candidate ->
            if (candidate == null || !candidate.isJsonPrimitive || !candidate.asJsonPrimitive.isString) {
                return@firstNotNullOfOrNull null
            }

            candidate.asString.takeIf { token ->
                SAFE_VENDOR_TOKEN.matches(token) &&
                    CREDENTIAL_PREFIXES.none { prefix -> token.startsWith(prefix, ignoreCase = true) } &&
                    forbiddenFragments.none(token::contains) &&
                    forbiddenValues.none { value -> token.contains(value) || value.contains(token) }
            }
        }

        private fun knownSecretFragments(knownSecrets: Iterable<String>): Set<String> = buildSet {
            knownSecrets.forEach { rawSecret ->
                sequenceOf(rawSecret, rawSecret.trim())
                    .filter { it.length >= 4 }
                    .forEach { secret ->
                        add(secret)
                        add(secret.take(4))
                        add(secret.take(8))
                        add(secret.takeLast(4))
                    }
            }
        }
    }
}

/**
 * Consumes an unsuccessful response body at the single safe classification
 * boundary and returns an exception that contains only [ProviderErrorDetail].
 */
internal fun Response.toSafeProviderException(
    provider: ProviderErrorDetail.Provider,
    request: LLMRequest,
    knownSecrets: Iterable<String>
): LLMException {
    val forbiddenText = buildList {
        add(this@toSafeProviderException.request.url.toString())
        add(request.systemPrompt)
        request.messages.forEach { message ->
            add(message.text)
            message.imageBase64?.let(::add)
        }
    }
    val rawBody = try {
        consumeBoundedErrorBody()
    } catch (_: IOException) {
        null
    }
    return LLMErrorMapper.fromHttpFailure(
        provider = provider,
        model = request.model.orEmpty(),
        httpStatus = code,
        headers = headers.toMultimap().mapValues { (_, values) -> values.firstOrNull().orEmpty() },
        rawBody = rawBody,
        knownSecrets = knownSecrets,
        forbiddenText = forbiddenText
    )
}

/**
 * Reads at most the structural classification budget. The provider controls
 * this response, so calling ResponseBody.string() here would allocate an
 * attacker-sized body before any post-read length check could run.
 */
internal fun Response.consumeBoundedErrorBody(): String? {
    val responseBody = body
    val declaredLength = responseBody.contentLength()
    if (declaredLength > MAX_PROVIDER_ERROR_BODY_CHARS) return null

    return responseBody.charStream().use { reader ->
        val buffer = CharArray(4_096)
        val collected = StringBuilder()
        while (collected.length <= MAX_PROVIDER_ERROR_BODY_CHARS) {
            val remaining = MAX_PROVIDER_ERROR_BODY_CHARS + 1 - collected.length
            val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
            if (count == -1) return@use collected.toString()
            collected.append(buffer, 0, count)
        }
        null
    }
}
