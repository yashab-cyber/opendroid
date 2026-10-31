package com.opendroid.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The picked contact is messaged straight away and remembered for the next "text Chris",
 * so an answer may only pick a contact when it is unambiguous (#102).
 */
class ContactChoiceTest {

    private val chrises = listOf("Chris Evans", "Christopher Lee")

    @Test
    fun `stop cancels instead of picking the name that contains it`() {
        assertEquals(ContactChoice.Cancelled, ContactChoice.parse("stop", chrises))
    }

    @Test
    fun `no cancels instead of picking the name that contains it`() {
        assertEquals(ContactChoice.Cancelled, ContactChoice.parse("no", listOf("Johnny Noble", "Jane Doe")))
    }

    @Test
    fun `cancel words match the whole answer whatever the case and trailing punctuation`() {
        val answers = listOf("Stop!", "NO.", "cancel", "Never mind.", "nevermind", "nope", "don't", "Don’t", "abort")
        for (answer in answers) {
            assertEquals(answer, ContactChoice.Cancelled, ContactChoice.parse(answer, chrises))
        }
    }

    @Test
    fun `a name that merely contains a cancel word can still be picked`() {
        assertEquals(ContactChoice.Picked(0), ContactChoice.parse("Noble", listOf("Johnny Noble", "Jane Doe")))
    }

    @Test
    fun `a number picks that position`() {
        assertEquals(ContactChoice.Picked(1), ContactChoice.parse("2", chrises))
    }

    @Test
    fun `a position outside the list picks nobody`() {
        assertEquals(ContactChoice.NotUnderstood, ContactChoice.parse("5", chrises))
        assertEquals(ContactChoice.NotUnderstood, ContactChoice.parse("0", chrises))
        assertEquals(ContactChoice.NotUnderstood, ContactChoice.parse("-1", chrises))
        assertEquals(ContactChoice.NotUnderstood, ContactChoice.parse("third", chrises))
    }

    @Test
    fun `an ordinal picks that position`() {
        assertEquals(ContactChoice.Picked(1), ContactChoice.parse("the second one", chrises))
        assertEquals(ContactChoice.Picked(0), ContactChoice.parse("First", chrises))
    }

    @Test
    fun `a short positional phrase picks that position`() {
        assertEquals(ContactChoice.Picked(1), ContactChoice.parse("number 2", chrises))
        assertEquals(ContactChoice.Picked(1), ContactChoice.parse("2nd", chrises))
        assertEquals(ContactChoice.Picked(0), ContactChoice.parse("the first one.", chrises))
    }

    @Test
    fun `an ordinal only counts as a whole word`() {
        assertEquals(ContactChoice.NotUnderstood, ContactChoice.parse("firstly", chrises))
    }

    @Test
    fun `an ordinal mixed with other words picks nobody`() {
        assertEquals(ContactChoice.NotUnderstood, ContactChoice.parse("not the first one", chrises))
    }

    @Test
    fun `a full name picks that contact`() {
        assertEquals(ContactChoice.Picked(0), ContactChoice.parse("Chris Evans", chrises))
        assertEquals(ContactChoice.Picked(1), ContactChoice.parse("  christopher lee. ", chrises))
    }

    @Test
    fun `a full name wins even when it starts another candidate's name`() {
        val names = listOf("Chris Evans", "Chris Evanson")
        assertEquals(ContactChoice.Picked(0), ContactChoice.parse("Chris Evans", names))
    }

    @Test
    fun `a fragment that fits several candidates picks nobody`() {
        assertEquals(ContactChoice.NotUnderstood, ContactChoice.parse("chris", chrises))
    }

    @Test
    fun `a whole word unique to one candidate picks it`() {
        assertEquals(ContactChoice.Picked(1), ContactChoice.parse("Lee", chrises))
        assertEquals(ContactChoice.Picked(1), ContactChoice.parse("Christopher", chrises))
    }

    @Test
    fun `part of a word picks nobody`() {
        assertEquals(ContactChoice.NotUnderstood, ContactChoice.parse("ev", chrises))
    }

    @Test
    fun `a blank answer picks nobody`() {
        assertEquals(ContactChoice.NotUnderstood, ContactChoice.parse("  ", chrises))
    }
}
