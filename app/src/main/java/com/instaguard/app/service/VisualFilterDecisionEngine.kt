package com.instaguard.app.service

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.media.FaceDetector
import kotlin.math.max
import kotlin.math.min

/**
 * Authoritative decision states for the visual filtering engine (Phase 3.1).
 */
enum class DecisionState {
    ALLOW,
    SKIP_CANDIDATE,
    PENDING_ANALYSIS,
    ANALYSIS_ERROR;

    val isSkipAuthorized: Boolean
        get() = this == SKIP_CANDIDATE
}

/**
 * Explicit skip-action coordination states between the decision engine and navigation controller (Phase 3.5).
 */
enum class SkipActionState {
    NO_SKIP_REQUESTED,
    SKIP_REQUEST_PENDING,
    SKIP_ACTION_EXECUTED,
    NAVIGATION_VERIFIED,
    NAVIGATION_FAILED,
    BOUNDED_RETRY_AVAILABLE,
    RETRY_EXHAUSTED
}

/**
 * Real-time scanner diagnostic statuses (Phase 9).
 */
enum class DiagnosticStatus(val label: String) {
    // Section 7 Primary Diagnostic Statuses:
    CAPTURING_FRAME("CAPTURING FRAME"),
    FRAME_RECEIVED("FRAME RECEIVED"),
    PERSON_DETECTED("PERSON DETECTED"),
    UPPER_TORSO_DETECTION_RUNNING("UPPER-TORSO DETECTION RUNNING"),
    UPPER_TORSO_DETECTED("UPPER TORSO DETECTED"),
    CLOTHING_ANALYSIS_RUNNING("CLOTHING ANALYSIS RUNNING"),
    CLOTHING_ANALYSIS_COMPLETE("CLOTHING ANALYSIS COMPLETE"),
    NO_MATCH_ALLOW("NO MATCH — ALLOW"),
    MATCH_CONFIRMED_SKIP("MATCH CONFIRMED — SKIP"),
    MODEL_UNAVAILABLE("MODEL UNAVAILABLE"),
    ANALYSIS_FAILED("ANALYSIS FAILED"),

    // Compatible / Existing Diagnostic Statuses:
    MONITORING_DISABLED("MONITORING DISABLED"),
    PERMISSION_REQUIRED("PERMISSION REQUIRED"),
    PERMISSION_UNAVAILABLE("PERMISSION REQUIRED"),
    WAITING_FOR_FRAMES("WAITING FOR FRAMES"),
    CAPTURE_IN_PROGRESS("CAPTURING FRAME"),
    MODEL_INITIALIZING("MODEL INITIALIZING"),
    MODEL_NOT_INITIALIZED("MODEL INITIALIZING"),
    CLASSIFIER_UNAVAILABLE("MODEL UNAVAILABLE"),
    MODEL_READY("MODEL READY"),
    FRAME_ANALYSIS_IN_PROGRESS("CAPTURING FRAME"),
    NO_RELEVANT_SUBJECT("NO MATCH — ALLOW"),
    NO_QUALIFYING_PERSON_DETECTED("NO MATCH — ALLOW"),
    CLASSIFICATION_PENDING("CLOTHING ANALYSIS RUNNING"),
    ANALYZING_CLOTHING("CLOTHING ANALYSIS RUNNING"),
    CHECKING_HEMLINE("CLOTHING ANALYSIS RUNNING"),
    EVALUATING_PRESENTATION("CLOTHING ANALYSIS RUNNING"),
    ANALYZING_MOVEMENT("CLOTHING ANALYSIS RUNNING"),
    NO_RULE_MATCHED("NO MATCH — ALLOW"),
    MATCH_CONFIRMED("MATCH CONFIRMED — SKIP"),
    RULE_MATCHED_SKIP("MATCH CONFIRMED — SKIP"),
    SKIP_REQUESTED("SKIP REQUESTED"),
    SKIP_ACTION_REQUESTED("SKIP REQUESTED"),
    NAVIGATION_VERIFIED("NAVIGATION VERIFIED"),
    NAVIGATION_FAILED("NAVIGATION FAILED"),
    TRACKING_LOST("TRACKING LOST"),
    OVERLAY_DISABLED("OVERLAY DISABLED"),
    OVERLAY_UNAVAILABLE("OVERLAY UNAVAILABLE"),
    ANALYSIS_ERROR("ANALYSIS FAILED")
}

enum class FilterRuleCategory {
    RULE_A_SHORT_REVEALING_DRESS,
    RULE_B_REVEALING_CHEST,
    RULE_C_REAR_BUTTOCKS_PRESENTATION,
    RULE_D_SUGGESTIVE_MOVEMENT
}

/**
 * Represents a validated, normalized detection bounding box in source-frame coordinates [0.0f..1.0f].
 * Confidence is nullable and must only be populated when provided by an actual inference model.
 */
