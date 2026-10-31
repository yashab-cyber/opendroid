package com.opendroid.ai.core.llm.providers

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.opendroid.ai.core.llm.error.LLMErrorMapper

/*
 * Reply parsing shared by the cloud providers. Any field in a provider's JSON can be
 * missing or an explicit null, so a reply with no text must fail as a typed malformed
 * response. A NullPointerException or Gson cast error here reaches the user as
 * "Unknown" (#88).
 */

internal data class ParsedReply(val text: String, val tokensUsed: Int)

internal fun parseJsonObject(body: String, provider: String, model: String): JsonObject =
    try {
        JsonParser.parseString(body).objectOrNull()
    } catch (_: JsonParseException) {
        null
    } ?: throw LLMErrorMapper.malformed(provider, model)

/**
 * An OpenAI-style chat completion: `choices[0].message.content`, or the `refusal`
 * OpenAI sends with null content, plus `usage.total_tokens`.
 */
internal fun parseOpenAiStyleReply(body: String, provider: String, model: String): ParsedReply {
    val json = parseJsonObject(body, provider, model)
    val message = json.arrayOrNull("choices")?.firstOrNull()?.objectOrNull()?.objectOrNull("message")
    val text = message?.let { textOf(it.get("content")) ?: it.stringOrNull("refusal") }
        ?: throw LLMErrorMapper.malformed(provider, model)
    return ParsedReply(text, json.objectOrNull("usage")?.intOrNull("total_tokens") ?: 0)
}

/**
 * Text from a plain string, or from the `text`-typed blocks of a content array (Claude
 * blocks, Cohere items, reasoning models' parts). Null when there is no text.
 */
internal fun textOf(content: JsonElement?): String? = when {
    content == null || content.isJsonNull -> null
    content.isJsonPrimitive -> content.asString
    content.isJsonArray -> content.asJsonArray
        .mapNotNull { it.objectOrNull() }
        .filter { (it.stringOrNull("type") ?: "text") == "text" }
        .mapNotNull { it.stringOrNull("text") }
        .joinToString("")
        .ifEmpty { null }
    else -> null
}

internal fun JsonElement.objectOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject.objectOrNull(key: String): JsonObject? = get(key)?.objectOrNull()

internal fun JsonObject.arrayOrNull(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray

internal fun JsonObject.stringOrNull(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

internal fun JsonObject.intOrNull(key: String): Int? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
