// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailQueueTest {

    private class Harness {
        val started = mutableListOf<String>()
        val dones = mutableListOf<() -> Unit>()
        val queue = ThumbnailQueue { id, done ->
            started += id
            dones += done
        }
    }

    @Test
    fun oneInFlightAtATime() {
        val h = Harness()
        h.queue.enqueue(listOf("a", "b", "c"))
        assertEquals(listOf("a"), h.started)
        assertEquals(2, h.queue.pending)
        h.dones[0]()
        assertEquals(listOf("a", "b"), h.started)
        h.dones[1]()
        h.dones[2]()
        assertEquals(listOf("a", "b", "c"), h.started)
        assertEquals(0, h.queue.pending)
    }

    @Test
    fun duplicatesIgnoredWhileQueuedOrRunning() {
        val h = Harness()
        h.queue.enqueue(listOf("a", "b", "a"))
        h.queue.enqueue(listOf("a", "b"))
        assertEquals(listOf("a"), h.started)
        assertEquals(1, h.queue.pending)
        h.dones[0]()
        h.dones[1]()
        assertEquals(listOf("a", "b"), h.started)
        // Once finished, an id may be enqueued again.
        h.queue.enqueue(listOf("a"))
        assertEquals(listOf("a", "b", "a"), h.started)
    }

    @Test
    fun reentrantEnqueueFromCallbackIsSafe() {
        val started = mutableListOf<String>()
        var inFlight = 0
        var maxInFlight = 0
        lateinit var queue: ThumbnailQueue
        val dones = mutableListOf<() -> Unit>()
        queue = ThumbnailQueue { id, done ->
            started += id
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            dones += {
                inFlight--
                done()
                // progress callback re-enters, after done.
                queue.enqueue(listOf("x", "y"))
            }
        }
        queue.enqueue(listOf("a"))
        dones[0]()
        assertEquals(listOf("a", "x"), started)
        dones[1]()
        assertEquals(listOf("a", "x", "y"), started)
        assertEquals(1, maxInFlight)
    }

    @Test
    fun doneIsIdempotentAndSynchronousWorkersDoNotRecurse() {
        val h = Harness()
        h.queue.enqueue(listOf("a", "b"))
        h.dones[0]()
        h.dones[0]()
        assertEquals(listOf("a", "b"), h.started)

        val order = mutableListOf<String>()
        val sync = ThumbnailQueue { id, done -> order += id; done() }
        sync.enqueue((1..5000).map { it.toString() })
        assertEquals(5000, order.size)
    }

    @Test
    fun idleAfterLastJobCompletes() {
        val h = Harness()
        assertEquals(true, h.queue.idle)
        h.queue.enqueue(listOf("a"))
        assertEquals(false, h.queue.idle)
        h.dones[0]()
        assertEquals(true, h.queue.idle)
        h.queue.enqueue(listOf("b"))
        assertEquals(false, h.queue.idle)
    }

    @Test
    fun workerThrowAdvancesQueue() {
        val started = mutableListOf<String>()
        val queue = ThumbnailQueue { id, done ->
            started += id
            if (id == "a") throw IllegalStateException("boom")
            done()
        }
        try {
            queue.enqueue(listOf("a", "b"))
            org.junit.Assert.fail("expected exception")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertEquals(listOf("a", "b"), started)
        assertEquals(true, queue.idle)
    }
}
