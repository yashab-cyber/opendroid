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
    fun `parses compact adjacent units`() {
        assertEquals(5400, DurationParser.parseToSeconds("1h30m"))
        assertEquals(90, DurationParser.parseToSeconds("1m30s"))
    }

    @Test
    fun `parses decimal amounts instead of only the digits after the dot`() {
        // "1.5 hours" used to match only "5 hours" and start a 5-hour timer (#78).
        assertEquals(5400, DurationParser.parseToSeconds("1.5 hours"))
        assertEquals(5400, DurationParser.parseToSeconds("1.5h"))
        assertEquals(150, DurationParser.parseToSeconds("2.5 minutes"))
        assertEquals(30, DurationParser.parseToSeconds("0.5 min"))
    }

    @Test
    fun `rounds fractional seconds to the nearest second`() {
        assertEquals(180, DurationParser.parseToSeconds("2m59.56s"))
        assertEquals(90, DurationParser.parseToSeconds("1m30.4s"))
    }

    @Test
    fun `bare digit fallback ignores embedded numbers`() {
        assertEquals(5, DurationParser.parseToSeconds("5"))
        assertNull(DurationParser.parseToSeconds("at 2pm yesterday"))
    }

    @Test
    fun `returns null for empty input`() {
        assertNull(DurationParser.parseToSeconds(""))
        assertNull(DurationParser.parseToSeconds("   "))
    }
}
