package com.opendroid.ai.core.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The startup cleanup runs on every launch over every SEMANTIC row (#108). Knowledge-graph
 * memories, notes, screen notes, contact picks and facts typed on the Memory screen are all
 * SEMANTIC too, so the cleanup may only judge rows the automatic fact extraction wrote.
 */
class PoisonedMemoriesTest {

    @Test
    fun `an extracted fact holding agent error text is removed`() {
        assertTrue(PoisonedMemories.isPoisoned("user_name", "SEND_SMS is not registered in ActionDispatcher"))
        assertTrue(PoisonedMemories.isPoisoned("user_location", "Unknown action: NAVIGATE_TO"))
    }

    @Test
    fun `an ordinary extracted fact is kept`() {
        assertFalse(PoisonedMemories.isPoisoned("user_location", "Berlin"))
    }

    @Test
    fun `a remembered knowledge-graph fact that mentions not registered is kept`() {
        assertFalse(
            PoisonedMemories.isPoisoned(
                "pkg_node_explicit_car_1",
                """{"id":"explicit_car_1","label":"Car","summary":"Car is not registered until March"}"""
            )
        )
    }

    @Test
    fun `a fact typed on the Memory screen is kept`() {
        assertFalse(PoisonedMemories.isPoisoned("work_email", "My email is whitelisted at work"))
    }

    @Test
    fun `a saved note or screen note that quotes an error is kept`() {
        assertFalse(PoisonedMemories.isPoisoned("note_Phone_1700000000000", "Title: Phone\nDevice not registered with carrier"))
        assertFalse(PoisonedMemories.isPoisoned("screen_note_Bank_1700000000000", "Topic: Bank\n\nExecution error 42, call support"))
    }

    @Test
    fun `a remembered contact choice is kept`() {
        assertFalse(PoisonedMemories.isPoisoned("contact_pref_unknown action guy", "Sam||+15550100"))
    }
}
