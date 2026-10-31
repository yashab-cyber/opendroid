package com.opendroid.ai.data.repository

import com.opendroid.ai.data.db.dao.PlanDao
import com.opendroid.ai.data.db.entities.PlanEntity
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStatus
import com.opendroid.ai.data.models.PlanStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What reaches the unencrypted `plans` table (#107). A plan is saved as soon as it is
 * proposed, before approval, and the row outlives the plan, so a secret the user asked
 * to keep in Keystore-backed storage must not be written here in plaintext.
 */
class PlanRepositoryPrivacyTest {

    private val dao = RecordingPlanDao()
    private val repository = PlanRepository(dao)

    @Test
    fun `a sensitive secret is not written to the plans table`() = runBlocking {
        repository.savePlan(lockerPlan())

        val stored = dao.rows.getValue("plan-1")
        assertFalse(stored.stepsJson, stored.stepsJson.contains(SECRET))
    }

    @Test
    fun `the secret is scrubbed from the goal and step descriptions too`() = runBlocking {
        repository.savePlan(lockerPlan())

        val stored = dao.rows.getValue("plan-1")
        assertFalse(stored.goal, stored.goal.contains(SECRET))
        assertFalse(stored.stepsJson, stored.stepsJson.contains(SECRET))
        assertTrue(stored.goal.contains("locker combination"))
    }

    @Test
    fun `a stored plan still records which step it was and what it saved under`() = runBlocking {
        repository.savePlan(lockerPlan())

        val step = repository.getPlanById("plan-1")!!.steps.single { it.stepId == "s1" }
        assertEquals("SAVE_SENSITIVE_INFO", step.action)
        assertEquals("locker", step.params["key"])
        assertEquals("Locker", step.params["label"])
        assertTrue(step.params.containsKey("secret"))
    }

    @Test
    fun `ordinary step params are stored unchanged`() = runBlocking {
        repository.savePlan(lockerPlan())

        val step = repository.getPlanById("plan-1")!!.steps.single { it.stepId == "s2" }
        assertEquals(mapOf("time" to "07:30", "label" to "Gym"), step.params)
        assertEquals("Set an alarm for 07:30", step.description)
    }

    private fun lockerPlan() = Plan(
        planId = "plan-1",
        goal = "Securely remember my locker combination is $SECRET and wake me at 07:30",
        estimatedDuration = "5s",
        estimatedSteps = 2,
        steps = listOf(
            PlanStep(
                stepId = "s1",
                order = 1,
                description = "Save locker combination $SECRET to encrypted storage",
                action = "SAVE_SENSITIVE_INFO",
                params = mapOf("key" to "locker", "secret" to SECRET, "label" to "Locker")
            ),
            PlanStep(
                stepId = "s2",
                order = 2,
                description = "Set an alarm for 07:30",
                action = "SET_ALARM",
                params = mapOf("time" to "07:30", "label" to "Gym")
            )
        ),
        status = PlanStatus.PROPOSED,
        createdAt = 1L
    )

    /** Keeps the last row written per plan, the way Room's REPLACE insert does. */
    private class RecordingPlanDao : PlanDao {
        val rows = linkedMapOf<String, PlanEntity>()
        private val flow = MutableStateFlow<List<PlanEntity>>(emptyList())

        override fun getAllPlansFlow(): Flow<List<PlanEntity>> = flow
        override suspend fun getAllPlans(): List<PlanEntity> = rows.values.toList()
        override suspend fun getPlanById(planId: String): PlanEntity? = rows[planId]
        override suspend fun insertOrUpdatePlan(plan: PlanEntity) {
            rows[plan.planId] = plan
            flow.value = rows.values.toList()
        }
        override suspend fun deletePlan(planId: String) {
            rows.remove(planId)
            flow.value = rows.values.toList()
        }
    }

    private companion object {
        const val SECRET = "4589"
    }
}
