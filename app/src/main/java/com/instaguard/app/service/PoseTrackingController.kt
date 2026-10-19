package com.instaguard.app.service

/**
 * Dedicated Pose Tracking Controller (Phase 7 & Section 7).
 *
 * Specializes [BodyTrackingController] for real-time ML Kit anatomical pose tracking:
 * - Tracks moving, turning, leaning, arm-raising, and retreating subjects.
 * - Manages temporal continuity with velocity estimation and dead-reckoning extrapolation.
 * - Rejects out-of-order or stale frame detections.
 * - Purges tracking state immediately when content item transitions.
 * - Enforces zero interior fill and complete transparency on overlays.
 */
class PoseTrackingController(
    maxCoastingAgeMs: Long = 850L,
    staleTimeoutMs: Long = 1200L,
    velocityDampingFactor: Float = 0.0035f
) : BodyTrackingController(
    maxCoastingAgeMs = maxCoastingAgeMs,
    staleTimeoutMs = staleTimeoutMs,
    velocityDampingFactor = velocityDampingFactor
) {
    /**
     * Updates tracking state using validated ML Kit pose landmarks and derived region boxes.
     */
    fun updateWithPoseDetections(
        contentItemId: String,
        timestampMs: Long,
        landmarks: List<PoseLandmark>,
        personBox: NormalizedBox? = null,
        faceBox: NormalizedBox? = null,
        upperTorsoBox: NormalizedBox? = null,
        detectionConfidence: Float = 0.85f,
        nowMs: Long = timestampMs
    ) {
        updateWithFrameDetection(
            contentItemId = contentItemId,
            timestampMs = timestampMs,
            personBox = personBox,
            faceBox = faceBox,
            upperTorsoBox = upperTorsoBox,
            landmarks = landmarks,
            detectionConfidence = detectionConfidence,
            nowMs = nowMs
        )
    }
}
