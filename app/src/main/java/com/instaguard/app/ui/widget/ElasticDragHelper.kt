package com.instaguard.app.ui.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * Reusable interaction controller that attaches elastic press-and-drag physics,
 * gesture discrimination (tap vs drag), touch-slop thresholds, and spring relaxation
 * to any Android View.
 */
class ElasticDragHelper(
    private val targetView: View,
    var returnToAnchorOnRelease: Boolean = false,
    var pressHoldDurationMs: Long = 160L,
    var onDragStateChanged: ((isDragging: Boolean) -> Unit)? = null
) {
    private val density = targetView.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(targetView.context).scaledTouchSlop.toFloat()

    private var downRawX = 0f
    private var downRawY = 0f
    private var initialAnchorTx = targetView.translationX
    private var initialAnchorTy = targetView.translationY
    private var dragStartTx = 0f
    private var dragStartTy = 0f

    var isDragging = false
        private set
    var isDragOccurred = false
        private set

    private var springAnimator: ValueAnimator? = null

    private val holdRunnable = Runnable {
        if (!isDragging) {
            isDragging = true
            isDragOccurred = true
            onDragStateChanged?.invoke(true)
            targetView.parent?.requestDisallowInterceptTouchEvent(true)
            try {
                targetView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            } catch (_: Exception) {}
            targetView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
        }
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                springAnimator?.cancel()
                targetView.removeCallbacks(holdRunnable)

                downRawX = event.rawX
                downRawY = event.rawY
                dragStartTx = targetView.translationX
                dragStartTy = targetView.translationY
                isDragging = false
                isDragOccurred = false

                targetView.animate().scaleX(0.965f).scaleY(0.965f).setDuration(80).start()
                targetView.postDelayed(holdRunnable, pressHoldDurationMs)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                val dist = hypot(dx, dy)

                if (!isDragging && dist > touchSlop) {
                    targetView.removeCallbacks(holdRunnable)
                    isDragging = true
                    isDragOccurred = true
                    onDragStateChanged?.invoke(true)
                    targetView.parent?.requestDisallowInterceptTouchEvent(true)
                    targetView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                }

                if (isDragging) {
                    targetView.parent?.requestDisallowInterceptTouchEvent(true)

                    val parentWidth = (targetView.parent as? View)?.width ?: targetView.resources.displayMetrics.widthPixels
                    val parentHeight = (targetView.parent as? View)?.height ?: targetView.resources.displayMetrics.heightPixels

                    val minTx = -targetView.left.toFloat() + 12f * density
                    val maxTx = (parentWidth - targetView.right).toFloat() - 12f * density
                    val minTy = -targetView.top.toFloat() + 8f * density
                    val maxTy = (parentHeight - targetView.bottom).toFloat() - 8f * density

                    targetView.translationX = (dragStartTx + dx).coerceIn(minTx, maxTx)
                    targetView.translationY = (dragStartTy + dy).coerceIn(minTy, maxTy)
                    return true
                }
            }

            MotionEvent.ACTION_UP -> {
                targetView.removeCallbacks(holdRunnable)

                if (isDragging) {
                    startSpringRelease()
                    return true
                } else {
                    targetView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(90).start()
                    targetView.performClick()
                    return true
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                targetView.removeCallbacks(holdRunnable)
                targetView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(90).start()
                if (isDragging) {
                    startSpringRelease()
                }
                return true
            }
        }
        return false
    }

    private fun startSpringRelease() {
        springAnimator?.cancel()
        val startTx = targetView.translationX
        val startTy = targetView.translationY
        val targetTx = if (returnToAnchorOnRelease) initialAnchorTx else startTx
        val targetTy = if (returnToAnchorOnRelease) initialAnchorTy else startTy

        val springInterpolator = ElasticStretchButton.DampedSpringInterpolator(0.72f, 16.0f)
        springAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340L
            interpolator = springInterpolator
            addUpdateListener { animator ->
                val remaining = 1f - (animator.animatedValue as Float)
                if (returnToAnchorOnRelease) {
                    targetView.translationX = targetTx + (startTx - targetTx) * remaining
                    targetView.translationY = targetTy + (startTy - targetTy) * remaining
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (returnToAnchorOnRelease) {
                        targetView.translationX = targetTx
                        targetView.translationY = targetTy
                    }
                    isDragging = false
                    isDragOccurred = false
                    onDragStateChanged?.invoke(false)
                }
            })
            start()
        }
    }

    companion object {
        /**
         * Attaches elastic drag behavior to any existing View instance.
         */
        fun attach(
            view: View,
            returnToAnchor: Boolean = false,
            pressHoldDurationMs: Long = 160L,
            onDragStateChanged: ((Boolean) -> Unit)? = null
        ): ElasticDragHelper {
            val helper = ElasticDragHelper(view, returnToAnchor, pressHoldDurationMs, onDragStateChanged)
            view.setOnTouchListener { _, event -> helper.onTouchEvent(event) }
            return helper
        }
    }
}
