package com.opendroid.ai.core.agent

import com.opendroid.ai.data.models.AutoMode
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStatus
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A re-plan in the middle of a run (post-step re-evaluation or unknown-action replan)
 * must never let new or changed steps run without the approval the original plan
 * needed (#99). Step results reach the re-evaluation prompt, so a notification or
 * screen text can steer what it returns.
 */
class PlanRevisionTest {

    private val dndAndBrightness = plan(
        step("s1", "TOGGLE_DND", StepStatus.COMPLETED),
        step("s2", "SET_BRIGHTNESS", params = mapOf("level" to "30"))
    )
    private val granted = setOf("TOGGLE_DND", "SET_BRIGHTNESS")

    @Test
    fun `in AUTO a re-plan that adds an ungranted step needs approval`() {
        val revised = PlanRevision.merge(dndAndBrightness, listOf(step("x1", "SEND_SMS")))

        assertFalse(PlanRevision.mayRunWithoutApproval(AutoMode.AUTO, granted, dndAndBrightness, revised))
    }

    @Test
    fun `in AUTO a re-plan that only adds granted steps keeps running`() {
        val revised = PlanRevision.merge(
            dndAndBrightness,
            listOf(step("s2", "SET_BRIGHTNESS", params = mapOf("level" to "30")), step("x1", "TOGGLE_DND"))
        )

        assertTrue(PlanRevision.mayRunWithoutApproval(AutoMode.AUTO, granted, dndAndBrightness, revised))
    }

    @Test
    fun `in OFF any new step goes back to the user`() {
        val revised = PlanRevision.merge(
            dndAndBrightness,
            listOf(step("s2", "SET_BRIGHTNESS", params = mapOf("level" to "30")), step("x1", "TOGGLE_DND"))
        )

        assertFalse(PlanRevision.mayRunWithoutApproval(AutoMode.OFF, granted, dndAndBrightness, revised))
    }

    @Test
    fun `in OFF changing an approved step's params goes back to the user`() {
        val revised = PlanRevision.merge(dndAndBrightness, listOf(step("s2", "SET_BRIGHTNESS", params = mapOf("level" to "100"))))

        assertFalse(PlanRevision.mayRunWithoutApproval(AutoMode.OFF, granted, dndAndBrightness, revised))
    }

    @Test
    fun `an unchanged re-plan keeps running in every mode`() {
        val revised = PlanRevision.merge(dndAndBrightness, listOf(step("s2", "SET_BRIGHTNESS", params = mapOf("level" to "30"))))

        for (mode in AutoMode.values()) {
            assertTrue(mode.name, PlanRevision.mayRunWithoutApproval(mode, emptySet(), dndAndBrightness, revised))
        }
    }

    @Test
    fun `YOLO keeps running whatever the re-plan adds`() {
        val revised = PlanRevision.merge(dndAndBrightness, listOf(step("x1", "PAY_UPI")))

        assertTrue(PlanRevision.mayRunWithoutApproval(AutoMode.YOLO, emptySet(), dndAndBrightness, revised))
    }

    @Test
    fun `a pending step edited in place is kept, not dropped`() {
        // The re-evaluation prompt invites editing a remaining step under its own stepId;
        // the old merge kept only new stepIds, so s2 vanished and the plan "completed".
        val revised = PlanRevision.merge(dndAndBrightness, listOf(step("s2", "SET_BRIGHTNESS", params = mapOf("level" to "50"))))

        val pending = revised.steps.filter { it.status == StepStatus.PENDING }
        assertEquals(listOf("s2"), pending.map { it.stepId })
        assertEquals("50", pending.single().params["level"])
        assertEquals(listOf("s1", "s2"), revised.steps.map { it.stepId })
    }

    private fun plan(vararg steps: PlanStep) = Plan(
        planId = "p1",
        goal = "goal",
        steps = steps.toList(),
        estimatedDuration = "1m",
        estimatedSteps = steps.size,
        status = PlanStatus.RUNNING
    )

    private fun step(
        id: String,
        action: String,
        status: StepStatus = StepStatus.PENDING,
        params: Map<String, String> = emptyMap()
    ) = PlanStep(stepId = id, order = 0, description = action, action = action, params = params, status = status)
}
