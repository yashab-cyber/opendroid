package com.opendroid.ai.core.util

import kotlin.math.roundToInt

/**
 * Parses natural-language and numeric duration strings into seconds.
 * Examples: "5", "5s", "5 seconds", "2 minute", "1 hour", "5 minutes 30 seconds", "1.5 hours"
 */
object DurationParser {

    fun parseToSeconds(input: String): Int? {
        val normalized = input.trim().lowercase()
        if (normalized.isEmpty()) return null

        normalized.toIntOrNull()?.let { return it.coerceAtLeast(1) }

        var totalSeconds = 0.0
        var matched = false

        // Allow compact forms like "1h30m" by treating another digit as a valid unit boundary.
        // Amounts may be decimal ("1.5 hours"): a whole-number-only pattern would skip the
        // "1." and read "5 hours".
        val hourPattern = Regex("""(\d+(?:\.\d+)?)\s*(?:hours|hour|hrs|hr|h)(?=\d|\b)""")
        val minutePattern = Regex("""(\d+(?:\.\d+)?)\s*(?:minutes|minute|mins|min|m)(?=\d|\b)""")
        val secondPattern = Regex("""(\d+(?:\.\d+)?)\s*(?:seconds|second|secs|sec|s)(?=\d|\b)""")

        hourPattern.findAll(normalized).forEach {
            totalSeconds += it.groupValues[1].toDouble() * 3600
            matched = true
        }
        minutePattern.findAll(normalized).forEach {
            totalSeconds += it.groupValues[1].toDouble() * 60
            matched = true
        }
        secondPattern.findAll(normalized).forEach {
            totalSeconds += it.groupValues[1].toDouble()
            matched = true
        }

        if (matched) return totalSeconds.roundToInt().coerceAtLeast(1)

        // Only accept a bare number — avoid hijacking unrelated digits (e.g. "2pm").
        Regex("""^(\d+)$""").find(normalized)?.let {
            return it.groupValues[1].toIntOrNull()?.coerceAtLeast(1)
        }

        return null
    }
}
