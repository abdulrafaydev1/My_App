package com.instaguard.app.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure-Kotlin coordinate transformation utility that maps normalized source-frame
 * coordinates [0f..1f] into overlay View coordinates, accounting for source frame
 * aspect ratio, display dimensions, and window screen offsets (Phase 6.4).
 */
object CoordinateTransform {

    typealias MappedBox = DetectionCoordinateMapper.MappedBox

    fun mapNormalizedBoxToView(
        normLeft: Float,
        normTop: Float,
        normRight: Float,
        normBottom: Float,
        viewWidth: Int,
        viewHeight: Int,
        sourceWidth: Int = 0,
        sourceHeight: Int = 0,
        windowOffsetX: Int = 0,
        windowOffsetY: Int = 0
    ): MappedBox? = DetectionCoordinateMapper.mapNormalizedBoxToView(
        normLeft = normLeft,
        normTop = normTop,
        normRight = normRight,
        normBottom = normBottom,
        viewWidth = viewWidth,
        viewHeight = viewHeight,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        windowOffsetX = windowOffsetX,
        windowOffsetY = windowOffsetY
    )

    fun mapNormalizedBoxToView(
        box: NormalizedBox,
        viewWidth: Int,
        viewHeight: Int,
        windowOffsetX: Int = 0,
        windowOffsetY: Int = 0
    ): MappedBox? = DetectionCoordinateMapper.mapNormalizedBoxToView(
        box = box,
        viewWidth = viewWidth,
        viewHeight = viewHeight,
        windowOffsetX = windowOffsetX,
        windowOffsetY = windowOffsetY
    )
}

/**
 * Pure-Kotlin, unit-testable controller for the HUD Scanner Overlay (Phases 6 & 7).
 *
 * Enforces:
 * - Strict 0% interior fill / 0% blur guarantee ([interiorFillAlpha] == 0).
 * - Independent ON/OFF toggle ([isOverlayEnabled]) that hides boxes without affecting decision logic.
 * - Rejects invalid coordinates and removes stale detections older than [staleTimeoutMs].
 * - Clears boxes immediately when contentItemId changes or tracking is lost.
 */
class ScannerOverlayStateController(
    val maxBoxAgeMs: Long = 1400L,
    val staleTimeoutMs: Long = maxBoxAgeMs
) {
    /** Interior fill alpha is strictly 0 (100% transparent interior, never blurs or fills faces/bodies). */
    val interiorFillAlpha: Int = 0

    @Volatile
    var isOverlayEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) {
                activeBoxesInternal.clear()
            }
        }

    @Volatile
    var currentContentItemId: String = ""
        private set

    @Volatile
    var currentDiagnosticStatus: DiagnosticStatus = DiagnosticStatus.WAITING_FOR_FRAMES
        private set

    @Volatile
    var lastFrameTimestampMs: Long = 0L
        private set

    private val activeBoxesInternal = ArrayList<NormalizedBox>()

    @Synchronized
    fun updateState(
        boxes: List<NormalizedBox>,
        status: DiagnosticStatus,
        frameTimestampMs: Long,
        contentItemId: String = currentContentItemId,
        nowMs: Long = frameTimestampMs
    ) {
        updateOverlayState(
            contentItemId = contentItemId,
            boxes = boxes,
            status = status,
            frameTimestampMs = frameTimestampMs,
            nowMs = nowMs
        )
    }

    @Synchronized
    fun updateOverlayState(
        contentItemId: String,
        boxes: List<NormalizedBox>,
        status: DiagnosticStatus,
        frameTimestampMs: Long,
        nowMs: Long = frameTimestampMs
    ) {
        if (contentItemId != currentContentItemId) {
            currentContentItemId = contentItemId
            activeBoxesInternal.clear()
        }

        currentDiagnosticStatus = if (!isOverlayEnabled) {
            DiagnosticStatus.OVERLAY_DISABLED
        } else {
            status
        }
        lastFrameTimestampMs = frameTimestampMs

        if (!isOverlayEnabled) {
            activeBoxesInternal.clear()
            return
        }

        if (status == DiagnosticStatus.TRACKING_LOST) {
            activeBoxesInternal.clear()
            return
        }

        val validCurrentBoxes = boxes.filter { box ->
            box.isValid() && (nowMs - box.timestampMs) in 0L..staleTimeoutMs
        }
        activeBoxesInternal.clear()
        activeBoxesInternal.addAll(validCurrentBoxes)
    }

    @Synchronized
    fun onContentItemChanged(newContentItemId: String) {
        onNewContentItem(newContentItemId)
    }

    @Synchronized
    fun onNewContentItem(newItemId: String) {
        currentContentItemId = newItemId
        activeBoxesInternal.clear()
    }

    @Synchronized
    fun onTrackingLost() {
        currentDiagnosticStatus = DiagnosticStatus.TRACKING_LOST
        activeBoxesInternal.clear()
    }

    @Synchronized
    fun clearBoxes() {
        clearAll()
    }

    @Synchronized
    fun clearOverlay() {
        clearAll()
    }

    @Synchronized
    fun clearAll() {
        activeBoxesInternal.clear()
    }

    @Synchronized
    fun getRenderableBoxes(nowMs: Long): List<NormalizedBox> {
        return getVisibleBoxes(nowMs)
    }

    @Synchronized
    fun getVisibleBoxes(nowMs: Long): List<NormalizedBox> {
        if (!isOverlayEnabled) return emptyList()
        val iterator = activeBoxesInternal.iterator()
        while (iterator.hasNext()) {
            val box = iterator.next()
            if (!box.isValid() || (nowMs - box.timestampMs) > staleTimeoutMs) {
                iterator.remove()
            }
        }
        return ArrayList(activeBoxesInternal)
    }

    companion object {
        fun formatBoxHeaderText(box: NormalizedBox): String {
            val conf = box.confidence
            return if (conf != null && !conf.isNaN() && conf > 0f && conf <= 1f) {
                val pct = (conf * 100f).roundToInt().coerceIn(1, 100)
                "${box.regionLabel} $pct%"
            } else {
                box.regionLabel
            }
        }

        fun formatBoxLabel(box: NormalizedBox): String {
            return formatBoxHeaderText(box)
        }
    }
}

