// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderPolicyTest {
    @Test fun visibleRenders() = assertTrue(renderAllowed(true, true, false))
    @Test fun hiddenWithPauseDoesNot() = assertFalse(renderAllowed(false, true, false))
    @Test fun hiddenWithoutPauseRenders() = assertTrue(renderAllowed(false, false, false))
    @Test fun policySuspendedNeverRenders() {
        assertFalse(renderAllowed(true, true, true))
        assertFalse(renderAllowed(false, false, true))
    }
}
