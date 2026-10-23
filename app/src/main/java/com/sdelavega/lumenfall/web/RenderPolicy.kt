// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.web

/**
 * Whether the bundle is allowed to keep rendering/ticking. When false the
 * watchdog is stopped and ticks are not liveness evidence; when true the
 * watchdog runs and must be fed.
 */
internal fun renderAllowed(
    contentVisible: Boolean,
    pauseWhenHidden: Boolean,
    policySuspended: Boolean
): Boolean = !policySuspended && (contentVisible || !pauseWhenHidden)
