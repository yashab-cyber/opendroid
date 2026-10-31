package com.opendroid.ai.core.agent

import com.opendroid.ai.core.agent.PlanContinuation.Decision
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A plan like [CONFIRM_ACTION "Send ₹500 to bob@upi?", PAY_UPI] must stop when the user
 * says no, without depending on what the LLM's re-evaluation answers (#101).
 */
class PlanContinuationTest {

    @Test
    fun `a declined confirmation ends the plan`() {
        assertTrue(PlanContinuation.endsPlan(step("CONFIRM_ACTION", StepStatus.FAILED)))
    }

    @Test
    fun `an accepted confirmation or another failed step does not end it by itself`() {
        assertFalse(PlanContinuation.endsPlan(step("CONFIRM_ACTION", StepStatus.COMPLETED)))
        assertFalse(PlanContinuation.endsPlan(step("SET_BRIGHTNESS", StepStatus.FAILED)))
    }

    @Test
    fun `known re-evaluation decisions are read leniently`() {
        assertEquals(Decision.CONTINUE, PlanContinuation.decision("CONTINUE"))
        assertEquals(Decision.CONTINUE, PlanContinuation.decision(" continue. "))
        assertEquals(Decision.MODIFY, PlanContinuation.decision("Modify"))
        assertEquals(Decision.ABANDON, PlanContinuation.decision("ABANDON"))
    }

    @Test
    fun `anything else stops the plan instead of running the next step`() {
        // "STOP", "ABORT" or the prompt's own template echoed back used to fall through
        // the when and run the next step.
        for (raw in listOf("STOP", "ABORT", "CANCEL", "CONTINUE | MODIFY | ABANDON", "")) {
            assertEquals(raw, Decision.ABANDON, PlanContinuation.decision(raw))
        }
    }

    private fun step(action: String, status: StepStatus) =
        PlanStep(stepId = "s1", order = 1, description = action, action = action, status = status)
}
