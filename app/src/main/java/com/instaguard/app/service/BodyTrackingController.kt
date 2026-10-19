package com.instaguard.app.service

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Standardized anatomical landmark types for human pose estimation (Phases 2, 4, 5).
 */
enum class LandmarkType {
    NOSE,
    LEFT_EYE,
    RIGHT_EYE,
    LEFT_SHOULDER,
    RIGHT_SHOULDER,
    LEFT_ELBOW,
    RIGHT_ELBOW,
    LEFT_WRIST,
    RIGHT_WRIST,
    LEFT_HIP,
    RIGHT_HIP
}

/**
 * Validated, normalized 2D anatomical pose landmark in [0.0f..1.0f] coordinates.
 */
data class PoseLandmark(
    val type: LandmarkType,
    val x: Float,
    val y: Float,
    val confidence: Float = 1.0f
) {
    fun isValid(): Boolean {
        return !x.isNaN() && !y.isNaN() && x in 0.0f..1.0f && y in 0.0f..1.0f &&
            !confidence.isNaN() && confidence in 0.0f..1.0f
    }
}

/**
 * Explicit tracking lifecycle states (Phases 5 & 12).
 */
enum class TrackingLifecycleState(val diagnosticLabel: String) {
    IDLE("WAITING FOR FRAME"),
    WAITING_FOR_FRAME("WAITING FOR FRAME"),
    FRAME_RECEIVED("FRAME RECEIVED"),
    PERSON_DETECTED("PERSON DETECTED"),
    POSE_INFERENCE_RUNNING("POSE INFERENCE RUNNING"),
    LANDMARKS_UPDATED("LANDMARKS UPDATED"),
    TRACKING_SUBJECT("TRACKING SUBJECT"),
    TRACKING_CONFIDENCE_LOW("TRACKING CONFIDENCE LOW"),
    COASTING_PREDICTION("TRACKING SUBJECT"),
    TRACKING_LOST("TRACKING LOST"),
    ANALYSIS_ERROR("ANALYSIS ERROR")
}

/**
 * Represents estimated velocity (change in normalized units per millisecond).
 */
data class VelocityEstimate(
    val vx: Float = 0f,
    val vy: Float = 0f,
    val vw: Float = 0f,
    val vh: Float = 0f
)

/**
 * Comprehensive tracking state maintained for each active tracking target.
 */
data class TrackedSubject(
    val trackId: Int,
    val contentItemId: String,
    val lastDetectionTimestampMs: Long,
    val state: TrackingLifecycleState,
    val confidence: Float,
    val personBox: NormalizedBox?,
    val faceBox: NormalizedBox?,
    val upperTorsoBox: NormalizedBox?,
    val landmarks: List<PoseLandmark> = emptyList(),
    val velocity: VelocityEstimate = VelocityEstimate(),
    val consecutiveLostFrames: Int = 0
)

/**
 * High-performance, pure-Kotlin Body Tracking Controller (Phases 3, 4, 5, 6, 8, 11, 12).
 *
 * Key Capabilities:
 * - Maintains stable subject identities (trackId) across successive frames.
 * - Multi-metric subject association (IoU + centroid proximity + landmark vector alignment).
 * - Velocity-aware Kalman/exponential smoothing that eliminates lag behind moving subjects.
 * - Real-time continuous 60fps HUD rendering interpolation with velocity decay damping.
 * - Handles turning, leaning, arm raising, crouching, and partial subject visibility.
 * - Graceful dead-reckoning coasting during brief head turns or partial occlusions (<= 800ms).
 * - Immediate tracking purge and box clearance on content item transitions.
 * - Complete transparency guarantee: 0% interior fill, never blurs or obscures face/body.
 */
