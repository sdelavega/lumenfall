// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

/**
 * Serial work queue for thumbnail generation: exactly one [worker] call is in
 * flight at a time, and its `done` callback starts the next.
 *
 * Single-threaded by contract: every call (enqueue and every `done`) happens on
 * the main thread, so there is no locking. Ids already queued or in flight are
 * ignored by [enqueue]. `done` is idempotent per job, and workers that finish
 * synchronously are drained by a loop rather than recursion, so neither a
 * misbehaving generator nor rapid re-entry can double-advance or overflow the
 * stack. No android imports; unit-testable on the JVM.
 */
class ThumbnailQueue(private val worker: (id: String, done: () -> Unit) -> Unit) {

    private val queued = ArrayDeque<String>()
    private var inFlightId: String? = null
    private var currentJob: Any? = null
    private var pumping = false

    /** Ids waiting to start (excludes the one in flight). */
    val pending: Int get() = queued.size

    /** True when no job is in flight and nothing is queued. */
    val idle: Boolean get() = currentJob == null && queued.isEmpty()

    fun enqueue(ids: List<String>) {
        for (id in ids) {
            if (id == inFlightId || id in queued) continue
            queued.addLast(id)
        }
        pump()
    }

    private fun pump() {
        if (pumping) return
        pumping = true
        var failure: Throwable? = null
        try {
            while (currentJob == null && queued.isNotEmpty()) {
                val id = queued.removeFirst()
                val job = Any()
                currentJob = job
                inFlightId = id
                val done = {
                    if (currentJob === job) {
                        currentJob = null
                        inFlightId = null
                        pump()
                    }
                }
                try {
                    worker(id, done)
                } catch (t: Throwable) {
                    // A throwing worker counts as finished so the queue can't
                    // stall; remaining jobs still run, then the first failure
                    // is rethrown to the caller.
                    done()
                    if (failure == null) failure = t
                }
            }
        } finally {
            pumping = false
        }
        failure?.let { throw it }
    }
}
