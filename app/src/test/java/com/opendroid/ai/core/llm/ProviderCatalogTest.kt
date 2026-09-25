package com.opendroid.ai.core.llm

import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.models.selectedModelFor
import com.opendroid.ai.data.models.withActiveProvider
import com.opendroid.ai.data.models.withSelectedModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogTest {

    @Test
    fun `catalog has one default for every stable provider`() {
        assertEquals(16, ProviderCatalog.providers.size)
        ProviderCatalog.providers.forEach { provider ->
            assertTrue(provider.displayName, ProviderCatalog.defaultModel(provider.displayName).isNotBlank())
        }
    }

    @Test
    fun `nullable model map lazily migrates only the legacy active provider pair`() {
        val legacy = LLMConfig(
            activeProvider = "OpenAI",
            activeModel = "gpt-4-turbo",
            selectedModels = null
        )

        assertEquals("gpt-4-turbo", legacy.selectedModelFor("OpenAI"))
        assertEquals(
            ProviderCatalog.defaultModel("Google Gemini"),
            legacy.selectedModelFor("Google Gemini")
        )
        assertNull(legacy.selectedModels)
    }

    @Test
    fun `switching providers restores remembered model without losing other pairs`() {
        val config = LLMConfig()
            .withSelectedModel("OpenAI", "o3-mini")
            .withSelectedModel("Google Gemini", "gemini-1.5-pro")
            .withActiveProvider("OpenAI")
            .withActiveProvider("Google Gemini")

        assertEquals("gemini-1.5-pro", config.activeModel)
        assertEquals("o3-mini", config.selectedModelFor("OpenAI"))
        assertEquals("gemini-1.5-pro", config.selectedModelFor("Google Gemini"))
    }

    @Test
    fun `legacy on-device name normalizes to one canonical key`() {
        val config = LLMConfig(activeProvider = "Gemma 4 (On-device)", activeModel = "gemma-3n-multimodal")
            .withSelectedModel("Gemma 4 (On-device)", "gemma-4-on-device")

        assertEquals("On-Device AI", ProviderCatalog.canonicalName("Gemma 4 (On-device)"))
        assertEquals("gemma-4-on-device", config.selectedModelFor("On-Device AI"))
        assertTrue(config.selectedModels?.containsKey("Gemma 4 (On-device)") == false)
    }

    @Test
    fun `untrusted persisted Claude ids never cross the provider boundary`() {
        val config = LLMConfig(
            activeProvider = "Anthropic Claude",
            activeModel = "claude-attacker-controlled",
            selectedModels = mapOf("Anthropic Claude" to "claude-attacker-controlled")
        )

        assertEquals(ClaudeModelCatalog.defaultModelId, config.selectedModelFor("Anthropic Claude"))
    }

    @Test
    fun `Claude ids returned by Anthropic live list may cross the provider boundary`() {
        // Option 1 from #141: trust IDs Anthropic listed via /v1/models this session
        // (modelCache), even when they are not yet in the hardcoded catalog.
        val liveId = "claude-future-not-in-catalog"
        val config = LLMConfig(
            activeProvider = "Anthropic Claude",
            activeModel = liveId,
            selectedModels = mapOf("Anthropic Claude" to liveId),
            modelCache = mapOf(
                "Anthropic Claude" to listOf(
                    AIModel(
                        id = liveId,
                        displayName = "Claude Future",
                        provider = "Anthropic Claude"
                    )
                )
            )
        )

        assertEquals(liveId, config.selectedModelFor("Anthropic Claude"))
        assertEquals(
            liveId,
            config.withSelectedModel("Anthropic Claude", liveId)
                .selectedModels?.get("Anthropic Claude")
        )
    }

    @Test
    fun `Claude ids absent from both catalog and live cache still coerce`() {
        val config = LLMConfig(
            activeProvider = "Anthropic Claude",
            activeModel = "claude-future-not-in-catalog",
            selectedModels = mapOf("Anthropic Claude" to "claude-future-not-in-catalog"),
            modelCache = mapOf(
                "Anthropic Claude" to listOf(
                    AIModel(
                        id = "claude-sonnet-5",
                        displayName = "Claude Sonnet 5",
                        provider = "Anthropic Claude"
                    )
                )
            )
        )

        assertEquals(ClaudeModelCatalog.defaultModelId, config.selectedModelFor("Anthropic Claude"))
    }

    @Test
    fun `non-active provider resolves its own remembered model for Test All pairing`() {
        val config = LLMConfig(
            activeProvider = "OpenAI",
            activeModel = "gpt-4o",
            selectedModels = mapOf(
                "OpenAI" to "gpt-4o",
                "Google Gemini" to "gemini-1.5-pro",
                "Anthropic Claude" to ClaudeModelCatalog.defaultModelId
            )
        )

        assertEquals("gpt-4o", config.selectedModelFor("OpenAI"))
        assertEquals("gemini-1.5-pro", config.selectedModelFor("Google Gemini"))
        assertEquals(ClaudeModelCatalog.defaultModelId, config.selectedModelFor("Anthropic Claude"))
        assertEquals(
            ProviderCatalog.defaultModel("Groq"),
            config.selectedModelFor("Groq")
        )
    }
}
