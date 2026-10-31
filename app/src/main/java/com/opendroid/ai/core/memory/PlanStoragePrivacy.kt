package com.opendroid.ai.core.memory

import com.opendroid.ai.data.models.Plan

/**
 * Redaction rules for the `plans` table. A plan is written there as soon as it is
 * proposed, before approval, and the row stays after the plan finishes or is
 * rejected. Without this, the `secret` of a SAVE_SENSITIVE_INFO step would sit in
 * plaintext in Room long after the Keystore-backed store took it (#107).
 *
 * Sensitive params are picked by the same rule as execution history
 * ([ExecutionHistoryPrivacy.isSensitiveParam]). Their values are also scrubbed from
 * the goal and from every step's text, because the planner usually repeats them
 * there ("Save locker combination 4589 ...").
 *
 * Only the stored copy is redacted. The plan in memory keeps its values, so the step
 * still runs with the real secret. Nothing reads a stored plan back into execution.
 */
object PlanStoragePrivacy {

    /** Scrubbing values shorter than this from free text would shred ordinary words. */
    private const val MIN_SCRUBBED_LENGTH = 3

    fun redactForStorage(plan: Plan): Plan {
        val sensitiveValues = plan.steps.flatMap { step ->
            step.params.filter { (key, value) -> ExecutionHistoryPrivacy.isSensitiveParam(key, value) }.values
        }
        if (sensitiveValues.isEmpty()) return plan
        val secrets = sensitiveValues
            .filter { it.length >= MIN_SCRUBBED_LENGTH }
            .distinct()
            // Longest first, so a secret that contains another is replaced whole.
            .sortedByDescending { it.length }

        fun scrub(text: String): String =
            secrets.fold(text) { acc, secret -> acc.replace(secret, ExecutionHistoryPrivacy.REDACTED) }

        return plan.copy(
            goal = scrub(plan.goal),
            steps = plan.steps.map { step ->
                step.copy(
                    description = scrub(step.description),
                    params = step.params.mapValues { (key, value) ->
                        if (ExecutionHistoryPrivacy.isSensitiveParam(key, value)) {
                            ExecutionHistoryPrivacy.REDACTED
                        } else {
                            scrub(value)
                        }
                    },
                    fallback = scrub(step.fallback),
                    result = step.result?.let(::scrub),
                    error = step.error?.let(::scrub)
                )
            }
        )
    }
}
