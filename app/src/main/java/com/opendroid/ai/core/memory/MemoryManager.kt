package com.opendroid.ai.core.memory

import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.Memory
import com.opendroid.ai.data.models.MemoryType
import com.opendroid.ai.data.repository.ConversationRepository
import com.opendroid.ai.data.repository.MemoryRepository
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryManager @Inject constructor(
    val workingMemory: WorkingMemory,
    val episodicMemory: EpisodicMemory,
    val semanticMemory: SemanticMemory,
    val proceduralMemory: ProceduralMemory,
    private val memoryExtractor: MemoryExtractor,
    private val conversationRepository: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    @ApplicationContext private val context: Context
) {
    private val json = Json { prettyPrint = true }

    suspend fun storeMessage(message: ChatMessage) {
        workingMemory.addMessage(message)
        episodicMemory.storeMessage(message)

        // Automatically extract facts from the latest conversation turn
        val recent = conversationRepository.getLastMessages(5)
        extractFacts(recent)
    }

    suspend fun extractFacts(conversation: List<ChatMessage>) {
        memoryExtractor.extractFacts(conversation)
    }

    suspend fun recall(query: String): List<Memory> {
        return searchMemory(query)
    }

    suspend fun getRelevantContext(currentGoal: String): String {
        // Collect facts from semantic database — only valid (non-expired, non-poisoned) entries
        val facts = memoryRepository.getValidMemoriesByType(MemoryType.SEMANTIC)
        val dbFacts = facts
            .filter { memoryExtractor.shouldStoreInSemanticMemory(it.key) && memoryExtractor.shouldStoreInSemanticMemory(it.value) }
            .joinToString("; ") { "${it.key}: ${it.value}" }

        // Read user info from SharedPreferences
        val sharedPrefs = context.getSharedPreferences("opendroid_prefs", Context.MODE_PRIVATE)
        val userName = sharedPrefs.getString("user_name", "") ?: ""
        val userDob = sharedPrefs.getString("user_dob", "") ?: ""

        val userFactsList = mutableListOf<String>()
        if (userName.isNotEmpty()) {
            userFactsList.add("User Name: $userName")
        }
        if (userDob.isNotEmpty()) {
            userFactsList.add("User Date of Birth (DOB): $userDob")
        }
        if (dbFacts.isNotEmpty()) {
            userFactsList.add(dbFacts)
        }
        val factsContext = userFactsList.joinToString("; ")
        
        // Context from working memory
        val activePlanStr = workingMemory.activePlan?.let { "Active Plan Goal: ${it.goal}" } ?: "No active plan."
        
        return """
            [Facts about User]
            $factsContext
            
            [Working Session State]
            $activePlanStr
            Device State: Location=${workingMemory.location}, Battery=${workingMemory.batteryLevel}%, WiFi=${workingMemory.wifiState}, Connection=${workingMemory.connectivity}
        """.trimIndent()
    }

    suspend fun summarizeOldConversations() {
        // Compress old logs if message count > 50
        val history = conversationRepository.getLastMessages(60)
        if (history.size >= 50) {
            val textToCompress = history.joinToString("\n") { "${it.sender.name}: ${it.text}" }
            // Add a compiled summary fact
            semanticMemory.storeFact(
                "conversation_summary_${System.currentTimeMillis()}",
                "Recent dialogue summary compiled: ${textToCompress.take(200)}..."
            )
            // Optional: prune old messages from db if needed to save space
        }
    }

    suspend fun exportMemory(): String {
        val memories = memoryRepository.allMemories.first()
        return json.encodeToString(memories)
    }

    suspend fun clearMemory(type: MemoryType) {
        memoryRepository.clearMemoryByType(type)
        if (type == MemoryType.WORKING) {
            workingMemory.clear()
        }
        if (type == MemoryType.EPISODIC) {
            conversationRepository.clearAll()
        }
        if (type == MemoryType.PROCEDURAL) {
            memoryRepository.clearAllMacros()
        }
    }

    suspend fun searchMemory(query: String): List<Memory> {
        val all = memoryRepository.allMemories.first()
        return all.filter {
            it.key.contains(query, ignoreCase = true) || it.value.contains(query, ignoreCase = true)
        }
    }

    suspend fun cleanPoisonedMemories() {
        val poisonPhrases = listOf(
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

        val allFacts = memoryRepository.getMemoriesByType(MemoryType.SEMANTIC)
        val poisoned = allFacts.filter { fact ->
            poisonPhrases.any { phrase ->
                fact.key.contains(phrase, ignoreCase = true) ||
                fact.value.contains(phrase, ignoreCase = true)
            }
        }

        if (poisoned.isNotEmpty()) {
            poisoned.forEach { memoryRepository.deleteMemory(it.key) }
            Log.d("MemoryCleanup", "Removed ${poisoned.size} poisoned memory entries")
        }

        // Also clean expired memories
        memoryRepository.deleteExpiredMemories()
    }
}
