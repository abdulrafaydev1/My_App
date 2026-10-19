package com.instaguard.app.service

import kotlin.math.abs

/**
 * Pure-Kotlin, unit-testable coordinate transformation utility (Phase 7).
 *
 * Maps normalized source-frame coordinates [0.0f..1.0f] into overlay View display pixels,
 * accounting for source frame aspect ratio, target display dimensions, letterboxing / pillarboxing,
 * display density, status/navigation bar insets, and overlay window screen offsets.
 */
object DetectionCoordinateMapper {

    data class MappedBox(
        val trackId: Int = 1,
        val regionLabel: String = "BOX",
        val confidence: Float? = null,
        val leftPx: Float,
        val topPx: Float,
        val rightPx: Float,
        val bottomPx: Float
    ) {
        val left: Float get() = leftPx
        val top: Float get() = topPx
        val right: Float get() = rightPx
        val bottom: Float get() = bottomPx
        val width: Float get() = rightPx - leftPx
        val height: Float get() = bottomPx - topPx
    }

    data class MappingConfig(
        val viewWidth: Int,
        val viewHeight: Int,
        val sourceWidth: Int = 0,
        val sourceHeight: Int = 0,
        val windowOffsetX: Int = 0,
        val windowOffsetY: Int = 0,
        val statusBarInsetY: Int = 0,
        val navBarInsetY: Int = 0,
        val rotationDegrees: Int = 0
    )

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
    ): MappedBox? {
        val tempBox = NormalizedBox(
            trackId = 1,
            regionLabel = "BOX",
            confidence = null,
            left = normLeft,
            top = normTop,
            right = normRight,
            bottom = normBottom,
            timestampMs = 1L,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight
        )
        return mapNormalizedBoxToView(
            box = tempBox,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            windowOffsetX = windowOffsetX,
            windowOffsetY = windowOffsetY
        )
    }

    fun mapNormalizedBoxToView(
        box: NormalizedBox,
        viewWidth: Int,
        viewHeight: Int,
        windowOffsetX: Int = 0,
        windowOffsetY: Int = 0,
        statusBarInsetY: Int = 0,
        navBarInsetY: Int = 0
    ): MappedBox? {
        if (!box.isValid() || viewWidth <= 0 || viewHeight <= 0) return null

        val fullScreenW = (viewWidth + windowOffsetX).toFloat()
        val fullScreenH = (viewHeight + windowOffsetY).toFloat()

        val srcW = if (box.sourceWidth > 0) box.sourceWidth.toFloat() else fullScreenW
        val srcH = if (box.sourceHeight > 0) box.sourceHeight.toFloat() else fullScreenH

        val scaleX: Float
        val scaleY: Float
        val letterboxLeft: Float
        val letterboxTop: Float

        val srcAspect = srcW / srcH
        val dstAspect = fullScreenW / fullScreenH

        if (abs(srcAspect - dstAspect) < 0.03f) {
            scaleX = fullScreenW
            scaleY = fullScreenH
            letterboxLeft = 0f
            letterboxTop = 0f
        } else if (srcAspect > dstAspect) {
            // Source is wider than display: letterboxed top & bottom
            scaleX = fullScreenW
            scaleY = fullScreenW / srcAspect
            letterboxLeft = 0f
            letterboxTop = (fullScreenH - scaleY) * 0.5f
        } else {
            // Source is taller than display: pillarboxed left & right
            scaleY = fullScreenH
            scaleX = fullScreenH * srcAspect
            letterboxLeft = (fullScreenW - scaleX) * 0.5f
            letterboxTop = 0f
        }

        val rawLeft = letterboxLeft + box.left * scaleX - windowOffsetX
        val rawTop = letterboxTop + box.top * scaleY - windowOffsetY - statusBarInsetY
        val rawRight = letterboxLeft + box.right * scaleX - windowOffsetX
        val rawBottom = letterboxTop + box.bottom * scaleY - windowOffsetY - statusBarInsetY

        val left = rawLeft.coerceIn(0f, viewWidth.toFloat())
        val top = rawTop.coerceIn(0f, viewHeight.toFloat())
        val right = rawRight.coerceIn(0f, viewWidth.toFloat())
        val bottom = rawBottom.coerceIn(0f, viewHeight.toFloat())

        if (right - left < 4f || bottom - top < 4f) return null

        return MappedBox(
            trackId = box.trackId,
            regionLabel = box.regionLabel,
            confidence = box.confidence,
            leftPx = left,
            topPx = top,
            rightPx = right,
            bottomPx = bottom
        )
    }

    fun mapLandmarkToView(
        normX: Float,
        normY: Float,
        viewWidth: Int,
        viewHeight: Int,
        sourceWidth: Int = 0,
        sourceHeight: Int = 0,
        windowOffsetX: Int = 0,
        windowOffsetY: Int = 0
    ): Pair<Float, Float>? {
        if (normX.isNaN() || normY.isNaN() || normX < 0f || normX > 1f || normY < 0f || normY > 1f) {
            return null
        }
        val fullScreenW = (viewWidth + windowOffsetX).toFloat()
        val fullScreenH = (viewHeight + windowOffsetY).toFloat()
        val srcW = if (sourceWidth > 0) sourceWidth.toFloat() else fullScreenW
        val srcH = if (sourceHeight > 0) sourceHeight.toFloat() else fullScreenH

        val srcAspect = srcW / srcH
        val dstAspect = fullScreenW / fullScreenH

        val scaleX: Float
        val scaleY: Float
        val letterboxLeft: Float
        val letterboxTop: Float

        if (abs(srcAspect - dstAspect) < 0.03f) {
            scaleX = fullScreenW
            scaleY = fullScreenH
            letterboxLeft = 0f
            letterboxTop = 0f
        } else if (srcAspect > dstAspect) {
            scaleX = fullScreenW
            scaleY = fullScreenW / srcAspect
            letterboxLeft = 0f
            letterboxTop = (fullScreenH - scaleY) * 0.5f
        } else {
            scaleY = fullScreenH
            scaleX = fullScreenH * srcAspect
            letterboxLeft = (fullScreenW - scaleX) * 0.5f
            letterboxTop = 0f
        }

        val pxX = (letterboxLeft + normX * scaleX - windowOffsetX).coerceIn(0f, viewWidth.toFloat())
        val pxY = (letterboxTop + normY * scaleY - windowOffsetY).coerceIn(0f, viewHeight.toFloat())
        return Pair(pxX, pxY)
    }
}