/**
 * Transparent Tactical HUD Scanner Overlay View (Phase 6).
 *
 * Strict Non-Blur & Non-Fill Guarantee:
 * - Every bounding-box interior is 100% Color.TRANSPARENT (zero blur, zero fill, zero tint).
 * - Renders only genuine, validated detections supplied by the inference pipeline.
 * - Uses CoordinateTransform to align normalized source-frame coordinates with the display.
 */
class ScannerHudOverlayView(context: Context) : View(context) {

    val stateController = ScannerOverlayStateController(maxBoxAgeMs = 1400L)
    val bodyTracker = BodyTrackingController(maxCoastingAgeMs = 850L)

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val smoothedBoxes = HashMap<String, RectF>()
    private val reusableRect = RectF()
    private val tagBgRect = RectF()
    private val screenLocation = IntArray(2)

    private val outerBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FF1744")
        strokeWidth = 1.8f * density
    }

    private val secondaryOuterBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#B3FF1744")
        strokeWidth = 1.3f * density
    }

    private val cornerBracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FF1744")
        strokeWidth = 3.2f * density
        strokeCap = Paint.Cap.SQUARE
    }

    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#99FF1744")
        strokeWidth = 1.2f * density
    }

    private val tagFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.TRANSPARENT
    }

    private val tagStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#80FF1744")
        strokeWidth = 1.0f * density
    }

    private val tagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5252")
        textSize = 10.5f * scaledDensity
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(2.5f * density, 0f, 0f, Color.BLACK)
    }

    private val hudBannerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC090D16")
    }

    private val hudBannerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#88FF1744")
        strokeWidth = 1.0f * density
    }

    private val hudBannerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8A80")
        textSize = 9.5f * scaledDensity
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    fun updateHudState(
        boxes: List<NormalizedBox>,
        status: DiagnosticStatus,
        frameTimestampMs: Long,
        contentItemId: String = stateController.currentContentItemId,
        nowMs: Long = SystemClock.elapsedRealtime(),
        landmarks: List<PoseLandmark> = emptyList()
    ) {
        stateController.updateOverlayState(
            contentItemId = contentItemId,
            boxes = boxes,
            status = status,
            frameTimestampMs = frameTimestampMs,
            nowMs = nowMs
        )
        val pBox = boxes.firstOrNull { it.regionLabel == "PERSON" || it.regionLabel == "BODY" }
        val fBox = boxes.firstOrNull { it.regionLabel == "FACE" }
        val tBox = boxes.firstOrNull { it.regionLabel == "UPPER TORSO" }
        bodyTracker.updateWithFrameDetection(
            contentItemId = contentItemId,
            timestampMs = frameTimestampMs,
            personBox = pBox,
            faceBox = fBox,
            upperTorsoBox = tBox,
            landmarks = landmarks,
            detectionConfidence = fBox?.confidence ?: pBox?.confidence ?: 0.85f,
            nowMs = nowMs
        )
        if (boxes.isEmpty()) {
            smoothedBoxes.clear()
        }
        postInvalidateOnAnimation()
    }

    fun clearBoxes() {
        clearOverlay()
    }

    fun clearOverlay() {
        stateController.clearAll()
        bodyTracker.clearTracking()
        smoothedBoxes.clear()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!stateController.isOverlayEnabled) return

        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val nowElapsed = SystemClock.elapsedRealtime()
        val trackedBoxes = bodyTracker.getRenderableBoxes(nowElapsed)
        val activeTargets = if (trackedBoxes.isNotEmpty()) trackedBoxes else stateController.getVisibleBoxes(nowElapsed)

        drawHudStatusBadge(canvas, nowElapsed)

        if (activeTargets.isEmpty()) {
            smoothedBoxes.clear()
            return
        }

        getLocationOnScreen(screenLocation)
        val winOffsetX = screenLocation[0]
        val winOffsetY = screenLocation[1]

        val activeKeys = activeTargets.map { "${it.trackId}_${it.regionLabel}" }.toHashSet()
        val iterator = smoothedBoxes.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().key !in activeKeys) {
                iterator.remove()
            }
        }

        var needsNextAnimationFrame = false
        val cornerArmPx = 14f * density
        val reticleHalfPx = 6f * density

        for (box in activeTargets) {
            val mapped = CoordinateTransform.mapNormalizedBoxToView(
                box = box,
                viewWidth = w,
                viewHeight = h,
                windowOffsetX = winOffsetX,
                windowOffsetY = winOffsetY
            ) ?: continue

            val key = "${box.trackId}_${box.regionLabel}"
            val current = smoothedBoxes[key]
            if (current == null) {
                val initRect = RectF(mapped.leftPx, mapped.topPx, mapped.rightPx, mapped.bottomPx)
                smoothedBoxes[key] = initRect
                reusableRect.set(initRect)
            } else {
                val nextLeft = lerp(current.left, mapped.leftPx, 0.70f)
                val nextTop = lerp(current.top, mapped.topPx, 0.70f)
                val nextRight = lerp(current.right, mapped.rightPx, 0.70f)
                val nextBottom = lerp(current.bottom, mapped.bottomPx, 0.70f)

                val deltaPx = maxOf(
                    abs(nextLeft - mapped.leftPx),
                    abs(nextTop - mapped.topPx),
                    abs(nextRight - mapped.rightPx),
                    abs(nextBottom - mapped.bottomPx)
                )
                if (deltaPx > 1.0f || bodyTracker.currentLifecycleState == TrackingLifecycleState.TRACKING_SUBJECT) {
                    needsNextAnimationFrame = true
                }
                current.set(nextLeft, nextTop, nextRight, nextBottom)
                reusableRect.set(current)
            }

            val isFullBody = box.regionLabel == "BODY" || box.regionLabel == "PERSON"

            // 1. Hollow Red Bounding Rectangle (interior is strictly transparent)
            canvas.drawRect(
                reusableRect,
                if (isFullBody) secondaryOuterBoxPaint else outerBoxPaint
            )

            // 2. Four L-Shaped Corner Brackets
            val arm = min(cornerArmPx, min(reusableRect.width() * 0.32f, reusableRect.height() * 0.32f))
            canvas.drawLine(reusableRect.left, reusableRect.top, reusableRect.left + arm, reusableRect.top, cornerBracketPaint)
            canvas.drawLine(reusableRect.left, reusableRect.top, reusableRect.left, reusableRect.top + arm, cornerBracketPaint)

            canvas.drawLine(reusableRect.right - arm, reusableRect.top, reusableRect.right, reusableRect.top, cornerBracketPaint)
            canvas.drawLine(reusableRect.right, reusableRect.top, reusableRect.right, reusableRect.top + arm, cornerBracketPaint)

            canvas.drawLine(reusableRect.left, reusableRect.bottom - arm, reusableRect.left, reusableRect.bottom, cornerBracketPaint)
            canvas.drawLine(reusableRect.left, reusableRect.bottom, reusableRect.left + arm, reusableRect.bottom, cornerBracketPaint)

            canvas.drawLine(reusableRect.right - arm, reusableRect.bottom, reusableRect.right, reusableRect.bottom, cornerBracketPaint)
            canvas.drawLine(reusableRect.right, reusableRect.bottom - arm, reusableRect.right, reusableRect.bottom, cornerBracketPaint)

            // 3. Center Crosshair Reticle
            if (!isFullBody) {
                val cx = reusableRect.centerX()
                val cy = reusableRect.centerY()
                canvas.drawLine(cx - reticleHalfPx, cy, cx + reticleHalfPx, cy, reticlePaint)
                canvas.drawLine(cx, cy - reticleHalfPx, cx, cy + reticleHalfPx, reticlePaint)
            }

            // 4. Compact Top Label (only displays confidence when genuinely provided by model)
            val labelText = ScannerOverlayStateController.formatBoxLabel(box)
            val textWidth = tagTextPaint.measureText(labelText)
            val padH = 6f * density
            val padV = 3f * density
            val tagW = textWidth + padH * 2f
            val tagH = tagTextPaint.textSize + padV * 2f

            val tagLeft = if (isFullBody) {
                (reusableRect.right - tagW).coerceIn(8f * density, (w - tagW - 8f * density).coerceAtLeast(8f * density))
            } else {
                reusableRect.left.coerceIn(8f * density, (w - tagW - 8f * density).coerceAtLeast(8f * density))
            }

            // Position strictly outside the bounding box:
            // Prefer placing above the box; if insufficient clearance near screen top, flip below the box
            val hasSpaceAbove = reusableRect.top >= (tagH + 4f * density)
            val tagTop: Float
            val tagBottom: Float
            if (hasSpaceAbove) {
                tagBottom = reusableRect.top - 2f * density
                tagTop = tagBottom - tagH
            } else {
                tagTop = reusableRect.bottom + 2f * density
                tagBottom = tagTop + tagH
            }

            tagBgRect.set(tagLeft, tagTop, tagLeft + tagW, tagBottom)
            val cornerRadius = 3f * density
            // Stroke only: 100% transparent interior (no dark fill over face or body)
            canvas.drawRoundRect(tagBgRect, cornerRadius, cornerRadius, tagStrokePaint)

            val textY = tagBottom - padV - (tagTextPaint.descent() * 0.5f)
            canvas.drawText(labelText, tagLeft + padH, textY, tagTextPaint)
        }

        if (needsNextAnimationFrame) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawHudStatusBadge(canvas: Canvas, nowElapsed: Long) {
        val lastTs = stateController.lastFrameTimestampMs
        val ageMs = if (lastTs > 0L) {
            (nowElapsed - lastTs).coerceIn(0L, 9999L)
        } else {
            0L
        }
        val count = stateController.getVisibleBoxes(nowElapsed).size
        val countText = if (count > 0) " • $count BOXES" else ""
        val statusText = "HUD SCANNER • ${stateController.currentDiagnosticStatus.label}$countText • ${ageMs}ms"
        val textW = hudBannerTextPaint.measureText(statusText)
        val padH = 8f * density
        val padV = 4f * density
        val bannerW = textW + padH * 2f
        val bannerH = hudBannerTextPaint.textSize + padV * 2f

        val left = 14f * density
        val top = 72f * density
        tagBgRect.set(left, top, left + bannerW, top + bannerH)
        val r = 4f * density
        canvas.drawRoundRect(tagBgRect, r, r, hudBannerBgPaint)
        canvas.drawRoundRect(tagBgRect, r, r, hudBannerStrokePaint)

        val textY = top + bannerH - padV - (hudBannerTextPaint.descent() * 0.5f)
        canvas.drawText(statusText, left + padH, textY, hudBannerTextPaint)
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction
    }
}
