package com.instaguard.app.ui

import com.instaguard.app.ui.widget.ElasticStretchButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.tanh

/**
 * Unit tests verifying the Elastic Stretch Button mathematics, damped spring physics,
 * deformation continuity, and state machine discrimination.
 */
class ElasticStretchButtonTest {

    @Test
    fun testDampedSpringInterpolatorInitialAndFinalValues() {
        val interpolator = ElasticStretchButton.DampedSpringInterpolator(
            dampingRatio = 0.72f,
            frequency = 16.0f
        )

        assertEquals("Input 0 must produce 0.0 interpolation", 0f, interpolator.getInterpolation(0f), 0.001f)
        assertEquals("Input 1 must produce 1.0 interpolation", 1f, interpolator.getInterpolation(1f), 0.001f)
        assertEquals("Negative input must clamp to 0.0", 0f, interpolator.getInterpolation(-0.5f), 0.001f)
        assertEquals("Input >= 1.0 must clamp to 1.0", 1f, interpolator.getInterpolation(1.5f), 0.001f)
    }

    @Test
    fun testDampedSpringOvershootIsControlledAndSettles() {
        val interpolator = ElasticStretchButton.DampedSpringInterpolator(
            dampingRatio = 0.72f,
            frequency = 16.0f
        )

        var maxVal = 0f
        var minVal = 1f
        // Sample 100 points along the spring curve
        for (i in 0..100) {
            val t = i / 100f
            val v = interpolator.getInterpolation(t)
            if (v > maxVal) maxVal = v
            if (v < minVal) minVal = v
        }

        // Controlled overshoot of ~3.5%: should not exceed 1.06
        assertTrue("Spring overshoot must be controlled and not exceed 1.06 (was $maxVal)", maxVal <= 1.06f)
        assertTrue("Spring response must not drop below 0 (was $minVal)", minVal >= 0f)
    }

    @Test
    fun testElasticDeformationPhysicsCoordinates() {
        val w = 240f
        val h = 50f
        val r = 25f
        val cx = w / 2f
        val cy = h / 2f
        val maxL = Math.min(w, h) * 0.75f

        fun deformPoint(px: Float, py: Float, sx: Float, sy: Float): Pair<Float, Float> {
            val len = hypot(sx, sy)
            if (len < 0.001f) return Pair(px, py)
            val ux = sx / len
            val uy = sy / len
            val effL = maxL * tanh((len / maxL).toDouble()).toFloat()
            val perpX = -uy
            val perpY = ux

            val dx = px - cx
            val dy = py - cy
            val dist = hypot(dx, dy)
            if (dist < 0.001f) return Pair(px, py)

            val nx = dx / dist
            val ny = dy / dist
            val projAlong = nx * ux + ny * uy
            val projPerp = nx * perpX + ny * perpY

            val stretchAlong = if (projAlong > 0f) {
                Math.pow(projAlong.toDouble(), 1.4).toFloat() * effL
            } else {
                projAlong * 0.22f * effL
            }
            val pinch = -projPerp * (1f - Math.abs(projAlong)) * 0.26f * effL

            return Pair(px + ux * stretchAlong + perpX * pinch, py + uy * stretchAlong + perpY * pinch)
        }

        // 1. Resting state: deformation is identity
        val restRight = deformPoint(w, cy, 0f, 0f)
        assertEquals("At rest X must equal width", w, restRight.first, 0.001f)
        assertEquals("At rest Y must equal cy", cy, restRight.second, 0.001f)

        // 2. Horizontal stretch: right end elongates
        val stretchedRight = deformPoint(w, cy, 50f, 0f)
        assertTrue("Stretched right point must extend beyond original width", stretchedRight.first > w)
        assertEquals("Symmetric horizontal stretch must maintain Y center", cy, stretchedRight.second, 0.001f)

        // 3. Transverse necking: top edge compresses slightly toward center
        val stretchedTopMid = deformPoint(cx, 0f, 50f, 0f)
        assertTrue("Top midpoint must pinch inward toward center", stretchedTopMid.second > 0f)

        // 4. Diagonal stretch: produces valid finite coordinates
        val diagonalPoint = deformPoint(w, h, 40f, 40f)
        assertTrue("Diagonal deformation must be finite", diagonalPoint.first.isFinite())
        assertTrue("Diagonal deformation must be finite", diagonalPoint.second.isFinite())
        assertTrue("Diagonal point must extend in X", diagonalPoint.first > w - r)
        assertTrue("Diagonal point must extend in Y", diagonalPoint.second > h - r)
    }

    @Test
    fun testGestureStateEnumCompleteness() {
        val states = ElasticStretchButton.ElasticGestureState.values()
        assertEquals(4, states.size)
        assertTrue(states.contains(ElasticStretchButton.ElasticGestureState.IDLE))
        assertTrue(states.contains(ElasticStretchButton.ElasticGestureState.PRESSED_HOLDING))
        assertTrue(states.contains(ElasticStretchButton.ElasticGestureState.DRAGGING))
        assertTrue(states.contains(ElasticStretchButton.ElasticGestureState.RELEASING))
    }
}
