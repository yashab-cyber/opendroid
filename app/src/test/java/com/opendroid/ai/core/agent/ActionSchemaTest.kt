package com.opendroid.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ActionSchema] — the typed action registry used to validate
 * LLM-produced params, inject defaults, and feed the planning prompt.
 *
 * Pure logic, no Android dependencies.
 */
class ActionSchemaTest {

    // ── validateParams ──────────────────────────────────────────────────

    @Test
    fun `valid when required param is present`() {
        val (result, enriched) = ActionSchema.validateParams("OPEN_APP", mapOf("appName" to "Camera"))
        assertTrue(result is ActionSchema.ValidationResult.Valid)
        assertEquals("Camera", enriched["appName"])
    }

    @Test
    fun `missing required param is reported`() {
        val (result, _) = ActionSchema.validateParams("OPEN_APP", emptyMap())
        assertTrue(result is ActionSchema.ValidationResult.MissingParams)
        assertTrue((result as ActionSchema.ValidationResult.MissingParams).params.contains("appName"))
    }

    @Test
    fun `missing optional param with default is auto-applied and stays valid`() {
        val (result, enriched) = ActionSchema.validateParams("TOGGLE_FLASHLIGHT", emptyMap())
        assertTrue(result is ActionSchema.ValidationResult.Valid)
        assertEquals("toggle", enriched["state"])
    }

    @Test
    fun `enum synonym is corrected to a canonical value`() {
        // "enable" is a synonym for "on" in the on/off/toggle enum
        val (result, enriched) = ActionSchema.validateParams("TOGGLE_WIFI", mapOf("state" to "enable"))
        assertTrue(result is ActionSchema.ValidationResult.Valid)
        assertEquals("on", enriched["state"])
    }

    @Test
    fun `unrecognized enum value with no synonym is reported as missing`() {
        val (result, _) = ActionSchema.validateParams("SET_RINGER_MODE", mapOf("mode" to "banana"))
        assertTrue(result is ActionSchema.ValidationResult.MissingParams)
        assertTrue((result as ActionSchema.ValidationResult.MissingParams).params.contains("mode"))
    }

    @Test
    fun `unknown action yields InvalidAction`() {
        val (result, _) = ActionSchema.validateParams("NONEXISTENT_ACTION", emptyMap())
        assertTrue(result is ActionSchema.ValidationResult.InvalidAction)
    }

    // ── applyDefaults ───────────────────────────────────────────────────

    @Test
    fun `applyDefaults fills in missing defaulted params`() {
        val enriched = ActionSchema.applyDefaults("TOGGLE_FLASHLIGHT", emptyMap())
        assertEquals("toggle", enriched["state"])
    }

    @Test
    fun `applyDefaults leaves provided params untouched`() {
        val enriched = ActionSchema.applyDefaults("OPEN_APP", mapOf("appName" to "Spotify"))
        assertEquals("Spotify", enriched["appName"])
    }

    // ── Lookup utilities ────────────────────────────────────────────────

    @Test
    fun `getAction finds known actions and misses unknown ones`() {
        assertNotNull(ActionSchema.getAction("OPEN_APP"))
        assertNull(ActionSchema.getAction("NOPE"))
    }

    @Test
    fun `isValid reflects schema membership`() {
        assertTrue(ActionSchema.isValid("CHAT"))
        assertFalse(ActionSchema.isValid("NOPE"))
    }

    @Test
    fun `getAllActionNames is non-empty and sorted`() {
        val names = ActionSchema.getAllActionNames()
        assertTrue(names.isNotEmpty())
        assertTrue(names.contains("OPEN_APP"))
        assertEquals(names.sorted(), names)
    }

    @Test
    fun `getSimpleActions separates simple from compound actions`() {
        val simple = ActionSchema.getSimpleActions()
        assertTrue(simple.contains("TOGGLE_FLASHLIGHT")) // isSimple = true
        assertFalse(simple.contains("SEND_EMAIL"))       // isSimple = false
    }
}
