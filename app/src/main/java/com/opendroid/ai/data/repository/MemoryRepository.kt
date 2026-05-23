package com.opendroid.ai.data.repository

import com.opendroid.ai.data.db.dao.MemoryDao
import com.opendroid.ai.data.db.dao.TaskHistoryDao
import com.opendroid.ai.data.db.dao.MacroDao
import com.opendroid.ai.data.db.entities.MemoryEntity
import com.opendroid.ai.data.db.entities.TaskHistoryEntity
import com.opendroid.ai.data.db.entities.MacroEntity
import com.opendroid.ai.data.models.Memory
import com.opendroid.ai.data.models.MemoryType
import com.opendroid.ai.data.models.Macro
import com.opendroid.ai.data.models.PlanStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryRepository @Inject constructor(
    private val memoryDao: MemoryDao,
    private val taskHistoryDao: TaskHistoryDao,
    private val macroDao: MacroDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Memory operations
    val allMemories: Flow<List<Memory>> = memoryDao.getAllMemoriesFlow().map { entities ->
        entities.map { entity ->
            Memory(
                key = entity.key,
                value = entity.value,
                type = MemoryType.valueOf(entity.type),
                timestamp = entity.timestamp
            )
        }
    }

    suspend fun getMemoriesByType(type: MemoryType): List<Memory> {
        return memoryDao.getMemoriesByType(type.name).map { entity ->
            Memory(
                key = entity.key,
                value = entity.value,
                type = MemoryType.valueOf(entity.type),
                timestamp = entity.timestamp
            )
        }
    }

    suspend fun getValidMemoriesByType(type: MemoryType): List<Memory> {
        return memoryDao.getValidMemoriesByType(type.name).map { entity ->
            Memory(
                key = entity.key,
                value = entity.value,
                type = MemoryType.valueOf(entity.type),
                timestamp = entity.timestamp
            )
        }
    }

    suspend fun saveMemory(memory: Memory) {
        memoryDao.insertOrUpdateMemory(
            MemoryEntity(
                key = memory.key,
                value = memory.value,
                type = memory.type.name,
                timestamp = memory.timestamp
            )
        )
    }

    suspend fun deleteMemory(key: String) {
        memoryDao.deleteMemory(key)
    }

    suspend fun clearMemoryByType(type: MemoryType) {
        memoryDao.clearMemoryByType(type.name)
    }

    suspend fun deleteExpiredMemories() {
        memoryDao.deleteExpiredMemories()
    }

    // Task History operations
    val taskHistory: Flow<List<TaskHistoryEntity>> = taskHistoryDao.getTaskHistoryFlow()

    suspend fun logTaskExecution(
        stepId: String,
        planId: String,
        description: String,
        actionType: String,
        params: Map<String, String>,
        success: Boolean,
        resultData: String?,
        errorMessage: String?
    ) {
        val paramsJson = json.encodeToString(params)
        taskHistoryDao.insertHistory(
            TaskHistoryEntity(
                stepId = stepId,
                planId = planId,
                description = description,
                actionType = actionType,
                paramsJson = paramsJson,
                success = success,
                resultData = resultData,
                errorMessage = errorMessage
            )
        )
    }

    // Macro operations
    val allMacros: Flow<List<Macro>> = macroDao.getAllMacrosFlow().map { entities ->
        entities.map { entity -> mapEntityToMacro(entity) }
    }

    suspend fun getMacroByName(name: String): Macro? {
        val entity = macroDao.getMacroByName(name) ?: return null
        return mapEntityToMacro(entity)
    }

    suspend fun saveMacro(macro: Macro) {
        val stepsJson = json.encodeToString(macro.steps)
        macroDao.insertMacro(
            MacroEntity(
                id = macro.id,
                name = macro.name,
                trigger = macro.trigger,
                stepsJson = stepsJson,
                isSystem = macro.isSystem,
                isEnabled = macro.isEnabled
            )
        )
    }

    suspend fun deleteMacro(id: String) {
        macroDao.deleteMacro(id)
    }

    suspend fun clearAllMacros() {
        macroDao.clearAllMacros()
    }

    private fun mapEntityToMacro(entity: MacroEntity): Macro {
        val steps = try {
            json.decodeFromString<List<PlanStep>>(entity.stepsJson)
        } catch (e: Exception) {
            emptyList()
        }
        return Macro(
            id = entity.id,
            name = entity.name,
            trigger = entity.trigger,
            steps = steps,
            isSystem = entity.isSystem,
            isEnabled = entity.isEnabled
        )
    }
}
