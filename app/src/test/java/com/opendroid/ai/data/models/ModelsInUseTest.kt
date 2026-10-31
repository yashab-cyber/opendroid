package com.opendroid.ai.data.models

import com.opendroid.ai.core.llm.ProviderCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What "Delete Unused Models" keeps (#115). [LLMConfig.activeModel] is the active
 * provider's model, a cloud model ID whenever a cloud provider is active, so keeping only
 * that one deleted the model selected for on-device use.
 */
class ModelsInUseTest {

    private val qwen = "qwen-2.5-0.5b-it-litert"
    private val gemmaE2b = "gemma-4-e2b-it-litert"

    @Test
    fun `the on-device selection is kept while a cloud provider is active`() {
        val config = LLMConfig(activeProvider = "Google Gemini")
            .withSelectedModel(ProviderCatalog.ON_DEVICE, gemmaE2b)

        assertTrue(gemmaE2b in config.modelsInUse())
    }

    @Test
    fun `the direct LiteRT selection is kept while a cloud provider is active`() {
        val config = LLMConfig(activeProvider = "OpenAI", activeModel = "gpt-4o")
            .withSelectedModel("LiteRT-LM (On-device)", qwen)

        assertTrue(qwen in config.modelsInUse())
    }

    @Test
    fun `a pre-migration config with on-device active keeps its model`() {
        val config = LLMConfig(
            activeProvider = ProviderCatalog.ON_DEVICE,
            activeModel = gemmaE2b,
            selectedModels = null
        )

        assertTrue(gemmaE2b in config.modelsInUse())
    }

    @Test
    fun `a model nothing selects is not kept`() {
        val config = LLMConfig(activeProvider = "Google Gemini")
            .withSelectedModel(ProviderCatalog.ON_DEVICE, gemmaE2b)

        assertFalse("gemma-3n-e4b-it-litert" in config.modelsInUse())
    }
}