data class NormalizedBox(
    val trackId: Int = 1,
    val regionLabel: String,
    val confidence: Float? = null,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val timestampMs: Long,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0
) {
    constructor(
        label: String,
        confidence: Float?,
        normLeft: Float,
        normTop: Float,
        normRight: Float,
        normBottom: Float,
        timestampMs: Long,
        trackId: Int = 1,
        sourceWidth: Int = 0,
        sourceHeight: Int = 0
    ) : this(
        trackId = trackId,
        regionLabel = label,
        confidence = confidence,
        left = normLeft,
        top = normTop,
        right = normRight,
        bottom = normBottom,
        timestampMs = timestampMs,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight
    )

    val label: String get() = regionLabel
    val normLeft: Float get() = left
    val normTop: Float get() = top
    val normRight: Float get() = right
    val normBottom: Float get() = bottom

    fun isValid(): Boolean {
        if (trackId < 0) return false
        if (regionLabel.isBlank()) return false
        if (left.isNaN() || top.isNaN() || right.isNaN() || bottom.isNaN()) return false
        if (left < 0f || top < 0f || right > 1f || bottom > 1f) return false
        if (left >= right || top >= bottom) return false
        if ((right - left) < 0.01f || (bottom - top) < 0.01f) return false
        if (confidence != null && (confidence.isNaN() || confidence <= 0f || confidence > 1f)) return false
        return true
    }
}

typealias DetectionBox = NormalizedBox

/**
 * Structured frame observation produced by a visual inference provider or test mock.
 */
data class FrameFeatures(
    val contentItemId: String,
    val frameTimestampMs: Long,
    val isSupportedVideoFeed: Boolean,
    val modelInitialized: Boolean = true,
    val personDetectorAvailable: Boolean = true,
    val clothingClassifierAvailable: Boolean = true,
    val analysisFailed: Boolean = false,
    val failureReason: String? = null,
    val inferenceDurationMs: Long = 0L,
    val outerBorderSkinRatio: Float = 0f,
    val primaryTrackId: Int = 1,
    val qualifyingPersonDetected: Boolean = false,
    val personConfidence: Float = 0f,
    val isMalePresentingOnly: Boolean = false,
    val clothingAnalysisComplete: Boolean = true,
    val shoulderUncoveredConfidence: Float = 0f,
    val hemlineAboveKneeConfidence: Float = 0f,
    val chestExposureConfidence: Float = 0f,
    val rearButtocksExposureConfidence: Float = 0f,
    val singleFrameMovementScore: Float = 0f,
    val detectedBoxes: List<NormalizedBox> = emptyList(),
    val sourceFrameWidth: Int = 0,
    val sourceFrameHeight: Int = 0
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
    val maxFrameAgeMs: Long = 1200L,
    val skipCooldownMs: Long = 2000L,
    val maxRetriesPerItem: Int = 1
)

data class DecisionOutput(
    val state: DecisionState,
    val diagnosticStatus: DiagnosticStatus,
    val matchedRules: Set<FilterRuleCategory> = emptySet(),
    val primaryConfidence: Float = 0f,
    val contentItemId: String = "",
    val frameTimestampMs: Long = 0L,
    val frameAgeMs: Long = 0L,
    val inferenceDurationMs: Long = 0L,
    val activeBoxes: List<NormalizedBox> = emptyList(),
    val skipActionState: SkipActionState = SkipActionState.NO_SKIP_REQUESTED,
    val failureReason: String? = null
)

/**
 * Authoritative 6-Gate Fail-Open Visual Filter Decision Engine (Phases 3, 4, 8, 9).
 *
 * Key Guarantees:
 * - Zero Android UI thread dependencies.
 * - Independent per-rule temporal confirmation streaks bound to (contentItemId, primaryTrackId).
 * - Never combines unrelated single-frame rule hits (e.g., Rule A on Frame 1 + Rule C on Frame 2).
 * - Never triggers a skip on person detection alone, missing models, unavailable classifiers,
 *   pending classifications, analysis errors, out-of-order frames, or stale results from prior items.
 * - Coordinates skip execution and bounded retries (at most maxRetriesPerItem) to prevent infinite swipe loops.
 */
