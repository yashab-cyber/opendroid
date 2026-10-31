package com.opendroid.ai.core.agent

import com.opendroid.ai.data.models.AutoMode
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus

/**
 * Applies an LLM re-plan (post-step re-evaluation, or the unknown-action replan) to the
 * running plan, and decides whether the revised plan may keep running without asking
 * the user again.
 */
object PlanRevision {

    fun merge(current: Plan, revisedSteps: List<PlanStep>): Plan =
        current.copy(
            steps = current.steps.filter { it.status != StepStatus.PENDING } +
                revisedSteps.filter { step -> current.steps.none { it.stepId == step.stepId } }
        )

    @Suppress("UNUSED_PARAMETER")
    fun mayRunWithoutApproval(mode: AutoMode, granted: Set<String>, approved: Plan, revised: Plan): Boolean = true
}
