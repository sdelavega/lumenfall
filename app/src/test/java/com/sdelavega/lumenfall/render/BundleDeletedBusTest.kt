// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.render

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class BundleDeletedBusTest {
    private val token = Any()

    @Before fun install() { BundleDeletedBus.dispatcher = MainThreadDispatcher { it() } }

    @After fun restore() {
        BundleDeletedBus.unregister(token)
        BundleDeletedBus.dispatcher = AndroidMainDispatcher
    }

    @Test fun notifiesOnlyRegisteredAndStopsAfterUnregister() {
        val got = mutableListOf<String>()
        BundleDeletedBus.notifyDeleted("before")
        BundleDeletedBus.register(token) { got += it }
        BundleDeletedBus.notifyDeleted("x")
        BundleDeletedBus.unregister(token)
        BundleDeletedBus.notifyDeleted("y")
        assertEquals(listOf("x"), got)
    }
}
