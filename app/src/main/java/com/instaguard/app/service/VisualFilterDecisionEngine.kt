package com.instaguard.app.service

import android.graphics.Bitmap
import android.graphics.Color
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class DecisionState {
    ALLOW,
    SKIP,
    PENDING_ANALYSIS,
    ANALYSIS_ERROR
}

enum class DiagnosticStatus(val label: String) {
    PERMISSION_UNAVAILABLE("ANALYSIS UNAVAILABLE — NO PERMISSION"),
    WAITING_FOR_FRAMES("WAITING FOR FRAMES"),
    MODEL_NOT_INITIALIZED("MODEL NOT INITIALIZED"),
    FRAME_ANALYSIS_IN_PROGRESS("SCANNING..."),
    PERSON_DETECTED("PERSON DETECTED"),
    NO_QUALIFYING_PERSON_DETECTED("NO MATCH — ALLOW (NO TARGET PERSON)"),
    ANALYZING_CLOTHING("ANALYZING CLOTHING"),
    CHECKING_HEMLINE("CHECKING HEMLINE"),
    EVALUATING_PRESENTATION("EVALUATING PRESENTATION"),
    ANALYZING_MOVEMENT("ANALYZING MOVEMENT"),
    NO_RULE_MATCHED("NO MATCH — ALLOW"),
    RULE_MATCHED_SKIP("MATCH CONFIRMED — SKIP"),
    SKIP_ACTION_REQUESTED("SKIPPING VIDEO..."),
    NAVIGATION_VERIFIED("NAVIGATION VERIFIED"),
    TRACKING_LOST("TRACKING LOST"),
    OVERLAY_UNAVAILABLE("OVERLAY UNAVAILABLE"),
    ANALYSIS_FAILED("ANALYSIS UNAVAILABLE")
}

enum class FilterRuleCategory {
    RULE_A_SHORT_REVEALING_DRESS,
    RULE_B_REVEALING_CHEST,
    RULE_C_REAR_BUTTOCKS_PRESENTATION,
    RULE_D_SUGGESTIVE_MOVEMENT
}

data class NormalizedBox(
    val trackId: Int,
    val regionLabel: String, // "BODY", "FACE", "CHEST", "BACK", "BUTTOCKS", "HEMLINE", "FEET"
    val confidence: Float,   // 0.0f .. 1.0f
    val left: Float,         // 0.0f .. 1.0f
    val top: Float,
    val right: Float,
    val bottom: Float,
    val timestampMs: Long
)

data class FrameFeatures(
    val contentItemId: String,
    val frameTimestampMs: Long,
    val isSupportedVideoFeed: Boolean,
    val analysisFailed: Boolean = false,
    val outerBorderSkinRatio: Float = 0f,
    val qualifyingPersonDetected: Boolean = false,
    val personConfidence: Float = 0f,
    val isMalePresentingOnly: Boolean = false,
    val clothingAnalysisComplete: Boolean = true,
    val shoulderUncoveredConfidence: Float = 0f,
    val hemlineAboveKneeConfidence: Float = 0f,
    val chestExposureConfidence: Float = 0f,
    val rearButtocksExposureConfidence: Float = 0f,
    val singleFrameMovementScore: Float = 0f,
    val detectedBoxes: List<NormalizedBox> = emptyList()
)

data class FilterConfig(
    val monitoringEnabled: Boolean = true,
    val shortDressThreshold: Float = 0.78f,
    val chestExposureThreshold: Float = 0.78f,
    val rearButtocksThreshold: Float = 0.80f,
    val movementThreshold: Float = 0.76f,
    val minPersonConfidence: Float = 0.75f,
    val requiredStaticConfirmFrames: Int = 2,
    val requiredMovementConfirmFrames: Int = 3,
    val maxFrameAgeMs: Long = 2500L
)

data class DecisionOutput(
    val state: DecisionState,
    val diagnosticStatus: DiagnosticStatus,
    val matchedRules: Set<FilterRuleCategory> = emptySet(),
    val primaryConfidence: Float = 0f,
    val contentItemId: String = "",
    val frameTimestampMs: Long = 0L,
    val activeBoxes: List<NormalizedBox> = emptyList()
)

/**
 * Authoritative 6-Stage Fail-Open Visual Filter Decision Engine (v10.0).
 * Deterministic, item-bound, and free of Android UI thread dependencies for full unit testing.
 */
