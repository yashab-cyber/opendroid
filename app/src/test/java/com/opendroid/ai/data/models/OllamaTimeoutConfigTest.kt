package com.opendroid.ai.data.models

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** The user-set Ollama timeout on [LLMConfig]: default, migration and bounds. */
class OllamaTimeoutConfigTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `new configs default to 120 seconds`() {
        assertEquals(120, LLMConfig().resolvedOllamaTimeoutSeconds())
    }

    @Test
    fun `configs saved before the field existed decode to the default`() {
        val legacy = json.decodeFromString<LLMConfig>("""{"ollamaUrl":"http://192.168.1.50:11434"}""")
        assertEquals(OllamaTimeout.DEFAULT_SECONDS, legacy.ollamaTimeoutSeconds)
    }

    @Test
    fun `out-of-range values clamp to the 15 to 600 second bounds`() {
        assertEquals(15, LLMConfig(ollamaTimeoutSeconds = 0).resolvedOllamaTimeoutSeconds())
        assertEquals(15, LLMConfig(ollamaTimeoutSeconds = -5).resolvedOllamaTimeoutSeconds())
        assertEquals(600, LLMConfig(ollamaTimeoutSeconds = 999).resolvedOllamaTimeoutSeconds())
        assertEquals(300, LLMConfig(ollamaTimeoutSeconds = 300).resolvedOllamaTimeoutSeconds())
    }
}
