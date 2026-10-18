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
 * Performs 100% on-device visual analysis and browser text/search classification.
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

    // User-specified required list + comprehensive adult keywords & domains
    private val EXPLICIT_BROWSER_KEYWORDS = listOf(
        // Required exact phrases
        "porn video",
        "xhamster",
        "sexy video",
        "sexy female",
        "female boobs",
        "female pussy",
        // Comprehensive adult domains & sites
        "pornhub",
        "xvideos",
        "xnxx",
        "redtube",
        "youporn",
        "spankbang",
        "brazzers",
        "onlyfans",
        "chaturbate",
        "stripchat",
        "eporner",
        "hqporner",
        "rule34",
        "beeg",
        "naughtyamerica",
        "sexvid",
        "desipapa",
        "mms leak",
        // Explicit search terms
        "sex video",
        "xxx",
        "porn",
        "nude",
        "naked",
        "boobs",
        "pussy",
        "tits",
        "ass video",
        "twerk video",
        "hot sexy",
        "adult video",
        "hentai",
        "erotic",
        "nsfw",
        "call girl",
        "camgirl",
        "bikini hot",
        "cleavage"
    )

    // Simultaneous UI caption, hashtag & audio description triggers
    private val SUGGESTIVE_METADATA_TRIGGERS = listOf(
        "sexy",
        "hot girl",
        "cleavage",
        "boobs",
        "twerk",
        "booty",
        "bikini",
        "lingerie",
        "baddie",
        "thirst trap",
        "grwm",
        "mini dress",
        "short dress",
        "backless",
        "curvy",
        "thick",
        "gyatt",
        "strip",
        "belly dance",
        "shaking",
        "onlyfans",
        "nsfw",
        "18+",
        "hot dance",
        "saree hot",
        "bold",
        "figure",
        "lust",
        // Common hashtag versions
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
        "#grwm"
    )

    // Temporal skin grid cache for Zone 2, Zone 3, and Zone 4 motion-delta calculation
    @Volatile
    private var previousSkinGrid: BooleanArray? = null

    fun resetTemporalState() {
        previousSkinGrid = null
    }

    /**
     * Feature 3: Real-Time Search Query, URL Bar & Webpage Inspection for Google Chrome & Google App.
     */
    fun checkBrowserForExplicitContent(root: AccessibilityNodeInfo?): AdultDetectionResult {
        if (root == null) return AdultDetectionResult(matched = false)

        val sb = StringBuilder()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(Pair(root, 0))

        var nodeCount = 0
        var hasActiveVideoPlayer = false

        while (!queue.isEmpty() && nodeCount < 200) {
            val (node, depth) = queue.poll() ?: break
            nodeCount++

            // SAFETY: NEVER inspect password, PIN, or secure input fields!
            if (!node.isPassword) {
                val text = node.text?.toString()
                val desc = node.contentDescription?.toString()
                val className = node.className?.toString() ?: ""
                val viewId = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""

                if (!text.isNullOrBlank()) {
                    sb.append(" ").append(text)
                }
                if (!desc.isNullOrBlank()) {
                    sb.append(" ").append(desc)
                }

                // Check for active video playback player in browser
                if (className.contains("SurfaceView", ignoreCase = true) ||
                    className.contains("TextureView", ignoreCase = true) ||
                    className.contains("VideoView", ignoreCase = true) ||
                    className.contains("Player", ignoreCase = true) ||
                    viewId.contains("video", ignoreCase = true) ||
                    viewId.contains("player", ignoreCase = true)
                ) {
                    hasActiveVideoPlayer = true
                }

                // Give omnibox / URL fields special capture
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
        if (fullText.isEmpty()) {
            return AdultDetectionResult(matched = false)
        }

        // 1. Context Whitelist Check (Prevents false positives on educational/medical topics)
        for (whitelistWord in CONTEXT_WHITELIST) {
            if (fullText.contains(whitelistWord)) {
                return AdultDetectionResult(matched = false)
            }
        }

        // 2. Prohibited Keyword & Domain Matching
        for (keyword in EXPLICIT_BROWSER_KEYWORDS) {
            if (fullText.contains(keyword)) {
                val sig = keyword.hashCode().toString()
                val reason = if (hasActiveVideoPlayer) {
                    "Explicit video playback with keyword: $keyword"
                } else {
                    "NSFW keyword/domain: $keyword"
                }
                return AdultDetectionResult(matched = true, signature = sig, reason = reason)
            }
        }

        return AdultDetectionResult(matched = false)
    }

    /**
     * Feature 2 B: Simultaneous UI Caption, Hashtag & Audio Description Scanner.
     */
    fun hasSuggestiveVideoMetadata(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false

        val sb = StringBuilder()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(Pair(root, 0))

        var nodeCount = 0
        while (!queue.isEmpty() && nodeCount < 140) {
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
        for (trigger in SUGGESTIVE_METADATA_TRIGGERS) {
            if (fullText.contains(trigger)) return true
        }

        // Contextual: #grwm with dress or outfit
        if (fullText.contains("grwm") && (fullText.contains("dress") || fullText.contains("outfit") || fullText.contains("fit"))) {
            return true
        }

        return false
    }

    /**
     * Feature 2 A: Multi-Zone Anatomical Skin, Attire & Motion-Delta Visual Scanner.
     * Evaluates a 96 x 170 software bitmap across 4 distinct anatomical zones:
     * - Zone 1: Face & Shoulders (Y = 15% to 32%)
     * - Zone 2: Chest / Cleavage & Upper Bare Back (Y = 32% to 56%, X = 20% to 80%)
     * - Zone 3: Midriff / Waist / Hip & Buttocks (Y = 56% to 78%, X = 18% to 82%)
     * - Zone 4: Upper Thigh / Short Skirt & Slit Dress (Y = 78% to 92%, X = 20% to 80%)
     */
    fun analyzeBitmapForSuggestiveContent(scaledBitmap: Bitmap): Boolean {
        val width = scaledBitmap.width
        val height = scaledBitmap.height
        if (width < 20 || height < 40) return false

        val totalPixels = width * height
        val pixels = IntArray(totalPixels)
        scaledBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Zone Coordinate Boundaries
        val z1Top = (height * 0.15f).toInt().coerceIn(0, height - 1)
        val z1Bottom = (height * 0.32f).toInt().coerceIn(0, height - 1)

        val z2Top = (height * 0.32f).toInt().coerceIn(0, height - 1)
        val z2Bottom = (height * 0.56f).toInt().coerceIn(0, height - 1)
        val z2Left = (width * 0.20f).toInt().coerceIn(0, width - 1)
        val z2Right = (width * 0.80f).toInt().coerceIn(0, width - 1)

        val z3Top = (height * 0.56f).toInt().coerceIn(0, height - 1)
        val z3Bottom = (height * 0.78f).toInt().coerceIn(0, height - 1)
        val z3Left = (width * 0.18f).toInt().coerceIn(0, width - 1)
        val z3Right = (width * 0.82f).toInt().coerceIn(0, width - 1)

        val z4Top = (height * 0.78f).toInt().coerceIn(0, height - 1)
        val z4Bottom = (height * 0.92f).toInt().coerceIn(0, height - 1)
        val z4Left = (width * 0.20f).toInt().coerceIn(0, width - 1)
        val z4Right = (width * 0.80f).toInt().coerceIn(0, width - 1)

        var z1SkinCount = 0
        var z1Total = 0

        var z2SkinCount = 0
        var z2Total = 0

        var z3SkinCount = 0
        var z3Total = 0

        var z4SkinCount = 0
        var z4Total = 0

        // Continuous vertical skin sampling
        val motionGrid = ArrayList<Boolean>(200)

        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val color = pixels[rowOffset + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                // YCbCr + RGB Skin Tone Rule:
                // y = 0.299 * r + 0.587 * g + 0.114 * b
                // cb = 128 - 0.168736 * r - 0.331264 * g + 0.5 * b
                // cr = 128 + 0.5 * r - 0.418688 * g - 0.081312 * b
                val lumaY = 0.299 * r + 0.587 * g + 0.114 * b
                val cb = 128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b
                val cr = 128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b

                val isSkin = lumaY > 55.0 &&
                        cb in 77.0..127.0 &&
                        cr in 133.0..175.0 &&
                        r > g && r > b &&
                        (r - minOf(g, b)) > 15

                // Zone 1
                if (y in z1Top..z1Bottom) {
                    z1Total++
                    if (isSkin) z1SkinCount++
                }

                // Zone 2
                if (y in z2Top..z2Bottom && x in z2Left..z2Right) {
                    z2Total++
                    if (isSkin) z2SkinCount++
                    if (x % 3 == 0 && y % 3 == 0) motionGrid.add(isSkin)
                }

                // Zone 3
                if (y in z3Top..z3Bottom && x in z3Left..z3Right) {
                    z3Total++
                    if (isSkin) z3SkinCount++
                    if (x % 3 == 0 && y % 3 == 0) motionGrid.add(isSkin)
                }

                // Zone 4
                if (y in z4Top..z4Bottom && x in z4Left..z4Right) {
                    z4Total++
                    if (isSkin) z4SkinCount++
                    if (x % 3 == 0 && y % 3 == 0) motionGrid.add(isSkin)
                }
            }
        }

        val z2Ratio = if (z2Total > 0) z2SkinCount.toFloat() / z2Total else 0f
        val z3Ratio = if (z3Total > 0) z3SkinCount.toFloat() / z3Total else 0f
        val z4Ratio = if (z4Total > 0) z4SkinCount.toFloat() / z4Total else 0f

        val z2PlusZ3Ratio = if (z2Total + z3Total > 0) (z2SkinCount + z3SkinCount).toFloat() / (z2Total + z3Total) else 0f
        val z3PlusZ4Ratio = if (z3Total + z4Total > 0) (z3SkinCount + z4SkinCount).toFloat() / (z3Total + z4Total) else 0f

        // Condition 1 & 2: Chest / Cleavage Exposure & Revealing Short Dress
        val cond1And2 = (z2Ratio >= 0.28f && (z3Ratio in 0.10f..0.55f || z4Ratio >= 0.30f)) ||
                (z2PlusZ3Ratio >= 0.36f)

        // Condition 3: Bare Back or Buttocks Exposure
        val cond3 = (z2PlusZ3Ratio >= 0.34f) || (z3PlusZ4Ratio >= 0.35f)

        // Condition 4 & 5: Suggestive Movement, Shaking Back/Buttocks, or Dancing in Short Dress
        var cond4And5 = false
        val currentGrid = motionGrid.toBooleanArray()
        val prevGrid = previousSkinGrid
        if (prevGrid != null && prevGrid.size == currentGrid.size) {
            var diffCount = 0
            for (i in prevGrid.indices) {
                if (prevGrid[i] != currentGrid[i]) diffCount++
            }
            val motionShiftRatio = diffCount.toFloat() / prevGrid.size
            val avgActiveSkin = (z2Ratio + z3Ratio + z4Ratio) / 3f
            if (avgActiveSkin >= 0.22f && motionShiftRatio >= 0.14f) {
                cond4And5 = true
            }
        }
        previousSkinGrid = currentGrid

        val matched = cond1And2 || cond3 || cond4And5
        if (matched) {
            Log.d(TAG, "Suggestive visual match: c12=$cond1And2 (z2=$z2Ratio, z3=$z3Ratio), c3=$cond3, c45=$cond4And5")
        }
        return matched
    }

    /**
     * Checks if a frame has high skin exposure (>= threshold, default 0.32f).
     * Used for explicit video playback verification inside browsers.
     */
    fun isHighExposureFrame(scaledBitmap: Bitmap, threshold: Float = 0.32f): Boolean {
        val width = scaledBitmap.width
        val height = scaledBitmap.height
        if (width < 20 || height < 40) return false

        val totalPixels = width * height
        val pixels = IntArray(totalPixels)
        scaledBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var skinCount = 0
        for (color in pixels) {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val lumaY = 0.299 * r + 0.587 * g + 0.114 * b
            val cb = 128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b
            val cr = 128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b

            if (lumaY > 55.0 && cb in 77.0..127.0 && cr in 133.0..175.0 && r > g && r > b && (r - minOf(g, b)) > 15) {
                skinCount++
            }
        }
        val ratio = skinCount.toFloat() / totalPixels
        return ratio >= threshold
    }
}
