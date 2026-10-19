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
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.mlkit.vision.pose.PoseLandmark as MlPoseLandmark
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Production-grade Google ML Kit Pose Detection & TFLite Selfie Segmentation Provider (STREAM_MODE).
 *
 * Implements:
 * - Official ML Kit Pose Detection API in [PoseDetectorOptions.STREAM_MODE] for real-time video frames.
 * - Official ML Kit TFLite Selfie Segmentation API in [SelfieSegmenterOptions.STREAM_MODE] for foreground human masks.
 * - Persistent detector client reuse across frames to preserve temporal tracking state.
 * - GATE 1: TFLite Human Foreground Verification (>= 12% human) + ML Kit 33-Landmark Skeleton Lock.
 * - GATE 2: Male Rejection & Personalized Face-Calibrated Skin-Tone Dermis Lock.
 * - GATE 3: Short Dress Analyzer ("Shoulder se Niche" + "Ghutne se Upar" + Legs/Feet Visible).
 * - GATE 4: Female Bust / Cleavage Analyzer (Left hemisphere, center cleavage cleft shadow, right hemisphere, fabric contrast).
 * - GATE 5: Rear / Back / ASS / Buttocks Analyzer (Rear orientation, glute skin & cleft shadow, back skin, silhouette).
 */
object MlKitPoseInferenceProvider : VisualInferenceProvider {

    private const val TAG = "MlKitPoseProvider"
    private const val MIN_LANDMARK_LIKELIHOOD = 0.45f
    private const val INFERENCE_TIMEOUT_MS = 1400L

    @Volatile
    private var poseDetectorInstance: PoseDetector? = null

    @Volatile
    private var selfieSegmenterInstance: Segmenter? = null

    @Volatile
    override var isModelInitialized: Boolean = false
        private set

    override val supportsPersonDetection: Boolean = true
    override val supportsClothingClassification: Boolean = true

    @Synchronized
    fun getOrCreateDetector(): PoseDetector {
        val existing = poseDetectorInstance
        if (existing != null) return existing

        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()

        val newDetector = PoseDetection.getClient(options)
        poseDetectorInstance = newDetector
        isModelInitialized = true
        Log.i(TAG, "Initialized Google ML Kit PoseDetector in STREAM_MODE.")
        return newDetector
    }

