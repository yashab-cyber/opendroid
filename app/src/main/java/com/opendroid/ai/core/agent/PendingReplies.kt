package com.opendroid.ai.core.agent

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * The auto-reply job waiting to fire for each conversation key.
 *
 * Replies are scheduled from the notification listener's IO scope and finish on
 * the engine's Default scope, so the map is concurrent. A job unregisters only
 * itself: when a newer message has already replaced it, the newer reply has to
 * stay cancellable (#83).
 */
internal class PendingReplies {
    private val jobs = ConcurrentHashMap<String, Job>()

    /** Registers [job] for [key], cancelling the reply it replaces. */
    fun register(key: String, job: Job) {
        jobs.put(key, job)?.takeIf { it !== job }?.cancel()
    }

    /** Called when [job] ends; a newer reply registered for [key] is left in place. */
    fun finished(key: String, job: Job) {
        jobs.remove(key, job)
    }

    /** Cancels the reply waiting for [key], if any. */
    fun cancel(key: String) {
        jobs.remove(key)?.cancel()
    }

    fun cancelAll() {
        jobs.keys.toList().forEach(::cancel)
    }

    fun registered(key: String): Job? = jobs[key]
}
