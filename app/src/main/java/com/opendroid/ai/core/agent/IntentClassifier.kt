package com.opendroid.ai.core.agent

import com.opendroid.ai.core.llm.LLMProviderFactory
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ResponseFormat
import com.opendroid.ai.data.models.ChatMessage
import java.util.UUID
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

enum class QueryComplexity {
    SIMPLE, MEDIUM, COMPLEX
}

@Singleton
class IntentClassifier @Inject constructor(
    private val llmProviderFactory: dagger.Lazy<LLMProviderFactory>
) {
    suspend fun requiresAction(query: String): Boolean {
        // Broad local heuristic check for action words
        val actionKeywords = listOf(
            "open", "launch", "start", "turn", "toggle", "enable", "disable", "set", "lock", "restart",
            "take", "record", "send", "make", "play", "pause", "resume", "next", "prev", "order", "search",
            "pay", "check", "split", "run", "create", "schedule", "list", "read", "write", "delete", "click",
            "type", "scroll", "get", "show", "whatsapp", "call", "sms", "email", "alarm", "timer", "reminder",
            "note", "notes", "calendar", "weather", "news", "flashlight", "flash", "wifi", "bluetooth",
            "brightness", "volume", "screenshot", "dnd", "mute", "unmute"
        )
        val hasActionKeyword = actionKeywords.any { query.contains(it, ignoreCase = true) }

        return try {
            val provider = llmProviderFactory.get().getActiveProvider()
            val prompt = """
                Classify the user's intent: "$query".
                Does this request require executing one or more device/app actions (e.g. opening an app, toggling a setting like flashlight/wifi/bluetooth, setting volume/brightness, sending a message/email, making a call, setting an alarm/timer/reminder, playing music, booking a ride, checking weather/news, paying via UPI, etc.)?
                Return strictly "ACTION" if it requires executing an action, or "CONVERSATIONAL" if it is a general chat, question, or statement that can be answered directly with a conversational text response.
            """.trimIndent()

            val response = provider.complete(
                LLMRequest(
                    systemPrompt = "You are an intent classification routing helper.",
                    messages = listOf(
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            text = prompt,
                            sender = ChatMessage.Sender.USER
                        )
                    ),
                    temperature = 0.0f,
                    maxTokens = 5,
                    responseFormat = ResponseFormat.TEXT
                )
            )
            val responseText = response.content.uppercase()
            when {
                responseText.contains("ACTION") -> true
                responseText.contains("CONVERSATIONAL") -> false
                else -> hasActionKeyword
            }
        } catch (e: Exception) {
            // Fallback to keyword heuristics if LLM is unreachable or offline
            hasActionKeyword
        }
    }

    fun classifyComplexity(query: String): QueryComplexity {
        val lowercaseQuery = query.lowercase()

        // Check for compound indicators FIRST — these always indicate multi-step tasks
        val compoundIndicators = listOf(
            " and then ", " then ", " after that ", " after ",
            " also ", " plus ", "and send", "and message",
            "and call", "and tell", "and notify", "and text",
            "and book", "and set", "and create", "and open",
            "then send", "then call", "then message",
            "then open", "then set", "then play",
            "and also ", "followed by", "afterwards"
        )

        val isCompound = compoundIndicators.any { lowercaseQuery.contains(it) }

        // Additionally check for " and " with action verbs on both sides
        val andWithActions = hasActionsAroundAnd(lowercaseQuery)

        if (isCompound || andWithActions) {
            // Compound tasks are ALWAYS at least MEDIUM
            // Check if it's complex (3+ actions)
            val compoundCount = compoundIndicators.count { lowercaseQuery.contains(it) }
            return if (compoundCount >= 2) QueryComplexity.COMPLEX else QueryComplexity.MEDIUM
        }

        // ONLY then check for simple patterns using original logic
        val actionTriggers = listOf(
            "open", "launch", "start", "turn", "toggle", "enable", "disable", "set", "lock", "restart",
            "take", "record", "send", "make", "play", "pause", "resume", "next", "prev", "order", "search",
            "pay", "check", "split", "run", "create", "schedule", "list", "read", "write", "delete", "click",
            "type", "scroll", "get", "show", "whatsapp", "call", "sms", "email", "alarm", "timer", "reminder",
            "note", "notes", "calendar", "weather", "news", "flashlight", "flash", "wifi", "bluetooth",
            "brightness", "volume", "screenshot", "dnd", "mute", "unmute"
        )
        
        val sequenceConjunctions = listOf(
            "and then", "then", "after that", "next", "also", "followed by", "afterwards", "later"
        )
        
        // Check for multiple commands separated by punctuation
        val commandSeparators = Pattern.compile("[,.;]")
        val matcher = commandSeparators.matcher(query)
        var separatorCount = 0
        while (matcher.find()) {
            separatorCount++
        }

        val triggerCount = actionTriggers.count { lowercaseQuery.contains(it) }
        val conjunctionCount = sequenceConjunctions.count { lowercaseQuery.contains(it) }
        
        val totalComplexityScore = triggerCount + conjunctionCount + (separatorCount * 0.5)

        return when {
            totalComplexityScore >= 4.0 || lowercaseQuery.contains("loop") || lowercaseQuery.contains("repeat") -> QueryComplexity.COMPLEX
            totalComplexityScore >= 2.0 -> QueryComplexity.MEDIUM
            else -> QueryComplexity.SIMPLE
        }
    }

    private fun hasActionsAroundAnd(query: String): Boolean {
        // Detect patterns like "open X and send Y" where "and" connects two action verbs
        val actionVerbs = listOf(
            "open", "send", "call", "message", "set", "play", "book",
            "create", "make", "turn", "toggle", "take", "record",
            "search", "order", "pay", "check", "read", "write"
        )
        val andIndex = query.indexOf(" and ")
        if (andIndex == -1) return false

        val before = query.substring(0, andIndex)
        val after = query.substring(andIndex + 5)

        val hasActionBefore = actionVerbs.any { before.contains(it) }
        val hasActionAfter = actionVerbs.any { after.contains(it) }

        return hasActionBefore && hasActionAfter
    }
}
