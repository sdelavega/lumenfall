// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeolocationPermissionTest {
    @Test fun fineOnlyIsPermitted() = assertTrue(locationPermitted(fineGranted = true, coarseGranted = false))
    @Test fun coarseOnlyIsPermitted() = assertTrue(locationPermitted(fineGranted = false, coarseGranted = true))
    @Test fun bothArePermitted() = assertTrue(locationPermitted(fineGranted = true, coarseGranted = true))
    @Test fun neitherIsDenied() = assertFalse(locationPermitted(fineGranted = false, coarseGranted = false))
}
