package com.opendroid.ai.core.agent

import com.opendroid.ai.data.models.PlanStep

/** What the plan loop does after a step and its re-evaluation. */
object PlanContinuation {

    enum class Decision { CONTINUE, MODIFY, ABANDON }

    @Suppress("UNUSED_PARAMETER")
    fun endsPlan(step: PlanStep): Boolean = false

    fun decision(raw: String): Decision = when (raw.uppercase()) {
        "MODIFY" -> Decision.MODIFY
        "ABANDON" -> Decision.ABANDON
        else -> Decision.CONTINUE
    }
}
