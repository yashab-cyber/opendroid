package com.opendroid.ai.actions.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ActionResult] — the sealed result contract every action
 * returns, plus the legacy `invoke(success, data, error)` companion factory
 * still used by call sites like AgentLoop.
 *
 * Pure logic, no Android dependencies.
 */
class ActionResultTest {

    // ── Success ─────────────────────────────────────────────────────────

    @Test
    fun `success exposes the message entry as data`() {
        val result = ActionResult.Success(mapOf("message" to "Done"))
        assertTrue(result.success)
        assertEquals("Done", result.data)
        assertNull(result.error)
    }

    @Test
    fun `success without a message key falls back to the map string`() {
        val result = ActionResult.Success(mapOf("foo" to "bar"))
        assertEquals(mapOf("foo" to "bar").toString(), result.data)
    }

    @Test
    fun `empty success has null data`() {
        val result = ActionResult.Success(emptyMap())
        assertTrue(result.success)
        assertNull(result.data)
    }

    // ── Failure ─────────────────────────────────────────────────────────

    @Test
    fun `failure exposes the error message`() {
        val result = ActionResult.Failure("boom")
        assertFalse(result.success)
        assertNull(result.data)
        assertEquals("boom", result.error)
    }

    @Test
    fun `failure carries an optional fallback hint`() {
        val result = ActionResult.Failure("boom", "try WiFi")
        assertEquals("try WiFi", result.fallback)
    }

    // ── UnknownAction / NeedsInput ──────────────────────────────────────

    @Test
    fun `unknown action describes the attempted action in its error`() {
        val result = ActionResult.UnknownAction("FOO", listOf("OPEN_APP"))
        assertFalse(result.success)
        assertEquals("Action 'FOO' is not registered in ActionDispatcher", result.error)
    }

    @Test
    fun `needs input surfaces the question in its error`() {
        val result = ActionResult.NeedsInput("Pick one", listOf("a", "b"))
        assertFalse(result.success)
        assertEquals("Needs user input: Pick one", result.error)
    }

    // ── Companion invoke factory ────────────────────────────────────────

    @Test
    fun `invoke with success wraps data into a Success`() {
        val result = ActionResult(success = true, data = "Saved")
        assertTrue(result is ActionResult.Success)
        assertTrue(result.success)
        assertEquals("Saved", result.data)
    }

    @Test
    fun `invoke with success and null data yields empty Success`() {
        val result = ActionResult(success = true, data = null)
        assertTrue(result is ActionResult.Success)
        assertNull(result.data)
    }

    @Test
    fun `invoke with failure maps error through`() {
        val result = ActionResult(success = false, data = null, error = "nope")
        assertTrue(result is ActionResult.Failure)
        assertEquals("nope", result.error)
    }

    @Test
    fun `invoke failure with fallbackExecuted routes data into the fallback`() {
        val result = ActionResult(false, "fallback ran", "primary failed", fallbackExecuted = true)
        assertTrue(result is ActionResult.Failure)
        assertEquals("primary failed", result.error)
        assertEquals("fallback ran", (result as ActionResult.Failure).fallback)
    }

    @Test
    fun `invoke failure with no message defaults the error text`() {
        val result = ActionResult(success = false, data = null, error = null)
        assertEquals("Unknown error", result.error)
    }
}
