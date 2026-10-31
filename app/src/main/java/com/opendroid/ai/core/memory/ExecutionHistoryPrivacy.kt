package com.opendroid.ai.core.memory

import com.opendroid.ai.core.crash.CrashLogRedactor

/**
 * Redaction rules for the local execution-history sink and macro recorder.
 * Parameters are sorted here as well, so history-derived macro JSON is
 * deterministic across runs.
 */
object ExecutionHistoryPrivacy {
    const val REDACTED = "[REDACTED]"

    private val sensitiveKey = Regex(
        "(?:api[_-]?key|access[_-]?token|auth[_-]?token|token|secret|password|credential|authorization|bearer|private[_-]?key|client[_-]?secret)",
        RegexOption.IGNORE_CASE
    )

    fun sanitizeParams(
        actionName: String,
        params: Map<String, String>
    ): Map<String, String> = params.toSortedMap().mapValues { (key, value) ->
        if (actionName.equals("SEND_EMAIL", ignoreCase = true) || isSensitiveParam(key, value)) {
            REDACTED
        } else {
            value
        }
    }

    /**
     * Whether a param is secret material by its name (`secret`, `token`, ...) or by its
     * value's shape. Shared with [PlanStoragePrivacy] so every local sink redacts the
     * same params.
     */
    fun isSensitiveParam(key: String, value: String): Boolean =
        sensitiveKey.containsMatchIn(key) || CrashLogRedactor.redact(value) != value

    fun sanitizeDescription(actionName: String, description: String): String =
        if (actionName.equals("SEND_EMAIL", ignoreCase = true)) {
            "Email action"
        } else {
            CrashLogRedactor.redact(description)
        }
}
