package com.opendroid.ai.core.agent

/**
 * The user's answer to the contact picker ("Which 'Chris' do you mean?"), read
 * against the candidate names in the order they were offered.
 */
sealed interface ContactChoice {
    /** [index] is 0-based into the candidate names. */
    data class Picked(val index: Int) : ContactChoice
    data object Cancelled : ContactChoice
    data object NotUnderstood : ContactChoice

    companion object {
        fun parse(answer: String, candidateNames: List<String>): ContactChoice {
            val lower = answer.lowercase()
            val index = when {
                answer.trim().toIntOrNull() != null -> answer.trim().toInt() - 1
                lower.contains("first") -> 0
                lower.contains("second") -> 1
                lower.contains("third") -> 2
                lower.contains("fourth") -> 3
                lower.contains("fifth") -> 4
                else -> candidateNames.indexOfFirst { answer.contains(it, ignoreCase = true) }
                    .takeIf { it >= 0 }
                    ?: candidateNames.indexOfFirst { it.contains(answer.trim(), ignoreCase = true) }
            }
            return if (index in candidateNames.indices) Picked(index) else NotUnderstood
        }
    }
}