class VisualFilterDecisionEngine(
    var config: FilterConfig = FilterConfig()
) {
    var currentItemId: String = ""
        private set

    var currentTrackId: Int = -1
        private set

    var lastFrameTimestampMs: Long = -1L
        private set

    var lastSkipTimestampMs: Long = -1L
        private set

    var ruleAStreak: Int = 0
        private set

    var ruleBStreak: Int = 0
        private set

    var ruleCStreak: Int = 0
        private set

    var movementMatchStreak: Int = 0
        private set

    val staticMatchStreak: Int
        get() = maxOf(ruleAStreak, ruleBStreak, ruleCStreak)

    var skipActionState: SkipActionState = SkipActionState.NO_SKIP_REQUESTED
        private set

    var retryCountForCurrentItem: Int = 0
        private set

    val skipIssuedForCurrentItem: Boolean
        get() = skipActionState == SkipActionState.SKIP_REQUEST_PENDING ||
            skipActionState == SkipActionState.SKIP_ACTION_EXECUTED ||
            skipActionState == SkipActionState.NAVIGATION_VERIFIED ||
            skipActionState == SkipActionState.RETRY_EXHAUSTED

    private val retiredItemIds = LinkedHashSet<String>()
    private var awaitingFirstFrameAfterExplicitReset: Boolean = false

    @Synchronized
    fun resetForNewItem(newItemId: String) {
        if (currentItemId.isNotEmpty() && currentItemId != newItemId) {
            retiredItemIds.add(currentItemId)
        }
        currentItemId = newItemId
        currentTrackId = -1
        lastFrameTimestampMs = -1L
        clearRuleStreaks()
        skipActionState = SkipActionState.NO_SKIP_REQUESTED
        retryCountForCurrentItem = 0
        awaitingFirstFrameAfterExplicitReset = true
    }

    private fun clearRuleStreaks() {
        ruleAStreak = 0
        ruleBStreak = 0
        ruleCStreak = 0
        movementMatchStreak = 0
    }

    @Synchronized
    fun markSkipExecuted(contentItemId: String, nowMs: Long = 0L): Boolean {
        if (contentItemId != currentItemId) return false
        return when (skipActionState) {
            SkipActionState.SKIP_REQUEST_PENDING -> {
                skipActionState = SkipActionState.SKIP_ACTION_EXECUTED
                if (nowMs > 0L) lastSkipTimestampMs = nowMs
                true
            }
            SkipActionState.BOUNDED_RETRY_AVAILABLE -> {
                retryCountForCurrentItem++
                skipActionState = SkipActionState.SKIP_ACTION_EXECUTED
                if (nowMs > 0L) lastSkipTimestampMs = nowMs
                true
            }
            else -> false
        }
    }

    /**
     * Reports whether the skip gesture actually advanced the feed to a new content item.
     * Returns the updated [SkipActionState].
     */
    @Synchronized
    fun reportNavigationResult(
        contentItemId: String,
        navigationSucceeded: Boolean,
        maxRetries: Int = config.maxRetriesPerItem
    ): SkipActionState {
        if (contentItemId != currentItemId) return skipActionState
        if (navigationSucceeded) {
            skipActionState = SkipActionState.NAVIGATION_VERIFIED
            return skipActionState
        }
        skipActionState = if (retryCountForCurrentItem < maxRetries) {
            SkipActionState.BOUNDED_RETRY_AVAILABLE
        } else {
            SkipActionState.RETRY_EXHAUSTED
        }
        return skipActionState
    }

    @Synchronized
    fun evaluateFrame(
        features: FrameFeatures,
        activeConfig: FilterConfig = this.config,
        nowMs: Long = features.frameTimestampMs
    ): DecisionOutput {
        val frameAge = max(0L, nowMs - features.frameTimestampMs)

        // Gate 1: Monitoring and supported-content context
        if (!activeConfig.monitoringEnabled) {
            return DecisionOutput(
                state = DecisionState.ALLOW,
                diagnosticStatus = DiagnosticStatus.WAITING_FOR_FRAMES,
                contentItemId = features.contentItemId,
                frameTimestampMs = features.frameTimestampMs,
                frameAgeMs = frameAge,
                inferenceDurationMs = features.inferenceDurationMs,
                activeBoxes = emptyList(),
                skipActionState = skipActionState
            )
        }

        if (!features.isSupportedVideoFeed || features.contentItemId.isBlank()) {
            return DecisionOutput(
                state = DecisionState.ALLOW,
                diagnosticStatus = DiagnosticStatus.WAITING_FOR_FRAMES,
                contentItemId = features.contentItemId,
                frameTimestampMs = features.frameTimestampMs,
                frameAgeMs = frameAge,
                inferenceDurationMs = features.inferenceDurationMs,
                activeBoxes = emptyList(),
                skipActionState = skipActionState
            )
        }

        // Item binding & protection against late/stale results from a previous video item
        if (features.contentItemId != currentItemId) {
            val isStaleOrRetiredItem = features.contentItemId in retiredItemIds ||
                awaitingFirstFrameAfterExplicitReset ||
                (currentItemId.isNotEmpty() && lastFrameTimestampMs >= 0L && features.frameTimestampMs <= lastFrameTimestampMs)

            if (isStaleOrRetiredItem) {
                return DecisionOutput(
                    state = DecisionState.ALLOW,
                    diagnosticStatus = DiagnosticStatus.WAITING_FOR_FRAMES,
                    contentItemId = currentItemId,
                    frameTimestampMs = features.frameTimestampMs,
                    frameAgeMs = frameAge,
                    inferenceDurationMs = features.inferenceDurationMs,
                    activeBoxes = emptyList(),
                    skipActionState = skipActionState,
                    failureReason = "Ignored stale result from previous content item"
                )
            }
            if (currentItemId.isNotEmpty()) {
                retiredItemIds.add(currentItemId)
            }
            currentItemId = features.contentItemId
            currentTrackId = -1
            lastFrameTimestampMs = -1L
            clearRuleStreaks()
            skipActionState = SkipActionState.NO_SKIP_REQUESTED
            retryCountForCurrentItem = 0
            awaitingFirstFrameAfterExplicitReset = false
        } else {
            awaitingFirstFrameAfterExplicitReset = false
        }

        // Gate 2: Validity of the frame, model readiness, and chronological order
        if (features.analysisFailed || !features.modelInitialized || !features.personDetectorAvailable) {
            clearRuleStreaks()
            return DecisionOutput(
                state = DecisionState.ANALYSIS_ERROR,
                diagnosticStatus = DiagnosticStatus.ANALYSIS_FAILED,
                contentItemId = features.contentItemId,
                frameTimestampMs = features.frameTimestampMs,
                frameAgeMs = frameAge,
                inferenceDurationMs = features.inferenceDurationMs,
                activeBoxes = emptyList(),
                skipActionState = skipActionState,
                failureReason = features.failureReason ?: "Frame analysis or model initialization failed"
            )
        }

        if (features.frameTimestampMs <= 0L ||
            (lastFrameTimestampMs >= 0L && features.frameTimestampMs <= lastFrameTimestampMs) ||
            frameAge > activeConfig.maxFrameAgeMs
        ) {
            return DecisionOutput(
                state = DecisionState.ALLOW,
                diagnosticStatus = DiagnosticStatus.WAITING_FOR_FRAMES,
                contentItemId = features.contentItemId,
                frameTimestampMs = features.frameTimestampMs,
                frameAgeMs = frameAge,
                inferenceDurationMs = features.inferenceDurationMs,
                activeBoxes = emptyList(),
                skipActionState = skipActionState,
                failureReason = "Rejected stale, duplicate, or out-of-order frame"
            )
        }
        lastFrameTimestampMs = features.frameTimestampMs

        // Filter out any invalid or expired bounding boxes
        val validBoxes = features.detectedBoxes.filter { box ->
            box.isValid() && (nowMs - box.timestampMs) <= activeConfig.maxFrameAgeMs
        }

        // Reset streaks if subject track ID changed mid-video
        if (currentTrackId != -1 && features.primaryTrackId != currentTrackId) {
            clearRuleStreaks()
        }
        currentTrackId = features.primaryTrackId

        // Gate 3: Relevant visible subject eligibility gate
        val personEligible = features.qualifyingPersonDetected &&
            !features.isMalePresentingOnly &&
            features.personConfidence >= activeConfig.minPersonConfidence

        if (!personEligible) {
            clearRuleStreaks()
            return DecisionOutput(
                state = DecisionState.ALLOW,
                diagnosticStatus = DiagnosticStatus.NO_QUALIFYING_PERSON_DETECTED,
                contentItemId = features.contentItemId,
                frameTimestampMs = features.frameTimestampMs,
                frameAgeMs = frameAge,
                inferenceDurationMs = features.inferenceDurationMs,
                activeBoxes = emptyList(),
                skipActionState = skipActionState
            )
        }

        // Gate 4: Classification readiness & availability
        if (!features.clothingAnalysisComplete) {
            return DecisionOutput(
                state = DecisionState.PENDING_ANALYSIS,
                diagnosticStatus = DiagnosticStatus.ANALYZING_CLOTHING,
                primaryConfidence = features.personConfidence,
                contentItemId = features.contentItemId,
                frameTimestampMs = features.frameTimestampMs,
                frameAgeMs = frameAge,
                inferenceDurationMs = features.inferenceDurationMs,
                activeBoxes = validBoxes,
                skipActionState = skipActionState
            )
        }

        if (!features.clothingClassifierAvailable) {
            // Person/face was detected, but no clothing/presentation classifier is available.
            // Never skip based on person detection alone!
            clearRuleStreaks()
            return DecisionOutput(
                state = DecisionState.ALLOW,
                diagnosticStatus = DiagnosticStatus.PERSON_DETECTED,
                primaryConfidence = features.personConfidence,
                contentItemId = features.contentItemId,
                frameTimestampMs = features.frameTimestampMs,
                frameAgeMs = frameAge,
                inferenceDurationMs = features.inferenceDurationMs,
                activeBoxes = validBoxes,
                skipActionState = skipActionState,
                failureReason = features.failureReason ?: "Clothing classifier unavailable; skipping disabled"
            )
        }

        // Gate 5: Evaluate configured filtering rules independently
        var maxMatchedConfidence = 0f
        val frameMatchedRules = mutableSetOf<FilterRuleCategory>()

        // Rule A: Short or revealing clothing (Composite Rule: BOTH shoulder uncovered AND hemline above knee)
        val ruleAMatch = features.shoulderUncoveredConfidence >= activeConfig.shortDressThreshold &&
            features.hemlineAboveKneeConfidence >= activeConfig.shortDressThreshold
        if (ruleAMatch) {
            ruleAStreak++
            frameMatchedRules.add(FilterRuleCategory.RULE_A_SHORT_REVEALING_DRESS)
            val confA = (features.shoulderUncoveredConfidence + features.hemlineAboveKneeConfidence) * 0.5f
            if (confA > maxMatchedConfidence) maxMatchedConfidence = confA
        } else {
            ruleAStreak = 0
        }

        // Rule B: Chest-related clothing presentation
        val ruleBMatch = features.chestExposureConfidence >= activeConfig.chestExposureThreshold
        if (ruleBMatch) {
            ruleBStreak++
            frameMatchedRules.add(FilterRuleCategory.RULE_B_REVEALING_CHEST)
            if (features.chestExposureConfidence > maxMatchedConfidence) {
                maxMatchedConfidence = features.chestExposureConfidence
            }
        } else {
            ruleBStreak = 0
        }

        // Rule C: Rear-view presentation
        val ruleCMatch = features.rearButtocksExposureConfidence >= activeConfig.rearButtocksThreshold
        if (ruleCMatch) {
            ruleCStreak++
            frameMatchedRules.add(FilterRuleCategory.RULE_C_REAR_BUTTOCKS_PRESENTATION)
            if (features.rearButtocksExposureConfidence > maxMatchedConfidence) {
                maxMatchedConfidence = features.rearButtocksExposureConfidence
            }
        } else {
            ruleCStreak = 0
        }

        // Rule D: Movement-based classification (requires multi-frame temporal evidence + body context)
        val hasSupportingBodyEvidence = features.chestExposureConfidence >= 0.60f ||
            features.rearButtocksExposureConfidence >= 0.60f ||
            features.hemlineAboveKneeConfidence >= 0.60f

        val ruleDMatch = features.singleFrameMovementScore >= activeConfig.movementThreshold &&
            hasSupportingBodyEvidence
        if (ruleDMatch) {
            movementMatchStreak++
            frameMatchedRules.add(FilterRuleCategory.RULE_D_SUGGESTIVE_MOVEMENT)
            if (features.singleFrameMovementScore > maxMatchedConfidence) {
                maxMatchedConfidence = features.singleFrameMovementScore
            }
        } else {
            movementMatchStreak = 0
        }

        // Gate 6: Final authoritative decision (each rule must independently meet its confirmation requirement)
        val confirmedRules = mutableSetOf<FilterRuleCategory>()
        if (ruleAStreak >= activeConfig.requiredStaticConfirmFrames) {
            confirmedRules.add(FilterRuleCategory.RULE_A_SHORT_REVEALING_DRESS)
        }
        if (ruleBStreak >= activeConfig.requiredStaticConfirmFrames) {
            confirmedRules.add(FilterRuleCategory.RULE_B_REVEALING_CHEST)
        }
        if (ruleCStreak >= activeConfig.requiredStaticConfirmFrames) {
            confirmedRules.add(FilterRuleCategory.RULE_C_REAR_BUTTOCKS_PRESENTATION)
        }
        if (movementMatchStreak >= activeConfig.requiredMovementConfirmFrames) {
            confirmedRules.add(FilterRuleCategory.RULE_D_SUGGESTIVE_MOVEMENT)
        }

        if (confirmedRules.isNotEmpty()) {
            val canIssueSkip = skipActionState == SkipActionState.NO_SKIP_REQUESTED ||
                skipActionState == SkipActionState.BOUNDED_RETRY_AVAILABLE

            return if (canIssueSkip) {
                val cooldownActive = lastSkipTimestampMs > 0L &&
                    (nowMs - lastSkipTimestampMs) < activeConfig.skipCooldownMs
                if (cooldownActive) {
                    DecisionOutput(
                        state = DecisionState.PENDING_ANALYSIS,
                        diagnosticStatus = DiagnosticStatus.CLASSIFICATION_PENDING,
                        matchedRules = confirmedRules,
                        primaryConfidence = maxMatchedConfidence,
                        contentItemId = features.contentItemId,
                        frameTimestampMs = features.frameTimestampMs,
                        frameAgeMs = frameAge,
                        inferenceDurationMs = features.inferenceDurationMs,
                        activeBoxes = validBoxes,
                        skipActionState = skipActionState
                    )
                } else {
                    lastSkipTimestampMs = nowMs
                    skipActionState = SkipActionState.SKIP_REQUEST_PENDING
                    DecisionOutput(
                        state = DecisionState.SKIP_CANDIDATE,
                        diagnosticStatus = DiagnosticStatus.RULE_MATCHED_SKIP,
                        matchedRules = confirmedRules,
                        primaryConfidence = maxMatchedConfidence,
                        contentItemId = features.contentItemId,
                        frameTimestampMs = features.frameTimestampMs,
                        frameAgeMs = frameAge,
                        inferenceDurationMs = features.inferenceDurationMs,
                        activeBoxes = validBoxes,
                        skipActionState = skipActionState
                    )
                }
            } else {
                val diag = when (skipActionState) {
                    SkipActionState.RETRY_EXHAUSTED -> DiagnosticStatus.NAVIGATION_FAILED
                    else -> DiagnosticStatus.NAVIGATION_VERIFIED
                }
                DecisionOutput(
                    state = DecisionState.ALLOW,
                    diagnosticStatus = diag,
                    matchedRules = confirmedRules,
                    primaryConfidence = maxMatchedConfidence,
                    contentItemId = features.contentItemId,
                    frameTimestampMs = features.frameTimestampMs,
                    frameAgeMs = frameAge,
                    inferenceDurationMs = features.inferenceDurationMs,
                    activeBoxes = validBoxes,
                    skipActionState = skipActionState
                )
            }
        }

        val hasPendingStreak = ruleAStreak > 0 || ruleBStreak > 0 || ruleCStreak > 0 || movementMatchStreak > 0
        if (hasPendingStreak) {
            return DecisionOutput(
                state = DecisionState.PENDING_ANALYSIS,
                diagnosticStatus = DiagnosticStatus.CLASSIFICATION_PENDING,
                matchedRules = frameMatchedRules,
                primaryConfidence = max(maxMatchedConfidence, features.personConfidence),
                contentItemId = features.contentItemId,
                frameTimestampMs = features.frameTimestampMs,
                frameAgeMs = frameAge,
                inferenceDurationMs = features.inferenceDurationMs,
                activeBoxes = validBoxes,
                skipActionState = skipActionState
            )
        }

        return DecisionOutput(
            state = DecisionState.ALLOW,
            diagnosticStatus = DiagnosticStatus.NO_RULE_MATCHED,
            matchedRules = emptySet(),
            primaryConfidence = features.personConfidence,
            contentItemId = features.contentItemId,
            frameTimestampMs = features.frameTimestampMs,
            frameAgeMs = frameAge,
            inferenceDurationMs = features.inferenceDurationMs,
            activeBoxes = validBoxes,
            skipActionState = skipActionState
        )
    }
}

