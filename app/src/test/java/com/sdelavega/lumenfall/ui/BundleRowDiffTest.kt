// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundleRowDiffTest {
    private fun row(thumbnailVersion: Long = 0, isActive: Boolean = false) = BundleRow(
        id = "b1",
        title = "Title",
        subtitle = "web",
        thumbnailPath = "/data/b1/thumb.jpg",
        thumbnailVersion = thumbnailVersion,
        isActive = isActive,
        screening = null
    )

    @Test
    fun sameIdDifferentThumbnailIsChangeNotNewItem() {
        val old = row(thumbnailVersion = 1)
        val new = row(thumbnailVersion = 2)
        assertTrue(BundleRowDiff.areItemsTheSame(old, new))
        assertFalse(BundleRowDiff.areContentsTheSame(old, new))
    }

    @Test
    fun activeFlagChangeIsContentChange() {
        val old = row(isActive = false)
        val new = row(isActive = true)
        assertTrue(BundleRowDiff.areItemsTheSame(old, new))
        assertFalse(BundleRowDiff.areContentsTheSame(old, new))
    }
}
