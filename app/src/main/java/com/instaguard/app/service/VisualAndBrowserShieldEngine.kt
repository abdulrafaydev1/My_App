package com.instaguard.app.service

import android.graphics.Bitmap
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs

/**
 * v9.0 On-Device Privacy-Preserving Visual & Browser Content Shield Engine.
 * 
 * Performs 100% on-device visual analysis and browser text classification.
 * Strictly zero network usage, zero disk writing, and immediate bitmap recycling.
 */
object VisualAndBrowserShieldEngine {

    private const val TAG = "InstaGuardShield"

    data class AdultDetectionResult(
        val matched: Boolean,
        val signature: String = "",
        val reason: String = ""
    )

    val BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "com.google.android.googlequicksearchbox",
        "com.sec.android.app.sbrowser",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.brave.browser"
    )

    private val CONTEXT_WHITELIST = listOf(
        "breast cancer",
        "anatomy",
        "medical",
        "biology",
        "doctor",
        "health",
        "symptom",
        "treatment",
        "hospital",
        "wikipedia",
        "education",
        "feeding",
        "surgery"
    )

    private val ADULT_DOMAINS = listOf(
        "xhamster",
        "pornhub",
        "xvideos",
        "xnxx",
        "redtube",
        "youporn",
        "spankbang",
        "chaturbate",
        "stripchat",
        "onlyfans",
        "brazzers",
        "naughtyamerica",
        "sexvid",
        "beeg",
        "eporner",
        "hqporner",
        "rule34"
    )

    private val EXPLICIT_PHRASES = listOf(
        "porn video",
        "explicit video",
        "sex video",
        "xxx video",
        "pussy",
        "boobs video",
        "naked girl",
        "nude video",
        "blowjob",
        "handjob",
        "hentai",
        "sexy female",
        "hot cleavage",
        "twerk video",
        "ass shaking",
        "booty shake",
        "strip dance"
    )

    private val VISUAL_TRIGGER_KEYWORDS = listOf(
        "#bikini",
        "#cleavage",
        "#twerk",
        "#twerking",
        "#booty",
        "#thirsttrap",
        "#minidress",
        "#hotgirl",
        "#sexygirl",
        "#baddie",
        "bikini haul",
        "try on haul",
        "lingerie"
    )

    // Temporal luma cache for hip motion / rear-view oscillation detection
    @Volatile
    private var previousMidTorsoLumaArray: IntArray? = null

    fun resetTemporalState() {
        previousMidTorsoLumaArray = null
    }

    /**
     * Traverses rootInActiveWindow to detect adult websites or explicit searches in supported browsers.
     * Respects isPassword flags to never inspect secure fields.
     */
    fun checkBrowserOrSearchContent(root: AccessibilityNodeInfo?): AdultDetectionResult {
        if (root == null) return AdultDetectionResult(matched = false)

        val sb = StringBuilder()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(Pair(root, 0))

        var nodeCount = 0
        while (!queue.isEmpty() && nodeCount < 180) {
            val (node, depth) = queue.poll() ?: break
            nodeCount++

            // SAFETY FIRST: NEVER inspect password, PIN, or secure input fields!
            if (!node.isPassword) {
                val text = node.text?.toString()
                val desc = node.contentDescription?.toString()
                val viewId = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""

                if (!text.isNullOrBlank()) {
                    sb.append(" ").append(text)
                }
                if (!desc.isNullOrBlank()) {
                    sb.append(" ").append(desc)
                }

                // Give extra weight to omnibox / URL bar fields
                if (viewId.contains("url") || viewId.contains("omnibox") || viewId.contains("search")) {
                    if (!text.isNullOrBlank()) {
                        sb.append(" ").append(text)
                    }
                }
            }

            if (depth < 12) {
                val childCount = node.childCount
                for (i in 0 until childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        queue.add(Pair(child, depth + 1))
                    }
                }
            }
        }

        val fullText = sb.toString().lowercase(Locale.ROOT).trim()
        if (fullText.isEmpty()) return AdultDetectionResult(matched = false)

        // 1. Context Whitelist Check (Prevents false positives on educational/medical topics)
        for (whitelistWord in CONTEXT_WHITELIST) {
            if (fullText.contains(whitelistWord)) {
                return AdultDetectionResult(matched = false)
            }
        }

        // 2. Adult Domain Check
        for (domain in ADULT_DOMAINS) {
            if (fullText.contains(domain)) {
                val sig = domain.hashCode().toString()
                return AdultDetectionResult(matched = true, signature = sig, reason = "Adult domain: $domain")
            }
        }

        // 3. Explicit Query Matching
        for (phrase in EXPLICIT_PHRASES) {
            if (fullText.contains(phrase)) {
                val sig = phrase.hashCode().toString()
                return AdultDetectionResult(matched = true, signature = sig, reason = "Explicit search: $phrase")
            }
        }

        return AdultDetectionResult(matched = false)
    }

    /**
     * Fast UI Caption, Hashtag, and Audio Description check inside social apps (Instagram, YouTube, etc.).
     */
    fun checkNodeTextForVisualTriggers(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false

        val sb = StringBuilder()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(Pair(root, 0))

        var nodeCount = 0
        while (!queue.isEmpty() && nodeCount < 120) {
            val (node, depth) = queue.poll() ?: break
            nodeCount++

            if (!node.isPassword) {
                val text = node.text?.toString()
                val desc = node.contentDescription?.toString()
                if (!text.isNullOrBlank()) sb.append(" ").append(text)
                if (!desc.isNullOrBlank()) sb.append(" ").append(desc)
            }

            if (depth < 10) {
                val childCount = node.childCount
                for (i in 0 until childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        queue.add(Pair(child, depth + 1))
                    }
                }
            }
        }

        val fullText = sb.toString().lowercase(Locale.ROOT)
        for (trigger in VISUAL_TRIGGER_KEYWORDS) {
            if (fullText.contains(trigger)) return true
        }

        // Contextual trigger: #grwm with short dress or outfit
        if (fullText.contains("#grwm") && (fullText.contains("short dress") || fullText.contains("outfit"))) {
            return true
        }

        return false
    }

    /**
     * On-Device Spatial & Temporal Bitmap Analyzer.
     * Inspects 3 Regions of Interest (ROI) via ITU-R BT.601 YCbCr skin-tone and clothing analysis:
     * - UpperChestROI (x = 25%..75%, y = 18%..48%)
     * - MidTorsoHipROI (x = 22%..78%, y = 45%..72%)
     * - LowerThighROI (x = 25%..75%, y = 68%..84%)
     */
    fun analyzeCapturedBitmap(scaledBitmap: Bitmap, isStrict: Boolean): Boolean {
        val width = scaledBitmap.width
        val height = scaledBitmap.height
        if (width < 20 || height < 40) return false

        val totalPixels = width * height
        val pixels = IntArray(totalPixels)
        scaledBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // ROI Boundaries in pixels
        val upperChestLeft = (width * 0.25f).toInt().coerceIn(0, width - 1)
        val upperChestRight = (width * 0.75f).toInt().coerceIn(0, width - 1)
        val upperChestTop = (height * 0.18f).toInt().coerceIn(0, height - 1)
        val upperChestBottom = (height * 0.48f).toInt().coerceIn(0, height - 1)

        val midHipLeft = (width * 0.22f).toInt().coerceIn(0, width - 1)
        val midHipRight = (width * 0.78f).toInt().coerceIn(0, width - 1)
        val midHipTop = (height * 0.45f).toInt().coerceIn(0, height - 1)
        val midHipBottom = (height * 0.72f).toInt().coerceIn(0, height - 1)

        val lowerThighLeft = (width * 0.25f).toInt().coerceIn(0, width - 1)
        val lowerThighRight = (width * 0.75f).toInt().coerceIn(0, width - 1)
        val lowerThighTop = (height * 0.68f).toInt().coerceIn(0, height - 1)
        val lowerThighBottom = (height * 0.84f).toInt().coerceIn(0, height - 1)

        var skinUpperChestCount = 0
        var totalUpperChestCount = 0

        var skinMidHipCount = 0
        var totalMidHipCount = 0

        var skinLowerThighCount = 0
        var totalLowerThighCount = 0

        val midLumaList = ArrayList<Int>(100)

        // Cleavage Center Shadow Tracking
        val cleavageCenterLeft = (width * 0.46f).toInt()
        val cleavageCenterRight = (width * 0.54f).toInt()
        var centerLumaSum = 0L
        var centerLumaCount = 0
        var lateralLumaSum = 0L
        var lateralLumaCount = 0

        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val color = pixels[rowOffset + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                // Fast ITU-R BT.601 integer conversion
                val lumaY = (299 * r + 587 * g + 114 * b) / 1000
                val cb = ((-169 * r - 331 * g + 500 * b) / 1000) + 128
                val cr = ((500 * r - 419 * g - 81 * b) / 1000) + 128

                // YCbCr Skin Tone Exposure Test
                val isSkin = cr in 135..180 && cb in 85..135 && lumaY > 60

                // 1. Upper Chest ROI
                if (y in upperChestTop..upperChestBottom && x in upperChestLeft..upperChestRight) {
                    totalUpperChestCount++
                    if (isSkin) skinUpperChestCount++

                    if (x in cleavageCenterLeft..cleavageCenterRight) {
                        centerLumaSum += lumaY
                        centerLumaCount++
                    } else if ((x in upperChestLeft..(cleavageCenterLeft - 4)) || (x in (cleavageCenterRight + 4)..upperChestRight)) {
                        lateralLumaSum += lumaY
                        lateralLumaCount++
                    }
                }

                // 2. Mid Torso / Hip ROI
                if (y in midHipTop..midHipBottom && x in midHipLeft..midHipRight) {
                    totalMidHipCount++
                    if (isSkin) skinMidHipCount++

                    // Sample luma grid for temporal motion difference
                    if (x % 4 == 0 && y % 4 == 0) {
                        midLumaList.add(lumaY)
                    }
                }

                // 3. Lower Thigh ROI
                if (y in lowerThighTop..lowerThighBottom && x in lowerThighLeft..lowerThighRight) {
                    totalLowerThighCount++
                    if (isSkin) skinLowerThighCount++
                }
            }
        }

        val upperChestSkinRatio = if (totalUpperChestCount > 0) skinUpperChestCount.toFloat() / totalUpperChestCount else 0f
        val midHipSkinRatio = if (totalMidHipCount > 0) skinMidHipCount.toFloat() / totalMidHipCount else 0f
        val lowerThighSkinRatio = if (totalLowerThighCount > 0) skinLowerThighCount.toFloat() / totalLowerThighCount else 0f

        // Center cleavage shadow gradient detection: center is darker than lateral skin tones
        val avgCenterLuma = if (centerLumaCount > 0) centerLumaSum / centerLumaCount else 128L
        val avgLateralLuma = if (lateralLumaCount > 0) lateralLumaSum / lateralLumaCount else 128L
        val hasCleavageShadow = (avgLateralLuma - avgCenterLuma) >= 12L

        // Condition 1: Breast / Chest-Focused Close-up
        val upperChestThreshold = if (isStrict) 0.46f else 0.54f
        val cond1Matched = (upperChestSkinRatio >= upperChestThreshold && hasCleavageShadow) ||
                (upperChestSkinRatio >= 0.65f)

        // Condition 2: Short / Revealing Dress + Cleavage/Thighs Exposed
        val cond2Matched = upperChestSkinRatio >= 0.28f &&
                midHipSkinRatio in 0.12f..0.65f && // Tight/revealing clothing or cut-outs
                lowerThighSkinRatio >= 0.36f       // Bare legs/thighs exposed

        // Condition 3: Buttocks / Rear-View & Suggestive Temporal Motion
        var cond3Matched = false
        val currentLumaArray = midLumaList.toIntArray()
        val prevLuma = previousMidTorsoLumaArray
        if (prevLuma != null && prevLuma.size == currentLumaArray.size) {
            var totalDelta = 0L
            for (i in prevLuma.indices) {
                totalDelta += abs(prevLuma[i] - currentLumaArray[i])
            }
            val avgMotionDelta = totalDelta.toFloat() / prevLuma.size
            // High skin in lower/mid or sustained rear-view framing + rapid oscillation
            if ((midHipSkinRatio >= 0.38f || lowerThighSkinRatio >= 0.45f) && avgMotionDelta >= 14f) {
                cond3Matched = true
            }
        }
        previousMidTorsoLumaArray = currentLumaArray

        val result = cond1Matched || cond2Matched || cond3Matched
        if (result) {
            Log.d(TAG, "Visual match: cond1=$cond1Matched (upperChest=$upperChestSkinRatio), cond2=$cond2Matched (thighs=$lowerThighSkinRatio), cond3=$cond3Matched")
        }
        return result
    }
}
