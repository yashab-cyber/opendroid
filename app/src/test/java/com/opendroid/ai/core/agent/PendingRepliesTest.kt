package com.opendroid.ai.core.agent

import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingRepliesTest {

    private val key = "com.whatsapp:Alice"

    @Test
    fun `a new message's reply replaces and cancels the one waiting`() {
        val pending = PendingReplies()
        val first = Job()
        val second = Job()

        pending.register(key, first)
        pending.register(key, second)

        assertTrue(first.isCancelled)
        assertFalse(second.isCancelled)
        assertSame(second, pending.registered(key))
    }

    @Test
    fun `the replaced reply finishing does not unregister its successor`() {
        // Message 1 schedules a reply, message 2 replaces it. The cancelled first
        // reply's cleanup runs afterwards and must leave the second one cancellable (#83).
        val pending = PendingReplies()
        val first = Job()
        val second = Job()
        pending.register(key, first)
        pending.register(key, second)

        pending.finished(key, first)

        assertSame(second, pending.registered(key))
        pending.cancel(key)
        assertTrue(second.isCancelled)
    }

    @Test
    fun `a reply finishing unregisters itself`() {
        val pending = PendingReplies()
        val only = Job()
        pending.register(key, only)

        pending.finished(key, only)

        assertNull(pending.registered(key))
    }

    @Test
    fun `cancel cancels only the reply waiting for that conversation`() {
        val pending = PendingReplies()
        val alice = Job()
        val bob = Job()
        pending.register(key, alice)
        pending.register("com.whatsapp:Bob", bob)

        pending.cancel(key)

        assertTrue(alice.isCancelled)
        assertFalse(bob.isCancelled)
        assertNull(pending.registered(key))
    }
}
