package com.opendroid.ai.core.memory

/**
 * Which SEMANTIC rows [MemoryManager.cleanPoisonedMemories] removes at startup: facts that
 * earlier versions extracted from agent error text ("SEND_SMS is not registered ...").
 *
 * Only rows under [MemoryExtractor]'s own keys are judged. Knowledge-graph memories, notes,
 * screen notes, contact picks and facts typed on the Memory screen are SEMANTIC rows too,
 * and a phrase match there is the user's own text ("Car is not registered until March"),
 * so they are never deleted (#108).
 */
internal object PoisonedMemories {

    /**
     * Every key [MemoryExtractor.extractFacts] writes. A key added there and not here is
     * simply never cleaned, which is the safe direction.
     */
    private val extractedKeys = setOf(
        "user_name",
        "wife_name",
        "husband_name",
        "user_location",
        "office_address",
        "home_address",
        "user_phone"
    )

    private val poisonPhrases = listOf(
        "invalid_action",
        "not registered",
        "actiondispatcher",
        "do not use this action",
        "whitelisted",
        "execution error",
        "action module",
        "unknown action",
        "not a valid action"
    )

    fun isPoisoned(key: String, value: String): Boolean =
        key in extractedKeys && poisonPhrases.any { phrase -> value.contains(phrase, ignoreCase = true) }
}
