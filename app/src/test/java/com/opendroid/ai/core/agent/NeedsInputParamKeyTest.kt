package com.opendroid.ai.core.agent

import com.opendroid.ai.actions.base.ActionResult
import org.junit.Assert.assertEquals
import org.junit.Test

class NeedsInputParamKeyTest {

    @Test
    fun `metadata param is preferred`() {
        val needsInput = ActionResult.NeedsInput(
            question = "I need the message to complete this.",
            metadata = mapOf("param" to "message")
        )

        assertEquals("message", paramKeyForNeedsInput(needsInput, "SEND_SMS"))
    }

    @Test
    fun `communication number prompt without metadata updates contact`() {
        val needsInput = ActionResult.NeedsInput(
            question = "I couldn't find 'dad'. What's their phone number?"
        )

        assertEquals("contact", paramKeyForNeedsInput(needsInput, "SEND_SMS"))
    }

    @Test
    fun `non communication prompt without metadata falls back to value`() {
        val needsInput = ActionResult.NeedsInput(
            question = "What value should I use?"
        )

        assertEquals("value", paramKeyForNeedsInput(needsInput, "SET_VOLUME"))
    }
}