class VisualFilterDecisionEngine(
    var config: FilterConfig = FilterConfig()
) {
    var currentItemId: String = ""
        private set

    var lastFrameTimestampMs: Long = -1L
        private set

    var staticMatchStreak: Int = 0
        private set

    var movementMatchStreak: Int = 0
        private set

    val recentPersonFrames: ArrayDeque<Boolean> = ArrayDeque()

    var skipIssuedForCurrentItem: Boolean = false
        private set

    @Synchronized
    fun resetForNewItem(newItemId: String) {
        currentItemId = newItemId
        lastFrameTimestampMs = -1L
        staticMatchStreak = 0
        movementMatchStreak = 0
        recentPersonFrames.clear()
        skipIssuedForCurrentItem = false
    }

    @Synchronized
    fun evaluateFrame(
        features: FrameFeatures,
        activeConfig: FilterConfig = this.config,
        nowMs: Long = features.frameTimestampMs
    ): DecisionOutput {
        // Bind to content item: reset immediately if contentItemId changed so prior video never bleeds over
        if (features.contentItemId != currentItemId) {
            resetForNewItem(features.contentItemId)
        }

        // Stage 1: Active Video Context Gate
        if (!activeConfig.monitoringEnabled || !features.isSupportedVideoFeed) {
            return DecisionOutput(
                state = DecisionState.ALLOW,
                diagnosticStatus = DiagnosticStatus.WAITING_FOR_FRAMES,
                contentItemId = features.contentItemId,
                frameTimestampMs = features.frameTimestampMs,
                activeBoxes = emptyList()
            )
        }

        // Stage 2: Frame Validity, Chronological Order & Fail-Open Error Check
        if (features.analysisFailed) {
            return DecisionOutput(
                state = DecisionState.ANALYSIS_ERROR,
                diagnosticStatus = DiagnosticStatus.ANALYSIS_FAILED,
                contentItemId = features.contentItemId,
                frameTimestampMs = features.frameTimestampMs,
                activeBoxes = emptyList()
            )
        }

        if ((lastFrameTimestampMs >= 0L && features.frameTimestampMs <= lastFrameTimestampMs) ||
            (nowMs - features.frameTimestampMs > activeConfig.maxFrameAgeMs)
        ) {
            return DecisionOutput(
                state = DecisionState.ALLOW,
                diagnosticStatus = DiagnosticStatus.WAITING_FOR_FRAMES,
                contentItemId = features.contentItemId,
                frameTimestampMs = features.frameTimestampMs,
                activeBoxes = emptyList()
            )
        }
        lastFrameTimestampMs = features.frameTimestampMs

        // Stage 3: Qualifying Female-Presenting Person Eligibility Gate
        val personEligible = features.qualifyingPersonDetected &&
            !features.isMalePresentingOnly &&
            features.personConfidence >= activeConfig.minPersonConfidence &&
            features.outerBorderSkinRatio < 0.22f

        recentPersonFrames.addLast(personEligible)
        while (recentPersonFrames.size > 5) {
            recentPersonFrames.removeFirst()
        }

        if (!personEligible) {
            staticMatchStreak = 0
            movementMatchStreak = 0
            return DecisionOutput(
                state = DecisionState.ALLOW,
                diagnosticStatus = DiagnosticStatus.NO_QUALIFYING_PERSON_DETECTED,
                contentItemId = features.contentItemId,
                frameTimestampMs = features.frameTimestampMs,
                activeBoxes = emptyList()
            )
        }

        // Stage 4: Pending Clothing Analysis Check
        if (!features.clothingAnalysisComplete) {
            return DecisionOutput(
                state = DecisionState.PENDING_ANALYSIS,
                diagnosticStatus = DiagnosticStatus.ANALYZING_CLOTHING,
                primaryConfidence = features.personConfidence,
                contentItemId = features.contentItemId,
                frameTimestampMs = features.frameTimestampMs,
                activeBoxes = features.detectedBoxes
            )
        }

        // Stage 5: Independent Rule Evaluation
        val matchedRules = mutableSetOf<FilterRuleCategory>()
        var maxConfidence = 0f

        // Rule A: Short / Revealing Dress (Composite Rule: BOTH shoulder uncovered AND hemline above knee)
        if (features.shoulderUncoveredConfidence >= activeConfig.shortDressThreshold &&
            features.hemlineAboveKneeConfidence >= activeConfig.shortDressThreshold
        ) {
            matchedRules.add(FilterRuleCategory.RULE_A_SHORT_REVEALING_DRESS)
            val ruleAConf = (features.shoulderUncoveredConfidence + features.hemlineAboveKneeConfidence) * 0.5f
            if (ruleAConf > maxConfidence) maxConfidence = ruleAConf
        }

        // Rule B: Revealing Chest Presentation
        if (features.chestExposureConfidence >= activeConfig.chestExposureThreshold) {
            matchedRules.add(FilterRuleCategory.RULE_B_REVEALING_CHEST)
            if (features.chestExposureConfidence > maxConfidence) {
                maxConfidence = features.chestExposureConfidence
            }
        }

        // Rule C: Rear-View / Buttocks Presentation
        if (features.rearButtocksExposureConfidence >= activeConfig.rearButtocksThreshold) {
            matchedRules.add(FilterRuleCategory.RULE_C_REAR_BUTTOCKS_PRESENTATION)
            if (features.rearButtocksExposureConfidence > maxConfidence) {
                maxConfidence = features.rearButtocksExposureConfidence
            }
        }

        // Update Static Rule Streak (Rules A, B, C)
        val hasStaticMatch = matchedRules.isNotEmpty()
        if (hasStaticMatch) {
            staticMatchStreak++
        } else {
            staticMatchStreak = 0
        }

        // Rule D: Suggestive Movement (Multi-Frame Temporal Rule)
        val hasBodyRuleContext = features.chestExposureConfidence >= 0.60f ||
            features.rearButtocksExposureConfidence >= 0.60f ||
            features.hemlineAboveKneeConfidence >= 0.60f

        if (features.singleFrameMovementScore >= activeConfig.movementThreshold && hasBodyRuleContext) {
            movementMatchStreak++
        } else {
            movementMatchStreak = 0
        }

        if (movementMatchStreak >= activeConfig.requiredMovementConfirmFrames) {
            matchedRules.add(FilterRuleCategory.RULE_D_SUGGESTIVE_MOVEMENT)
            if (features.singleFrameMovementScore > maxConfidence) {
                maxConfidence = features.singleFrameMovementScore
            }
        }

        // Stage 6: Final Authoritative Decision & Duplicate-Skip Prevention
        val confirmedMatch = (staticMatchStreak >= activeConfig.requiredStaticConfirmFrames) ||
            (movementMatchStreak >= activeConfig.requiredMovementConfirmFrames)

        if (confirmedMatch) {
            if (skipIssuedForCurrentItem) {
                return DecisionOutput(
                    state = DecisionState.ALLOW,
                    diagnosticStatus = DiagnosticStatus.NAVIGATION_VERIFIED,
                    matchedRules = matchedRules,
                    primaryConfidence = maxConfidence,
                    contentItemId = features.contentItemId,
                    frameTimestampMs = features.frameTimestampMs,
                    activeBoxes = features.detectedBoxes
                )
            }
            skipIssuedForCurrentItem = true
            return DecisionOutput(
                state = DecisionState.SKIP,
                diagnosticStatus = DiagnosticStatus.RULE_MATCHED_SKIP,
                matchedRules = matchedRules,
                primaryConfidence = maxConfidence,
                contentItemId = features.contentItemId,
                frameTimestampMs = features.frameTimestampMs,
                activeBoxes = features.detectedBoxes
            )
        }

        if (staticMatchStreak == 1 ||
            (movementMatchStreak in 1 until activeConfig.requiredMovementConfirmFrames)
        ) {
            return DecisionOutput(
                state = DecisionState.PENDING_ANALYSIS,
                diagnosticStatus = DiagnosticStatus.EVALUATING_PRESENTATION,
                matchedRules = matchedRules,
                primaryConfidence = max(maxConfidence, features.personConfidence),
                contentItemId = features.contentItemId,
                frameTimestampMs = features.frameTimestampMs,
                activeBoxes = features.detectedBoxes
            )
        }

        return DecisionOutput(
            state = DecisionState.ALLOW,
            diagnosticStatus = DiagnosticStatus.NO_RULE_MATCHED,
            matchedRules = emptySet(),
            primaryConfidence = features.personConfidence,
            contentItemId = features.contentItemId,
            frameTimestampMs = features.frameTimestampMs,
            activeBoxes = features.detectedBoxes
        )
    }
}

