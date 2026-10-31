package com.opendroid.ai.core.agent

import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus

/**
 * What the plan loop does after a step and its re-evaluation (#101). Both rules fail
 * closed: a plan like [CONFIRM_ACTION "Send ₹500?", PAY_UPI] must stop when the user
 * says no, whatever the LLM's re-evaluation answers.
 */
object PlanContinuation {

    enum class Decision { CONTINUE, MODIFY, ABANDON }

    /** A CONFIRM_ACTION the user declined ends the plan before any later step runs. */
    fun endsPlan(step: PlanStep): Boolean =
        step.action.equals("CONFIRM_ACTION", ignoreCase = true) && step.status == StepStatus.FAILED

    /**
     * Reads a re-evaluation decision leniently ("continue." is CONTINUE). Anything it
     * doesn't recognise — "STOP", "ABORT", the prompt's own template echoed back — ends
     * the plan instead of running the next step.
     */
    fun decision(raw: String): Decision = when (raw.trim().trimEnd('.', '!').uppercase()) {
        "CONTINUE" -> Decision.CONTINUE
        "MODIFY" -> Decision.MODIFY
        else -> Decision.ABANDON
    }
}
