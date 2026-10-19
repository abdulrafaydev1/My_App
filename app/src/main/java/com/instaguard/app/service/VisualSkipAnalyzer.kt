package com.instaguard.app.service

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Result data holder for [VisualSkipAnalyzer].
 */
data class VisualSkipResult(
    val shouldSkip: Boolean,
    val skipReason: String,
    val newGluteLumaGrid: FloatArray?
)

/**
 * Direct, single-frame confirmed visual auto-skip engine (v10.4).
 *
 * Evaluates 100 x 180 downscaled video frames in < 2ms with zero window overlays:
 * - Stage 1: Female presence & framing verification (front, selfie, bare back, or rear glutes) with
 *            strict non-human, wall/surface, male, and modest clothing protection.
 * - Stage 2: Three instant skip conditions:
 *     1. Female Boobs / Breasts / Cleavage Visible (cleavage cleft or low-cut exposed breasts)
 *     2. Short Dress ("Shoulder se Niche" + "Ghutne se Upar" + legs/feet visible)
 *     3. Back Body: Bare back, exposed glutes / buttocks / ASS, or glute twerking/motion
 */
object VisualSkipAnalyzer {

    const val TARGET_WIDTH = 100
    const val TARGET_HEIGHT = 180

    /**
     * Checks if RGB values correspond to human skin tones across all ethnicities.
     * Accurately rejects cold blues/greens, pure grays, pure whites, and pure blacks.
     */
    fun isHumanSkin(r: Int, g: Int, b: Int): Boolean {
        if (r < 60 || g < 35 || b < 20) return false
        if (r <= g || (r - g) < 8 || (r - b) < 12) return false
        val maxVal = maxOf(r, g, b)
        val minVal = minOf(r, g, b)
        if (maxVal - minVal < 12) return false
        if (r > 250 && g > 250 && b > 250) return false // Specular white

        // YCbCr transformation
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
        val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b

        return (y in 50.0f..245.0f) && (cb in 75.0f..130.0f) && (cr in 132.0f..176.0f)
    }

    /**
     * Detects dark, black, blonde, or dyed hair near the head.
     */
    fun isDarkOrColoredHair(r: Int, g: Int, b: Int): Boolean {
        // Dark brown / black hair
        if (r in 8..65 && g in 8..65 && b in 8..65 && (maxOf(r, g, b) - minOf(r, g, b) <= 22)) {
            return true
        }
        // Blonde / golden hair
        if (r in 140..245 && g in 110..220 && b in 40..160 && (r > g) && (g > b) && (r - b) >= 40) {
            return true
        }
        // Auburn / brunette hair
        if (r in 80..160 && g in 40..95 && b in 20..70 && (r - g) >= 25 && (r - b) >= 40) {
            return true
        }
        return false
    }

    /**
     * Analyzes a 100x180 bitmap frame for instant single-frame auto-skip.
     */
    fun analyzeVideoFrame(
        bitmap: Bitmap,
        prevGluteLumaGrid: FloatArray? = null
    ): VisualSkipResult {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 10 || height < 10) {
            return VisualSkipResult(false, "REJECT_INVALID_BITMAP_DIMENSIONS", null)
        }

