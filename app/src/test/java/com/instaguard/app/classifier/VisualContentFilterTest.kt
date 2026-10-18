package com.instaguard.app.classifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit Tests for VisualContentClassifier (v9.4 Intelligent Visual Content Filter).
 * Covers:
 * - Calibrated RGB+HSV+YCbCr Skin Pixel Classification (Human skin vs non-skin/walls)
 * - Rule A: Short / Revealing Clothing
 * - Rule B: Chest / Cleavage Focused Framing
 * - Rule C: Rear-View & Buttocks Emphasis (Backless dresses)
 * - Rule D: Suggestive Movement & Temporal Frame Motion Delta
 * - Rule E: Aggregation & Multi-Rule Scoring
 * - Rule F: False-Positive Rejection (Ambient lighting & Fully-clothed protection)
 * - Explicit Adult Search Query Detection vs Medical/Educational Whitelist
 * - Adult Domain & Website Blocker
 * - Suggestive Reel UI Metadata / Caption / Hashtag Filters
 */
class VisualContentFilterTest {

    private fun rgb(r: Int, g: Int, b: Int): Int {
        return (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
    }

    // =========================================================================
    // 1. SKIN PIXEL CLASSIFIER TESTS
    // =========================================================================

    @Test
    fun testSkinPixelClassifier_PositiveSkinTones() {
        // Fair / Peach skin tone (RGB: 235, 185, 155)
        val fairSkin = rgb(235, 185, 155)
        assertTrue("Fair skin should be classified as skin", VisualContentClassifier.isSkinPixel(fairSkin))

        // Tan skin tone (RGB: 195, 140, 95)
        val tanSkin = rgb(195, 140, 95)
        assertTrue("Tan skin should be classified as skin", VisualContentClassifier.isSkinPixel(tanSkin))

        // Olive skin tone (RGB: 172, 139, 100)
        val oliveSkin = rgb(172, 139, 100)
        assertTrue("Olive skin should be classified as skin", VisualContentClassifier.isSkinPixel(oliveSkin))

        // Medium brown skin tone (RGB: 141, 85, 50)
        val brownSkin = rgb(141, 85, 50)
        assertTrue("Brown skin should be classified as skin", VisualContentClassifier.isSkinPixel(brownSkin))
    }

    @Test
    fun testSkinPixelClassifier_NegativeNonSkinTones() {
        // Pure Blue
        assertFalse("Pure blue should not be skin", VisualContentClassifier.isSkinPixel(rgb(0, 0, 255)))

        // Pure Green
        assertFalse("Pure green should not be skin", VisualContentClassifier.isSkinPixel(rgb(0, 255, 0)))

        // Pure White
        assertFalse("Pure white should not be skin", VisualContentClassifier.isSkinPixel(rgb(255, 255, 255)))

        // Pure Black
        assertFalse("Pure black should not be skin", VisualContentClassifier.isSkinPixel(rgb(0, 0, 0)))

        // Neutral Gray
        assertFalse("Neutral gray should not be skin", VisualContentClassifier.isSkinPixel(rgb(128, 128, 128)))

        // Deep red fabric
        assertFalse("Deep red clothing should not be skin", VisualContentClassifier.isSkinPixel(rgb(180, 20, 25)))
    }

    // =========================================================================
    // 2. EXPLICIT SEARCH QUERY VS MEDICAL/EDUCATIONAL WHITELIST
    // =========================================================================

    @Test
    fun testExplicitSearchQueryDetection() {
        val explicitQueries = listOf(
            "porn video",
            "xhamster streaming hd",
            "sexy female cleavage",
            "xxx movies",
            "nude photos of models",
            "chaturbate live room",
            "erotic dance video"
        )

        for (query in explicitQueries) {
            val (isMatch, reason) = VisualContentClassifier.evaluateSearchQuery(query, isAdultSearchEnabled = true)
            assertTrue("Query '$query' should be detected as explicit", isMatch)
            assertNotNull("Reason should not be null for '$query'", reason)
        }
    }

    @Test
    fun testMedicalAndEducationalWhitelistSafeguard() {
        val safeMedicalQueries = listOf(
            "breast cancer prevention and symptoms",
            "gynecology clinic near me",
            "human anatomy textbook for medical students",
            "breast feeding guidelines for new mothers",
            "dermatology examination of skin rash",
            "reproductive health educational seminar",
            "mammogram screening schedule"
        )

        for (query in safeMedicalQueries) {
            val (isMatch, reason) = VisualContentClassifier.evaluateSearchQuery(query, isAdultSearchEnabled = true)
            assertFalse("Medical query '$query' should NOT be blocked", isMatch)
            assertEquals("Reason should be null for whitelisted query '$query'", null, reason)
        }
    }

    @Test
    fun testHarmlessEverydaySearches() {
        val everydayQueries = listOf(
            "kotlin coroutines tutorial android",
            "accessibility service dispatch gesture guide",
            "how to cook authentic italian pasta",
            "weather forecast for tomorrow",
            "calculus differential equations notes"
        )

        for (query in everydayQueries) {
            val (isMatch, reason) = VisualContentClassifier.evaluateSearchQuery(query, isAdultSearchEnabled = true)
            assertFalse("Harmless query '$query' should NOT be blocked", isMatch)
            assertEquals(null, reason)
        }
    }

    // =========================================================================
    // 3. ADULT WEBSITE DOMAIN BLOCKER TESTS
    // =========================================================================

    @Test
    fun testAdultDomainBlocking() {
        val explicitUrls = listOf(
            "https://www.xhamster.com/videos/trending",
            "https://pornhub.com/view_video.php",
            "https://www.xvideos.com/hot",
            "https://chaturbate.com/female-cams"
        )

        for (url in explicitUrls) {
            val (isMatch, reason) = VisualContentClassifier.evaluateBrowserPage(url = url, pageText = "", isAdultWebsitesEnabled = true)
            assertTrue("Adult url '$url' should be blocked", isMatch)
            assertNotNull(reason)
        }
    }

    @Test
    fun testSafeWebsiteBrowsing() {
        val safeUrls = listOf(
            "https://developer.android.com/reference/android/accessibilityservice/AccessibilityService",
            "https://github.com/google/guava",
            "https://en.wikipedia.org/wiki/Computer_vision",
            "https://news.google.com"
        )

        for (url in safeUrls) {
            val (isMatch, reason) = VisualContentClassifier.evaluateBrowserPage(url = url, pageText = "Computer science research documentation", isAdultWebsitesEnabled = true)
            assertFalse("Safe url '$url' should NOT be blocked", isMatch)
            assertEquals(null, reason)
        }
    }

    // =========================================================================
    // 4. SUGGESTIVE REEL UI METADATA & HASHTAGS
    // =========================================================================

    @Test
    fun testSuggestiveReelMetadataDetection() {
        val suggestiveCaptions = listOf(
            "Check out my new #bikini try-on haul #sexy",
            "Summer vibes in a mini dress and deep cleavage",
            "Trending #twerk dance video",
            "Late night #thirsttrap reels",
            "Stunning backless dress outfit"
        )

        for (caption in suggestiveCaptions) {
            val (isMatch, indicator) = VisualContentClassifier.hasSuggestiveReelMetadata(caption)
            assertTrue("Caption '$caption' should match suggestive indicator", isMatch)
            assertNotNull(indicator)
        }

        val normalCaptions = listOf(
            "Learning Android development with Android Studio",
            "Beautiful sunset from mountain peak",
            "Recipe for homemade bread"
        )

        for (caption in normalCaptions) {
            val (isMatch, indicator) = VisualContentClassifier.hasSuggestiveReelMetadata(caption)
            assertFalse("Normal caption '$caption' should NOT match", isMatch)
            assertEquals(null, indicator)
        }
    }

    // =========================================================================
    // 5. VISUAL FRAME RULE EVALUATION TESTS (SYNTHESIZED 80x140 FRAMES)
    // =========================================================================

    private fun createSyntheticFrame(
        width: Int = 80,
        height: Int = 140,
        fillSkinRegions: (x: Int, y: Int) -> Boolean
    ): IntArray {
        val pixels = IntArray(width * height)
        val skinPixel = rgb(235, 185, 155) // Valid skin tone
        val darkBg = rgb(20, 24, 33)       // Dark non-skin background

        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (fillSkinRegions(x, y)) skinPixel else darkBg
            }
        }
        return pixels
    }

    @Test
    fun testRuleA_ShortClothingExposureDetected() {
        val width = 80
        val height = 140

        // Lower glute/thigh region: y between 72% and 90% (y = 101..126)
        // Center column: x between 20% and 80% (x = 16..64)
        // Mid torso region: y between 56% and 72% (y = 79..100) -> small amount (10%)
        val pixels = createSyntheticFrame(width, height) { x, y ->
            val inCenter = x in 24..56
            val inLowerThigh = y in 102..125
            val inSmallMid = y in 95..98 && x in 35..45
            inCenter && (inLowerThigh || inSmallMid)
        }

        val (result, _) = VisualContentClassifier.evaluatePixelGrid(
            pixels = pixels,
            width = width,
            height = height,
            isStrict = false,
            ruleAEnabled = true,
            ruleBEnabled = true,
            ruleCEnabled = true,
            ruleDEnabled = true
        )

        assertTrue("Rule A should be detected for exposed thighs / short clothing", result.ruleADetected)
        assertTrue("Frame should be marked as match", result.isMatch)
    }

    @Test
    fun testRuleB_ChestAndCleavageFramingDetected() {
        val width = 80
        val height = 140

        // Chest region: y between 34% and 56% (y = 48..78)
        // Mid-torso covered by dark clothing: y between 56% and 72% (y = 79..100)
        val pixels = createSyntheticFrame(width, height) { x, y ->
            val inCenter = x in 26..54
            val inChest = y in 50..76
            inCenter && inChest
        }

        val (result, _) = VisualContentClassifier.evaluatePixelGrid(
            pixels = pixels,
            width = width,
            height = height,
            isStrict = false,
            ruleAEnabled = true,
            ruleBEnabled = true,
            ruleCEnabled = true,
            ruleDEnabled = true
        )

        assertTrue("Rule B should be detected for high chest exposure with covered mid-torso", result.ruleBDetected)
        assertTrue("Frame should be marked as match", result.isMatch)
    }

    @Test
    fun testRuleC_RearViewBacklessExposureDetected() {
        val width = 80
        val height = 140

        // Upper back and mid-torso exposed (chest + midTorso >= 0.44f): y = 48..98
        val pixels = createSyntheticFrame(width, height) { x, y ->
            val inCenter = x in 24..56
            val inBack = y in 49..96
            inCenter && inBack
        }

        val (result, _) = VisualContentClassifier.evaluatePixelGrid(
            pixels = pixels,
            width = width,
            height = height,
            isStrict = false,
            ruleAEnabled = true,
            ruleBEnabled = true,
            ruleCEnabled = true,
            ruleDEnabled = true
        )

        assertTrue("Rule C should be detected for high backless dress exposure", result.ruleCDetected)
        assertTrue("Frame should be marked as match", result.isMatch)
    }

    @Test
    fun testRuleF_AmbientWallLightingRejection() {
        val width = 80
        val height = 140

        // Entire frame (including outer borders x < 10% and x > 90%) filled with skin-colored wall
        val pixels = createSyntheticFrame(width, height) { _, _ -> true }

        val (result, _) = VisualContentClassifier.evaluatePixelGrid(
            pixels = pixels,
            width = width,
            height = height,
            isStrict = false
        )

        assertFalse("Full screen warm wall lighting should be rejected by Rule F", result.isMatch)
        assertEquals("Outer ambient lighting rejected", result.primaryReason)
    }

    @Test
    fun testRuleF_FullyClothedPersonProtection() {
        val width = 80
        val height = 140

        // Face only: y between 12% and 33% (y = 17..46)
        // Chest, mid-torso, and lower body are fully covered by dark clothing
        val pixels = createSyntheticFrame(width, height) { x, y ->
            val inCenter = x in 32..48
            val inFace = y in 18..44
            inCenter && inFace
        }

        val (result, _) = VisualContentClassifier.evaluatePixelGrid(
            pixels = pixels,
            width = width,
            height = height,
            isStrict = false
        )

        assertFalse("Normal video with talking face and fully clothed body must NOT be skipped", result.isMatch)
        assertEquals("Fully-clothed person confirmed", result.primaryReason)
    }
}
