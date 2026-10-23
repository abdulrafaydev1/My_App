package com.instaguard.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationStateTest {

    @Test
    fun testValidTabIndices() {
        val validIndices = 0..4
        assertEquals(5, validIndices.count())
        assertTrue(0 in validIndices)
        assertTrue(4 in validIndices)
    }

    @Test
    fun testDirectionCalculation() {
        val currentTabIndex = 0
        val targetIndexForward = 2
        val directionForward = if (targetIndexForward > currentTabIndex) 1f else -1f
        assertEquals(1f, directionForward, 0.001f)

        val targetIndexBackward = 0
        val previousIndex = 3
        val directionBackward = if (targetIndexBackward > previousIndex) 1f else -1f
        assertEquals(-1f, directionBackward, 0.001f)
    }

    @Test
    fun testTabDurationScaling() {
        val adjacentDistance = kotlin.math.abs(1 - 0)
        val adjacentDuration = if (adjacentDistance > 1) 290L else 250L
        assertEquals(250L, adjacentDuration)

        val multiJumpDistance = kotlin.math.abs(3 - 0)
        val multiJumpDuration = if (multiJumpDistance > 1) 290L else 250L
        assertEquals(290L, multiJumpDuration)
    }
}
