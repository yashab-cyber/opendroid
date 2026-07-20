package com.opendroid.ai.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DurationParserTest {

    @Test
    fun `parses plain seconds`() {
        assertEquals(5, DurationParser.parseToSeconds("5"))
        assertEquals(60, DurationParser.parseToSeconds("60"))
    }

    @Test
    fun `parses second units`() {
        assertEquals(5, DurationParser.parseToSeconds("5s"))
        assertEquals(5, DurationParser.parseToSeconds("5 seconds"))
        assertEquals(30, DurationParser.parseToSeconds("30 sec"))
    }

    @Test
    fun `parses minute units`() {
        assertEquals(300, DurationParser.parseToSeconds("5 minutes"))
        assertEquals(120, DurationParser.parseToSeconds("2 minute"))
        assertEquals(60, DurationParser.parseToSeconds("1 min"))
    }

    @Test
    fun `parses hour units`() {
        assertEquals(3600, DurationParser.parseToSeconds("1 hour"))
        assertEquals(7200, DurationParser.parseToSeconds("2 hours"))
    }

    @Test
    fun `returns null for empty input`() {
        assertNull(DurationParser.parseToSeconds(""))
        assertNull(DurationParser.parseToSeconds("   "))
    }
}