    @Synchronized
    fun getOrCreateSegmenter(): Segmenter {
        val existing = selfieSegmenterInstance
        if (existing != null) return existing

        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            .build()

        val newSegmenter = Segmentation.getClient(options)
        selfieSegmenterInstance = newSegmenter
        Log.i(TAG, "Initialized Google ML Kit SelfieSegmenter in STREAM_MODE.")
        return newSegmenter
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
            val segmenter = getOrCreateSegmenter()
            val poseInput = InputImage.fromBitmap(bitmap, rotationDegrees)
            val segInput = InputImage.fromBitmap(bitmap, rotationDegrees)

            // Execute inference synchronously on worker thread via Tasks.await with timeout safeguard
            val poseTask = detector.process(poseInput)
            val segTask = segmenter.process(segInput)

            val pose: Pose = Tasks.await(poseTask, INFERENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val segMask: SegmentationMask? = try {
                Tasks.await(segTask, INFERENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (segEx: Exception) {
                Log.w(TAG, "Selfie segmentation task timed out or failed; proceeding with pose only", segEx)
                null
            }
            val durationMs = (System.nanoTime() - startNs) / 1_000_000L

            processPoseResult(
                pose = pose,
                segMask = segMask,
                bitmap = bitmap,
                srcW = srcW,
                srcH = srcH,
                contentItemId = contentItemId,
                timestampMs = timestampMs,
                durationMs = durationMs,
                isSupportedFeed = isSupportedFeed
            )
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit Vision inference encountered an exception; falling back to AndroidSdkFaceInferenceProvider", e)
            try {
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
        segMask: SegmentationMask?,
        bitmap: Bitmap,
        srcW: Int,
        srcH: Int,
        contentItemId: String,
        timestampMs: Long,
        durationMs: Long,
        isSupportedFeed: Boolean
    ): FrameFeatures {
        // TFLite Human Selfie Segmentation Mask Evaluation (Gate 1: >= 12% human foreground)
        var humanForegroundRatio = 0f
        var maskBuffer: ByteBuffer? = null
        var maskW = 0
        var maskH = 0

        if (segMask != null) {
            maskW = segMask.width
            maskH = segMask.height
            val buf = segMask.buffer
            buf.rewind()
            val totalMaskPixels = maskW * maskH
            if (totalMaskPixels > 0) {
                var fgCount = 0
                for (i in 0 until totalMaskPixels) {
                    if (buf.getFloat() >= 0.50f) {
                        fgCount++
                    }
                }
                humanForegroundRatio = fgCount.toFloat() / totalMaskPixels
                buf.rewind()
                maskBuffer = buf
            }

            // Gate 1 Fail-Open: If human foreground is less than 12%, this is a non-human video
            // (e.g. landscape, cars, animals, gaming, food, text, graphics, room).
            if (humanForegroundRatio < 0.12f) {
                return FrameFeatures(
                    contentItemId = contentItemId,
                    frameTimestampMs = timestampMs,
                    isSupportedVideoFeed = isSupportedFeed,
                    modelInitialized = true,
                    personDetectorAvailable = true,
                    clothingClassifierAvailable = false,
                    clothingAnalysisComplete = true,
                    qualifyingPersonDetected = false,
                    isFemaleConfirmed = false,
                    isMalePresentingOnly = false,
                    personConfidence = 0.0f,
                    detectedBoxes = emptyList(),
                    detectedLandmarks = emptyList(),
                    inferenceDurationMs = durationMs,
                    failureReason = "Non-human video feed (< 12% human foreground) — ALLOW"
                )
            }
        }

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
                isFemaleConfirmed = false,
                isMalePresentingOnly = false,
                personConfidence = 0.0f,
                detectedBoxes = emptyList(),
                detectedLandmarks = emptyList(),
                inferenceDurationMs = durationMs,
                failureReason = "No landmarks detected — ALLOW"
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
                isFemaleConfirmed = false,
                isMalePresentingOnly = false,
                personConfidence = if (validCount > 0) totalLikelihood / validCount else 0.0f,
                detectedBoxes = emptyList(),
                detectedLandmarks = extractedLandmarks,
                inferenceDurationMs = durationMs,
                failureReason = "Insufficient valid landmarks — ALLOW"
            )
        }

        val overallPersonConfidence = (totalLikelihood / validCount).coerceIn(0.1f, 1.0f)
        val boxes = ArrayList<NormalizedBox>(4)

        // Core Landmarks
        val nose = extractedLandmarks.firstOrNull { it.type == LandmarkType.NOSE }
        val leftEye = extractedLandmarks.firstOrNull { it.type == LandmarkType.LEFT_EYE }
        val rightEye = extractedLandmarks.firstOrNull { it.type == LandmarkType.RIGHT_EYE }
        val leftEar = extractedLandmarks.firstOrNull { it.type == LandmarkType.LEFT_EAR }
        val rightEar = extractedLandmarks.firstOrNull { it.type == LandmarkType.RIGHT_EAR }
        val leftMouth = extractedLandmarks.firstOrNull { it.type == LandmarkType.MOUTH_LEFT }
        val rightMouth = extractedLandmarks.firstOrNull { it.type == LandmarkType.MOUTH_RIGHT }
        val leftShoulder = extractedLandmarks.firstOrNull { it.type == LandmarkType.LEFT_SHOULDER }
        val rightShoulder = extractedLandmarks.firstOrNull { it.type == LandmarkType.RIGHT_SHOULDER }
        val leftHip = extractedLandmarks.firstOrNull { it.type == LandmarkType.LEFT_HIP }
        val rightHip = extractedLandmarks.firstOrNull { it.type == LandmarkType.RIGHT_HIP }
        val leftKnee = extractedLandmarks.firstOrNull { it.type == LandmarkType.LEFT_KNEE }
        val rightKnee = extractedLandmarks.firstOrNull { it.type == LandmarkType.RIGHT_KNEE }
        val leftAnkle = extractedLandmarks.firstOrNull { it.type == LandmarkType.LEFT_ANKLE }
        val rightAnkle = extractedLandmarks.firstOrNull { it.type == LandmarkType.RIGHT_ANKLE }

        // GATE 1: Strict 33-Landmark Skeleton Lock
        val upperKeyLms = listOfNotNull(nose, leftShoulder, rightShoulder)
        val lowerKeyLms = listOfNotNull(leftHip, rightHip, leftKnee, rightKnee)
        val hasUpperSkeleton = upperKeyLms.count { it.confidence >= 0.72f } >= 2
        val hasLowerSkeleton = lowerKeyLms.count { it.confidence >= 0.72f } >= 2

        if (!hasUpperSkeleton && !hasLowerSkeleton) {
            // There is NO human skeleton in this frame (car, nature, animal, gaming, food, or text video).
            return FrameFeatures(
                contentItemId = contentItemId,
                frameTimestampMs = timestampMs,
                isSupportedVideoFeed = isSupportedFeed,
                modelInitialized = true,
                personDetectorAvailable = true,
                clothingClassifierAvailable = false,
                clothingAnalysisComplete = true,
                qualifyingPersonDetected = false,
                isFemaleConfirmed = false,
                isMalePresentingOnly = false,
                personConfidence = 0.0f,
                detectedBoxes = emptyList(),
                detectedLandmarks = extractedLandmarks,
                inferenceDurationMs = durationMs,
                failureReason = "No confirmed 33-landmark skeleton (>= 0.72f) — ALLOW"
            )
        }

        // Helper pixel functions
        fun getPixelSafe(x: Int, y: Int): Int {
            val px = x.coerceIn(0, srcW - 1)
            val py = y.coerceIn(0, srcH - 1)
            return bitmap.getPixel(px, py)
        }

        fun luminance(pixel: Int): Float {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            return 0.299f * r + 0.587f * g + 0.114f * b
        }

        // Measure shoulder width & hip width
        val shoulderW = if (leftShoulder != null && rightShoulder != null) {
            abs(leftShoulder.x - rightShoulder.x) * srcW
        } else 0f

        val hipW = if (leftHip != null && rightHip != null) {
            abs(leftHip.x - rightHip.x) * srcW
        } else 0f

        // Sample cheek luminance vs lower jaw/chin luminance for facial hair/stubble detection
        var cheekLumaSum = 0f
        var cheekCount = 0
        if (nose != null) {
            val nX = (nose.x * srcW).toInt()
            val nY = (nose.y * srcH).toInt()
            val dx = (srcW * 0.04f).toInt().coerceAtLeast(8)
            for (step in 1..4) {
                val pL = getPixelSafe(nX - dx * step / 2, nY)
                val pR = getPixelSafe(nX + dx * step / 2, nY)
                cheekLumaSum += luminance(pL) + luminance(pR)
                cheekCount += 2
            }
        }
        val cheekLuminance = if (cheekCount > 0) cheekLumaSum / cheekCount else 128f

        var jawLumaSum = 0f
        var jawCount = 0
        if (nose != null) {
            val nX = (nose.x * srcW).toInt()
            val mouthY = if (leftMouth != null && rightMouth != null) {
                ((leftMouth.y + rightMouth.y) * 0.5f * srcH).toInt()
            } else {
                (nose.y * srcH + srcH * 0.04f).toInt()
            }
            val dy = (srcH * 0.015f).toInt().coerceAtLeast(4)
            for (step in 1..4) {
                val pJaw = getPixelSafe(nX, mouthY + dy * step)
                jawLumaSum += luminance(pJaw)
                jawCount++
            }
        }
        val jawLuminance = if (jawCount > 0) jawLumaSum / jawCount else 128f

        val hasBeardOrMoustacheShadow = jawCount > 0 && (jawLuminance < cheekLuminance - 14.0f)
        val hasMasculineVTorso = shoulderW > 0f && hipW > 0f && (shoulderW / max(hipW, 1f) >= 1.30f)

        // Check female hair signature (long hair alongside neck past ears down to shoulders)
        var hairPixelCount = 0
        var hairSampleCount = 0
        if (leftEar != null && leftShoulder != null) {
            val startY = (leftEar.y * srcH).toInt()
            val endY = (leftShoulder.y * srcH).toInt()
            val xPos = (leftEar.x * srcW).toInt()
            if (endY > startY) {
                val stepY = max(1, (endY - startY) / 6)
                for (y in startY..endY step stepY) {
                    val p = getPixelSafe(xPos, y)
                    if (luminance(p) < 60f) hairPixelCount++
                    hairSampleCount++
                }
            }
        }
        if (rightEar != null && rightShoulder != null) {
            val startY = (rightEar.y * srcH).toInt()
            val endY = (rightShoulder.y * srcH).toInt()
            val xPos = (rightEar.x * srcW).toInt()
            if (endY > startY) {
                val stepY = max(1, (endY - startY) / 6)
                for (y in startY..endY step stepY) {
                    val p = getPixelSafe(xPos, y)
                    if (luminance(p) < 60f) hairPixelCount++
                    hairSampleCount++
                }
            }
        }
        val hasLongFemaleHair = hairSampleCount > 0 && (hairPixelCount.toFloat() / hairSampleCount >= 0.40f)

        // Reject male subjects
        val isMaleSubject = hasBeardOrMoustacheShadow || (hasMasculineVTorso && !hasLongFemaleHair)

        val eyesConfidence = (leftEye?.confidence ?: 0f) + (rightEye?.confidence ?: 0f)
        val noseConfidence = nose?.confidence ?: 0f
        val isShowingBackOrAssPose = (eyesConfidence < 0.60f && noseConfidence < 0.50f) ||
            (hasLongFemaleHair && eyesConfidence < 0.80f)

        // Check female visual signatures
        val hasFemaleSilhouette = (hipW > 0f && shoulderW > 0f && (hipW / shoulderW >= 0.80f))
        val hasSmoothFaceAndContour = !hasBeardOrMoustacheShadow && abs(jawLuminance - cheekLuminance) < 10.0f
        val femaleSignaturesCount = listOf(hasLongFemaleHair, hasFemaleSilhouette, hasSmoothFaceAndContour).count { it }
        val isFrontFemale = !isMaleSubject && (femaleSignaturesCount >= 2)
        val isRearFemale = isShowingBackOrAssPose && !isMaleSubject && (hasLongFemaleHair || hasFemaleSilhouette || (hipW > 0f && shoulderW > 0f && hipW / shoulderW >= 0.75f))
        val isFemaleConfirmed = isFrontFemale || isRearFemale

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

        // 3. Derive genuine BODY Bounding Box enclosing visible landmarks
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
                regionLabel = "BODY",
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

        // STRICT RULE #1: If there is NO female confirmed in frame, IMMEDIATELY return ALLOW!
        if (!isFemaleConfirmed) {
            return FrameFeatures(
                contentItemId = contentItemId,
                frameTimestampMs = timestampMs,
                isSupportedVideoFeed = isSupportedFeed,
                modelInitialized = true,
                personDetectorAvailable = true,
                clothingClassifierAvailable = false,
                clothingAnalysisComplete = true,
                qualifyingPersonDetected = true,
                isFemaleConfirmed = false,
                isMalePresentingOnly = isMaleSubject,
                personConfidence = overallPersonConfidence,
                detectedBoxes = boxes,
                detectedLandmarks = extractedLandmarks,
                inferenceDurationMs = durationMs,
                failureReason = if (isMaleSubject) "Male subject detected — ALLOW" else "No confirmed female in frame — ALLOW"
            )
        }

        // =========================================================================
        // GATE 2: Personalized Skin-Tone Calibration
        // =========================================================================
        fun isStrictDermisPixel(r: Int, g: Int, b: Int): Boolean {
            if (r <= 75 || r <= g || g <= b || (r - g) < 11) return false
            val cb = 128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b
            val cr = 128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b
            return cb in 82.0..122.0 && cr in 136.0..170.0
        }

        var refCbSum = 0.0
        var refCrSum = 0.0
        var refSkinCount = 0

        val samplePoints = mutableListOf<PointF>()
        if (nose != null) samplePoints.add(PointF(nose.x, nose.y))
        if (leftMouth != null && rightMouth != null) {
            samplePoints.add(PointF((leftMouth.x + rightMouth.x) * 0.5f, (leftMouth.y + rightMouth.y) * 0.5f))
        }
        if (leftShoulder != null && rightShoulder != null) {
            val neckX = (leftShoulder.x + rightShoulder.x) * 0.5f
            val neckY = ((leftShoulder.y + rightShoulder.y) * 0.5f - 0.03f).coerceAtLeast(0f)
            samplePoints.add(PointF(neckX, neckY))
        }

        for (pt in samplePoints) {
            val cx = (pt.x * srcW).toInt()
            val cy = (pt.y * srcH).toInt()
            for (dy in -3..3) {
                for (dx in -3..3) {
                    val p = getPixelSafe(cx + dx * 2, cy + dy * 2)
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    if (isStrictDermisPixel(r, g, b)) {
                        val cb = 128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b
                        val cr = 128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b
                        refCbSum += cb
                        refCrSum += cr
                        refSkinCount++
                    }
                }
            }
        }

        val hasRefSkin = refSkinCount >= 4
        val refCb = if (hasRefSkin) (refCbSum / refSkinCount).toFloat() else 102.0f
        val refCr = if (hasRefSkin) (refCrSum / refSkinCount).toFloat() else 153.0f

        // Sample background outer 8% screen border
        var borderCbSum = 0.0
        var borderCrSum = 0.0
        var borderLumaSum = 0.0
        var borderSampleCount = 0

        val borderLeftMaxX = (srcW * 0.08f).toInt().coerceAtLeast(2)
        val borderRightMinX = (srcW * 0.92f).toInt().coerceAtMost(srcW - 3)

        for (yStep in 1..8) {
            val sy = (srcH * yStep / 9)
            val pLeft = getPixelSafe(borderLeftMaxX / 2, sy)
            val pRight = getPixelSafe(borderRightMinX + (srcW - borderRightMinX) / 2, sy)
            for (p in listOf(pLeft, pRight)) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val yVal = 0.299f * r + 0.587f * g + 0.114f * b
                val cb = 128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b
                val cr = 128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b
                borderCbSum += cb
                borderCrSum += cr
                borderLumaSum += yVal
                borderSampleCount++
            }
        }

        val hasBorderSample = borderSampleCount > 0
        val borderCb = if (hasBorderSample) (borderCbSum / borderSampleCount).toFloat() else 128f
        val borderCr = if (hasBorderSample) (borderCrSum / borderSampleCount).toFloat() else 128f
        val borderLuma = if (hasBorderSample) (borderLumaSum / borderSampleCount).toFloat() else 128f

        fun isPointOnHumanBody(normX: Float, normY: Float): Boolean {
            if (maskW <= 0 || maskH <= 0 || maskBuffer == null) return true
            val mx = (normX * maskW).toInt().coerceIn(0, maskW - 1)
            val my = (normY * maskH).toInt().coerceIn(0, maskH - 1)
            val byteOffset = (my * maskW + mx) * 4
            val conf = maskBuffer.getFloat(byteOffset)
            return conf >= 0.35f
        }

        fun isSubjectBareSkin(pixel: Int, normX: Float, normY: Float): Boolean {
            if (!isPointOnHumanBody(normX, normY)) return false
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            if (!isStrictDermisPixel(r, g, b)) return false

            val yVal = 0.299f * r + 0.587f * g + 0.114f * b
            val cb = (128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b).toFloat()
            val cr = (128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b).toFloat()

            // Must NOT match background border wall
            if (hasBorderSample) {
                val distBorder = hypot(cb - borderCb, cr - borderCr)
                if (distBorder < 6.0f && abs(yVal - borderLuma) < 15.0f) {
                    return false
                }
            }

            // Must match person's own face/neck calibrated skin tone
            if (hasRefSkin) {
                val distRef = hypot(cb - refCb, cr - refCr)
                if (distRef > 18.0f) {
                    return false
                }
            }
            return true
        }

        fun isSubjectBareSkin(pixel: Int, x: Int, y: Int): Boolean {
            return isSubjectBareSkin(pixel, x.toFloat() / srcW, y.toFloat() / srcH)
        }

        fun isSubjectBareSkin(pixel: Int): Boolean {
            return isSubjectBareSkin(pixel, 0.5f, 0.5f)
        }

        fun isSubjectClothingFabric(pixel: Int, x: Int, y: Int): Boolean {
            val normX = x.toFloat() / srcW
            val normY = y.toFloat() / srcH
            if (!isPointOnHumanBody(normX, normY)) return false
            return !isSubjectBareSkin(pixel, normX, normY)
        }

        // =========================================================================
        // GATE 3: Short Dress Analyzer ("Shoulder se Niche" + "Ghutne se Upar" + Legs/Feet Visible)
        // =========================================================================
        var shortDressRuleMatched = false
        var shoulderUncoveredConfidence = 0.0f
        var hemlineAboveKneeConfidence = 0.0f

        val shoulderY = if (leftShoulder != null && rightShoulder != null) {
            (leftShoulder.y + rightShoulder.y) * 0.5f
        } else leftShoulder?.y ?: rightShoulder?.y ?: 0.25f

        val hipY = if (leftHip != null && rightHip != null) {
            (leftHip.y + rightHip.y) * 0.5f
        } else leftHip?.y ?: rightHip?.y ?: 0.60f

        val torsoHeight = max(0.15f, hipY - shoulderY)

        // Check A: "Shoulder se Niche" (Below shoulders)
        val sZoneTopY = (shoulderY * srcH).toInt()
        val sZoneBottomY = ((shoulderY + 0.32f * torsoHeight) * srcH).toInt()
        val sMinX = ((min(leftShoulder?.x ?: 0.35f, rightShoulder?.x ?: 0.65f) + 0.02f) * srcW).toInt()
        val sMaxX = ((max(leftShoulder?.x ?: 0.35f, rightShoulder?.x ?: 0.65f) - 0.02f) * srcW).toInt()

        var sSkinPixels = 0
        var sTotalPixels = 0
        if (sZoneBottomY > sZoneTopY && sMaxX > sMinX) {
            val stepY = max(1, (sZoneBottomY - sZoneTopY) / 5)
            val stepX = max(1, (sMaxX - sMinX) / 8)
            for (y in sZoneTopY..sZoneBottomY step stepY) {
                for (x in sMinX..sMaxX step stepX) {
                    val p = getPixelSafe(x, y)
                    if (isSubjectBareSkin(p, x, y)) sSkinPixels++
                    sTotalPixels++
                }
            }
        }
        val shoulderAndUpperChestBareSkinRatio = if (sTotalPixels > 0) sSkinPixels.toFloat() / sTotalPixels else 0f
        val isBelowShoulders = shoulderAndUpperChestBareSkinRatio >= 0.36f

        // Check B: "Ghutne se Upar" (Above knees) with Legs/Feet Visible
        val kneesVisible = leftKnee != null && rightKnee != null &&
            leftKnee.confidence >= 0.70f && rightKnee.confidence >= 0.70f

        var isAboveKneesWithLegsVisible = false
        if (kneesVisible) {
            val kneeY = (leftKnee!!.y + rightKnee!!.y) * 0.5f
            val thighLength = max(0.10f, kneeY - hipY)
            val ankleY = if (leftAnkle != null && rightAnkle != null) {
                (leftAnkle.y + rightAnkle.y) * 0.5f
            } else kneeY + thighLength * 0.85f

            // Torso dress fabric ratio
            var torsoFabricCount = 0
            var torsoSampleCount = 0
            val tTop = ((shoulderY + 0.35f * torsoHeight) * srcH).toInt()
            val tBottom = ((hipY - 0.05f * torsoHeight) * srcH).toInt()
            if (tBottom > tTop && sMaxX > sMinX) {
                val stepY = max(1, (tBottom - tTop) / 6)
                val stepX = max(1, (sMaxX - sMinX) / 8)
                for (y in tTop..tBottom step stepY) {
                    for (x in sMinX..sMaxX step stepX) {
                        val p = getPixelSafe(x, y)
                        if (isSubjectClothingFabric(p, x, y)) torsoFabricCount++
                        torsoSampleCount++
                    }
                }
            }
            val torsoClothingRatio = if (torsoSampleCount > 0) torsoFabricCount.toFloat() / torsoSampleCount else 0f

            // Thigh zone (hipY to kneeY)
            var thighSkinCount = 0
            var thighSampleCount = 0
            val thTop = (hipY * srcH).toInt()
            val thBottom = (kneeY * srcH).toInt()
            var hemlineNormY = hipY

            if (thBottom > thTop && sMaxX > sMinX) {
                val stepY = max(1, (thBottom - thTop) / 8)
                val stepX = max(1, (sMaxX - sMinX) / 8)
                for (y in thTop..thBottom step stepY) {
                    var rowSkinCount = 0
                    var rowTotal = 0
                    for (x in sMinX..sMaxX step stepX) {
                        val p = getPixelSafe(x, y)
                        if (isSubjectBareSkin(p, x, y)) {
                            thighSkinCount++
                            rowSkinCount++
                        }
                        thighSampleCount++
                        rowTotal++
                    }
                    if (rowTotal > 0 && (rowSkinCount.toFloat() / rowTotal >= 0.40f) && hemlineNormY == hipY) {
                        hemlineNormY = y.toFloat() / srcH
                    }
                }
            }
            val thighBareSkinRatio = if (thighSampleCount > 0) thighSkinCount.toFloat() / thighSampleCount else 0f

            // Lower leg / feet zone (kneeY to ankleY)
            var lowerLegSkinCount = 0
            var lowerLegSampleCount = 0
            val llTop = (kneeY * srcH).toInt()
            val llBottom = (ankleY * srcH).toInt()
            if (llBottom > llTop && sMaxX > sMinX) {
                val stepY = max(1, (llBottom - llTop) / 6)
                val stepX = max(1, (sMaxX - sMinX) / 6)
                for (y in llTop..llBottom step stepY) {
                    for (x in sMinX..sMaxX step stepX) {
                        val p = getPixelSafe(x, y)
                        if (isSubjectBareSkin(p, x, y)) lowerLegSkinCount++
                        lowerLegSampleCount++
                    }
                }
            }
            val lowerLegBareSkinRatio = if (lowerLegSampleCount > 0) lowerLegSkinCount.toFloat() / lowerLegSampleCount else 0f

            val hemlineAboveKnees = hemlineNormY <= (kneeY - 0.18f * thighLength)
            isAboveKneesWithLegsVisible = torsoClothingRatio >= 0.35f &&
                hemlineAboveKnees &&
                thighBareSkinRatio >= 0.42f &&
                lowerLegBareSkinRatio >= 0.30f
        }

        shortDressRuleMatched = isBelowShoulders && isAboveKneesWithLegsVisible
        if (shortDressRuleMatched) {
            shoulderUncoveredConfidence = shoulderAndUpperChestBareSkinRatio.coerceIn(0.80f, 0.99f)
            hemlineAboveKneeConfidence = 0.88f
            val kneeYNorm = (leftKnee?.y ?: 0.70f + (rightKnee?.y ?: 0.70f)) * 0.5f
            val ankleYNorm = (leftAnkle?.y ?: 0.92f + (rightAnkle?.y ?: 0.92f)) * 0.5f
            boxes.add(
                NormalizedBox(
                    trackId = 1,
                    regionLabel = "FEET",
                    confidence = 0.88f,
                    left = min(leftShoulder?.x ?: 0.30f, rightShoulder?.x ?: 0.70f),
                    top = kneeYNorm,
                    right = max(leftShoulder?.x ?: 0.30f, rightShoulder?.x ?: 0.70f),
                    bottom = min(0.99f, ankleYNorm + 0.05f),
                    timestampMs = timestampMs,
                    sourceWidth = srcW,
                    sourceHeight = srcH
                )
            )
        }

        // =========================================================================
        // GATE 4: Female Bust / Cleavage Analyzer
        // =========================================================================
        var boobsBreastVisibleMatched = false
        var chestExposureConfidence = 0.0f

        val bustTopYNorm = shoulderY + 0.12f * torsoHeight
        val bustBottomYNorm = shoulderY + 0.52f * torsoHeight
        val bustLeftNorm = min(leftShoulder?.x ?: 0.30f, rightShoulder?.x ?: 0.70f)
        val bustRightNorm = max(leftShoulder?.x ?: 0.30f, rightShoulder?.x ?: 0.70f)
        val bustWidthNorm = bustRightNorm - bustLeftNorm

        val noseYNorm = nose?.y ?: (shoulderY - 0.12f)
        val notChinNeckShadow = bustTopYNorm > (noseYNorm + 1.35f * abs(shoulderY - noseYNorm))

        if (notChinNeckShadow && bustWidthNorm > 0.08f) {
            val bTopPx = (bustTopYNorm * srcH).toInt()
            val bBottomPx = (bustBottomYNorm * srcH).toInt()
            val bLeftPx = (bustLeftNorm * srcW).toInt()
            val bRightPx = (bustRightNorm * srcW).toInt()
            val bSpanXPx = bRightPx - bLeftPx

            val lHemiStart = bLeftPx + (bSpanXPx * 0.25f).toInt()
            val lHemiEnd = bLeftPx + (bSpanXPx * 0.46f).toInt()
            val cleftStart = lHemiEnd
            val cleftEnd = bLeftPx + (bSpanXPx * 0.54f).toInt()
            val rHemiStart = cleftEnd
            val rHemiEnd = bLeftPx + (bSpanXPx * 0.75f).toInt()

            var lSkin = 0
            var lTotal = 0
            var lLumaSum = 0f

            var rSkin = 0
            var rTotal = 0
            var rLumaSum = 0f

            var cLumaSum = 0f
            var cTotal = 0

            val stepY = max(1, (bBottomPx - bTopPx) / 6)
            val stepX = max(1, (bSpanXPx * 0.21f).toInt() / 4)

            for (y in bTopPx..bBottomPx step stepY) {
                // Left hemisphere
                for (x in lHemiStart..lHemiEnd step stepX) {
                    val p = getPixelSafe(x, y)
                    val luma = luminance(p)
                    if (isSubjectBareSkin(p, x, y)) {
                        lSkin++
                        lLumaSum += luma
                    }
                    lTotal++
                }
                // Right hemisphere
                for (x in rHemiStart..rHemiEnd step stepX) {
                    val p = getPixelSafe(x, y)
                    val luma = luminance(p)
                    if (isSubjectBareSkin(p, x, y)) {
                        rSkin++
                        rLumaSum += luma
                    }
                    rTotal++
                }
                // Center cleft
                for (x in cleftStart..cleftEnd step max(1, (cleftEnd - cleftStart) / 2)) {
                    val p = getPixelSafe(x, y)
                    cLumaSum += luminance(p)
                    cTotal++
                }
            }

            val leftSkinRatio = if (lTotal > 0) lSkin.toFloat() / lTotal else 0f
            val rightSkinRatio = if (rTotal > 0) rSkin.toFloat() / rTotal else 0f
            val leftAvgLuma = if (lSkin > 0) lLumaSum / lSkin else 128f
            val rightAvgLuma = if (rSkin > 0) rLumaSum / rSkin else 128f
            val centerAvgLuma = if (cTotal > 0) cLumaSum / cTotal else 128f

            val v1 = leftSkinRatio >= 0.30f && rightSkinRatio >= 0.30f
            val breastAvgLuma = (leftAvgLuma + rightAvgLuma) * 0.5f
            val v2 = centerAvgLuma <= (breastAvgLuma - 9.5f)

            var fabricContrastFound = false
            val lowerHalfY = bTopPx + (bBottomPx - bTopPx) / 2
            for (y in lowerHalfY..bBottomPx step max(1, (bBottomPx - lowerHalfY) / 3)) {
                for (x in lHemiStart..rHemiEnd step stepX) {
                    val p = getPixelSafe(x, y)
                    if (isSubjectClothingFabric(p, x, y)) {
                        val fLuma = luminance(p)
                        if (abs(fLuma - breastAvgLuma) >= 20.0f) {
                            fabricContrastFound = true
                            break
                        }
                    }
                }
                if (fabricContrastFound) break
            }

            boobsBreastVisibleMatched = v1 && v2 && fabricContrastFound
            if (boobsBreastVisibleMatched) {
                chestExposureConfidence = ((leftSkinRatio + rightSkinRatio) * 0.5f).coerceIn(0.80f, 0.99f)
                boxes.add(
                    NormalizedBox(
                        trackId = 1,
                        regionLabel = "CHEST",
                        confidence = chestExposureConfidence,
                        left = bustLeftNorm,
                        top = bustTopYNorm,
                        right = bustRightNorm,
                        bottom = bustBottomYNorm,
                        timestampMs = timestampMs,
                        sourceWidth = srcW,
                        sourceHeight = srcH
                    )
                )
            }
        }

        // =========================================================================
        // GATE 5: Rear / Back / ASS / Buttocks Analyzer
        // =========================================================================
        var backOrButtocksAssMatched = false
        var rearButtocksExposureConfidence = 0.0f

        val eyesConfidence = (leftEye?.confidence ?: 0f) + (rightEye?.confidence ?: 0f)
        val noseConfidence = nose?.confidence ?: 0f
        val isShowingBackOrAssPose = (eyesConfidence < 0.60f && noseConfidence < 0.50f) ||
            (hasLongFemaleHair && eyesConfidence < 0.80f)

        if (isShowingBackOrAssPose) {
            val thighLen = if (leftKnee != null && leftHip != null) {
                abs(leftKnee.y - leftHip.y)
            } else 0.25f

            val gluteTopNorm = (hipY - 0.10f * torsoHeight).coerceIn(0.01f, 0.95f)
            val gluteBottomNorm = (hipY + 0.48f * thighLen).coerceIn(gluteTopNorm + 0.05f, 0.99f)
            val hipSpanNorm = if (leftHip != null && rightHip != null) {
                abs(leftHip.x - rightHip.x)
            } else 0.25f
            val gluteLeftNorm = (min(leftHip?.x ?: 0.35f, rightHip?.x ?: 0.65f) - 0.18f * hipSpanNorm).coerceIn(0.01f, 0.90f)
            val gluteRightNorm = (max(leftHip?.x ?: 0.35f, rightHip?.x ?: 0.65f) + 0.18f * hipSpanNorm).coerceIn(gluteLeftNorm + 0.08f, 0.99f)

            val gTopPx = (gluteTopNorm * srcH).toInt()
            val gBottomPx = (gluteBottomNorm * srcH).toInt()
            val gLeftPx = (gluteLeftNorm * srcW).toInt()
            val gRightPx = (gluteRightNorm * srcW).toInt()
            val gSpanX = gRightPx - gLeftPx

            var leftGluteSkinCount = 0
            var leftGluteTotal = 0
            var rightGluteSkinCount = 0
            var rightGluteTotal = 0
            var backSkinCount = 0
            var backTotal = 0

            val stepY = max(1, (gBottomPx - gTopPx) / 6)
            val stepX = max(1, gSpanX / 8)

            // Back zone: from shoulderY to hipY
            val bZoneTop = (shoulderY * srcH).toInt()
            val bZoneBottom = (hipY * srcH).toInt()
            if (bZoneBottom > bZoneTop && gRightPx > gLeftPx) {
                val bStepY = max(1, (bZoneBottom - bZoneTop) / 5)
                for (y in bZoneTop..bZoneBottom step bStepY) {
                    for (x in gLeftPx..gRightPx step stepX) {
                        val p = getPixelSafe(x, y)
                        if (isSubjectBareSkin(p, x, y)) backSkinCount++
                        backTotal++
                    }
                }
            }
            val backBareSkinRatio = if (backTotal > 0) backSkinCount.toFloat() / backTotal else 0f

            // Glute zone: left and right
            val midGluteX = gLeftPx + gSpanX / 2
            for (y in gTopPx..gBottomPx step stepY) {
                for (x in gLeftPx..midGluteX step stepX) {
                    val p = getPixelSafe(x, y)
                    if (isSubjectBareSkin(p, x, y)) leftGluteSkinCount++
                    leftGluteTotal++
                }
                for (x in midGluteX..gRightPx step stepX) {
                    val p = getPixelSafe(x, y)
                    if (isSubjectBareSkin(p, x, y)) rightGluteSkinCount++
                    rightGluteTotal++
                }
            }

            val leftGluteSkin = if (leftGluteTotal > 0) leftGluteSkinCount.toFloat() / leftGluteTotal else 0f
            val rightGluteSkin = if (rightGluteTotal > 0) rightGluteSkinCount.toFloat() / rightGluteTotal else 0f

            val conditionA = (leftGluteSkin >= 0.36f && rightGluteSkin >= 0.36f) || backBareSkinRatio >= 0.45f
            val waistWidth = shoulderW * 0.70f
            val gluteBoxWidth = (gluteRightNorm - gluteLeftNorm) * srcW
            val conditionB = waistWidth > 0f && (gluteBoxWidth / waistWidth >= 1.18f) && (leftGluteSkin >= 0.20f || rightGluteSkin >= 0.20f)

            backOrButtocksAssMatched = conditionA || conditionB
            if (backOrButtocksAssMatched) {
                rearButtocksExposureConfidence = 0.88f
                val isGlutes = leftGluteSkin >= 0.30f || rightGluteSkin >= 0.30f
                boxes.add(
                    NormalizedBox(
                        trackId = 1,
                        regionLabel = if (isGlutes) "BUTTOCKS" else "BACK",
                        confidence = rearButtocksExposureConfidence,
                        left = gluteLeftNorm,
                        top = if (isGlutes) gluteTopNorm else (shoulderY.coerceIn(0.01f, 0.90f)),
                        right = gluteRightNorm,
                        bottom = gluteBottomNorm,
                        timestampMs = timestampMs,
                        sourceWidth = srcW,
                        sourceHeight = srcH
                    )
                )
            }
        }

        return FrameFeatures(
            contentItemId = contentItemId,
            frameTimestampMs = timestampMs,
            isSupportedVideoFeed = isSupportedFeed,
            modelInitialized = true,
            personDetectorAvailable = true,
            clothingClassifierAvailable = true,
            clothingAnalysisComplete = true,
            qualifyingPersonDetected = true,
            personConfidence = overallPersonConfidence,
            isMalePresentingOnly = isMaleSubject,
            isFemaleConfirmed = isFemaleConfirmed,
            shortDressRuleMatched = shortDressRuleMatched,
            boobsBreastVisibleMatched = boobsBreastVisibleMatched,
            backOrButtocksAssMatched = backOrButtocksAssMatched,
            shoulderUncoveredConfidence = shoulderUncoveredConfidence,
            hemlineAboveKneeConfidence = hemlineAboveKneeConfidence,
            chestExposureConfidence = chestExposureConfidence,
            rearButtocksExposureConfidence = rearButtocksExposureConfidence,
            detectedBoxes = boxes,
            detectedLandmarks = extractedLandmarks,
            inferenceDurationMs = durationMs
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
            MlPoseLandmark.LEFT_MOUTH -> LandmarkType.MOUTH_LEFT
            MlPoseLandmark.RIGHT_MOUTH -> LandmarkType.MOUTH_RIGHT
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
        try {
            selfieSegmenterInstance?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing SelfieSegmenter", e)
        } finally {
            selfieSegmenterInstance = null
        }
    }
}
