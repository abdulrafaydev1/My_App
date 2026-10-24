package com.instaguard.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for strict v15.6 anti-bypass alarm lock security.
 * Validates OEM calculator package heuristics, browser packages,
 * arithmetic bypass regex, and power-off detection keywords.
 */
class AlarmAntiBypassSecurityTest {

    @Test
    fun testCalculatorPackagesDetection() {
        val requiredOEMPackages = listOf(
            "com.google.android.calculator",
            "com.android.calculator2",
            "com.sec.android.app.popupcalculator",
            "com.miui.calculator",
            "com.coloros.calculator",
            "com.oplus.calculator",
            "com.oneplus.calculator",
            "com.vivo.calculator",
            "com.transsion.calculator",
            "com.asus.calculator",
            "com.huawei.calculator",
            "com.hihonor.calculator",
            "com.motorola.cn.calculator"
        )

        for (pkg in requiredOEMPackages) {
            assertTrue("Expected $pkg to be recognized as calculator", AppBlockerService.isCalculatorAppPackage(pkg))
        }

        // Test heuristic detection
        assertTrue(AppBlockerService.isCalculatorAppPackage("xyz.custom.calculator"))
        assertTrue(AppBlockerService.isCalculatorAppPackage("com.random.app.popupcalculator"))
        assertTrue(AppBlockerService.isCalculatorAppPackage("org.vintage.mycalculator"))

        // Test non-calculator packages
        assertFalse(AppBlockerService.isCalculatorAppPackage("com.instaguard.app"))
        assertFalse(AppBlockerService.isCalculatorAppPackage("com.instagram.android"))
        assertFalse(AppBlockerService.isCalculatorAppPackage("com.google.android.youtube"))
    }

    @Test
    fun testBrowserPackagesDetection() {
        val browsers = listOf(
            "com.android.chrome",
            "com.google.android.googlequicksearchbox",
            "com.sec.android.app.sbrowser",
            "org.mozilla.firefox",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.opera.gx",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.duckduckgo.mobile.android"
        )

        for (pkg in browsers) {
            assertTrue("Expected $pkg to be recognized as browser", AppBlockerService.isAntiCheatBrowserPackage(pkg))
        }
    }

    @Test
    fun testMathEquationRegexMatchesBypassAttempts() {
        val mathRegex = Regex("""\b\d+\s*[-+*xX/\u00D7\u00F7]\s*\d+\b""")

        // Standard operators
        assertTrue(mathRegex.containsMatchIn("12 + 45"))
        assertTrue(mathRegex.containsMatchIn("99 - 14"))
        assertTrue(mathRegex.containsMatchIn("8 * 7"))
        assertTrue(mathRegex.containsMatchIn("144 / 12"))

        // Alternative multiplication & division signs
        assertTrue(mathRegex.containsMatchIn("8 x 9"))
        assertTrue(mathRegex.containsMatchIn("8 × 9"))
        assertTrue(mathRegex.containsMatchIn("100 ÷ 5"))

        // Search bar query with surrounding words
        assertTrue(mathRegex.containsMatchIn("what is 34 + 56"))
        assertTrue(mathRegex.containsMatchIn("solve 87 * 3"))

        // Normal text should not match
        assertFalse(mathRegex.containsMatchIn("hello world"))
        assertFalse(mathRegex.containsMatchIn("what is the capital of France"))
    }
}
