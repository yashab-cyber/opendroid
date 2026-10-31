package com.opendroid.ai.core.memory

/**
 * Which SEMANTIC rows [MemoryManager.cleanPoisonedMemories] removes at startup: facts that
 * earlier versions extracted from agent error text ("SEND_SMS is not registered ...").
 */
internal object PoisonedMemories {

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
        poisonPhrases.any { phrase ->
            key.contains(phrase, ignoreCase = true) || value.contains(phrase, ignoreCase = true)
        }
}
