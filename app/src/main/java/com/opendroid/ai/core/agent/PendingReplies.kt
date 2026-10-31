package com.opendroid.ai.core.agent

import kotlinx.coroutines.Job

/**
 * The auto-reply job waiting to fire for each conversation key.
 */
internal class PendingReplies {
    private val jobs = mutableMapOf<String, Job>()

    /** Registers [job] for [key], cancelling the reply it replaces. */
    fun register(key: String, job: Job) {
        jobs[key]?.cancel()
        jobs[key] = job
    }

    /** Called when [job] ends. */
    fun finished(key: String, job: Job) {
        jobs.remove(key)
    }

    /** Cancels the reply waiting for [key], if any. */
    fun cancel(key: String) {
        jobs[key]?.cancel()
        jobs.remove(key)
    }

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    fun registered(key: String): Job? = jobs[key]
}
