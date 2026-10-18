package com.instaguard.app.classifier

import android.graphics.Bitmap
import android.graphics.Color
import com.instaguard.app.data.GuardPreferences
import java.util.Locale

/**
 * Intelligent On-Device Visual Content Filter & Adult-Content Classifier.
 * Calibrated specifically against real-world reference frames:
 * - Rule A: Short dresses / skirts, high slits, tight mini dress silhouettes.
 * - Rule B: Chest-focused presentation, deep necklines, cleavage framing.
 * - Rule C: Rear-view presentation, backless dresses, buttocks emphasis.
 * - Rule D: Suggestive dancing, shaking movements, temporal frame delta.
 * - Rule E: Multi-rule aggregation with single-trigger execution.
 * - Rule F: False-positive safeguards (warm ambient lighting & fully clothed protection).
 *
 * Privacy & Security Guarantee:
 * 100% on-device processing. No images, video frames, or search queries are ever
 * stored permanently or transmitted to external servers.
 */
object VisualContentClassifier {

    data class ClassificationResult(
        val isMatch: Boolean,
        val matchedRules: List<String>,
        val primaryReason: String,
        val confidence: Float,
        val ruleADetected: Boolean = false,
        val ruleBDetected: Boolean = false,
        val ruleCDetected: Boolean = false,
        val ruleDDetected: Boolean = false
    )

    val SUGGESTIVE_REEL_INDICATORS = listOf(
        "cleavage", "bikini", "lingerie", "twerk", "booty", "boobs", "baddie",
        "thirst trap", "thirsttrap", "short dress", "mini dress", "backless",
        "hot girl", "sexy girl", "sexy dress", "sexy video", " sexy ", "#sexy",
        "#hot", "curvy", "thick", "gyatt", "strip", "belly dance", "shaking",
        "onlyfans", "nsfw", "underboob", "sideboob", "bootyshorts", "hot reel", "bold reel"
    )

    val EXPLICIT_SEARCH_TERMS = listOf(
        "porn video", "xhamster", "sexy video", "sexy female", "female boobs", "female pussy",
        "pornhub", "xvideos", "xnxx", "redtube", "youporn", "spankbang", "brazzers",
        "onlyfans", "chaturbate", "stripchat", "sex video", "xxx", "nude", "naked",
        "boobs", "pussy", "tits", "ass video", "twerk video", "hot sexy", "adult video",
        "hentai", "rule34", "erotic", "nsfw", "desipapa", "mms leak", "call girl",
        "camgirl", "bikini hot", "cleavage", "porn"
    )

    val ADULT_DOMAINS = listOf(
        "xhamster.com", "pornhub.com", "xvideos.com", "xnxx.com", "redtube.com",
        "youporn.com", "spankbang.com", "brazzers.com", "onlyfans.com", "chaturbate.com",
        "stripchat.com", "cam4.com", "camsoda.com", "livejasmin.com", "faphouse.com"
    )

    val MEDICAL_EDUCATIONAL_WHITELIST = listOf(
        "breast cancer", "breast examination", "breast feeding", "breastfeed",
        "mammogram", "gynecology", "obstetrics", "reproductive health",
        "anatomy textbook", "medical biology", "dermatology", "std prevention",
        "health clinic", "pediatric", "oncology", "biology lecture", "medical research"
    )

    /**
     * Calibrated Skin Pixel Classifier (Combined RGB + HSV + YCbCr).
     * Strictly rejects wooden/beige walls, orange/yellow lighting, and neutral clothing.
     */
    fun isSkinPixel(p: Int): Boolean {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val y = 0.299 * r + 0.587 * g + 0.114 * b
        val cb = 128.0 - 0.1687 * r - 0.3313 * g + 0.5 * b
        val cr = 128.0 + 0.5 * r - 0.4187 * g - 0.0813 * b
        return (r > 80 && g > 45 && b > 30 && r > g && r > b &&
                (r - minOf(g, b)) > 12 &&
                cb in 80.0..124.0 && cr in 135.0..172.0 && y in 60.0..240.0)
    }

