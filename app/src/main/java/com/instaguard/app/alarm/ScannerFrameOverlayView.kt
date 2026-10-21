package com.instaguard.app.alarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 3-State Camera Scanner Frame Overlay.
 * Matches reference UI specifications:
 * 1. IDLE_WHITE: Outer rounded rect (28dp, 2dp stroke, #CCFFFFFF) + 4 inner white brackets (4dp, 28dp arm, #FFFFFFFF).
 * 2. ERROR_RED: Outer glow (18dp shadow, #FF1744) + 3.5dp stroke + 4 inner red brackets (4.5dp, #FF1744).
 * 3. VERIFIED_GREEN: Outer glow (18dp shadow, #00E676) + 3.5dp stroke + 4 inner green brackets (4.5dp, #00E676).
 */
enum class ScanUiState {
    IDLE_WHITE,
    ERROR_RED,
    VERIFIED_GREEN
}

class ScannerFrameOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var scanState: ScanUiState = ScanUiState.IDLE_WHITE
        set(value) {
            field = value
            invalidate()
        }

    private val density = context.resources.displayMetrics.density

    private val outerRectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val rectF = RectF()
    private val cornerPath = Path()

    init {
        // Enable software rendering so shadowLayer glow works across all Android versions
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cornerRadius = 28f * density
        val armLength = 28f * density
        val bracketInset = 4f * density

        when (scanState) {
            ScanUiState.IDLE_WHITE -> {
                outerRectPaint.strokeWidth = 2f * density
                outerRectPaint.color = Color.parseColor("#CCFFFFFF")
                outerRectPaint.clearShadowLayer()

                bracketPaint.strokeWidth = 4f * density
                bracketPaint.color = Color.parseColor("#FFFFFFFF")
                bracketPaint.clearShadowLayer()
            }
            ScanUiState.ERROR_RED -> {
                val redColor = Color.parseColor("#FF1744")
                outerRectPaint.strokeWidth = 3.5f * density
                outerRectPaint.color = redColor
                outerRectPaint.setShadowLayer(18f * density, 0f, 0f, redColor)

                bracketPaint.strokeWidth = 4.5f * density
                bracketPaint.color = redColor
                bracketPaint.setShadowLayer(12f * density, 0f, 0f, redColor)
            }
            ScanUiState.VERIFIED_GREEN -> {
                val greenColor = Color.parseColor("#00E676")
                outerRectPaint.strokeWidth = 3.5f * density
                outerRectPaint.color = greenColor
                outerRectPaint.setShadowLayer(18f * density, 0f, 0f, greenColor)

                bracketPaint.strokeWidth = 4.5f * density
                bracketPaint.color = greenColor
                bracketPaint.setShadowLayer(12f * density, 0f, 0f, greenColor)
            }
        }

        // Draw outer rounded rectangle
        val halfStroke = outerRectPaint.strokeWidth / 2f
        val padding = 6f * density + halfStroke
        rectF.set(padding, padding, w - padding, h - padding)
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, outerRectPaint)

        // Draw 4 inner corner L-brackets
        val left = rectF.left + bracketInset
        val top = rectF.top + bracketInset
        val right = rectF.right - bracketInset
        val bottom = rectF.bottom - bracketInset
        val r = (cornerRadius - bracketInset).coerceAtLeast(8f * density)

        // Top-Left L-bracket
        cornerPath.reset()
        cornerPath.moveTo(left + armLength, top)
        cornerPath.lineTo(left + r, top)
        cornerPath.quadTo(left, top, left, top + r)
        cornerPath.lineTo(left, top + armLength)
        canvas.drawPath(cornerPath, bracketPaint)

        // Top-Right L-bracket
        cornerPath.reset()
        cornerPath.moveTo(right - armLength, top)
        cornerPath.lineTo(right - r, top)
        cornerPath.quadTo(right, top, right, top + r)
        cornerPath.lineTo(right, top + armLength)
        canvas.drawPath(cornerPath, bracketPaint)

        // Bottom-Left L-bracket
        cornerPath.reset()
        cornerPath.moveTo(left, bottom - armLength)
        cornerPath.lineTo(left, bottom - r)
        cornerPath.quadTo(left, bottom, left + r, bottom)
        cornerPath.lineTo(left + armLength, bottom)
        canvas.drawPath(cornerPath, bracketPaint)

        // Bottom-Right L-bracket
        cornerPath.reset()
        cornerPath.moveTo(right, bottom - armLength)
        cornerPath.lineTo(right, bottom - r)
        cornerPath.quadTo(right, bottom, right - r, bottom)
        cornerPath.lineTo(right - armLength, bottom)
        canvas.drawPath(cornerPath, bracketPaint)
    }
}