/**
 * Pure-Kotlin Anatomical Region & Clothing Coverage Pixel Analyzer (v10.0).
 * Scans a downscaled 100x180 bitmap on the background analyzer thread.
 */
object FramePixelExtractor {

    fun extractFeaturesFromBitmap(
        bitmap: Bitmap,
        contentItemId: String,
        timestampMs: Long,
        isSupportedFeed: Boolean,
        prevLumaGrid: IntArray?
    ): Pair<FrameFeatures, IntArray> {
        val w = 100
        val h = 180
        val scaled = if (bitmap.width == w && bitmap.height == h) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        }
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) {
            scaled.recycle()
        }
        return extractFeaturesFromPixels(
            pixels = pixels,
            w = w,
            h = h,
            contentItemId = contentItemId,
            timestampMs = timestampMs,
            isSupportedFeed = isSupportedFeed,
            prevLumaGrid = prevLumaGrid
        )
    }

    fun extractFeaturesFromPixels(
        pixels: IntArray,
        w: Int,
        h: Int,
        contentItemId: String,
        timestampMs: Long,
        isSupportedFeed: Boolean,
        prevLumaGrid: IntArray?
    ): Pair<FrameFeatures, IntArray> {
        val total = w * h
        if (pixels.size < total) {
            return Pair(
                FrameFeatures(
                    contentItemId = contentItemId,
                    frameTimestampMs = timestampMs,
                    isSupportedVideoFeed = isSupportedFeed,
                    analysisFailed = true
                ),
                prevLumaGrid ?: IntArray(0)
            )
        }

        val isSkin = BooleanArray(total)
        val isHair = BooleanArray(total)
        val lumaGrid = IntArray(total)

        for (i in 0 until total) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val y = ((0.299f * r) + (0.587f * g) + (0.114f * b)).toInt()
            lumaGrid[i] = y

            val skin = isHumanSkinRgbYCbCr(r, g, b, y)
            isSkin[i] = skin
            isHair[i] = !skin && isDarkHairPixel(r, g, b, y)
        }

        // 1. Outer 8% Border Skin Ratio (rejects warm walls, desert landscapes, wood backgrounds)
        val borderMarginX = max(1, (w * 0.08f).toInt())
        val borderMarginY = max(1, (h * 0.06f).toInt())
        var borderSkinPx = 0
        var borderTotalPx = 0
        for (y in 0 until h) {
            val row = y * w
            val isTopOrBottomEdge = y < borderMarginY || y >= (h - borderMarginY)
            for (x in 0 until w) {
                if (isTopOrBottomEdge || x < borderMarginX || x >= (w - borderMarginX)) {
                    borderTotalPx++
                    if (isSkin[row + x]) borderSkinPx++
                }
            }
        }
        val outerBorderSkinRatio = borderSkinPx.toFloat() / max(1, borderTotalPx)

        // 2. Head / Face / Hair Region (y = 12%..38%, x = 14%..86%)
        val headY0 = (h * 0.12f).toInt()
        val headY1 = (h * 0.38f).toInt()
        val scanX0 = (w * 0.14f).toInt()
        val scanX1 = (w * 0.86f).toInt()

        var headSkinPx = 0
        var headHairPx = 0
        var headTotalPx = 0
        var headSumX = 0L
        var headSumY = 0L
        var minHeadX = w
        var maxHeadX = 0
        var minHeadY = h
        var maxHeadY = 0

        for (y in headY0 until headY1) {
            val row = y * w
            for (x in scanX0 until scanX1) {
                headTotalPx++
                val idx = row + x
                if (isSkin[idx] || isHair[idx]) {
                    if (isSkin[idx]) headSkinPx++ else headHairPx++
                    headSumX += x
                    headSumY += y
                    if (x < minHeadX) minHeadX = x
                    if (x > maxHeadX) maxHeadX = x
                    if (y < minHeadY) minHeadY = y
                    if (y > maxHeadY) maxHeadY = y
                }
            }
        }

        val headCombinedPx = headSkinPx + headHairPx
        val headDensity = headCombinedPx.toFloat() / max(1, headTotalPx)
        val hasHeadRegion = headDensity >= 0.13f && headCombinedPx >= 28 && (headSkinPx >= 8 || headHairPx >= 18)

        // Check lower-jaw beard shadow vs long lateral hair frame (y = 26%..38%)
        var jawDarkPx = 0
        var jawSkinPx = 0
        var sideHairPx = 0
        val jawY0 = (h * 0.25f).toInt()
        val jawY1 = (h * 0.37f).toInt()
        for (y in jawY0 until jawY1) {
            val row = y * w
            for (x in (w * 0.35f).toInt() until (w * 0.65f).toInt()) {
                if (isHair[row + x]) jawDarkPx++
                if (isSkin[row + x]) jawSkinPx++
            }
            for (x in (w * 0.18f).toInt() until (w * 0.35f).toInt()) {
                if (isHair[row + x]) sideHairPx++
            }
            for (x in (w * 0.65f).toInt() until (w * 0.82f).toInt()) {
                if (isHair[row + x]) sideHairPx++
            }
        }
        val hasLongHairFrame = sideHairPx >= 22 || headHairPx >= 35
        val hasBeardJawShadow = jawDarkPx >= 18 && jawSkinPx >= 10 && sideHairPx < 12

        // Determine subject horizontal center
        val torsoCenterX: Float = if (hasHeadRegion && headCombinedPx > 0) {
            ((headSumX / headCombinedPx).toFloat() / w).coerceIn(0.25f, 0.75f)
        } else {
            var sumX = 0L
            var cnt = 0
            for (y in (h * 0.35f).toInt() until (h * 0.72f).toInt()) {
                val row = y * w
                for (x in scanX0 until scanX1) {
                    if (isSkin[row + x]) {
                        sumX += x
                        cnt++
                    }
                }
            }
            if (cnt >= 20) ((sumX / cnt).toFloat() / w).coerceIn(0.25f, 0.75f) else 0.50f
        }

        // 3. Chest / Shoulder / Neckline Region (y = 35%..56%)
        val chestLeft = (torsoCenterX - 0.17f).coerceIn(0.08f, 0.58f)
        val chestRight = (torsoCenterX + 0.17f).coerceIn(chestLeft + 0.24f, 0.92f)
        val cX0 = (chestLeft * w).toInt()
        val cX1 = (chestRight * w).toInt()
        val cY0 = (h * 0.35f).toInt()
        val cY1 = (h * 0.56f).toInt()

        var chestSkinPx = 0
        var chestTotalPx = 0
        var shoulderSkinPx = 0
        var shoulderTotalPx = 0
        val shoulderY1 = (h * 0.43f).toInt()

        var leftLumaSum = 0L
        var leftLumaCnt = 0
        var rightLumaSum = 0L
        var rightLumaCnt = 0
        var cleftLumaSum = 0L
        var cleftLumaCnt = 0
        val midX = (cX0 + cX1) / 2
        val cleftHalfW = max(1, ((cX1 - cX0) * 0.11f).toInt())

        for (y in cY0 until cY1) {
            val row = y * w
            for (x in cX0 until cX1) {
                chestTotalPx++
                val skin = isSkin[row + x]
                if (skin) chestSkinPx++
                if (y < shoulderY1) {
                    shoulderTotalPx++
                    if (skin) shoulderSkinPx++
                }
                val luma = lumaGrid[row + x]
                if (abs(x - midX) <= cleftHalfW) {
                    cleftLumaSum += luma
                    cleftLumaCnt++
                } else if (x < midX) {
                    leftLumaSum += luma
                    leftLumaCnt++
                } else {
                    rightLumaSum += luma
                    rightLumaCnt++
                }
            }
        }

        val chestSkinRatio = chestSkinPx.toFloat() / max(1, chestTotalPx)
        val shoulderSkinRatio = shoulderSkinPx.toFloat() / max(1, shoulderTotalPx)
        val avgLeftLuma = leftLumaSum.toFloat() / max(1, leftLumaCnt)
        val avgRightLuma = rightLumaSum.toFloat() / max(1, rightLumaCnt)
        val avgCleftLuma = cleftLumaSum.toFloat() / max(1, cleftLumaCnt)
        val cleavageShadowDelta = ((avgLeftLuma + avgRightLuma) * 0.5f) - avgCleftLuma
        val hasCleavageShadow = cleavageShadowDelta >= 5.5f && chestSkinRatio >= 0.20f

        // 4. Back / Waist / Midriff Region (y = 48%..68%)
        val waistLeft = (torsoCenterX - 0.15f).coerceIn(0.08f, 0.60f)
        val waistRight = (torsoCenterX + 0.15f).coerceIn(waistLeft + 0.22f, 0.92f)
        val wX0 = (waistLeft * w).toInt()
        val wX1 = (waistRight * w).toInt()
        val wY0 = (h * 0.48f).toInt()
        val wY1 = (h * 0.68f).toInt()

        var waistSkinPx = 0
        var waistTotalPx = 0
        var waistMaxRowWidth = 0
        for (y in wY0 until wY1) {
            val row = y * w
            var rowW = 0
            for (x in wX0 until wX1) {
                waistTotalPx++
                if (isSkin[row + x]) {
                    waistSkinPx++
                    rowW++
                }
            }
            if (rowW > waistMaxRowWidth) waistMaxRowWidth = rowW
        }
        val waistSkinRatio = waistSkinPx.toFloat() / max(1, waistTotalPx)

        // 5. Buttocks / Hip Region (y = 58%..82%)
        val hipLeft = (torsoCenterX - 0.19f).coerceIn(0.06f, 0.56f)
        val hipRight = (torsoCenterX + 0.19f).coerceIn(hipLeft + 0.28f, 0.94f)
        val hX0 = (hipLeft * w).toInt()
        val hX1 = (hipRight * w).toInt()
        val hY0 = (h * 0.58f).toInt()
        val hY1 = (h * 0.82f).toInt()

        var hipSkinPx = 0
        var hipTotalPx = 0
        var hipMaxRowWidth = 0
        for (y in hY0 until hY1) {
            val row = y * w
            var rowW = 0
            for (x in hX0 until hX1) {
                hipTotalPx++
                if (isSkin[row + x]) {
                    hipSkinPx++
                    rowW++
                }
            }
            if (rowW > hipMaxRowWidth) hipMaxRowWidth = rowW
        }
        val hipSkinRatio = hipSkinPx.toFloat() / max(1, hipTotalPx)
        val hasGluteCurvature = hipMaxRowWidth >= 10 && hipMaxRowWidth > (waistMaxRowWidth * 1.15f)

        // 6. Lower Legs / Hemline / Feet Region (y = 74%..92%)
        val legLeft = (torsoCenterX - 0.16f).coerceIn(0.08f, 0.58f)
        val legRight = (torsoCenterX + 0.16f).coerceIn(legLeft + 0.22f, 0.92f)
        val lX0 = (legLeft * w).toInt()
        val lX1 = (legRight * w).toInt()
        val lY0 = (h * 0.74f).toInt()
        val lY1 = (h * 0.92f).toInt()

        var legSkinPx = 0
        var legTotalPx = 0
        for (y in lY0 until lY1) {
            val row = y * w
            for (x in lX0 until lX1) {
                legTotalPx++
                if (isSkin[row + x]) legSkinPx++
            }
        }
        val legSkinRatio = legSkinPx.toFloat() / max(1, legTotalPx)

        // 7. Frame-to-Frame Movement Score inside Torso/Hip Region
        var movementDiffSum = 0L
        var movementPxCnt = 0
        if (prevLumaGrid != null && prevLumaGrid.size == total) {
            for (y in cY0 until hY1) {
                val row = y * w
                for (x in hX0 until hX1) {
                    val idx = row + x
                    val diff = abs(lumaGrid[idx] - prevLumaGrid[idx])
                    if (diff >= 18) movementDiffSum++
                    movementPxCnt++
                }
            }
        }
        val rawMotionFraction = if (movementPxCnt > 0) movementDiffSum.toFloat() / movementPxCnt else 0f
        val singleFrameMovementScore = (rawMotionFraction / 0.14f).coerceIn(0f, 0.99f)

        // 8. Subject & Male-Only vs Female-Presenting Classification
        val hasUpperBodyPresence = hasHeadRegion || chestSkinRatio >= 0.18f || waistSkinRatio >= 0.18f
        val hasLowerOrTorsoPresence = chestSkinRatio >= 0.12f || waistSkinRatio >= 0.12f || hipSkinRatio >= 0.14f || legSkinRatio >= 0.14f
        val hasBoundedHumanSubject = outerBorderSkinRatio < 0.22f && hasUpperBodyPresence && hasLowerOrTorsoPresence

        val isMalePresentingOnly = hasBoundedHumanSubject &&
            hasBeardJawShadow &&
            !hasLongHairFrame &&
            !hasCleavageShadow &&
            !hasGluteCurvature &&
            chestSkinRatio < 0.16f &&
            legSkinRatio < 0.15f

        val isRearViewPresentation = hasBoundedHumanSubject &&
            (headHairPx > headSkinPx * 1.2f) &&
            !hasCleavageShadow &&
            (waistSkinRatio >= 0.14f || hipSkinRatio >= 0.16f || hasGluteCurvature)

        val qualifyingPersonDetected = hasBoundedHumanSubject &&
            !isMalePresentingOnly &&
            (hasLongHairFrame || hasCleavageShadow || hasGluteCurvature || isRearViewPresentation ||
                chestSkinRatio >= 0.16f || waistSkinRatio >= 0.16f || legSkinRatio >= 0.16f)

        val personConfidence = if (qualifyingPersonDetected) {
            val base = 0.82f + min(0.16f, (chestSkinRatio + headDensity + hipSkinRatio) * 0.22f)
            base.coerceIn(0.84f, 0.99f)
        } else {
            0f
        }

        // 9. Compute Rule Confidence Scores (0.0f .. 0.99f)
        val shoulderUncoveredConfidence = when {
            shoulderSkinRatio >= 0.28f || chestSkinRatio >= 0.26f ->
                (0.79f + (max(shoulderSkinRatio, chestSkinRatio) - 0.25f) * 0.55f).coerceIn(0.79f, 0.98f)
            shoulderSkinRatio >= 0.18f ->
                (0.55f + shoulderSkinRatio * 0.9f).coerceIn(0.55f, 0.77f)
            else -> (shoulderSkinRatio * 2.2f).coerceIn(0f, 0.50f)
        }

        val hemlineAboveKneeConfidence = when {
            legSkinRatio >= 0.20f || hipSkinRatio >= 0.24f ->
                (0.79f + (max(legSkinRatio, hipSkinRatio) - 0.20f) * 0.55f).coerceIn(0.79f, 0.98f)
            legSkinRatio >= 0.12f ->
                (0.50f + legSkinRatio * 1.4f).coerceIn(0.50f, 0.76f)
            else -> (legSkinRatio * 2.0f).coerceIn(0f, 0.45f)
        }

        val chestExposureConfidence = when {
            !isRearViewPresentation && ((chestSkinRatio >= 0.24f && hasCleavageShadow) || chestSkinRatio >= 0.33f || (chestSkinRatio >= 0.22f && waistSkinRatio >= 0.20f)) ->
                (0.80f + (chestSkinRatio * 0.45f) + (if (hasCleavageShadow) 0.06f else 0f)).coerceIn(0.80f, 0.98f)
            chestSkinRatio >= 0.16f ->
                (0.52f + chestSkinRatio * 0.95f).coerceIn(0.52f, 0.76f)
            else -> (chestSkinRatio * 2.0f).coerceIn(0f, 0.48f)
        }

        val rearButtocksExposureConfidence = when {
            isRearViewPresentation && (hipSkinRatio >= 0.22f || hasGluteCurvature || waistSkinRatio >= 0.24f) ->
                (0.82f + (hipSkinRatio * 0.40f)).coerceIn(0.82f, 0.97f)
            hipSkinRatio >= 0.28f && (hasGluteCurvature || legSkinRatio >= 0.20f) ->
                (0.81f + (hipSkinRatio * 0.38f)).coerceIn(0.81f, 0.96f)
            else -> (hipSkinRatio * 1.8f).coerceIn(0f, 0.65f)
        }

        // 10. Build Normalized Bounding Boxes ONLY for Regions Actually Detected
        val boxes = ArrayList<NormalizedBox>(6)
        if (qualifyingPersonDetected) {
            val bodyLeft = (torsoCenterX - 0.26f).coerceIn(0.06f, 0.44f)
            val bodyRight = (torsoCenterX + 0.26f).coerceIn(bodyLeft + 0.40f, 0.94f)
            val bodyTop = if (hasHeadRegion) (minHeadY.toFloat() / h).coerceIn(0.08f, 0.24f) else 0.16f
            val bodyBottom = if (legSkinRatio >= 0.12f) 0.91f else 0.84f

            boxes.add(
                NormalizedBox(
                    trackId = 1,
                    regionLabel = "BODY",
                    confidence = personConfidence,
                    left = bodyLeft,
                    top = bodyTop,
                    right = bodyRight,
                    bottom = bodyBottom,
                    timestampMs = timestampMs
                )
            )

            if (hasHeadRegion) {
                val fLeft = ((minHeadX.toFloat() / w) - 0.02f).coerceIn(torsoCenterX - 0.16f, torsoCenterX - 0.08f)
                val fRight = ((maxHeadX.toFloat() / w) + 0.02f).coerceIn(torsoCenterX + 0.08f, torsoCenterX + 0.16f)
                val fTop = ((minHeadY.toFloat() / h) - 0.01f).coerceIn(0.11f, 0.24f)
                val fBottom = ((maxHeadY.toFloat() / h) + 0.01f).coerceIn(fTop + 0.14f, 0.39f)
                val faceConf = (0.86f + min(0.12f, headDensity * 0.35f)).coerceIn(0.84f, 0.99f)
                boxes.add(
                    NormalizedBox(
                        trackId = 1,
                        regionLabel = "FACE",
                        confidence = faceConf,
                        left = fLeft,
                        top = fTop,
                        right = fRight,
                        bottom = fBottom,
                        timestampMs = timestampMs
                    )
                )
            }

            if (isRearViewPresentation) {
                val backConf = (0.88f + min(0.09f, waistSkinRatio * 0.30f)).coerceIn(0.88f, 0.96f)
                boxes.add(
                    NormalizedBox(
                        trackId = 1,
                        regionLabel = "BACK",
                        confidence = backConf,
                        left = chestLeft,
                        top = 0.36f,
                        right = chestRight,
                        bottom = 0.58f,
                        timestampMs = timestampMs
                    )
                )
            } else if (chestSkinRatio >= 0.12f || hasCleavageShadow) {
                val chestConf = max(chestExposureConfidence, 0.89f).coerceIn(0.88f, 0.98f)
                boxes.add(
                    NormalizedBox(
                        trackId = 1,
                        regionLabel = "CHEST",
                        confidence = chestConf,
                        left = chestLeft,
                        top = 0.36f,
                        right = chestRight,
                        bottom = 0.56f,
                        timestampMs = timestampMs
                    )
                )
            }

            if (isRearViewPresentation || hipSkinRatio >= 0.18f || hasGluteCurvature) {
                val gluteConf = max(rearButtocksExposureConfidence, 0.91f).coerceIn(0.90f, 0.97f)
                boxes.add(
                    NormalizedBox(
                        trackId = 1,
                        regionLabel = "BUTTOCKS",
                        confidence = gluteConf,
                        left = hipLeft,
                        top = 0.59f,
                        right = hipRight,
                        bottom = 0.83f,
                        timestampMs = timestampMs
                    )
                )
            }

            if (legSkinRatio >= 0.14f) {
                val feetConf = (0.89f + min(0.08f, legSkinRatio * 0.25f)).coerceIn(0.88f, 0.95f)
                boxes.add(
                    NormalizedBox(
                        trackId = 1,
                        regionLabel = "FEET",
                        confidence = feetConf,
                        left = legLeft,
                        top = 0.75f,
                        right = legRight,
                        bottom = 0.91f,
                        timestampMs = timestampMs
                    )
                )
            }
        }

        val features = FrameFeatures(
            contentItemId = contentItemId,
            frameTimestampMs = timestampMs,
            isSupportedVideoFeed = isSupportedFeed,
            analysisFailed = false,
            outerBorderSkinRatio = outerBorderSkinRatio,
            qualifyingPersonDetected = qualifyingPersonDetected,
            personConfidence = personConfidence,
            isMalePresentingOnly = isMalePresentingOnly,
            clothingAnalysisComplete = true,
            shoulderUncoveredConfidence = shoulderUncoveredConfidence,
            hemlineAboveKneeConfidence = hemlineAboveKneeConfidence,
            chestExposureConfidence = chestExposureConfidence,
            rearButtocksExposureConfidence = rearButtocksExposureConfidence,
            singleFrameMovementScore = singleFrameMovementScore,
            detectedBoxes = boxes
        )

        return Pair(features, lumaGrid)
    }

    private fun isHumanSkinRgbYCbCr(r: Int, g: Int, b: Int, y: Int): Boolean {
        if (r <= 70 || g <= 40 || b <= 25 || r <= g || r <= b) return false
        if ((r - min(g, b)) < 10) return false
        val cb = 128.0 - (0.1687 * r) - (0.3313 * g) + (0.5 * b)
        val cr = 128.0 + (0.5 * r) - (0.4187 * g) - (0.0813 * b)
        return y in 50..242 && cb in 77.0..126.0 && cr in 133.0..173.0
    }

    private fun isDarkHairPixel(r: Int, g: Int, b: Int, y: Int): Boolean {
        val spread = max(r, max(g, b)) - min(r, min(g, b))
        return y in 12..72 && spread < 42
    }
}
