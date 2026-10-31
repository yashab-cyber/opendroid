package com.opendroid.ai.core.agent

import com.opendroid.ai.data.models.AutoMode
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus

/**
 * Applies an LLM re-plan (post-step re-evaluation, or the unknown-action replan) to the
 * running plan, and decides whether the revised plan may keep running without asking
 * the user again (#99). Step results feed the re-evaluation prompt, so notification or
 * screen text can steer what a re-plan returns: it must never widen what the user (or
 * the AUTO allowlist) approved.
 */
object PlanRevision {

    /**
     * Keeps finished steps and replaces the pending ones with the revision's steps: a
     * pending step revised under its own stepId is updated in place, new stepIds are
     * appended, and revised steps that reuse a finished step's ID are ignored.
     */
    fun merge(current: Plan, revisedSteps: List<PlanStep>): Plan {
        val finished = current.steps.filter { it.status != StepStatus.PENDING }
        val finishedIds = finished.map { it.stepId }.toSet()
        val revised = revisedSteps
            .filter { it.stepId !in finishedIds }
            .distinctBy { it.stepId }
        return current.copy(steps = finished + revised)
    }

    /**
     * True when [revised] may keep running: YOLO approves everything; otherwise every
     * pending step must be one the user already approved in [approved], unchanged, or —
     * in AUTO — the pending steps must pass the auto-approval allowlist on their own.
     */
    fun mayRunWithoutApproval(mode: AutoMode, granted: Set<String>, approved: Plan, revised: Plan): Boolean {
        if (mode == AutoMode.YOLO) return true
        val approvedPending = approved.steps
            .filter { it.status == StepStatus.PENDING }
            .associateBy { it.stepId }
        val pending = revised.steps.filter { it.status == StepStatus.PENDING }
        val unchanged = pending.all { step ->
            val before = approvedPending[step.stepId]
            before != null && before.action == step.action && before.fallback == step.fallback && before.params == step.params
        }
        if (unchanged) return true
        return mode == AutoMode.AUTO &&
            AutoApprovalPolicy.shouldAutoApprove(mode, granted, revised.copy(steps = pending))
    }
}
