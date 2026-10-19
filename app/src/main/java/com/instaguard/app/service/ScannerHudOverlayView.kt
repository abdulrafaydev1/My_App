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
 * Tactical HUD Scanner Outline Overlay View (v10.0).
 *
 * Strict Non-Blur Guarantee:
 * - Never blurs, darkens, or fills the interior of any face or body box (100% Color.TRANSPARENT inside).
 * - Renders thin red outer rectangle (#FF1744, 1.8dp), thick corner L-brackets (#FF1744, 3.2dp, 14dp arm),
 *   center reticle crosshair (+, 6dp), and top-left dark pill tag (#D9090D16 with #FF1744 border)
 *   showing monospace label + confidence percentage: e.g., FACE [98%], CHEST [95%], BACK [91%], BUTTOCKS [95%], FEET [92%].
 * - Smooth 60-FPS LERP interpolation (factor 0.35f) and automatic 1400ms stale box cleanup.
 */
class ScannerHudOverlayView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    @Volatile
    private var targetBoxes: List<NormalizedBox> = emptyList()

    @Volatile
    private var currentDiagnosticStatus: DiagnosticStatus = DiagnosticStatus.WAITING_FOR_FRAMES

    @Volatile
    private var lastFrameTimestampMs: Long = 0L

    private val smoothedBoxes = HashMap<String, RectF>()
    private val reusableRect = RectF()
    private val tagBgRect = RectF()

    private val outerBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FF1744")
        strokeWidth = 1.8f * density
    }

    private val bodyOuterBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
        color = Color.parseColor("#CCFF1744")
        strokeWidth = 1.4f * density
    }

    private val tagFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#D9090D16")
    }

    private val tagStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FF1744")
        strokeWidth = 1.1f * density
    }

    private val tagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5252")
        textSize = 10.5f * scaledDensity
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
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
        frameTimestampMs: Long
    ) {
        this.targetBoxes = boxes
        this.currentDiagnosticStatus = status
        this.lastFrameTimestampMs = frameTimestampMs
        if (boxes.isEmpty()) {
            smoothedBoxes.clear()
        }
        postInvalidateOnAnimation()
    }

    fun clearBoxes() {
        clearOverlay()
    }

    fun clearOverlay() {
        targetBoxes = emptyList()
        smoothedBoxes.clear()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val nowElapsed = SystemClock.elapsedRealtime()
        val activeTargets = targetBoxes.filter { (nowElapsed - it.timestampMs) <= 1400L }

        // Draw Top-Left HUD Status Badge (at y = 72dp below floating counter pill)
        drawHudStatusBadge(canvas, nowElapsed)

        if (activeTargets.isEmpty()) {
            smoothedBoxes.clear()
            return
        }

        // Remove stale keys no longer in activeTargets
        val activeKeys = activeTargets.map { "${it.trackId}_${it.regionLabel}" }.toHashSet()
        val iterator = smoothedBoxes.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in activeKeys) {
                iterator.remove()
            }
        }

        var needsNextAnimationFrame = false
        val cornerArmPx = 14f * density
        val reticleHalfPx = 6f * density

        for (box in activeTargets) {
            val key = "${box.trackId}_${box.regionLabel}"
            val current = smoothedBoxes[key]
            val displayed: RectF
            if (current == null) {
                displayed = RectF(box.left, box.top, box.right, box.bottom)
                smoothedBoxes[key] = displayed
            } else {
                val nextLeft = lerp(current.left, box.left, 0.35f)
                val nextTop = lerp(current.top, box.top, 0.35f)
                val nextRight = lerp(current.right, box.right, 0.35f)
                val nextBottom = lerp(current.bottom, box.bottom, 0.35f)

                val delta = maxOf(
                    abs(nextLeft - box.left),
                    abs(nextTop - box.top),
                    abs(nextRight - box.right),
                    abs(nextBottom - box.bottom)
                )
                if (delta > 0.002f) {
                    needsNextAnimationFrame = true
                }
                current.set(nextLeft, nextTop, nextRight, nextBottom)
                displayed = current
            }

            reusableRect.set(
                displayed.left * w,
                displayed.top * h,
                displayed.right * w,
                displayed.bottom * h
            )

            val isBodyBox = box.regionLabel == "BODY"

            // 1. Hollow Red Bounding Rectangle (NEVER fills or blurs interior)
            canvas.drawRect(
                reusableRect,
                if (isBodyBox) bodyOuterBoxPaint else outerBoxPaint
            )

            // 2. Thick Tactical Corner L-Brackets at all 4 corners
            val arm = min(cornerArmPx, min(reusableRect.width() * 0.32f, reusableRect.height() * 0.32f))
            // Top-Left corner
            canvas.drawLine(reusableRect.left, reusableRect.top, reusableRect.left + arm, reusableRect.top, cornerBracketPaint)
            canvas.drawLine(reusableRect.left, reusableRect.top, reusableRect.left, reusableRect.top + arm, cornerBracketPaint)
            // Top-Right corner
            canvas.drawLine(reusableRect.right - arm, reusableRect.top, reusableRect.right, reusableRect.top, cornerBracketPaint)
            canvas.drawLine(reusableRect.right, reusableRect.top, reusableRect.right, reusableRect.top + arm, cornerBracketPaint)
            // Bottom-Left corner
            canvas.drawLine(reusableRect.left, reusableRect.bottom - arm, reusableRect.left, reusableRect.bottom, cornerBracketPaint)
            canvas.drawLine(reusableRect.left, reusableRect.bottom, reusableRect.left + arm, reusableRect.bottom, cornerBracketPaint)
            // Bottom-Right corner
            canvas.drawLine(reusableRect.right - arm, reusableRect.bottom, reusableRect.right, reusableRect.bottom, cornerBracketPaint)
            canvas.drawLine(reusableRect.right, reusableRect.bottom - arm, reusableRect.right, reusableRect.bottom, cornerBracketPaint)

            // 3. Subtle Center Reticle Crosshair (+)
            if (!isBodyBox) {
                val cx = reusableRect.centerX()
                val cy = reusableRect.centerY()
                canvas.drawLine(cx - reticleHalfPx, cy, cx + reticleHalfPx, cy, reticlePaint)
                canvas.drawLine(cx, cy - reticleHalfPx, cx, cy + reticleHalfPx, reticlePaint)
            }

            // 4. Top-Left (or Top-Right for BODY) Dark Pill Tag with Label + Confidence %
            val pct = (box.confidence * 100f).roundToInt().coerceIn(1, 99)
            val labelText = "${box.regionLabel} [$pct%]"
            val textWidth = tagTextPaint.measureText(labelText)
            val padH = 6f * density
            val padV = 3f * density
            val tagW = textWidth + padH * 2f
            val tagH = tagTextPaint.textSize + padV * 2f

            val tagLeft = if (isBodyBox) {
                (reusableRect.right - tagW).coerceAtLeast(8f * density)
            } else {
                reusableRect.left.coerceIn(8f * density, (w - tagW - 8f * density).coerceAtLeast(8f * density))
            }
            val tagBottom = (reusableRect.top - 2f * density).coerceAtLeast(tagH + 4f * density)
            val tagTop = tagBottom - tagH

            tagBgRect.set(tagLeft, tagTop, tagLeft + tagW, tagBottom)
            val cornerRadius = 3f * density
            canvas.drawRoundRect(tagBgRect, cornerRadius, cornerRadius, tagFillPaint)
            canvas.drawRoundRect(tagBgRect, cornerRadius, cornerRadius, tagStrokePaint)

            val textY = tagBottom - padV - (tagTextPaint.descent() * 0.5f)
            canvas.drawText(labelText, tagLeft + padH, textY, tagTextPaint)
        }

        if (needsNextAnimationFrame) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawHudStatusBadge(canvas: Canvas, nowElapsed: Long) {
        val ageMs = if (lastFrameTimestampMs > 0L) {
            (nowElapsed - lastFrameTimestampMs).coerceIn(0L, 9999L)
        } else {
            0L
        }
        val statusText = "OBJECT DETECTION MODE • ${currentDiagnosticStatus.label} • ${ageMs}ms"
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
