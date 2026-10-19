package com.instaguard.app.service

import android.graphics.Bitmap
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseDetectorOptions
import com.google.mlkit.vision.pose.PoseLandmark as MlPoseLandmark
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Production-grade Google ML Kit Pose Detection Provider (STREAM_MODE).
 *
 * Implements:
 * - Official ML Kit Pose Detection API in [PoseDetectorOptions.STREAM_MODE] for real-time video frames.
 * - Persistent detector client reuse across frames to preserve temporal tracking state.
 * - Extracts genuine anatomical landmarks (head, shoulders, elbows, wrists, hips, knees, ankles).
 * - Derives separate genuine bounding boxes for FACE, UPPER TORSO, and full PERSON/BODY.
 * - Calculates true confidence values from [MlPoseLandmark.inFrameLikelihood] (never fabricated).
 * - Fails open safely when no custom clothing classifier is bundled.
 * - Automatic fallback to [AndroidSdkFaceInferenceProvider] if Play Services ML Kit is unavailable.
 */
object MlKitPoseInferenceProvider : VisualInferenceProvider {

    private const val TAG = "MlKitPoseProvider"
    private const val MIN_LANDMARK_LIKELIHOOD = 0.45f
    private const val INFERENCE_TIMEOUT_MS = 1400L

    @Volatile
    private var poseDetectorInstance: PoseDetector? = null

    @Volatile
    override var isModelInitialized: Boolean = false
        private set

    override val supportsPersonDetection: Boolean = true
    override val supportsClothingClassification: Boolean = false

    @Synchronized
    fun getOrCreateDetector(): PoseDetector {
        val existing = poseDetectorInstance
        if (existing != null) return existing

        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .setPreferredHardwareConfigs(PoseDetectorOptions.CPU_GPU)
            .build()

        val newDetector = PoseDetection.getClient(options)
        poseDetectorInstance = newDetector
        isModelInitialized = true
        Log.i(TAG, "Initialized Google ML Kit PoseDetector in STREAM_MODE.")
        return newDetector
    }

    override fun analyzeBitmap(
        bitmap: Bitmap,
        contentItemId: String,
        timestampMs: Long,
        isSupportedFeed: Boolean
    ): FrameFeatures {
        return analyzeBitmapWithRotation(
            bitmap = bitmap,
            contentItemId = contentItemId,
            timestampMs = timestampMs,
            isSupportedFeed = isSupportedFeed,
            rotationDegrees = 0
        )
    }