    /**
     * Evaluates a downscaled video frame (e.g. 80x140) against the modular visual rules.
     * Evaluates 4 Anatomical Zones in the central column:
     * - faceRatio: 12% to 33% height
     * - chestRatio: 34% to 56% height
     * - midTorsoRatio: 56% to 72% height
     * - lowerGluteThighRatio: 72% to 90% height
     */
    fun evaluateVisualFrame(
        bitmap: Bitmap,
        prefs: GuardPreferences,
        previousSkinGrid: BooleanArray?
    ): Pair<ClassificationResult, BooleanArray?> {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            return Pair(ClassificationResult(false, emptyList(), "Invalid frame dimensions", 0f), previousSkinGrid)
        }

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return evaluatePixelGrid(
            pixels = pixels,
            width = width,
            height = height,
            isStrict = prefs.isStrictVisualMode,
            ruleAEnabled = prefs.isRuleShortClothingEnabled,
            ruleBEnabled = prefs.isRuleChestCleavageEnabled,
            ruleCEnabled = prefs.isRuleRearButtocksEnabled,
            ruleDEnabled = prefs.isRuleSuggestiveMotionEnabled,
            previousSkinGrid = previousSkinGrid
        )
    }

    fun evaluatePixelGrid(
        pixels: IntArray,
        width: Int,
        height: Int,
        prefs: GuardPreferences,
        previousSkinGrid: BooleanArray?
    ): Pair<ClassificationResult, BooleanArray?> {
        return evaluatePixelGrid(
            pixels = pixels,
            width = width,
            height = height,
            isStrict = prefs.isStrictVisualMode,
            ruleAEnabled = prefs.isRuleShortClothingEnabled,
            ruleBEnabled = prefs.isRuleChestCleavageEnabled,
            ruleCEnabled = prefs.isRuleRearButtocksEnabled,
            ruleDEnabled = prefs.isRuleSuggestiveMotionEnabled,
            previousSkinGrid = previousSkinGrid
        )
    }

    fun evaluatePixelGrid(
        pixels: IntArray,
        width: Int,
        height: Int,
        isStrict: Boolean = false,
        ruleAEnabled: Boolean = true,
        ruleBEnabled: Boolean = true,
        ruleCEnabled: Boolean = true,
        ruleDEnabled: Boolean = true,
        previousSkinGrid: BooleanArray? = null
    ): Pair<ClassificationResult, BooleanArray?> {
        if (width <= 0 || height <= 0 || pixels.isEmpty()) {
            return Pair(ClassificationResult(false, emptyList(), "Invalid frame dimensions", 0f), previousSkinGrid)
        }

        val skinMap = BooleanArray(pixels.size)
        val lumMap = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            skinMap[i] = isSkinPixel(p)
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lumMap[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        val cenMinX = (width * 0.20f).toInt()
        val cenMaxX = (width * 0.80f).toInt()

        val faceMinY = (height * 0.12f).toInt()
        val faceMaxY = (height * 0.33f).toInt()
        val chestMinY = (height * 0.34f).toInt()
        val chestMaxY = (height * 0.56f).toInt()
        val midMinY = (height * 0.56f).toInt()
        val midMaxY = (height * 0.72f).toInt()
        val lowMinY = (height * 0.72f).toInt()
        val lowMaxY = (height * 0.90f).toInt()

        val borderLeftMax = (width * 0.10f).toInt()
        val borderRightMin = (width * 0.90f).toInt()

        var borderTotal = 0; var borderSkin = 0
        var faceTotal = 0; var faceSkin = 0
        var chestTotal = 0; var chestSkin = 0
        var midTorsoTotal = 0; var midTorsoSkin = 0
        var lowerGluteThighTotal = 0; var lowerGluteThighSkin = 0

        // Sub-boxes for chestCleavageContrast (x = 28%..45%, 45%..55%, 55%..72%)
        val chestLeftMinX = (width * 0.28f).toInt()
        val chestLeftMaxX = (width * 0.45f).toInt()
        val cleavageMinX = (width * 0.45f).toInt()
        val cleavageMaxX = (width * 0.55f).toInt()
        val chestRightMinX = (width * 0.55f).toInt()
        val chestRightMaxX = (width * 0.72f).toInt()

        var cLeftTotal = 0; var cLeftSkin = 0; var cLeftLum = 0f
        var cCenterTotal = 0; var cCenterSkin = 0; var cCenterLum = 0f
        var cRightTotal = 0; var cRightSkin = 0; var cRightLum = 0f

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val isSkin = skinMap[row + x]
                val lum = lumMap[row + x]

                // Outer edge borders
                if (x < borderLeftMax || x > borderRightMin) {
                    borderTotal++
                    if (isSkin) borderSkin++
                }

                // Center column
                if (x in cenMinX..cenMaxX) {
                    if (y in faceMinY..faceMaxY) {
                        faceTotal++
                        if (isSkin) faceSkin++
                    } else if (y in chestMinY..chestMaxY) {
                        chestTotal++
                        if (isSkin) chestSkin++

                        if (x in chestLeftMinX until chestLeftMaxX) {
                            cLeftTotal++
                            if (isSkin) cLeftSkin++
                            cLeftLum += lum
                        } else if (x in cleavageMinX until cleavageMaxX) {
                            cCenterTotal++
                            if (isSkin) cCenterSkin++
                            cCenterLum += lum
                        } else if (x in chestRightMinX..chestRightMaxX) {
                            cRightTotal++
                            if (isSkin) cRightSkin++
                            cRightLum += lum
                        }
                    } else if (y in midMinY..midMaxY) {
                        midTorsoTotal++
                        if (isSkin) midTorsoSkin++
                    } else if (y in lowMinY..lowMaxY) {
                        lowerGluteThighTotal++
                        if (isSkin) lowerGluteThighSkin++
                    }
                }
            }
        }

        val outerEdgeSkinRatio = borderSkin.toFloat() / maxOf(1, borderTotal)
        val faceRatio = faceSkin.toFloat() / maxOf(1, faceTotal)
        val chestRatio = chestSkin.toFloat() / maxOf(1, chestTotal)
        val midTorsoRatio = midTorsoSkin.toFloat() / maxOf(1, midTorsoTotal)
        val lowerGluteThighRatio = lowerGluteThighSkin.toFloat() / maxOf(1, lowerGluteThighTotal)

        // Rule F Safeguard 1: Full-Screen Warm Wall / Desert Lighting Rejection
        if (outerEdgeSkinRatio > 0.38f) {
            return Pair(
                ClassificationResult(false, emptyList(), "Protected: Ambient Wall/Background Lighting ($outerEdgeSkinRatio)", 0.1f),
                skinMap
            )
        }

        // Rule F Safeguard 2: Normal Clothed Person Protection (Covered Chest, Torso, and Legs)
        if (chestRatio < 0.18f && midTorsoRatio < 0.16f && lowerGluteThighRatio < 0.18f) {
            return Pair(
                ClassificationResult(false, emptyList(), "Protected: Fully Clothed Person", 0.1f),
                skinMap
            )
        }

        // Cleavage contrast calculation
        val leftLumAvg = cLeftLum / maxOf(1, cLeftTotal)
        val centerLumAvg = cCenterLum / maxOf(1, cCenterTotal)
        val rightLumAvg = cRightLum / maxOf(1, cRightTotal)
        val leftChestSkinRatio = cLeftSkin.toFloat() / maxOf(1, cLeftTotal)
        val centerCleavageSkinRatio = cCenterSkin.toFloat() / maxOf(1, cCenterTotal)
        val rightChestSkinRatio = cRightSkin.toFloat() / maxOf(1, cRightTotal)

        val chestCleavageContrast = (centerLumAvg < ((leftLumAvg + rightLumAvg) / 2f) - 8f) ||
            (centerCleavageSkinRatio < 0.25f && leftChestSkinRatio >= 0.28f && rightChestSkinRatio >= 0.28f)

        // Temporal Frame Motion Delta (Rule D)
        var frameMotionDelta = 0f
        if (previousSkinGrid != null && previousSkinGrid.size == skinMap.size) {
            var diffCount = 0
            var totalTested = 0
            for (y in chestMinY..lowMaxY) {
                val row = y * width
                for (x in cenMinX..cenMaxX) {
                    totalTested++
                    if (skinMap[row + x] != previousSkinGrid[row + x]) {
                        diffCount++
                    }
                }
            }
            frameMotionDelta = diffCount.toFloat() / maxOf(1, totalTested)
        }

        // RULE A: Short or Revealing Clothing (Mini dresses, skirts, slits)
        val ruleAThreshold = if (isStrict) 0.22f else 0.25f
        val ruleADetected = (lowerGluteThighRatio >= ruleAThreshold) &&
            ((midTorsoRatio in 0.05f..0.65f) || chestRatio >= 0.18f)

        // RULE B: Chest-Focused Presentation (Cleavage, low-cut tops, plunging neckline)
        val chestThreshold = if (isStrict) 0.22f else 0.26f
        val ruleBDetected = (chestRatio >= chestThreshold) &&
            ((midTorsoRatio < chestRatio * 0.85f) || chestCleavageContrast)

        // RULE C: Rear-View or Buttocks-Focused Presentation (Backless dresses, glute emphasis)
        val ruleCDetected = (chestRatio + midTorsoRatio >= 0.44f) ||
            (lowerGluteThighRatio >= 0.28f && (midTorsoRatio in 0.05f..0.65f))

        // RULE D: Suggestive Dancing and Movements (Temporal frame delta + exposure)
        val ruleDDetected = ((chestRatio >= 0.20f || lowerGluteThighRatio >= 0.20f) && frameMotionDelta >= 0.10f)

        val matchedRules = mutableListOf<String>()
        if (ruleADetected && ruleAEnabled) {
            matchedRules.add("Rule A: Short/Revealing Clothing")
        }
        if (ruleBDetected && ruleBEnabled) {
            matchedRules.add("Rule B: Chest/Cleavage Framing")
        }
        if (ruleCDetected && ruleCEnabled) {
            matchedRules.add("Rule C: Rear-View/Buttocks Emphasis")
        }
        if (ruleDDetected && ruleDEnabled) {
            matchedRules.add("Rule D: Suggestive Movement")
        }

        val isMatch = matchedRules.isNotEmpty()
        val primaryReason = if (isMatch) matchedRules.joinToString(" + ") else "Within normal content thresholds"
        val confidence = when {
            matchedRules.size >= 2 -> 0.95f
            isMatch -> 0.85f
            else -> 0.20f
        }

        return Pair(
            ClassificationResult(
                isMatch = isMatch,
                matchedRules = matchedRules,
                primaryReason = primaryReason,
                confidence = confidence,
                ruleADetected = ruleADetected,
                ruleBDetected = ruleBDetected,
                ruleCDetected = ruleCDetected,
                ruleDDetected = ruleDDetected
            ),
            skinMap
        )
    }

    /**
     * Rule A (Phase 6): Evaluates search query for explicit adult content.
     * Safely distinguishes explicit queries from medical, scientific, and educational searches.
     */
    fun evaluateSearchQuery(query: String, prefs: GuardPreferences): Pair<Boolean, String?> {
        return evaluateSearchQuery(query, prefs.isAdultSearchBlockEnabled)
    }

    fun evaluateSearchQuery(query: String, isAdultSearchEnabled: Boolean = true): Pair<Boolean, String?> {
        if (!isAdultSearchEnabled) return Pair(false, null)
        val lower = query.trim().lowercase(Locale.ROOT)
        if (lower.isEmpty()) return Pair(false, null)

        // Medical & Educational Whitelist Safeguard
        for (safeTerm in MEDICAL_EDUCATIONAL_WHITELIST) {
            if (lower.contains(safeTerm)) {
                return Pair(false, null)
            }
        }

        // Adult Term & Domain Matching
        for (term in EXPLICIT_SEARCH_TERMS) {
            if (lower.contains(term)) {
                return Pair(true, "Explicit Search Query: '$term'")
            }
        }

        return Pair(false, null)
    }

    /**
     * Rule B (Phase 6): Evaluates opened browser URL and page text for adult content.
     */
    fun evaluateBrowserPage(url: String?, pageText: String, prefs: GuardPreferences): Pair<Boolean, String?> {
        return evaluateBrowserPage(url, pageText, prefs.isAdultWebsitesBlockEnabled)
    }

    fun evaluateBrowserPage(url: String?, pageText: String, isAdultWebsitesEnabled: Boolean = true): Pair<Boolean, String?> {
        if (!isAdultWebsitesEnabled) return Pair(false, null)
        val lowerUrl = url?.lowercase(Locale.ROOT) ?: ""
        val lowerText = pageText.lowercase(Locale.ROOT)

        // Check Known Adult Domains
        for (domain in ADULT_DOMAINS) {
            if (lowerUrl.contains(domain) || lowerText.contains(domain)) {
                return Pair(true, "Adult Website Domain: '$domain'")
            }
        }

        // Whitelist check
        for (safeTerm in MEDICAL_EDUCATIONAL_WHITELIST) {
            if (lowerText.contains(safeTerm) || lowerUrl.contains(safeTerm.replace(" ", ""))) {
                return Pair(false, null)
            }
        }

        // Keyword density check
        for (term in EXPLICIT_SEARCH_TERMS) {
            if (lowerUrl.contains(term.replace(" ", "")) || lowerText.contains(term)) {
                return Pair(true, "Adult Page Content: '$term'")
            }
        }

        return Pair(false, null)
    }

    /**
     * Fast UI Caption / Hashtag / Audio Metadata Trigger
     */
    fun hasSuggestiveReelMetadata(fullText: String, prefs: GuardPreferences? = null): Pair<Boolean, String?> {
        val lower = fullText.lowercase(Locale.ROOT)
        for (indicator in SUGGESTIVE_REEL_INDICATORS) {
            if (lower.contains(indicator)) {
                return Pair(true, indicator)
            }
        }
        return Pair(false, null)
    }
}
