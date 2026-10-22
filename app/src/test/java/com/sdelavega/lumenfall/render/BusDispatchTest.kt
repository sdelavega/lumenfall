// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.render

import com.sdelavega.lumenfall.storage.PerformanceSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class BusDispatchTest {
    private class QueueDispatcher : MainThreadDispatcher {
        val queue = ArrayDeque<() -> Unit>()
        override fun dispatch(block: () -> Unit) { queue.addLast(block) }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst()() }
    }

    private val q = QueueDispatcher()
    private val token = Any()

    @Before fun install() {
        PerformanceSettingsBus.dispatcher = q
        PropertyBus.dispatcher = q
        ActiveBundleBus.dispatcher = q
    }

    @After fun restore() {
        PerformanceSettingsBus.unregister(token)
        PropertyBus.unregister(token)
        ActiveBundleBus.unregister(token)
        PerformanceSettingsBus.dispatcher = AndroidMainDispatcher
        PropertyBus.dispatcher = AndroidMainDispatcher
        ActiveBundleBus.dispatcher = AndroidMainDispatcher
    }

    @Test fun performanceListenersRunThroughDispatcher() {
        var calls = 0
        PerformanceSettingsBus.register(token) { calls++ }
        PerformanceSettingsBus.notifyChanged(PerformanceSettings())
        assertEquals(0, calls)
        q.drain()
        assertEquals(1, calls)
    }

    @Test fun propertyListenersRunThroughDispatcherForMatchingBundleOnly() {
        val got = mutableListOf<String>()
        PropertyBus.register(token, "a") { k, _ -> got += "a:$k" }
        val other = Any()
        PropertyBus.register(other, "b") { k, _ -> got += "b:$k" }
        try {
            PropertyBus.notify("a", "k", 1)
            assertEquals(emptyList<String>(), got)
            q.drain()
            assertEquals(listOf("a:k"), got)
        } finally {
            PropertyBus.unregister(other)
        }
    }

    @Test fun activeBundleListenersRunThroughDispatcher() {
        var calls = 0
        ActiveBundleBus.register(token) { calls++ }
        ActiveBundleBus.notifyChanged()
        assertEquals(0, calls)
        q.drain()
        assertEquals(1, calls)
    }
}