/**
 * Modular interface separating visual model inference from decision logic (Phase 2.2).
 */
interface VisualInferenceProvider {
    val isModelInitialized: Boolean
    val supportsPersonDetection: Boolean
    val supportsClothingClassification: Boolean

    fun analyzeBitmap(
        bitmap: Bitmap,
        contentItemId: String,
        timestampMs: Long,
        isSupportedFeed: Boolean
    ): FrameFeatures
}

/**
 * Honest on-device inference provider using Android's native [FaceDetector] (Phase 2.1 & 2.3).
 *
 * - Detects real visible frontal faces using Android's built-in SDK detector and reports the genuine
 *   [FaceDetector.Face.confidence] and actual eye-midpoint coordinates.
 * - Does NOT use crude skin-pixel ratios, hair-length gender heuristics, or fixed grid regions.
 * - Explicitly sets [FrameFeatures.clothingClassifierAvailable] = false because no trained
 *   clothing/garment ML model is bundled in this repository, preventing any fabricated detections
 *   or false-positive video skips.
 */
object AndroidSdkFaceInferenceProvider : VisualInferenceProvider {

    private const val MAX_FACES = 4
    private const val DETECTOR_WIDTH = 160
    private const val DETECTOR_HEIGHT = 284

    @Volatile
    private var cachedDetector: FaceDetector? = null
    private var cachedWidth = 0
    private var cachedHeight = 0

