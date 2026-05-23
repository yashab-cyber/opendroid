package com.opendroid.ai.core.agent

import android.content.Context
import com.opendroid.ai.core.memory.WorkingMemory
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStatus
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus
import com.opendroid.ai.data.repository.PlanRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlanManager @Inject constructor(
    private val planRepository: PlanRepository,
    private val workingMemory: WorkingMemory,
    private val planValidator: dagger.Lazy<PlanValidator>
) {
    private val _currentPlan = MutableStateFlow<Plan?>(null)
    val currentPlan: StateFlow<Plan?> = _currentPlan.asStateFlow()

    suspend fun startNewPlan(plan: Plan, context: Context) {
        val validatedPlan = planValidator.get().validateAndFix(plan, context)
        val runningPlan = validatedPlan.copy(status = PlanStatus.RUNNING)
        _currentPlan.value = runningPlan
        workingMemory.activePlan = runningPlan
        saveCurrentPlan()
    }

    suspend fun updateStepStatus(stepId: String, status: StepStatus, result: String? = null, error: String? = null) {
        val plan = _currentPlan.value ?: return
        val updatedSteps = plan.steps.map { step ->
            if (step.stepId == stepId) {
                step.copy(status = status, result = result, error = error)
            } else {
                step
            }
        }
        val updatedPlan = plan.copy(steps = updatedSteps)
        _currentPlan.value = updatedPlan
        workingMemory.activePlan = updatedPlan
        saveCurrentPlan()
    }

    suspend fun updatePlanStatus(status: PlanStatus) {
        val plan = _currentPlan.value ?: return
        val updatedPlan = plan.copy(status = status)
        _currentPlan.value = updatedPlan
        if (status == PlanStatus.COMPLETED || status == PlanStatus.FAILED) {
            workingMemory.activePlan = null
        } else {
            workingMemory.activePlan = updatedPlan
        }
        saveCurrentPlan()
    }

    suspend fun loadPlan(planId: String): Boolean {
        val plan = planRepository.getPlanById(planId)
        return if (plan != null) {
            _currentPlan.value = plan
            workingMemory.activePlan = plan
            true
        } else {
            false
        }
    }

    suspend fun saveCurrentPlan() {
        val plan = _currentPlan.value ?: return
        planRepository.savePlan(plan)
    }

    fun clearPlan() {
        _currentPlan.value = null
        workingMemory.activePlan = null
    }

    fun getActiveStep(): PlanStep? {
        val plan = _currentPlan.value ?: return null
        if (plan.status != PlanStatus.RUNNING) return null
        // Return the first pending step that doesn't have pending/running dependencies
        return plan.steps.firstOrNull { step ->
            step.status == StepStatus.PENDING && hasDependenciesMet(step, plan.steps)
        }
    }

    private fun hasDependenciesMet(step: PlanStep, allSteps: List<PlanStep>): Boolean {
        if (step.dependsOn.isEmpty()) return true
        // A dependency is met when it has finished executing (COMPLETED or FAILED).
        // Only PENDING or RUNNING dependencies should block execution.
        // Previously this required COMPLETED, which meant a step with dependsOn
        // would be stuck forever if the dependency succeeded but returned no data
        // (since the dependency check was too strict).
        return step.dependsOn.all { depId ->
            val depStep = allSteps.find { it.stepId == depId }
            depStep != null && (depStep.status == StepStatus.COMPLETED || depStep.status == StepStatus.FAILED)
        }
    }
}
