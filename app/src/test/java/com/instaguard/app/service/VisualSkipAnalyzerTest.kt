package com.instaguard.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualSkipAnalyzerTest {

    private val width = VisualSkipAnalyzer.TARGET_WIDTH
    private val height = VisualSkipAnalyzer.TARGET_HEIGHT

    // Helper to create an RGB integer
    private fun rgb(r: Int, g: Int, b: Int): Int {
        return (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
    }

    // Typical test colors
    private val fairSkin = rgb(235, 185, 160)
    private val wheatishSkin = rgb(210, 155, 120)
    private val darkSkin = rgb(120, 80, 55)
    private val blackHair = rgb(25, 20, 20)
    private val darkBlueFabric = rgb(20, 30, 80)
    private val greenGrass = rgb(40, 160, 50)
    private val grayWall = rgb(128, 128, 128)

    // Helper to build a blank grid with a default background
    private fun createGrid(bg: Int = rgb(30, 30, 40)): IntArray {
        return IntArray(width * height) { bg }
    }

    // Helper to paint a rectangular zone on the grid
    private fun fillZone(grid: IntArray, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        val cx1 = x1.coerceIn(0, width - 1)
        val cx2 = x2.coerceIn(0, width - 1)
        val cy1 = y1.coerceIn(0, height - 1)
        val cy2 = y2.coerceIn(0, height - 1)
        for (y in cy1..cy2) {
            val offset = y * width
            for (x in cx1..cx2) {
                grid[offset + x] = color
            }
        }
    }

    // =========================================================================
    // 1. Color Classifier Unit Tests
    // =========================================================================

    @Test
    fun isHumanSkin_detectsVariousEthnicSkinTones() {
        assertTrue("Fair skin", VisualSkipAnalyzer.isHumanSkin(240, 190, 170))
        assertTrue("Wheatish skin", VisualSkipAnalyzer.isHumanSkin(210, 155, 120))
        assertTrue("Tan skin", VisualSkipAnalyzer.isHumanSkin(170, 115, 85))
        assertTrue("Dark skin", VisualSkipAnalyzer.isHumanSkin(115, 75, 52))
    }

    @Test
    fun isHumanSkin_rejectsNonSkinColors() {
        assertFalse("Pure white specular", VisualSkipAnalyzer.isHumanSkin(255, 255, 255))
        assertFalse("Pure black", VisualSkipAnalyzer.isHumanSkin(10, 10, 10))
        assertFalse("Cold blue fabric", VisualSkipAnalyzer.isHumanSkin(30, 60, 180))
        assertFalse("Neutral gray", VisualSkipAnalyzer.isHumanSkin(128, 128, 128))
        assertFalse("Green foliage", VisualSkipAnalyzer.isHumanSkin(40, 180, 50))
    }

    @Test
    fun isDarkOrColoredHair_detectsHairColors() {
        assertTrue("Black hair", VisualSkipAnalyzer.isDarkOrColoredHair(20, 18, 18))
        assertTrue("Dark brown hair", VisualSkipAnalyzer.isDarkOrColoredHair(50, 42, 38))
        assertTrue("Blonde hair", VisualSkipAnalyzer.isDarkOrColoredHair(210, 180, 80))
        assertTrue("Auburn hair", VisualSkipAnalyzer.isDarkOrColoredHair(130, 60, 35))
    }

    // =========================================================================
    // 2. Stage 1 Protection Tests (Walls, Landscapes, Modest Content)
    // =========================================================================

    @Test
    fun fullScreenTanWall_rejectedDueToOuterBorderSkin() {
        val grid = createGrid(bg = wheatishSkin)
        val result = VisualSkipAnalyzer.analyzePixelGrid(grid, width, height)
        assertFalse(result.shouldSkip)
        assertEquals("REJECT_OUTER_BORDER_SKIN_WALL_OR_SURFACE", result.skipReason)
    }

    @Test
    fun landscapeOrDarkScreen_rejectedDueToInsufficientCentralSkin() {
        val grid = createGrid(bg = rgb(15, 20, 25))
        // Paint some green hills and blue sky
        fillZone(grid, 0, 0, 99, 80, rgb(60, 120, 200))
        fillZone(grid, 0, 81, 99, 179, greenGrass)

        val result = VisualSkipAnalyzer.analyzePixelGrid(grid, width, height)
        assertFalse(result.shouldSkip)
        assertEquals("REJECT_INSUFFICIENT_CENTRAL_SKIN", result.skipReason)
    }

    @Test
    fun fullyModestClothedPerson_rejected() {
        val grid = createGrid()
        // Head / hair
        fillZone(grid, 35, 10, 65, 22, blackHair)
        fillZone(grid, 40, 23, 60, 35, fairSkin) // small face
        // Modest high-neck shirt / long clothes
        fillZone(grid, 25, 36, 75, 100, darkBlueFabric)
        // Modest long jeans / pants covering lower body
        fillZone(grid, 25, 101, 75, 170, rgb(30, 40, 70))

        val result = VisualSkipAnalyzer.analyzePixelGrid(grid, width, height)
        assertFalse(result.shouldSkip)
        assertEquals("REJECT_INSUFFICIENT_CENTRAL_SKIN", result.skipReason)
    }

    // =========================================================================
    // 3. Condition 1: Female Boobs / Breasts / Cleavage Visible
    // =========================================================================

    @Test
    fun femaleCleavageCleft_triggersSkip() {
        val grid = createGrid()
        // Head & hair
        fillZone(grid, 30, 8, 70, 25, blackHair)
        fillZone(grid, 38, 20, 62, 38, fairSkin)

        // Breasts with cleavage cleft in breast zone (y: 42..80)
        // Left lobe of skin mound
        fillZone(grid, 32, 45, 46, 78, fairSkin)
        // Right lobe of skin mound
        fillZone(grid, 54, 45, 68, 78, fairSkin)
        // Center cleavage crease / shadow
        fillZone(grid, 47, 45, 53, 78, rgb(20, 15, 25))

        val result = VisualSkipAnalyzer.analyzePixelGrid(grid, width, height)
        assertTrue("Condition 1 should trigger skip", result.shouldSkip)
        assertEquals("SKIP_CONDITION_1_FEMALE_BREASTS_OR_CLEAVAGE", result.skipReason)
    }

    @Test
    fun lowCutExposedCleavage_triggersSkip() {
        val grid = createGrid()
        // Head & hair
        fillZone(grid, 30, 8, 70, 25, blackHair)
        fillZone(grid, 38, 20, 62, 38, wheatishSkin)

        // Low-cut exposed cleavage across chest (y: 42..75)
        fillZone(grid, 30, 42, 70, 75, wheatishSkin)
        // Fabric framing shoulders
        fillZone(grid, 15, 45, 28, 75, darkBlueFabric)
        fillZone(grid, 72, 45, 85, 75, darkBlueFabric)

        val result = VisualSkipAnalyzer.analyzePixelGrid(grid, width, height)
        assertTrue("Low-cut exposed cleavage should trigger skip", result.shouldSkip)
        assertEquals("SKIP_CONDITION_1_FEMALE_BREASTS_OR_CLEAVAGE", result.skipReason)
    }

    // =========================================================================
    // 4. Condition 2: Short Dress With Exposed Legs
    // =========================================================================

    @Test
    fun shortDressWithExposedLegs_triggersSkip() {
        val grid = createGrid()
        // Head & hair
        fillZone(grid, 35, 8, 65, 22, blackHair)
        fillZone(grid, 38, 23, 62, 38, fairSkin)

        // Torso covered by short dress (y: 45..92)
        fillZone(grid, 28, 45, 72, 92, rgb(180, 30, 50)) // Red dress covering torso

        // Thighs / bare legs below short dress hemline (y: 93..160)
        fillZone(grid, 30, 95, 48, 160, fairSkin) // left leg bare skin
        fillZone(grid, 52, 95, 70, 160, fairSkin) // right leg bare skin

        val result = VisualSkipAnalyzer.analyzePixelGrid(grid, width, height)
        assertTrue("Short dress should trigger skip", result.shouldSkip)
        assertEquals("SKIP_CONDITION_2_SHORT_DRESS_WITH_EXPOSED_LEGS", result.skipReason)
    }

    // =========================================================================
    // 5. Condition 3: Back Body — Bare Back & Exposed Glutes
    // =========================================================================

    @Test
    fun bareBackPose_triggersSkip() {
        val grid = createGrid()
        // Hair at top (no face)
        fillZone(grid, 30, 8, 70, 35, blackHair)

        // Bare skin on middle and lower back (y: 45..105)
        fillZone(grid, 28, 45, 72, 105, fairSkin)

        val result = VisualSkipAnalyzer.analyzePixelGrid(grid, width, height)
        assertTrue("Bare back should trigger skip", result.shouldSkip)
        assertEquals("SKIP_CONDITION_3_BARE_BACK", result.skipReason)
    }

    @Test
    fun exposedGlutesButtocks_triggersSkip() {
        val grid = createGrid()
        // Hair at top
        fillZone(grid, 35, 10, 65, 30, blackHair)

        // Upper body
        fillZone(grid, 30, 35, 70, 75, darkBlueFabric)

        // Exposed glutes / buttocks bare skin (y: 86..130)
        fillZone(grid, 28, 86, 72, 130, darkSkin)

        val result = VisualSkipAnalyzer.analyzePixelGrid(grid, width, height)
        assertTrue("Exposed glutes should trigger skip", result.shouldSkip)
        assertEquals("SKIP_CONDITION_3_EXPOSED_GLUTES_BUTTOCKS", result.skipReason)
    }

    @Test
    fun gluteMotionTwerking_triggersSkip() {
        val grid = createGrid()
        fillZone(grid, 35, 10, 65, 30, blackHair)
        fillZone(grid, 30, 35, 70, 80, darkBlueFabric)
        // Glutes zone with moderate skin
        fillZone(grid, 28, 86, 72, 130, fairSkin)

        // Initial frame grid to compute base luma
        val initialResult = VisualSkipAnalyzer.analyzePixelGrid(grid, width, height, null)
        val baseLuma = initialResult.newGluteLumaGrid

        // Second frame where glute luma has changed dramatically due to motion / twerking
        val movedGrid = grid.clone()
        fillZone(movedGrid, 28, 86, 72, 110, fairSkin)
        fillZone(movedGrid, 28, 111, 72, 130, rgb(250, 220, 200)) // lighter luma movement

        val motionResult = VisualSkipAnalyzer.analyzePixelGrid(movedGrid, width, height, baseLuma)
        assertTrue("Glute twerking / motion should trigger skip", motionResult.shouldSkip)
    }
}