class BodyTrackingController(
    val maxCoastingAgeMs: Long = 850L,
    val staleTimeoutMs: Long = 1200L,
    val velocityDampingFactor: Float = 0.0035f // exp(-lambda * tau)
) {
    @Volatile
    var isOverlayEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) {
                synchronized(this) {
                    activeSubject = null
                }
            }
        }

    @Volatile
    var currentContentItemId: String = ""
        private set

    @Volatile
    var currentLifecycleState: TrackingLifecycleState = TrackingLifecycleState.WAITING_FOR_FRAME
        private set

    @Volatile
    var lastDetectionTimestampMs: Long = 0L
        private set

    @Volatile
    var lastInferenceDurationMs: Long = 0L
        private set

    private var nextTrackId: Int = 1
    private var activeSubject: TrackedSubject? = null

    /**
     * Resets tracking state completely when the user navigates or scrolls to a new video.
     */
    @Synchronized
    fun onContentItemChanged(newContentItemId: String) {
        currentContentItemId = newContentItemId
        activeSubject = null
        currentLifecycleState = TrackingLifecycleState.WAITING_FOR_FRAME
        lastDetectionTimestampMs = 0L
    }

    @Synchronized
    fun resetForNewItem(newItemId: String) {
        onContentItemChanged(newItemId)
    }

    /**
     * Ingests a new set of per-frame detections and landmarks from the inference pipeline.
     */
    @Synchronized
    fun updateWithFrameDetection(
        contentItemId: String,
        timestampMs: Long,
        personBox: NormalizedBox?,
        faceBox: NormalizedBox?,
        upperTorsoBox: NormalizedBox?,
        landmarks: List<PoseLandmark> = emptyList(),
        detectionConfidence: Float = 0.85f,
        inferenceDurationMs: Long = 0L,
        nowMs: Long = timestampMs
    ) {
        // Gate: Content item binding
        if (contentItemId != currentContentItemId) {
            onContentItemChanged(contentItemId)
        }

        // Gate: Reject out-of-order or duplicate timestamps
        if (lastDetectionTimestampMs > 0L && timestampMs <= lastDetectionTimestampMs) {
            return
        }
        lastInferenceDurationMs = inferenceDurationMs

        val hasValidDetections = (personBox != null && personBox.isValid()) ||
            (faceBox != null && faceBox.isValid()) ||
            (upperTorsoBox != null && upperTorsoBox.isValid()) ||
            landmarks.isNotEmpty()

        if (!hasValidDetections) {
            handleMissingDetection(nowMs)
            return
        }

        val primaryIncomingBox = personBox ?: upperTorsoBox ?: faceBox!!
        val current = activeSubject

        val trackId: Int
        val velocity: VelocityEstimate
        val dt = if (current != null && current.lastDetectionTimestampMs > 0L) {
            max(1L, timestampMs - current.lastDetectionTimestampMs)
        } else {
            0L
        }

        if (current != null && isSameSubject(current, primaryIncomingBox, landmarks)) {
            trackId = current.trackId
            val prevRefBox = current.personBox ?: current.upperTorsoBox ?: current.faceBox ?: primaryIncomingBox
            velocity = if (dt in 20L..1500L) {
                calculateSmoothedVelocity(
                    prevBox = prevRefBox,
                    newBox = primaryIncomingBox,
                    dtMs = dt,
                    prevVelocity = current.velocity
                )
            } else {
                VelocityEstimate()
            }
        } else {
            // New subject or new track
            trackId = nextTrackId++
            velocity = VelocityEstimate()
        }

        val validLandmarks = landmarks.filter { it.isValid() }
        val updatedSubject = TrackedSubject(
            trackId = trackId,
            contentItemId = contentItemId,
            lastDetectionTimestampMs = timestampMs,
            state = if (validLandmarks.isNotEmpty()) {
                TrackingLifecycleState.TRACKING_SUBJECT
            } else {
                TrackingLifecycleState.PERSON_DETECTED
            },
            confidence = detectionConfidence.coerceIn(0.1f, 1.0f),
            personBox = personBox?.copy(trackId = trackId),
            faceBox = faceBox?.copy(trackId = trackId),
            upperTorsoBox = upperTorsoBox?.copy(trackId = trackId),
            landmarks = validLandmarks,
            velocity = velocity,
            consecutiveLostFrames = 0
        )

        activeSubject = updatedSubject
        lastDetectionTimestampMs = timestampMs
        currentLifecycleState = if (validLandmarks.isNotEmpty()) {
            TrackingLifecycleState.TRACKING_SUBJECT
        } else {
            TrackingLifecycleState.PERSON_DETECTED
        }
    }

    private fun handleMissingDetection(nowMs: Long) {
        val current = activeSubject ?: return
        val ageMs = nowMs - current.lastDetectionTimestampMs

        if (ageMs <= maxCoastingAgeMs) {
            // Coasting: maintain prediction with decaying confidence
            val decayedConfidence = (current.confidence * 0.85f).coerceAtLeast(0.20f)
            val updatedSubject = current.copy(
                state = TrackingLifecycleState.COASTING_PREDICTION,
                confidence = decayedConfidence,
                consecutiveLostFrames = current.consecutiveLostFrames + 1
            )
            activeSubject = updatedSubject
            currentLifecycleState = TrackingLifecycleState.TRACKING_CONFIDENCE_LOW
        } else {
            // Tracking lost: clear boxes cleanly
            activeSubject = null
            currentLifecycleState = TrackingLifecycleState.TRACKING_LOST
        }
    }

    /**
     * Computes smoothed velocity vector using exponential moving average.
     */
    private fun calculateSmoothedVelocity(
        prevBox: NormalizedBox,
        newBox: NormalizedBox,
        dtMs: Long,
        prevVelocity: VelocityEstimate
    ): VelocityEstimate {
        val dt = dtMs.toFloat()
        val rawVx = ((newBox.left + newBox.right) * 0.5f - (prevBox.left + prevBox.right) * 0.5f) / dt
        val rawVy = ((newBox.top + newBox.bottom) * 0.5f - (prevBox.top + prevBox.bottom) * 0.5f) / dt
        val rawVw = ((newBox.right - newBox.left) - (prevBox.right - prevBox.left)) / dt
        val rawVh = ((newBox.bottom - newBox.top) - (prevBox.bottom - prevBox.top)) / dt

        // EMA smoothing factor: 0.60 current, 0.40 previous
        val alpha = 0.60f
        return VelocityEstimate(
            vx = alpha * rawVx + (1f - alpha) * prevVelocity.vx,
            vy = alpha * rawVy + (1f - alpha) * prevVelocity.vy,
            vw = alpha * rawVw + (1f - alpha) * prevVelocity.vw,
            vh = alpha * rawVh + (1f - alpha) * prevVelocity.vh
        )
    }

    /**
     * Determines whether incoming detections belong to the active tracked subject.
     */
    private fun isSameSubject(
        tracked: TrackedSubject,
        incomingBox: NormalizedBox,
        incomingLandmarks: List<PoseLandmark>
    ): Boolean {
        val trackedRefBox = tracked.personBox ?: tracked.upperTorsoBox ?: tracked.faceBox ?: return false

        // Metric 1: Intersection-over-Union (IoU)
        val iou = calculateIoU(trackedRefBox, incomingBox)
        if (iou >= 0.15f) return true

        // Metric 2: Centroid Proximity
        val c1x = (trackedRefBox.left + trackedRefBox.right) * 0.5f
        val c1y = (trackedRefBox.top + trackedRefBox.bottom) * 0.5f
        val c2x = (incomingBox.left + incomingBox.right) * 0.5f
        val c2y = (incomingBox.top + incomingBox.bottom) * 0.5f
        val distSq = (c1x - c2x) * (c1x - c2x) + (c1y - c2y) * (c1y - c2y)
        if (distSq <= 0.16f) return true // within 40% of screen diagonal

        // Metric 3: Landmark overlap
        if (incomingLandmarks.isNotEmpty() && tracked.landmarks.isNotEmpty()) {
            val commonCount = incomingLandmarks.count { inLm ->
                tracked.landmarks.any { trLm ->
                    trLm.type == inLm.type &&
                        (trLm.x - inLm.x) * (trLm.x - inLm.x) + (trLm.y - inLm.y) * (trLm.y - inLm.y) <= 0.09f
                }
            }
            if (commonCount >= 2) return true
        }

        return false
    }

    private fun calculateIoU(a: NormalizedBox, b: NormalizedBox): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)

        if (interRight <= interLeft || interBottom <= interTop) return 0f

        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val unionArea = areaA + areaB - interArea
        if (unionArea <= 0f) return 0f
        return interArea / unionArea
    }

    /**
     * Queries the active boxes for real-time 60fps rendering, applying velocity-aware
     * dead-reckoning extrapolation and position interpolation (Phase 6).
     */
    @Synchronized
    fun getRenderableBoxes(renderNowMs: Long): List<NormalizedBox> {
        if (!isOverlayEnabled) return emptyList()
        val subject = activeSubject ?: return emptyList()

        val ageMs = renderNowMs - subject.lastDetectionTimestampMs
        if (ageMs > staleTimeoutMs || ageMs < 0L) {
            activeSubject = null
            currentLifecycleState = TrackingLifecycleState.TRACKING_LOST
            return emptyList()
        }

        val tau = min(ageMs.toFloat(), maxCoastingAgeMs.toFloat())
        val damping = exp(-velocityDampingFactor * tau)

        val dx = subject.velocity.vx * tau * damping
        val dy = subject.velocity.vy * tau * damping
        val dw = subject.velocity.vw * tau * damping
        val dh = subject.velocity.vh * tau * damping

        val outBoxes = ArrayList<NormalizedBox>(3)

        // Interpolate Person Box
        subject.personBox?.let { base ->
            val p = extrapolateBox(base, dx, dy, dw, dh, renderNowMs)
            if (p.isValid()) outBoxes.add(p)
        }

        // Interpolate Upper Torso Box
        subject.upperTorsoBox?.let { base ->
            val t = extrapolateBox(base, dx, dy, dw, dh, renderNowMs)
            if (t.isValid()) outBoxes.add(t)
        }

        // Interpolate Face Box
        subject.faceBox?.let { base ->
            val f = extrapolateBox(base, dx, dy, dw * 0.5f, dh * 0.5f, renderNowMs)
            if (f.isValid()) outBoxes.add(f)
        }

        return outBoxes
    }

    private fun extrapolateBox(
        base: NormalizedBox,
        dx: Float,
        dy: Float,
        dw: Float,
        dh: Float,
        nowMs: Long
    ): NormalizedBox {
        val rawW = (base.right - base.left) + dw
        val rawH = (base.bottom - base.top) + dh
        val w = rawW.coerceIn(0.04f, 0.98f)
        val h = rawH.coerceIn(0.04f, 0.98f)

        val cx = ((base.left + base.right) * 0.5f + dx).coerceIn(w * 0.5f, 1f - w * 0.5f)
        val cy = ((base.top + base.bottom) * 0.5f + dy).coerceIn(h * 0.5f, 1f - h * 0.5f)

        val left = (cx - w * 0.5f).coerceIn(0.01f, 0.95f)
        val right = (cx + w * 0.5f).coerceIn(left + 0.04f, 0.99f)
        val top = (cy - h * 0.5f).coerceIn(0.01f, 0.95f)
        val bottom = (cy + h * 0.5f).coerceIn(top + 0.04f, 0.99f)

        return base.copy(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            timestampMs = nowMs
        )
    }

    /**
     * Reports active diagnostic information for HUD inspection (Phase 12).
     */
    @Synchronized
    fun getDiagnosticSummary(nowMs: Long): String {
        val subject = activeSubject
        val ageMs = if (subject != null && lastDetectionTimestampMs > 0L) {
            max(0L, nowMs - lastDetectionTimestampMs)
        } else {
            0L
        }
        val trackText = if (subject != null) " [ID: ${subject.trackId}]" else ""
        val boxCount = if (subject != null) {
            (if (subject.personBox != null) 1 else 0) +
                (if (subject.upperTorsoBox != null) 1 else 0) +
                (if (subject.faceBox != null) 1 else 0)
        } else {
            0
        }
        val countText = if (boxCount > 0) " • $boxCount BOXES" else ""
        return "${currentLifecycleState.diagnosticLabel}$trackText$countText • ${ageMs}ms"
    }

    @Synchronized
    fun onTrackingLost() {
        activeSubject = null
        currentLifecycleState = TrackingLifecycleState.TRACKING_LOST
    }

    @Synchronized
    fun clearTracking() {
        activeSubject = null
        currentLifecycleState = TrackingLifecycleState.WAITING_FOR_FRAME
    }
}