    fun analyzeBitmapWithRotation(
        bitmap: Bitmap,
        contentItemId: String,
        timestampMs: Long,
        isSupportedFeed: Boolean,
        rotationDegrees: Int = 0
    ): FrameFeatures {
        val startNs = System.nanoTime()
        val srcW = bitmap.width
        val srcH = bitmap.height

        if (srcW <= 0 || srcH <= 0 || bitmap.isRecycled) {
            return FrameFeatures(
                contentItemId = contentItemId,
                frameTimestampMs = timestampMs,
                isSupportedVideoFeed = isSupportedFeed,
                analysisFailed = true,
                failureReason = "Invalid source bitmap dimensions (${srcW}x${srcH})"
            )
        }

        return try {
            val detector = getOrCreateDetector()
            val inputImage = InputImage.fromBitmap(bitmap, rotationDegrees)

            // Execute inference synchronously on worker thread via Tasks.await with timeout safeguard
            val task = detector.process(inputImage)
            val pose: Pose = Tasks.await(task, INFERENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val durationMs = (System.nanoTime() - startNs) / 1_000_000L

            processPoseResult(
                pose = pose,
                srcW = srcW,
                srcH = srcH,
                contentItemId = contentItemId,
                timestampMs = timestampMs,
                durationMs = durationMs,
                isSupportedFeed = isSupportedFeed
            )
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit Pose Detection encountered an exception; falling back to AndroidSdkFaceInferenceProvider", e)
            try {
                // Safe fallback to native FaceDetector so video processing never halts
                AndroidSdkFaceInferenceProvider.analyzeBitmap(
                    bitmap = bitmap,
                    contentItemId = contentItemId,
                    timestampMs = timestampMs,
                    isSupportedFeed = isSupportedFeed
                )
            } catch (fallbackEx: Exception) {
                FrameFeatures(
                    contentItemId = contentItemId,
                    frameTimestampMs = timestampMs,
                    isSupportedVideoFeed = isSupportedFeed,
                    analysisFailed = true,
                    failureReason = "Inference failed: ${e.message}"
                )
            }
        }
    }

    private fun processPoseResult(
        pose: Pose,
        srcW: Int,
        srcH: Int,
        contentItemId: String,
        timestampMs: Long,
        durationMs: Long,
        isSupportedFeed: Boolean
    ): FrameFeatures {
        val allMlLandmarks = pose.allPoseLandmarks
        if (allMlLandmarks.isEmpty()) {
            return FrameFeatures(
                contentItemId = contentItemId,
                frameTimestampMs = timestampMs,
                isSupportedVideoFeed = isSupportedFeed,
                modelInitialized = true,
                personDetectorAvailable = true,
                clothingClassifierAvailable = false,
                clothingAnalysisComplete = true,
                qualifyingPersonDetected = false,
                personConfidence = 0.0f,
                detectedBoxes = emptyList(),
                detectedLandmarks = emptyList(),
                inferenceDurationMs = durationMs
            )
        }

        // 1. Extract valid, normalized anatomical landmarks
        val extractedLandmarks = ArrayList<PoseLandmark>(allMlLandmarks.size)
        var totalLikelihood = 0f
        var validCount = 0

        for (mlLm in allMlLandmarks) {
            val likelihood = mlLm.inFrameLikelihood
            if (likelihood < MIN_LANDMARK_LIKELIHOOD) continue

            val mappedType = mapMlKitTypeToLandmarkType(mlLm.landmarkType) ?: continue
            val normX = (mlLm.position.x / srcW.toFloat()).coerceIn(0.0f, 1.0f)
            val normY = (mlLm.position.y / srcH.toFloat()).coerceIn(0.0f, 1.0f)

            extractedLandmarks.add(
                PoseLandmark(
                    type = mappedType,
                    x = normX,
                    y = normY,
                    confidence = likelihood
                )
            )
            totalLikelihood += likelihood
            validCount++
        }

        if (validCount < 2) {
            return FrameFeatures(
                contentItemId = contentItemId,
                frameTimestampMs = timestampMs,
                isSupportedVideoFeed = isSupportedFeed,
                modelInitialized = true,
                personDetectorAvailable = true,
                clothingClassifierAvailable = false,
                clothingAnalysisComplete = true,
                qualifyingPersonDetected = false,
                personConfidence = if (validCount > 0) totalLikelihood / validCount else 0.0f,
                detectedBoxes = emptyList(),
                detectedLandmarks = extractedLandmarks,
                inferenceDurationMs = durationMs
            )
        }

        val overallPersonConfidence = (totalLikelihood / validCount).coerceIn(0.1f, 1.0f)
        val boxes = ArrayList<NormalizedBox>(3)

        // 2. Derive genuine FACE Bounding Box from face landmarks
        val faceLandmarks = extractedLandmarks.filter { lm ->
            lm.type in setOf(
                LandmarkType.NOSE,
                LandmarkType.LEFT_EYE,
                LandmarkType.RIGHT_EYE,
                LandmarkType.LEFT_EAR,
                LandmarkType.RIGHT_EAR,
                LandmarkType.MOUTH_LEFT,
                LandmarkType.MOUTH_RIGHT
            )
        }
        if (faceLandmarks.isNotEmpty()) {
            val minX = faceLandmarks.minOf { it.x }
            val maxX = faceLandmarks.maxOf { it.x }
            val minY = faceLandmarks.minOf { it.y }
            val maxY = faceLandmarks.maxOf { it.y }
            val rawW = max(0.06f, maxX - minX)
            val rawH = max(0.08f, maxY - minY)

            // Cranial & chin anatomical margin
            val fLeft = (minX - rawW * 0.25f).coerceIn(0.01f, 0.95f)
            val fRight = (maxX + rawW * 0.25f).coerceIn(fLeft + 0.05f, 0.99f)
            val fTop = (minY - rawH * 0.35f).coerceIn(0.01f, 0.95f)
            val fBottom = (maxY + rawH * 0.20f).coerceIn(fTop + 0.05f, 0.99f)
            val faceConf = faceLandmarks.map { it.confidence }.average().toFloat().coerceIn(0.1f, 1.0f)

            boxes.add(
                NormalizedBox(
                    trackId = 1,
                    regionLabel = "FACE",
                    confidence = faceConf,
                    left = fLeft,
                    top = fTop,
                    right = fRight,
                    bottom = fBottom,
                    timestampMs = timestampMs,
                    sourceWidth = srcW,
                    sourceHeight = srcH
                )
            )
        }

        // 3. Derive genuine UPPER TORSO Bounding Box from shoulders and hips
        val leftShoulder = extractedLandmarks.firstOrNull { it.type == LandmarkType.LEFT_SHOULDER }
        val rightShoulder = extractedLandmarks.firstOrNull { it.type == LandmarkType.RIGHT_SHOULDER }
        val leftHip = extractedLandmarks.firstOrNull { it.type == LandmarkType.LEFT_HIP }
        val rightHip = extractedLandmarks.firstOrNull { it.type == LandmarkType.RIGHT_HIP }

        if (leftShoulder != null && rightShoulder != null) {
            val shoulderMinX = min(leftShoulder.x, rightShoulder.x)
            val shoulderMaxX = max(leftShoulder.x, rightShoulder.x)
            val shoulderY = (leftShoulder.y + rightShoulder.y) * 0.5f
            val shoulderSpan = max(0.10f, shoulderMaxX - shoulderMinX)

            val torsoLeft = (shoulderMinX - shoulderSpan * 0.12f).coerceIn(0.01f, 0.95f)
            val torsoRight = (shoulderMaxX + shoulderSpan * 0.12f).coerceIn(torsoLeft + 0.06f, 0.99f)
            val torsoTop = (shoulderY - shoulderSpan * 0.22f).coerceIn(0.01f, 0.95f)

            val torsoBottom = if (leftHip != null && rightHip != null) {
                ((leftHip.y + rightHip.y) * 0.5f).coerceIn(torsoTop + 0.08f, 0.99f)
            } else if (leftHip != null) {
                leftHip.y.coerceIn(torsoTop + 0.08f, 0.99f)
            } else if (rightHip != null) {
                rightHip.y.coerceIn(torsoTop + 0.08f, 0.99f)
            } else {
                (torsoTop + shoulderSpan * 1.35f).coerceIn(torsoTop + 0.08f, 0.99f)
            }

            val contributingTorsoLms = listOfNotNull(leftShoulder, rightShoulder, leftHip, rightHip)
            val torsoConf = contributingTorsoLms.map { it.confidence }.average().toFloat().coerceIn(0.1f, 1.0f)

            boxes.add(
                NormalizedBox(
                    trackId = 1,
                    regionLabel = "UPPER TORSO",
                    confidence = torsoConf,
                    left = torsoLeft,
                    top = torsoTop,
                    right = torsoRight,
                    bottom = torsoBottom,
                    timestampMs = timestampMs,
                    sourceWidth = srcW,
                    sourceHeight = srcH
                )
            )
        }

        // 4. Derive genuine PERSON / BODY Bounding Box enclosing all visible landmarks
        val bodyMinX = extractedLandmarks.minOf { it.x }
        val bodyMaxX = extractedLandmarks.maxOf { it.x }
        val bodyMinY = extractedLandmarks.minOf { it.y }
        val bodyMaxY = extractedLandmarks.maxOf { it.y }
        val spanX = max(0.10f, bodyMaxX - bodyMinX)
        val spanY = max(0.15f, bodyMaxY - bodyMinY)

        val pLeft = (bodyMinX - spanX * 0.10f).coerceIn(0.01f, 0.95f)
        val pRight = (bodyMaxX + spanX * 0.10f).coerceIn(pLeft + 0.06f, 0.99f)
        val pTop = (bodyMinY - spanY * 0.08f).coerceIn(0.01f, 0.95f)
        val pBottom = (bodyMaxY + spanY * 0.05f).coerceIn(pTop + 0.08f, 0.99f)

        boxes.add(
            NormalizedBox(
                trackId = 1,
                regionLabel = "PERSON",
                confidence = overallPersonConfidence,
                left = pLeft,
                top = pTop,
                right = pRight,
                bottom = pBottom,
                timestampMs = timestampMs,
                sourceWidth = srcW,
                sourceHeight = srcH
            )
        )

        return FrameFeatures(
            contentItemId = contentItemId,
            frameTimestampMs = timestampMs,
            isSupportedVideoFeed = isSupportedFeed,
            modelInitialized = true,
            personDetectorAvailable = true,
            clothingClassifierAvailable = false,
            clothingAnalysisComplete = true,
            qualifyingPersonDetected = true,
            personConfidence = overallPersonConfidence,
            detectedBoxes = boxes,
            detectedLandmarks = extractedLandmarks,
            inferenceDurationMs = durationMs,
            failureReason = "Visible subject detected; trained clothing classifier model (TFLite) not bundled in assets"
        )
    }

    private fun mapMlKitTypeToLandmarkType(mlType: Int): LandmarkType? {
        return when (mlType) {
            MlPoseLandmark.NOSE -> LandmarkType.NOSE
            MlPoseLandmark.LEFT_EYE_INNER -> LandmarkType.LEFT_EYE_INNER
            MlPoseLandmark.LEFT_EYE -> LandmarkType.LEFT_EYE
            MlPoseLandmark.LEFT_EYE_OUTER -> LandmarkType.LEFT_EYE_OUTER
            MlPoseLandmark.RIGHT_EYE_INNER -> LandmarkType.RIGHT_EYE_INNER
            MlPoseLandmark.RIGHT_EYE -> LandmarkType.RIGHT_EYE
            MlPoseLandmark.RIGHT_EYE_OUTER -> LandmarkType.RIGHT_EYE_OUTER
            MlPoseLandmark.LEFT_EAR -> LandmarkType.LEFT_EAR
            MlPoseLandmark.RIGHT_EAR -> LandmarkType.RIGHT_EAR
            MlPoseLandmark.MOUTH_LEFT -> LandmarkType.MOUTH_LEFT
            MlPoseLandmark.MOUTH_RIGHT -> LandmarkType.MOUTH_RIGHT
            MlPoseLandmark.LEFT_SHOULDER -> LandmarkType.LEFT_SHOULDER
            MlPoseLandmark.RIGHT_SHOULDER -> LandmarkType.RIGHT_SHOULDER
            MlPoseLandmark.LEFT_ELBOW -> LandmarkType.LEFT_ELBOW
            MlPoseLandmark.RIGHT_ELBOW -> LandmarkType.RIGHT_ELBOW
            MlPoseLandmark.LEFT_WRIST -> LandmarkType.LEFT_WRIST
            MlPoseLandmark.RIGHT_WRIST -> LandmarkType.RIGHT_WRIST
            MlPoseLandmark.LEFT_PINKY -> LandmarkType.LEFT_PINKY
            MlPoseLandmark.RIGHT_PINKY -> LandmarkType.RIGHT_PINKY
            MlPoseLandmark.LEFT_INDEX -> LandmarkType.LEFT_INDEX
            MlPoseLandmark.RIGHT_INDEX -> LandmarkType.RIGHT_INDEX
            MlPoseLandmark.LEFT_THUMB -> LandmarkType.LEFT_THUMB
            MlPoseLandmark.RIGHT_THUMB -> LandmarkType.RIGHT_THUMB
            MlPoseLandmark.LEFT_HIP -> LandmarkType.LEFT_HIP
            MlPoseLandmark.RIGHT_HIP -> LandmarkType.RIGHT_HIP
            MlPoseLandmark.LEFT_KNEE -> LandmarkType.LEFT_KNEE
            MlPoseLandmark.RIGHT_KNEE -> LandmarkType.RIGHT_KNEE
            MlPoseLandmark.LEFT_ANKLE -> LandmarkType.LEFT_ANKLE
            MlPoseLandmark.RIGHT_ANKLE -> LandmarkType.RIGHT_ANKLE
            MlPoseLandmark.LEFT_HEEL -> LandmarkType.LEFT_HEEL
            MlPoseLandmark.RIGHT_HEEL -> LandmarkType.RIGHT_HEEL
            MlPoseLandmark.LEFT_FOOT_INDEX -> LandmarkType.LEFT_FOOT_INDEX
            MlPoseLandmark.RIGHT_FOOT_INDEX -> LandmarkType.RIGHT_FOOT_INDEX
            else -> null
        }
    }

    @Synchronized
    fun close() {
        try {
            poseDetectorInstance?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing PoseDetector", e)
        } finally {
            poseDetectorInstance = null
            isModelInitialized = false
        }
    }
}
