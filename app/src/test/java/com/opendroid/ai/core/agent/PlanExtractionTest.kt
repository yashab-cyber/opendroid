package com.opendroid.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class PlanExtractionTest {

    @Test
    fun `extractJsonPayload extracts direct json`() {
        val input = """{"goal": "turn on flashlight", "steps": []}"""
        val result = extractJsonPayload(input)
        assertEquals(input, result)
    }

    @Test
    fun `extractJsonPayload extracts json from markdown code fence`() {
        val input = """
            ```json
            {"goal": "turn on flashlight", "steps": []}
            ```
        """.trimIndent()
        val expected = """{"goal": "turn on flashlight", "steps": []}"""
        assertEquals(expected, extractJsonPayload(input))
    }

    @Test
    fun `extractJsonPayload extracts json from conversational preamble and postamble`() {
        val input = """
            Sure, here is the plan to turn on your flashlight:
            ```json
            {
              "goal": "turn on flashlight",
              "steps": [
                {
                  "action": "TOGGLE_FLASHLIGHT",
                  "params": {"state": "on"}
                }
              ]
            }
            ```
            Let me know if you need anything else!
        """.trimIndent()

        val expected = """{
  "goal": "turn on flashlight",
  "steps": [
    {
      "action": "TOGGLE_FLASHLIGHT",
      "params": {"state": "on"}
    }
  ]
}"""
        assertEquals(expected, extractJsonPayload(input))
    }

    @Test
    fun `extractJsonPayload extracts bare json without code fences in conversational text`() {
        val input = """
            I created this plan for you:
            {"goal": "open whatsapp", "steps": []}
            Enjoy!
        """.trimIndent()

        val expected = """{"goal": "open whatsapp", "steps": []}"""
        assertEquals(expected, extractJsonPayload(input))
    }

    @Test
    fun `stripMarkdownFences strips fences properly`() {
        val input = "```json\n{\"test\": 1}\n```"
        assertEquals("{\"test\": 1}", stripMarkdownFences(input))
    }
}
