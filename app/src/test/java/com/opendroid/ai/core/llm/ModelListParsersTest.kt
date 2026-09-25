package com.opendroid.ai.core.llm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The picker must show what a provider serves *today*. These tests pin the
 * property that makes that true: no parser may key off a specific model name,
 * so a model released after this build still reaches the user.
 *
 * Robolectric supplies a real `org.json`; the unmocked android.jar returns
 * default values for it, which would make every assertion here vacuous.
 */
@RunWith(RobolectricTestRunner::class)
class ModelListParsersTest {

    @Test
    fun `gemini lists chat models by capability, not by name`() {
        val json = JSONObject(
            """
            {"models":[
              {"name":"models/gemini-nonexistent-9","displayName":"Gemini Nonexistent 9",
               "inputTokenLimit":1048576,"supportedGenerationMethods":["generateContent","countTokens"]},
              {"name":"models/text-embedding-004","displayName":"Embedding 004",
               "supportedGenerationMethods":["embedContent"]}
            ]}
            """.trimIndent()
        )

        val models = ModelListParsers.gemini(json, "Google Gemini")

        // A model this build has never heard of is listed the moment Google serves it.
        assertEquals(listOf("gemini-nonexistent-9"), models.map { it.id })
        assertEquals("Gemini Nonexistent 9", models.single().displayName)
        assertEquals(1048576, models.single().contextWindow)
    }

    @Test
    fun `gemini drops a model that cannot generate content`() {
        val json = JSONObject(
            """
            {"models":[{"name":"models/gemini-1-5-flash","supportedGenerationMethods":["countTokens"]}]}
            """.trimIndent()
        )

        assertTrue(ModelListParsers.gemini(json, "Google Gemini").isEmpty())
    }

    @Test
    fun `openai style keeps unknown chat families and drops non-chat endpoints`() {
        val json = JSONObject(
            """
            {"data":[
              {"id":"gpt-99-turbo"},
              {"id":"o9-mini"},
              {"id":"some-future-family-1"},
              {"id":"text-embedding-3-large"},
              {"id":"whisper-1"},
              {"id":"dall-e-3"}
            ]}
            """.trimIndent()
        )

        val ids = ModelListParsers.openAiStyle(json, "OpenAI").map { it.id }

        assertTrue(ids.containsAll(listOf("gpt-99-turbo", "o9-mini", "some-future-family-1")))
        assertFalse(ids.any { it in listOf("text-embedding-3-large", "whisper-1", "dall-e-3") })
    }

    @Test
    fun `anthropic list is authoritative and the catalog only decorates it`() {
        val known = ClaudeModelCatalog.models.first { it.isRecommended }
        val json = JSONObject(
            """
            {"data":[
              {"id":"${known.id}","display_name":"Ignored By Catalog"},
              {"id":"claude-unreleased-9","display_name":"Claude Unreleased 9"}
            ]}
            """.trimIndent()
        )

        val models = ModelListParsers.anthropic(json, "Anthropic Claude")

        assertEquals(2, models.size)
        val curated = models.single { it.id == known.id }
        assertEquals(known.displayName, curated.displayName)
        assertTrue(curated.isRecommended)
        // Unknown to the catalog, still selectable.
        val fresh = models.single { it.id == "claude-unreleased-9" }
        assertEquals("Claude Unreleased 9", fresh.displayName)
        assertFalse(fresh.isRecommended)
    }

    @Test
    fun `cohere uses declared endpoints to keep only chat models`() {
        val json = JSONObject(
            """
            {"models":[
              {"name":"command-future","endpoints":["chat","generate"],"context_length":128000},
              {"name":"embed-future","endpoints":["embed"]}
            ]}
            """.trimIndent()
        )

        val models = ModelListParsers.cohere(json, "Cohere")

        assertEquals(listOf("command-future"), models.map { it.id })
        assertEquals(128000, models.single().contextWindow)
    }

    @Test
    fun `openrouter sorts free models first without hardcoding one`() {
        val json = JSONObject(
            """
            {"data":[
              {"id":"vendor/paid-model","name":"Paid Model","context_length":200000},
              {"id":"vendor/free-model:free","name":"Free Model"}
            ]}
            """.trimIndent()
        )

        val models = ModelListParsers.openRouter(json, "OpenRouter")

        assertEquals(listOf("vendor/free-model:free", "vendor/paid-model"), models.map { it.id })
        assertTrue(models.first().isFree)
        assertFalse(models.last().isFree)
    }

    @Test
    fun `requesty lists managed policies first and merges the catalog`() {
        val managed = JSONObject(
            """
            {"data":[
              {"id":"vendor-model@eu","api":"chat","context_window":200000},
              {"id":"vendor-model","api":"chat"}
            ]}
            """.trimIndent()
        )
        val catalog = JSONObject(
            """
            {"data":[
              {"id":"vendor/model","api":"chat","context_window":128000},
              {"id":"vendor/text-embedding","api":"embedding"},
              {"id":"vendor-model","api":"chat"}
            ]}
            """.trimIndent()
        )

        val models = ModelListParsers.requesty(managed, catalog, "Requesty")

        assertEquals(listOf("vendor-model", "vendor-model@eu", "vendor/model"), models.map { it.id })
        assertEquals(listOf(true, true, false), models.map { it.isRecommended })
        assertEquals(200000, models[1].contextWindow)
        assertEquals(128000, models[2].contextWindow)
    }

    @Test
    fun `requesty still lists the catalog when managed policies are unavailable`() {
        val catalog = JSONObject("""{"data":[{"id":"vendor/model","api":"chat"}]}""")

        assertEquals(
            listOf("vendor/model"),
            ModelListParsers.requesty(null, catalog, "Requesty").map { it.id }
        )
    }

    @Test
    fun `requesty skips entries whose api is missing or not a string`() {
        val catalog = JSONObject(
            """
            {"data":[
              {"id":"vendor/model","api":"chat"},
              {"id":"vendor/no-api"},
              {"id":"vendor/null-api","api":null},
              {"id":"vendor/number-api","api":1}
            ]}
            """.trimIndent()
        )

        assertEquals(
            listOf("vendor/model"),
            ModelListParsers.requesty(null, catalog, "Requesty").map { it.id }
        )
    }

    @Test
    fun `ollama lists whatever the server has installed`() {
        val json = JSONObject("""{"models":[{"name":"llama9:latest"},{"name":"mistral:7b"}]}""")

        assertEquals(
            listOf("llama9:latest", "mistral:7b"),
            ModelListParsers.ollama(json, "Ollama").map { it.id }
        )
    }

    @Test
    fun `an unexpected response shape yields no models rather than throwing`() {
        val json = JSONObject("""{"unexpected":true}""")

        assertTrue(ModelListParsers.openAiStyle(json, "OpenAI").isEmpty())
        assertTrue(ModelListParsers.gemini(json, "Google Gemini").isEmpty())
        assertTrue(ModelListParsers.anthropic(json, "Anthropic Claude").isEmpty())
        assertTrue(ModelListParsers.cohere(json, "Cohere").isEmpty())
        assertTrue(ModelListParsers.openRouter(json, "OpenRouter").isEmpty())
        assertTrue(ModelListParsers.requesty(json, json, "Requesty").isEmpty())
        assertTrue(ModelListParsers.ollama(json, "Ollama").isEmpty())
    }
}