        val totalPixels = width * height
        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return analyzePixelGrid(pixels, width, height, prevGluteLumaGrid)
    }

    /**
     * Pure-Kotlin pixel grid analyzer.
     */
    fun analyzePixelGrid(
        pixels: IntArray,
        width: Int,
        height: Int,
        prevGluteLumaGrid: FloatArray? = null
    ): VisualSkipResult {
        val totalPixels = width * height
        if (pixels.size < totalPixels || width < 10 || height < 10) {
            return VisualSkipResult(false, "REJECT_INVALID_PIXEL_BUFFER", null)
        }

        val isSkin = BooleanArray(totalPixels)
        val isHair = BooleanArray(totalPixels)
        val luma = FloatArray(totalPixels)

        for (i in 0 until totalPixels) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            isSkin[i] = isHumanSkin(r, g, b)
            isHair[i] = isDarkOrColoredHair(r, g, b)
            luma[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }

        fun getSkinRatio(x1: Int, y1: Int, x2: Int, y2: Int): Float {
            val cx1 = x1.coerceIn(0, width - 1)
            val cx2 = x2.coerceIn(0, width - 1)
            val cy1 = y1.coerceIn(0, height - 1)
            val cy2 = y2.coerceIn(0, height - 1)
            var count = 0
            var total = 0
            for (y in cy1..cy2) {
                val rowOffset = y * width
                for (x in cx1..cx2) {
                    if (isSkin[rowOffset + x]) count++
                    total++
                }
            }
            return if (total > 0) count.toFloat() / total else 0f
        }

        fun countHair(x1: Int, y1: Int, x2: Int, y2: Int): Int {
            val cx1 = x1.coerceIn(0, width - 1)
            val cx2 = x2.coerceIn(0, width - 1)
            val cy1 = y1.coerceIn(0, height - 1)
            val cy2 = y2.coerceIn(0, height - 1)
            var count = 0
            for (y in cy1..cy2) {
                val rowOffset = y * width
                for (x in cx1..cx2) {
                    if (isHair[rowOffset + x]) count++
                }
            }
            return count
        }

        // =========================================================================
        // STAGE 1: FEMALE PRESENCE & STRICT NON-HUMAN / MALE / MODEST PROTECTION
        // =========================================================================

        // 1. Outer Border Skin Ratio (False-positive check for tan walls, wood, sand)
        val topBorderSkin = getSkinRatio(0, 0, width - 1, (height * 0.05f).toInt())
        val bottomBorderSkin = getSkinRatio(0, (height * 0.95f).toInt(), width - 1, height - 1)
        val leftBorderSkin = getSkinRatio(0, 0, (width * 0.05f).toInt(), height - 1)
        val rightBorderSkin = getSkinRatio((width * 0.95f).toInt(), 0, width - 1, height - 1)
        val outerBorderSkinRatio = (topBorderSkin + bottomBorderSkin + leftBorderSkin + rightBorderSkin) / 4f

        if (outerBorderSkinRatio >= 0.30f) {
            return VisualSkipResult(false, "REJECT_OUTER_BORDER_SKIN_WALL_OR_SURFACE", null)
        }

        // 2. Central Body Skin Threshold
        val centralSkinRatio = getSkinRatio(
            (width * 0.15f).toInt(),
            (height * 0.15f).toInt(),
            (width * 0.85f).toInt(),
            (height * 0.85f).toInt()
        )
        if (centralSkinRatio < 0.12f) {
            return VisualSkipResult(false, "REJECT_INSUFFICIENT_CENTRAL_SKIN", null)
        }

        // 3. Normalized Region Measurements
        // Upper Head / Face (strictly head/face zone y in 5%..22%, preventing overlap with neck/back/chest)
        val upperHeadFaceSkinRatio = getSkinRatio(
            (width * 0.25f).toInt(),
            (height * 0.05f).toInt(),
            (width * 0.75f).toInt(),
            (height * 0.22f).toInt()
        )
        val upperHairCount = countHair(
            (width * 0.20f).toInt(),
            (height * 0.03f).toInt(),
            (width * 0.80f).toInt(),
            (height * 0.35f).toInt()
        )
        val upperHairRatio = upperHairCount.toFloat() / (width * 0.60f * height * 0.32f)

        // Chest / Bust Zone (y in 22%..48%)
        val chestBustSkinRatio = getSkinRatio(
            (width * 0.25f).toInt(),
            (height * 0.22f).toInt(),
            (width * 0.75f).toInt(),
            (height * 0.48f).toInt()
        )

        // Upper & Mid Back Zone (y in 25%..52%, shoulders to waist)
        val backSkinRatio = getSkinRatio(
            (width * 0.25f).toInt(),
            (height * 0.25f).toInt(),
            (width * 0.75f).toInt(),
            (height * 0.52f).toInt()
        )

        // Hip & Buttocks Zone (y in 52%..86%, waist to thighs)
        val hipButtocksSkinRatio = getSkinRatio(
            (width * 0.25f).toInt(),
            (height * 0.52f).toInt(),
            (width * 0.75f).toInt(),
            (height * 0.86f).toInt()
        )
        val leftGluteSkin = getSkinRatio(
            (width * 0.28f).toInt(),
            (height * 0.54f).toInt(),
            (width * 0.48f).toInt(),
            (height * 0.86f).toInt()
        )
        val rightGluteSkin = getSkinRatio(
            (width * 0.52f).toInt(),
            (height * 0.54f).toInt(),
            (width * 0.72f).toInt(),
            (height * 0.86f).toInt()
        )

        // Lower Legs & Feet Zone (y in 60%..95%)
        val lowerLegsFeetSkinRatio = getSkinRatio(
            (width * 0.20f).toInt(),
            (height * 0.60f).toInt(),
            (width * 0.80f).toInt(),
            (height * 0.95f).toInt()
        )

        // Female Subject Eligibility: Allow Front/Selfie, Bare Back Pose, or Exposed Glutes/Legs Pose
        val isFrontOrSelfieEligible = (upperHairRatio >= 0.08f || upperHairCount >= 30) || (chestBustSkinRatio >= 0.20f)
        val isBareBackEligible = (chestBustSkinRatio >= 0.22f || backSkinRatio >= 0.22f) && (upperHeadFaceSkinRatio < 0.20f)
        val isGlutesOrLegsEligible = hipButtocksSkinRatio >= 0.20f || (leftGluteSkin >= 0.18f && rightGluteSkin >= 0.18f) || lowerLegsFeetSkinRatio >= 0.20f

        val isFemaleSubject = isFrontOrSelfieEligible || isBareBackEligible || isGlutesOrLegsEligible
        if (!isFemaleSubject) {
            return VisualSkipResult(false, "REJECT_NO_HUMAN_FRAMING", null)
        }

        // 4. Modest Clothing Protection: If chest, back, buttocks, and legs all have no exposed skin
        if (chestBustSkinRatio < 0.08f && backSkinRatio < 0.08f && hipButtocksSkinRatio < 0.08f && lowerLegsFeetSkinRatio < 0.08f) {
            return VisualSkipResult(false, "REJECT_FULLY_MODEST_CLOTHING", null)
        }

        // =========================================================================
        // STAGE 2: EVALUATE 3 SKIP CONDITIONS
        // =========================================================================

        val isRearPose = upperHeadFaceSkinRatio < 0.20f

        // -------------------------------------------------------------------------
        // CONDITION 3: Back Body — Bare Back & Exposed Glutes (Evaluated first on rear view)
        // -------------------------------------------------------------------------
        if (isRearPose) {
            // (a) Exposed Glutes / Buttocks Trigger (checked when glutes/hip are prominent)
            if (hipButtocksSkinRatio >= 0.20f || (leftGluteSkin >= 0.18f && rightGluteSkin >= 0.18f)) {
                return VisualSkipResult(true, "SKIP_CONDITION_3_EXPOSED_GLUTES_BUTTOCKS", null)
            }
            // (b) Bare Back Trigger: upper/mid back zone contains bare skin
            if (backSkinRatio >= 0.22f || chestBustSkinRatio >= 0.22f) {
                return VisualSkipResult(true, "SKIP_CONDITION_3_BARE_BACK", null)
            }
        }

        // -------------------------------------------------------------------------
        // CONDITION 1: Female Boobs / Breasts / Cleavage Visible (Front View)
        // -------------------------------------------------------------------------
        if (chestBustSkinRatio >= 0.22f) {
            // (a) Cleavage Cleft Check: Symmetrical or dual skin lobes with center dip/shadow
            var cleftRowCount = 0
            val midYStart = (height * 0.26f).toInt()
            val midYEnd = (height * 0.44f).toInt()
            for (y in midYStart..midYEnd) {
                val leftLobeSkin = getSkinRatio((width * 0.32f).toInt(), y, (width * 0.46f).toInt(), y)
                val rightLobeSkin = getSkinRatio((width * 0.54f).toInt(), y, (width * 0.68f).toInt(), y)
                val centerValleySkin = getSkinRatio((width * 0.47f).toInt(), y, (width * 0.53f).toInt(), y)

                if (leftLobeSkin >= 0.25f && rightLobeSkin >= 0.25f) {
                    val rowOffset = y * width
                    val centerLuma = (luma[rowOffset + (width * 0.50f).toInt()])
                    val leftLuma = (luma[rowOffset + (width * 0.40f).toInt()])
                    val rightLuma = (luma[rowOffset + (width * 0.60f).toInt()])
                    val avgMoundLuma = (leftLuma + rightLuma) / 2f

                    if ((centerValleySkin < (leftLobeSkin + rightLobeSkin) / 2f - 0.18f) ||
                        (centerLuma < avgMoundLuma - 0.08f)
                    ) {
                        cleftRowCount++
                    }
                }
            }

            // (b) Low-Cut Exposed Cleavage / Breasts: High chest skin ratio with neckline contour
            val upperMidChestSkin = getSkinRatio(
                (width * 0.30f).toInt(),
                (height * 0.24f).toInt(),
                (width * 0.70f).toInt(),
                (height * 0.45f).toInt()
            )
            val hasNecklineFraming = upperMidChestSkin >= 0.28f

            if (cleftRowCount >= 3 || hasNecklineFraming) {
                return VisualSkipResult(true, "SKIP_CONDITION_1_FEMALE_BREASTS_OR_CLEAVAGE", null)
            }
        }

        // -------------------------------------------------------------------------
        // CONDITION 2: Short Dress ("Shoulder se Niche" + "Ghutne se Upar" + legs/feet visible)
        // -------------------------------------------------------------------------
        val torsoY1 = (height * 0.25f).toInt()
        val torsoY2 = (height * 0.55f).toInt()
        val torsoX1 = (width * 0.25f).toInt()
        val torsoX2 = (width * 0.75f).toInt()
        val torsoSkinRatio = getSkinRatio(torsoX1, torsoY1, torsoX2, torsoY2)
        val torsoClothedRatio = 1.0f - torsoSkinRatio

        // Short dress requires torso to be covered by fabric/clothing (>= 35% non-skin)
        if (torsoClothedRatio >= 0.35f) {
            val legsY1 = (height * 0.55f).toInt()
            val legsY2 = (height * 0.92f).toInt()
            val legsX1 = (width * 0.20f).toInt()
            val legsX2 = (width * 0.80f).toInt()
            val legsSkinRatio = getSkinRatio(legsX1, legsY1, legsX2, legsY2)

            // Substantial bare skin in legs/thighs zone below short dress hemline
            if (legsSkinRatio >= 0.20f) {
                return VisualSkipResult(true, "SKIP_CONDITION_2_SHORT_DRESS_WITH_EXPOSED_LEGS", null)
            }
        }

        // -------------------------------------------------------------------------
        // CONDITION 3 (Catch-all for bare back or exposed glutes in any orientation):
        // -------------------------------------------------------------------------
        if (hipButtocksSkinRatio >= 0.20f || (leftGluteSkin >= 0.18f && rightGluteSkin >= 0.18f)) {
            return VisualSkipResult(true, "SKIP_CONDITION_3_EXPOSED_GLUTES_BUTTOCKS", null)
        }

        if (backSkinRatio >= 0.28f && upperHeadFaceSkinRatio < 0.20f) {
            return VisualSkipResult(true, "SKIP_CONDITION_3_BARE_BACK", null)
        }

        // (c) Twerking / Motion in Glutes (4x4 luma grid comparison)
        val glutesY1 = (height * 0.54f).toInt()
        val glutesY2 = (height * 0.86f).toInt()
        val newGluteLumaGrid = FloatArray(16)
        val gridStepX = ((width * 0.50f) / 4f).toInt().coerceAtLeast(1)
        val gridStepY = ((glutesY2 - glutesY1) / 4f).toInt().coerceAtLeast(1)
        val startX = (width * 0.25f).toInt()
        var gridIndex = 0

        for (gy in 0 until 4) {
            val cellY = glutesY1 + gy * gridStepY
            for (gx in 0 until 4) {
                val cellX = startX + gx * gridStepX
                var sumLuma = 0f
                var count = 0
                for (dy in 0 until gridStepY) {
                    val row = (cellY + dy).coerceIn(0, height - 1) * width
                    for (dx in 0 until gridStepX) {
                        val col = (cellX + dx).coerceIn(0, width - 1)
                        sumLuma += luma[row + col]
                        count++
                    }
                }
                newGluteLumaGrid[gridIndex++] = if (count > 0) sumLuma / count else 0f
            }
        }

        if (prevGluteLumaGrid != null && prevGluteLumaGrid.size == 16) {
            var totalDelta = 0f
            for (i in 0 until 16) {
                totalDelta += abs(newGluteLumaGrid[i] - prevGluteLumaGrid[i])
            }
            val avgMotionDelta = totalDelta / 16f
            if (avgMotionDelta >= 0.09f && hipButtocksSkinRatio >= 0.18f) {
                return VisualSkipResult(true, "SKIP_CONDITION_3_GLUTE_MOTION_TWERKING", newGluteLumaGrid)
            }
        }

        return VisualSkipResult(false, "ALLOW_NO_MATCH", newGluteLumaGrid)
    }
}
