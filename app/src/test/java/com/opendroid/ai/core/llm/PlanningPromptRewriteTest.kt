package com.opendroid.ai.core.llm

import com.opendroid.ai.core.llm.prompts.PlanningPrompts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On-device models get their own compact planning prompt, sized for a 1280-token window.
 * Swapping it for the full cloud prompt overflowed that window, and LiteRT cut the result
 * off mid-schema, before the JSON format and the rules (#113).
 */
class PlanningPromptRewriteTest {

    @Test
    fun `the on-device planning prompt is kept for on-device providers`() {
        val onDevicePrompt = PlanningPrompts.buildOnDevicePlanningPrompt()

        assertFalse(PlanningPromptRewrite.applies(ProviderCatalog.ON_DEVICE, onDevicePrompt))
        assertFalse(PlanningPromptRewrite.applies("LiteRT-LM (On-device)", onDevicePrompt))
        assertFalse(PlanningPromptRewrite.applies(ProviderCatalog.LEGACY_ON_DEVICE, onDevicePrompt))
    }

    @Test
    fun `a cloud planning request still gets the full prompt`() {
        assertTrue(PlanningPromptRewrite.applies("OpenAI", PlanningPrompts.PLANNING_SYSTEM_PROMPT))
        assertTrue(PlanningPromptRewrite.applies("Google Gemini", "AVAILABLE ACTIONS:\n- OPEN_APP"))
    }

    @Test
    fun `a cloud fallback for an on-device plan gets the full prompt`() {
        assertTrue(PlanningPromptRewrite.applies("Anthropic Claude", PlanningPrompts.buildOnDevicePlanningPrompt()))
    }

    @Test
    fun `an ordinary chat prompt is never rewritten`() {
        assertFalse(PlanningPromptRewrite.applies("OpenAI", "You are OpenDroid, a helpful assistant."))
        assertFalse(PlanningPromptRewrite.applies(ProviderCatalog.ON_DEVICE, "You are OpenDroid, a helpful assistant."))
    }
}
