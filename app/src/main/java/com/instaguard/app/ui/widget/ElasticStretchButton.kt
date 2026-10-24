package com.instaguard.app.ui.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.Interpolator
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.instaguard.app.R
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

/**
 * High-performance Elastic Stretch & Drag Button Interaction Component (v16.0).
 *
 * Provides real-time physical rubber shape deformation, dynamically adapting rounded corners,
 * responsive finger tracking, and premium damped spring recovery physics.
 *
 * Encapsulates:
 * - Direct finger tracking without initial touch offset jumps.
 * - Reliable gesture state machine distinguishing normal taps from elastic drags.
 * - Asymmetric cubic Bézier volume-conserving deformation (Poisson necking & leading-edge pull).
 * - Damped spring relaxation (zeta = 0.72, omega = 16.0 rad/s) on release.
 * - Configurable anchor return or dropped-position retention.
 * - Zero memory allocations in onDraw() and onTouchEvent().
 * - 100% compatibility with MaterialButton styling, text, icons, and click actions.
 */
class ElasticStretchButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    /**
     * Discrete interaction states for the touch state machine.
     */
    enum class ElasticGestureState {
        IDLE,
        PRESSED_HOLDING,
        DRAGGING,
        RELEASING
    }

    // =========================================================================
    // Configurable Properties
    // =========================================================================

    /** Whether the button springs back to its initial anchor translation, or stays at dropped location. */
    var returnToAnchorOnRelease: Boolean = false

    /** Hold duration in milliseconds before press-and-drag elastic deformation begins. */
    var pressHoldDurationMs: Long = 160L

    /** Maximum stretch displacement limit in density-independent pixels. */
    var maxStretchDistanceDp: Float = 44f

    /** Master toggle to enable or disable elastic stretch dragging. */
    var isElasticDragEnabled: Boolean = true

    /** Callback invoked on gesture state transitions. */
    var onGestureStateChangedListener: ((ElasticGestureState) -> Unit)? = null

    /** Current interaction state. */
    var gestureState: ElasticGestureState = ElasticGestureState.IDLE
        private set(value) {
            if (field != value) {
                field = value
                onGestureStateChangedListener?.invoke(value)
            }
        }

    // =========================================================================
    // Geometry, Paint & Pre-allocated Resources (Zero-Allocation on Draw)
    // =========================================================================

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val elasticPath = Path()
    private val restingRect = RectF()
    private val pathCoords = FloatArray(48) // 8 segments * 3 points * 2 coords = 48

    private val elasticPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val elasticStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private var baseBackgroundColor: Int = Color.parseColor("#161920")
    private var baseStrokeColor: Int = Color.TRANSPARENT
    private var baseStrokeWidthPx: Float = 0f
    private var baseCornerRadiusPx: Float = 24f * density

    // Touch Tracking State
    private var downRawX = 0f
    private var downRawY = 0f
    private var initialAnchorTx = 0f
    private var initialAnchorTy = 0f
    private var dragStartTx = 0f
    private var dragStartTy = 0f
    private var lastMoveRawX = 0f
    private var lastMoveRawY = 0f

    // Elastic Deformation Vector
    private var currentStretchX = 0f
    private var currentStretchY = 0f
    private var isDragOccurred = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private var springAnimator: ValueAnimator? = null

    private val holdRunnable = Runnable {
        if (gestureState == ElasticGestureState.PRESSED_HOLDING && isElasticDragEnabled) {
            gestureState = ElasticGestureState.DRAGGING
            isDragOccurred = true
            parent?.requestDisallowInterceptTouchEvent(true)
            try {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            } catch (_: Exception) {}
            // Smoothly release initial pressed scale back to 1.0f as rubber stretching begins
            animate().scaleX(1.0f).scaleY(1.0f).setDuration(90).start()
        }
    }

    init {
        // Parse custom XML attributes if supplied
        if (attrs != null) {
            val typedArray = context.theme.obtainStyledAttributes(
                attrs,
                R.styleable.ElasticStretchButton,
                defStyleAttr,
                0
            )
            try {
                returnToAnchorOnRelease = typedArray.getBoolean(
                    R.styleable.ElasticStretchButton_returnToAnchorOnRelease,
                    returnToAnchorOnRelease
                )
                pressHoldDurationMs = typedArray.getInt(
                    R.styleable.ElasticStretchButton_pressHoldDurationMs,
                    pressHoldDurationMs.toInt()
                ).toLong()
                maxStretchDistanceDp = typedArray.getDimension(
                    R.styleable.ElasticStretchButton_maxStretchDistanceDp,
                    maxStretchDistanceDp * density
                ) / density
                isElasticDragEnabled = typedArray.getBoolean(
                    R.styleable.ElasticStretchButton_isElasticDragEnabled,
                    isElasticDragEnabled
                )
            } finally {
                typedArray.recycle()
            }
        }

        // Cache initial styling from MaterialButton properties
        updateColorsFromButtonProperties()

        // Clear MaterialButton's rigid background drawable so we can draw our deformed rubber path
        background = null
    }

    private fun updateColorsFromButtonProperties() {
        val bgTint = backgroundTintList
        baseBackgroundColor = bgTint?.defaultColor ?: Color.parseColor("#161920")

        val stroke = strokeColor
        baseStrokeColor = stroke?.defaultColor ?: Color.TRANSPARENT
        baseStrokeWidthPx = strokeWidth.toFloat()
        baseCornerRadiusPx = cornerRadius.toFloat()

        elasticPaint.color = baseBackgroundColor
        elasticStrokePaint.color = baseStrokeColor
        elasticStrokePaint.strokeWidth = baseStrokeWidthPx
    }

    override fun setBackgroundTintList(tintList: ColorStateList?) {
        super.setBackgroundTintList(tintList)
        baseBackgroundColor = tintList?.defaultColor ?: Color.parseColor("#161920")
        elasticPaint.color = baseBackgroundColor
        invalidate()
    }

    override fun setStrokeColor(strokeColor: ColorStateList?) {
        super.setStrokeColor(strokeColor)
        baseStrokeColor = strokeColor?.defaultColor ?: Color.TRANSPARENT
        elasticStrokePaint.color = baseStrokeColor
        invalidate()
    }

    override fun setStrokeWidth(strokeWidth: Int) {
        super.setStrokeWidth(strokeWidth)
        baseStrokeWidthPx = strokeWidth.toFloat()
        elasticStrokePaint.strokeWidth = baseStrokeWidthPx
        invalidate()
    }

    override fun setCornerRadius(cornerRadius: Int) {
        super.setCornerRadius(cornerRadius)
        baseCornerRadiusPx = cornerRadius.toFloat()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        initialAnchorTx = translationX
        initialAnchorTy = translationY
    }

    // =========================================================================
    // Touch & Gesture State Machine
    // =========================================================================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || !isElasticDragEnabled) {
            return super.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Cancel any pending spring recovery immediately to retarget smoothly mid-flight
                springAnimator?.cancel()
                uiHandler.removeCallbacks(holdRunnable)

                downRawX = event.rawX
                downRawY = event.rawY
                lastMoveRawX = event.rawX
                lastMoveRawY = event.rawY
                dragStartTx = translationX
                dragStartTy = translationY
                isDragOccurred = false

                gestureState = ElasticGestureState.PRESSED_HOLDING
                isPressed = true

                // Subtle pressed scale (0.965f) for immediate tactile response
                animate().scaleX(0.965f).scaleY(0.965f).setDuration(80).start()

                // Post hold detection timer
                uiHandler.postDelayed(holdRunnable, pressHoldDurationMs)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                val dist = hypot(dx, dy)

                // If movement exceeds touch slop before hold timer, activate drag immediately
                if (gestureState == ElasticGestureState.PRESSED_HOLDING && dist > touchSlop) {
                    uiHandler.removeCallbacks(holdRunnable)
                    gestureState = ElasticGestureState.DRAGGING
                    isDragOccurred = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                }

                if (gestureState == ElasticGestureState.DRAGGING) {
                    parent?.requestDisallowInterceptTouchEvent(true)

                    // 1. Position tracking with bounds clamping
                    val targetTx = dragStartTx + dx
                    val targetTy = dragStartTy + dy

                    val parentWidth = (parent as? View)?.width ?: resources.displayMetrics.widthPixels
                    val parentHeight = (parent as? View)?.height ?: resources.displayMetrics.heightPixels

                    val minTx = -left.toFloat() + 12f * density
                    val maxTx = (parentWidth - right).toFloat() - 12f * density
                    val minTy = -top.toFloat() + 8f * density
                    val maxTy = (parentHeight - bottom).toFloat() - 8f * density

                    translationX = targetTx.coerceIn(minTx, maxTx)
                    translationY = targetTy.coerceIn(minTy, maxTy)

                    // 2. Real-time Elastic Stretch Vector Calculation
                    val moveDeltaX = event.rawX - lastMoveRawX
                    val moveDeltaY = event.rawY - lastMoveRawY
                    lastMoveRawX = event.rawX
                    lastMoveRawY = event.rawY

                    val maxStretchPx = maxStretchDistanceDp * density
                    // Blend instantaneous velocity with drag displacement for natural physical stretch
                    val targetStretchX = (moveDeltaX * 1.8f + dx * 0.28f).coerceIn(-maxStretchPx, maxStretchPx)
                    val targetStretchY = (moveDeltaY * 1.8f + dy * 0.28f).coerceIn(-maxStretchPx, maxStretchPx)

                    // Low-pass filter for smooth organic stretching without jitter
                    currentStretchX += (targetStretchX - currentStretchX) * 0.42f
                    currentStretchY += (targetStretchY - currentStretchY) * 0.42f

                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_UP -> {
                uiHandler.removeCallbacks(holdRunnable)
                isPressed = false

                if (gestureState == ElasticGestureState.DRAGGING) {
                    startSpringReleaseAnimation()
                    return true
                } else if (gestureState == ElasticGestureState.PRESSED_HOLDING) {
                    // Tap gesture completed within hold duration: restore scale and fire original click
                    animate().scaleX(1.0f).scaleY(1.0f).setDuration(90).start()
                    gestureState = ElasticGestureState.IDLE
                    performClick()
                    return true
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                uiHandler.removeCallbacks(holdRunnable)
                isPressed = false
                animate().scaleX(1.0f).scaleY(1.0f).setDuration(90).start()
                if (gestureState == ElasticGestureState.DRAGGING) {
                    startSpringReleaseAnimation()
                } else {
                    currentStretchX = 0f
                    currentStretchY = 0f
                    gestureState = ElasticGestureState.IDLE
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        // Prevent drag gestures from triggering original click callbacks on release
        if (isDragOccurred) {
            return false
        }
        return super.performClick()
    }

    // =========================================================================
    // Premium Elastic Physics & Damped Spring Recovery
    // =========================================================================

    private fun startSpringReleaseAnimation() {
        gestureState = ElasticGestureState.RELEASING
        springAnimator?.cancel()

        val startStretchX = currentStretchX
        val startStretchY = currentStretchY
        val startTx = translationX
        val startTy = translationY
        val targetTx = if (returnToAnchorOnRelease) initialAnchorTx else startTx
        val targetTy = if (returnToAnchorOnRelease) initialAnchorTy else startTy

        // Damped physical spring interpolator (Zeta = 0.72, Omega = 16 rad/s)
        val springInterpolator = DampedSpringInterpolator(dampingRatio = 0.72f, frequency = 16.0f)

        springAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340L
            interpolator = springInterpolator
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                val remaining = 1f - fraction

                currentStretchX = startStretchX * remaining
                currentStretchY = startStretchY * remaining

                if (returnToAnchorOnRelease) {
                    translationX = targetTx + (startTx - targetTx) * remaining
                    translationY = targetTy + (startTy - targetTy) * remaining
                }

                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    currentStretchX = 0f
                    currentStretchY = 0f
                    if (returnToAnchorOnRelease) {
                        translationX = targetTx
                        translationY = targetTy
                    }
                    gestureState = ElasticGestureState.IDLE
                    isDragOccurred = false
                    invalidate()
                }

                override fun onAnimationCancel(animation: Animator) {
                    gestureState = ElasticGestureState.IDLE
                    isDragOccurred = false
                }
            })
            start()
        }
    }

    // =========================================================================
    // Real-Time Elastic Shape Deformation & Dynamic Corner Rendering
    // =========================================================================

    /**
     * Transforms an un-deformed perimeter point (px, py) into its elastically stretched
     * coordinates with volume conservation (Poisson necking) and directional Bézier pull.
     * Writes results directly into pathCoords[offset] and pathCoords[offset + 1] with ZERO allocations.
     */
    private fun deformPerimeterPoint(
        px: Float,
        py: Float,
        cx: Float,
        cy: Float,
        ux: Float,
        uy: Float,
        effL: Float,
        offset: Int
    ) {
        val perpX = -uy
        val perpY = ux

        val dx = px - cx
        val dy = py - cy
        val dist = hypot(dx, dy)
        if (dist < 0.001f) {
            pathCoords[offset] = px
            pathCoords[offset + 1] = py
            return
        }

        val nx = dx / dist
        val ny = dy / dist
        val projAlong = nx * ux + ny * uy
        val projPerp = nx * perpX + ny * perpY

        // Asymmetric rubber pull: leading side elongates with power 1.4, trailing side pulls with 0.22 lag
        val stretchAlong = if (projAlong > 0f) {
            Math.pow(projAlong.toDouble(), 1.4).toFloat() * effL
        } else {
            projAlong * 0.22f * effL
        }

        // Transverse waist compression / Poisson necking
        val pinch = -projPerp * (1f - Math.abs(projAlong)) * 0.26f * effL

        pathCoords[offset] = px + ux * stretchAlong + perpX * pinch
        pathCoords[offset + 1] = py + uy * stretchAlong + perpY * pinch
    }

    /**
     * Reconstructs the deformed elastic path using 8 continuous cubic Bézier segments.
     * When stretch is 0, yields the exact resting rounded rectangle / pill.
     */
    private fun buildElasticPath(w: Float, h: Float, sx: Float, sy: Float) {
        elasticPath.reset()

        val r = min(baseCornerRadiusPx, min(w, h) / 2f)
        val cx = w / 2f
        val cy = h / 2f

        val len = hypot(sx, sy)
        if (len < 0.05f) {
            // Resting state: pristine rounded rectangle or pill
            restingRect.set(0f, 0f, w, h)
            elasticPath.addRoundRect(restingRect, r, r, Path.Direction.CW)
            return
        }

        val ux = sx / len
        val uy = sy / len
        val maxL = min(w, h) * 0.75f
        val effL = maxL * tanh((len / maxL).toDouble()).toFloat()
        val kappa = 0.55228475f

        // Segment 0: Top-Right Corner Arc (P0 -> C1 -> C2 -> P1)
        deformPerimeterPoint(w - r, 0f, cx, cy, ux, uy, effL, 0)
        deformPerimeterPoint(w - r + kappa * r, 0f, cx, cy, ux, uy, effL, 2)
        deformPerimeterPoint(w, r - kappa * r, cx, cy, ux, uy, effL, 4)

        // Segment 1: Right Edge (P1 -> E1A -> E1B -> P2)
        deformPerimeterPoint(w, r, cx, cy, ux, uy, effL, 6)
        deformPerimeterPoint(w, r + (h - 2f * r) / 3f, cx, cy, ux, uy, effL, 8)
        deformPerimeterPoint(w, r + 2f * (h - 2f * r) / 3f, cx, cy, ux, uy, effL, 10)

        // Segment 2: Bottom-Right Corner Arc (P2 -> C3 -> C4 -> P3)
        deformPerimeterPoint(w, h - r, cx, cy, ux, uy, effL, 12)
        deformPerimeterPoint(w, h - r + kappa * r, cx, cy, ux, uy, effL, 14)
        deformPerimeterPoint(w - r + kappa * r, h, cx, cy, ux, uy, effL, 16)

        // Segment 3: Bottom Edge (P3 -> E2A -> E2B -> P4)
        deformPerimeterPoint(w - r, h, cx, cy, ux, uy, effL, 18)
        deformPerimeterPoint(w - r - (w - 2f * r) / 3f, h, cx, cy, ux, uy, effL, 20)
        deformPerimeterPoint(w - r - 2f * (w - 2f * r) / 3f, h, cx, cy, ux, uy, effL, 22)

        // Segment 4: Bottom-Left Corner Arc (P4 -> C5 -> C6 -> P5)
        deformPerimeterPoint(r, h, cx, cy, ux, uy, effL, 24)
        deformPerimeterPoint(r - kappa * r, h, cx, cy, ux, uy, effL, 26)
        deformPerimeterPoint(0f, h - r + kappa * r, cx, cy, ux, uy, effL, 28)

        // Segment 5: Left Edge (P5 -> E3A -> E3B -> P6)
        deformPerimeterPoint(0f, h - r, cx, cy, ux, uy, effL, 30)
        deformPerimeterPoint(0f, h - r - (h - 2f * r) / 3f, cx, cy, ux, uy, effL, 32)
        deformPerimeterPoint(0f, h - r - 2f * (h - 2f * r) / 3f, cx, cy, ux, uy, effL, 34)

        // Segment 6: Top-Left Corner Arc (P6 -> C7 -> C8 -> P7)
        deformPerimeterPoint(0f, r, cx, cy, ux, uy, effL, 36)
        deformPerimeterPoint(0f, r - kappa * r, cx, cy, ux, uy, effL, 38)
        deformPerimeterPoint(r - kappa * r, 0f, cx, cy, ux, uy, effL, 40)

        // Segment 7: Top Edge (P7 -> E4A -> E4B -> P0)
        deformPerimeterPoint(r, 0f, cx, cy, ux, uy, effL, 42)
        deformPerimeterPoint(r + (w - 2f * r) / 3f, 0f, cx, cy, ux, uy, effL, 44)
        deformPerimeterPoint(r + 2f * (w - 2f * r) / 3f, 0f, cx, cy, ux, uy, effL, 46)

        // Assemble Continuous Closed Path
        elasticPath.moveTo(pathCoords[0], pathCoords[1])
        for (seg in 0 until 8) {
            val base = seg * 6
            elasticPath.cubicTo(
                pathCoords[base + 2], pathCoords[base + 3],
                pathCoords[base + 4], pathCoords[base + 5],
                pathCoords[(base + 6) % 48], pathCoords[(base + 7) % 48]
            )
        }
        elasticPath.close()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            super.onDraw(canvas)
            return
        }

        // Build real-time deformed rubber path
        buildElasticPath(w, h, currentStretchX, currentStretchY)

        // Determine active background color with subtle pressed tactile depth
        val activeBgColor = if (isPressed || gestureState == ElasticGestureState.PRESSED_HOLDING) {
            // Apply subtle tactile tint shift
            if (ColorUtils.calculateLuminance(baseBackgroundColor) > 0.4) {
                ColorUtils.blendARGB(baseBackgroundColor, Color.BLACK, 0.12f)
            } else {
                ColorUtils.blendARGB(baseBackgroundColor, Color.WHITE, 0.10f)
            }
        } else {
            baseBackgroundColor
        }
        elasticPaint.color = activeBgColor

        // 1. Draw elastic rubber fill
        canvas.drawPath(elasticPath, elasticPaint)

        // 2. Draw elastic rubber stroke if present
        if (baseStrokeWidthPx > 0f && Color.alpha(baseStrokeColor) > 0) {
            elasticStrokePaint.color = baseStrokeColor
            elasticStrokePaint.strokeWidth = baseStrokeWidthPx
            canvas.drawPath(elasticPath, elasticStrokePaint)
        }

        // 3. Render content layer (text & icon) visually stable with subtle liquid lag
        canvas.save()
        canvas.clipPath(elasticPath)
        // Subtle organic internal lag so text/icon feels floating inside liquid rubber
        canvas.translate(currentStretchX * 0.18f, currentStretchY * 0.18f)

        super.onDraw(canvas)
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        uiHandler.removeCallbacks(holdRunnable)
        springAnimator?.cancel()
    }

    // =========================================================================
    // Damped Spring Interpolator Math
    // =========================================================================

    /**
     * Exact physical damped harmonic oscillator equation:
     * x(t) = 1.0 - e^(-zeta * omega * t) * (cos(omega_d * t) + (zeta / sqrt(1 - zeta^2)) * sin(omega_d * t))
     * Produces a single controlled overshoot of ~3.5% with zero ringing.
     */
    class DampedSpringInterpolator(
        private val dampingRatio: Float = 0.72f,
        private val frequency: Float = 16.0f
    ) : Interpolator {
        private val sqrtTerm = Math.sqrt((1.0 - dampingRatio * dampingRatio).coerceAtLeast(0.001)).toFloat()
        private val dampedFreq = frequency * sqrtTerm
        private val sinMultiplier = dampingRatio / sqrtTerm

        override fun getInterpolation(input: Float): Float {
            if (input <= 0f) return 0f
            if (input >= 1f) return 1f

            val decay = Math.exp((-dampingRatio * frequency * input).toDouble()).toFloat()
            val cosTerm = cos((dampedFreq * input).toDouble()).toFloat()
            val sinTerm = sin((dampedFreq * input).toDouble()).toFloat()

            // Normalized to 0.0 at input=0 to 1.0 at input=1
            return 1f - decay * (cosTerm + sinMultiplier * sinTerm)
        }
    }
}
