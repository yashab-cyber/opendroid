package com.opendroid.ai.data.repository

import com.opendroid.ai.core.memory.PlanStoragePrivacy
import com.opendroid.ai.data.db.dao.PlanDao
import com.opendroid.ai.data.db.entities.PlanEntity
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStatus
import com.opendroid.ai.data.models.PlanStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlanRepository @Inject constructor(
    private val planDao: PlanDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    val allPlans: Flow<List<Plan>> = planDao.getAllPlansFlow().map { entities ->
        entities.map { entity -> mapEntityToPlan(entity) }
    }

    suspend fun getPlanById(planId: String): Plan? {
        val entity = planDao.getPlanById(planId) ?: return null
        return mapEntityToPlan(entity)
    }

    /** Writes a redacted copy: see [PlanStoragePrivacy]. The caller's [plan] keeps its values. */
    suspend fun savePlan(plan: Plan) {
        val stored = PlanStoragePrivacy.redactForStorage(plan)
        val stepsJson = json.encodeToString(stored.steps)
        planDao.insertOrUpdatePlan(
            PlanEntity(
                planId = stored.planId,
                goal = stored.goal,
                estimatedDuration = stored.estimatedDuration,
                estimatedSteps = stored.estimatedSteps,
                stepsJson = stepsJson,
                status = stored.status.name,
                createdAt = stored.createdAt
            )
        )
    }

    suspend fun deletePlan(planId: String) {
        planDao.deletePlan(planId)
    }

    private fun mapEntityToPlan(entity: PlanEntity): Plan {
        val steps = try {
            json.decodeFromString<List<PlanStep>>(entity.stepsJson)
        } catch (e: Exception) {
            emptyList()
        }
        return Plan(
            planId = entity.planId,
            goal = entity.goal,
            estimatedDuration = entity.estimatedDuration,
            estimatedSteps = entity.estimatedSteps,
            steps = steps,
            status = PlanStatus.valueOf(entity.status),
            createdAt = entity.createdAt
        )
    }
}
