package com.opendroid.ai.core.agent

/**
 * The user's answer to the contact picker ("Which 'Chris' do you mean?"), read
 * against the candidate names in the order they were offered. The picked contact
 * is messaged straight away and remembered for the query, so anything short of a
 * clear pick is [NotUnderstood], never a best guess (#102).
 *
 * The answer is compared word by word, ignoring case and punctuation. In order:
 * 1. Cancel: the whole answer is no, nope, stop, cancel, abort, don't or never mind.
 *    This wins over a name like "Johnny Noble"; not sending is the safe side.
 * 2. Position: dropping "the", "one", "number", "option" and "please" leaves one
 *    index ("2") or ordinal ("second", "2nd"). Out of range picks nobody.
 * 3. Full name: the answer is one candidate's whole name.
 * 4. Fragment: the answer starts a word in exactly one candidate's name, and is
 *    whole words there. With "Chris Evans" and "Christopher Lee", "Lee" picks the
 *    second; "chris" (it also starts "Christopher") and "ev" pick nobody.
 */
sealed interface ContactChoice {
    /** [index] is 0-based into the candidate names. */
    data class Picked(val index: Int) : ContactChoice
    data object Cancelled : ContactChoice
    data object NotUnderstood : ContactChoice

    companion object {
        private val CANCEL_ANSWERS = setOf(
            "no", "nope", "stop", "cancel", "abort", "don't", "dont", "never mind", "nevermind"
        )
        private val POSITION_FILLER = setOf("the", "one", "number", "option", "please")
        private val ORDINALS = mapOf(
            "first" to 0, "second" to 1, "third" to 2, "fourth" to 3, "fifth" to 4,
            "1st" to 0, "2nd" to 1, "3rd" to 2, "4th" to 3, "5th" to 4
        )
        // Keeps a minus sign before a digit, so "-1" is not read as "1".
        private val NON_WORD = Regex("""(?:[^\p{L}\p{M}\p{N}'-]|-(?!\p{N}))+""")

        fun parse(answer: String, candidateNames: List<String>): ContactChoice {
            val answerWords = words(answer)
            if (answerWords.isEmpty()) return NotUnderstood
            if (answerWords.joinToString(" ") in CANCEL_ANSWERS) return Cancelled

            val index = position(answerWords) ?: byName(padded(answer), candidateNames.map(::padded))
            return if (index != null && index in candidateNames.indices) Picked(index) else NotUnderstood
        }

        private fun position(words: List<String>): Int? {
            val word = words.filterNot { it in POSITION_FILLER }.singleOrNull() ?: return null
            return ORDINALS[word] ?: word.toIntOrNull()?.minus(1)
        }

        /** [answer] and [names] are [padded], so " lee " only matches whole words. */
        private fun byName(answer: String, names: List<String>): Int? {
            names.indices.singleOrNull { names[it] == answer }?.let { return it }
            val startingAWord = answer.trimEnd()
            val candidate = names.indices.singleOrNull { startingAWord in names[it] } ?: return null
            return candidate.takeIf { answer in names[candidate] }
        }

        private fun words(text: String): List<String> =
            text.lowercase()
                .replace('\u2019', '\'') // curly apostrophe, as phone keyboards type it
                .split(NON_WORD)
                .map { it.trim('\'') }
                .filter { it.isNotEmpty() }

        private fun padded(text: String): String = words(text).joinToString(" ", prefix = " ", postfix = " ")
    }
}