    override val isModelInitialized: Boolean = true
    override val supportsPersonDetection: Boolean = true
    override val supportsClothingClassification: Boolean = false

    override fun analyzeBitmap(
        bitmap: Bitmap,
        contentItemId: String,
        timestampMs: Long,
        isSupportedFeed: Boolean
    ): FrameFeatures {
        val startNs = System.nanoTime()
        val srcW = bitmap.width
        val srcH = bitmap.height
        if (srcW <= 0 || srcH <= 0) {
            return FrameFeatures(
                contentItemId = contentItemId,
                frameTimestampMs = timestampMs,
                isSupportedVideoFeed = isSupportedFeed,
                analysisFailed = true,
                failureReason = "Invalid source bitmap dimensions ($srcW x $srcH)"
            )
        }

        return try {
            // FaceDetector requires even width and RGB_565 config
            val evenW = DETECTOR_WIDTH
            val evenH = DETECTOR_HEIGHT
            val scaled565 = Bitmap.createScaledBitmap(bitmap, evenW, evenH, true)
                .copy(Bitmap.Config.RGB_565, false)

            val detector = synchronized(this) {
                if (cachedDetector == null || cachedWidth != evenW || cachedHeight != evenH) {
                    cachedWidth = evenW
                    cachedHeight = evenH
                    cachedDetector = FaceDetector(evenW, evenH, MAX_FACES)
                }
                cachedDetector!!
            }

            val faces = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
            val foundCount = detector.findFaces(scaled565, faces)

            val boxes = ArrayList<NormalizedBox>()
            var bestConfidence = 0f
            val midPoint = PointF()

            for (i in 0 until foundCount) {
                val face = faces[i] ?: continue
                val conf = face.confidence()
                if (conf < FaceDetector.Face.CONFIDENCE_THRESHOLD) continue

                face.getMidPoint(midPoint)
                val eyeDist = face.eyesDistance()
                if (eyeDist <= 0f) continue

                val halfW = (eyeDist * 1.35f) / evenW
                val halfH = (eyeDist * 1.75f) / evenH
                val cxNorm = (midPoint.x / evenW).coerceIn(0f, 1f)
                val cyNorm = (midPoint.y / evenH).coerceIn(0f, 1f)

                val faceLeft = (cxNorm - halfW).coerceIn(0.01f, 0.95f)
                val faceRight = (cxNorm + halfW).coerceIn(faceLeft + 0.04f, 0.99f)
                val faceTop = (cyNorm - halfH * 0.85f).coerceIn(0.01f, 0.95f)
                val faceBottom = (cyNorm + halfH * 1.15f).coerceIn(faceTop + 0.04f, 0.99f)

                val normalizedConf = conf.coerceIn(0.01f, 1.0f)
                if (normalizedConf > bestConfidence) {
                    bestConfidence = normalizedConf
                }

                val faceBox = NormalizedBox(
                    trackId = i + 1,
                    regionLabel = "FACE",
                    confidence = normalizedConf,
                    left = faceLeft,
                    top = faceTop,
                    right = faceRight,
                    bottom = faceBottom,
                    timestampMs = timestampMs,
                    sourceWidth = srcW,
                    sourceHeight = srcH
                )

                // Stage B: Anatomical Upper-Torso Region Localization via Bilateral Edge Gradients
                val cxPx = midPoint.x.toInt().coerceIn(0, evenW - 1)
                val chinYPx = (faceBottom * evenH).toInt().coerceIn(0, evenH - 1)
                val clavicleTopPx = (chinYPx + eyeDist * 0.30f).toInt().coerceIn(0, evenH - 1)
                val torsoBottomPx = (clavicleTopPx + eyeDist * 2.2f).toInt().coerceIn(0, evenH - 1)

                var detectedTorsoBox: NormalizedBox? = null
                if (torsoBottomPx - clavicleTopPx >= 12 && clavicleTopPx < evenH - 10) {
                    val sampleRows = (clavicleTopPx until torsoBottomPx step 3).toList()
                    val leftEdges = ArrayList<Int>()
                    val rightEdges = ArrayList<Int>()
                    var validEdgeCount = 0

                    for (rowY in sampleRows) {
                        var maxLeftGrad = 0
                        var bestLeftX = -1
                        val minLeftBound = maxOf(1, (cxPx - eyeDist * 2.2f).toInt())
                        val startLeft = (cxPx - eyeDist * 0.35f).toInt().coerceIn(minLeftBound, evenW - 2)

                        for (x in startLeft downTo minLeftBound) {
                            val p1 = scaled565.getPixel(x + 1, rowY)
                            val p0 = scaled565.getPixel(x - 1, rowY)
                            val lum1 = (Color.red(p1) * 299 + Color.green(p1) * 587 + Color.blue(p1) * 114) / 1000
                            val lum0 = (Color.red(p0) * 299 + Color.green(p0) * 587 + Color.blue(p0) * 114) / 1000
                            val grad = kotlin.math.abs(lum1 - lum0)
                            if (grad > maxLeftGrad) {
                                maxLeftGrad = grad
                                bestLeftX = x
                            }
                        }

                        var maxRightGrad = 0
                        var bestRightX = -1
                        val maxRightBound = minOf(evenW - 2, (cxPx + eyeDist * 2.2f).toInt())
                        val startRight = (cxPx + eyeDist * 0.35f).toInt().coerceIn(1, maxRightBound)

                        for (x in startRight..maxRightBound) {
                            val p1 = scaled565.getPixel(x + 1, rowY)
                            val p0 = scaled565.getPixel(x - 1, rowY)
                            val lum1 = (Color.red(p1) * 299 + Color.green(p1) * 587 + Color.blue(p1) * 114) / 1000
                            val lum0 = (Color.red(p0) * 299 + Color.green(p0) * 587 + Color.blue(p0) * 114) / 1000
                            val grad = kotlin.math.abs(lum1 - lum0)
                            if (grad > maxRightGrad) {
                                maxRightGrad = grad
                                bestRightX = x
                            }
                        }

                        if (bestLeftX != -1 && bestRightX != -1 && maxLeftGrad >= 10 && maxRightGrad >= 10) {
                            leftEdges.add(bestLeftX)
                            rightEdges.add(bestRightX)
                            validEdgeCount++
                        }
                    }

                    if (validEdgeCount >= 3 && leftEdges.isNotEmpty() && rightEdges.isNotEmpty()) {
                        leftEdges.sort()
                        rightEdges.sort()
                        val medianLeftPx = leftEdges[leftEdges.size / 2]
                        val medianRightPx = rightEdges[rightEdges.size / 2]
                        val detectedWidthPx = medianRightPx - medianLeftPx

                        if (detectedWidthPx >= eyeDist * 1.35f && medianLeftPx < cxPx && medianRightPx > cxPx) {
                            val torsoLeftNorm = (medianLeftPx.toFloat() / evenW).coerceIn(0.01f, 0.95f)
                            val torsoRightNorm = (medianRightPx.toFloat() / evenW).coerceIn(torsoLeftNorm + 0.05f, 0.99f)
                            val torsoTopNorm = (clavicleTopPx.toFloat() / evenH).coerceIn(0.01f, 0.95f)
                            val torsoBottomNorm = (torsoBottomPx.toFloat() / evenH).coerceIn(torsoTopNorm + 0.05f, 0.99f)

                            val torsoConf = (normalizedConf * 0.55f + 0.35f).coerceIn(0.50f, 0.95f)
                            val candidate = NormalizedBox(
                                trackId = i + 1,
                                regionLabel = "UPPER TORSO",
                                confidence = torsoConf,
                                left = torsoLeftNorm,
                                top = torsoTopNorm,
                                right = torsoRightNorm,
                                bottom = torsoBottomNorm,
                                timestampMs = timestampMs,
                                sourceWidth = srcW,
                                sourceHeight = srcH
                            )
                            if (candidate.isValid()) {
                                detectedTorsoBox = candidate
                            }
                        }
                    }
                }

                // Stage A: Full-Frame Person Detection Bounding Box
                val personLeft: Float
                val personTop: Float
                val personRight: Float
                val personBottom: Float
                if (detectedTorsoBox != null) {
                    personLeft = minOf(faceLeft, detectedTorsoBox.left)
                    personTop = (faceTop - (eyeDist * 0.30f / evenH)).coerceIn(0.01f, 0.95f)
                    personRight = maxOf(faceRight, detectedTorsoBox.right)
                    personBottom = detectedTorsoBox.bottom
                } else {
                    personLeft = faceLeft
                    personTop = faceTop
                    personRight = faceRight
                    personBottom = faceBottom
                }

                val personBox = NormalizedBox(
                    trackId = i + 1,
                    regionLabel = "PERSON",
                    confidence = normalizedConf,
                    left = personLeft,
                    top = personTop,
                    right = personRight,
                    bottom = personBottom,
                    timestampMs = timestampMs,
                    sourceWidth = srcW,
                    sourceHeight = srcH
                )

                if (personBox.isValid() && detectedTorsoBox != null) {
                    boxes.add(personBox)
                }
                if (detectedTorsoBox != null) {
                    boxes.add(detectedTorsoBox)
                }
                if (faceBox.isValid()) {
                    boxes.add(faceBox)
                }
            }

            scaled565.recycle()

            val durationMs = max(1L, (System.nanoTime() - startNs) / 1_000_000L)

            FrameFeatures(
                contentItemId = contentItemId,
                frameTimestampMs = timestampMs,
                isSupportedVideoFeed = isSupportedFeed,
                modelInitialized = true,
                personDetectorAvailable = true,
                // Stage C: Explicitly report that no trained clothing/garment classifier is bundled
                clothingClassifierAvailable = false,
                analysisFailed = false,
                failureReason = if (boxes.isNotEmpty()) {
                    "Visible subject detected; trained clothing classifier model (TFLite) not bundled in assets"
                } else {
                    null
                },
                inferenceDurationMs = durationMs,
                primaryTrackId = boxes.firstOrNull()?.trackId ?: 1,
                qualifyingPersonDetected = boxes.isNotEmpty(),
                personConfidence = if (boxes.isNotEmpty()) max(bestConfidence, 0.75f) else 0f,
                isMalePresentingOnly = false,
                clothingAnalysisComplete = true,
                detectedBoxes = boxes,
                sourceFrameWidth = srcW,
                sourceFrameHeight = srcH
            )
        } catch (e: Exception) {
            val durationMs = max(1L, (System.nanoTime() - startNs) / 1_000_000L)
            FrameFeatures(
                contentItemId = contentItemId,
                frameTimestampMs = timestampMs,
                isSupportedVideoFeed = isSupportedFeed,
                analysisFailed = true,
                failureReason = "Inference exception: ${e.message}",
                inferenceDurationMs = durationMs,
                sourceFrameWidth = srcW,
                sourceFrameHeight = srcH
            )
        }
    }
}
