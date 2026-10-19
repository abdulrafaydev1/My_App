package com.instaguard.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VisualFilterDecisionEngineTest {

    private lateinit var engine: VisualFilterDecisionEngine
    private val defaultConfig = FilterConfig()

    @Before
    fun setUp() {
        engine = VisualFilterDecisionEngine(defaultConfig)
    }

    @Test
    fun landscapeWithNoPerson_returnsAllow() {
        val frame = FrameFeatures(
            contentItemId = "reel_landscape_1",
            frameTimestampMs = 1000L,
            isSupportedVideoFeed = true,
            outerBorderSkinRatio = 0.35f, // warm sunset/desert tones on edges
            qualifyingPersonDetected = false,
            personConfidence = 0.10f
        )
        val out = engine.evaluateFrame(frame, defaultConfig, nowMs = 1050L)
        assertEquals(DecisionState.ALLOW, out.state)
        assertEquals(DiagnosticStatus.NO_QUALIFYING_PERSON_DETECTED, out.diagnosticStatus)
        assertTrue(out.activeBoxes.isEmpty())
    }

    @Test
    fun carOrVehicleWithNoPerson_returnsAllow() {
        val frame = FrameFeatures(
            contentItemId = "reel_car_2",
            frameTimestampMs = 2000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = false,
            personConfidence = 0.0f
        )
        val out = engine.evaluateFrame(frame, defaultConfig, nowMs = 2020L)
        assertEquals(DecisionState.ALLOW, out.state)
        assertEquals(DiagnosticStatus.NO_QUALIFYING_PERSON_DETECTED, out.diagnosticStatus)
    }

    @Test
    fun animalsOrPetsWithNoPerson_returnsAllow() {
        val frame = FrameFeatures(
            contentItemId = "reel_pet_3",
            frameTimestampMs = 3000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = false,
            personConfidence = 0.25f
        )
        val out = engine.evaluateFrame(frame, defaultConfig, nowMs = 3050L)
        assertEquals(DecisionState.ALLOW, out.state)
        assertEquals(DiagnosticStatus.NO_QUALIFYING_PERSON_DETECTED, out.diagnosticStatus)
    }

    @Test
    fun gamingScreenRecordingWithNoPerson_returnsAllow() {
        val frame = FrameFeatures(
            contentItemId = "reel_gaming_4",
            frameTimestampMs = 4000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = false,
            personConfidence = 0.15f,
            singleFrameMovementScore = 0.92f
        )
        val out = engine.evaluateFrame(frame, defaultConfig, nowMs = 4050L)
        assertEquals(DecisionState.ALLOW, out.state)
        assertEquals(DiagnosticStatus.NO_QUALIFYING_PERSON_DETECTED, out.diagnosticStatus)
    }

    @Test
    fun malePresentingPersonOnly_returnsAllow() {
        val frame1 = FrameFeatures(
            contentItemId = "reel_male_speaker_5",
            frameTimestampMs = 5000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.91f,
            isMalePresentingOnly = true,
            chestExposureConfidence = 0.85f
        )
        val frame2 = frame1.copy(frameTimestampMs = 5900L)
        val out1 = engine.evaluateFrame(frame1, defaultConfig, nowMs = 5020L)
        val out2 = engine.evaluateFrame(frame2, defaultConfig, nowMs = 5920L)

        assertEquals(DecisionState.ALLOW, out1.state)
        assertEquals(DecisionState.ALLOW, out2.state)
        assertEquals(DiagnosticStatus.NO_QUALIFYING_PERSON_DETECTED, out2.diagnosticStatus)
    }

    @Test
    fun noQualifyingPerson_withStalePositiveClothingScore_returnsAllow() {
        // Even if clothing fields accidentally carry high numbers, Stage 3 person gate must block skip
        val frame1 = FrameFeatures(
            contentItemId = "reel_empty_room_6",
            frameTimestampMs = 6000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = false,
            personConfidence = 0.20f,
            shoulderUncoveredConfidence = 0.95f,
            hemlineAboveKneeConfidence = 0.95f,
            chestExposureConfidence = 0.95f
        )
        val frame2 = frame1.copy(frameTimestampMs = 6900L)
        val out1 = engine.evaluateFrame(frame1, defaultConfig, nowMs = 6010L)
        val out2 = engine.evaluateFrame(frame2, defaultConfig, nowMs = 6910L)

        assertEquals(DecisionState.ALLOW, out1.state)
        assertEquals(DecisionState.ALLOW, out2.state)
        assertEquals(DiagnosticStatus.NO_QUALIFYING_PERSON_DETECTED, out2.diagnosticStatus)
    }

    @Test
    fun detectorFailure_returnsAnalysisErrorAndDoesNotSkip() {
        val failedFrame = FrameFeatures(
            contentItemId = "reel_error_7",
            frameTimestampMs = 7000L,
            isSupportedVideoFeed = true,
            analysisFailed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.95f,
            chestExposureConfidence = 0.95f
        )
        val out = engine.evaluateFrame(failedFrame, defaultConfig, nowMs = 7010L)
        assertEquals(DecisionState.ANALYSIS_ERROR, out.state)
        assertEquals(DiagnosticStatus.ANALYSIS_FAILED, out.diagnosticStatus)
        assertFalse(engine.skipIssuedForCurrentItem)
    }

    @Test
    fun personDetected_clothingAnalysisPending_returnsPendingAndDoesNotSkip() {
        val pendingFrame = FrameFeatures(
            contentItemId = "reel_pending_8",
            frameTimestampMs = 8000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.92f,
            clothingAnalysisComplete = false,
            chestExposureConfidence = 0.90f
        )
        val out = engine.evaluateFrame(pendingFrame, defaultConfig, nowMs = 8010L)
        assertEquals(DecisionState.PENDING_ANALYSIS, out.state)
        assertEquals(DiagnosticStatus.ANALYZING_CLOTHING, out.diagnosticStatus)
    }

    @Test
    fun personWearingOrdinaryModestClothing_returnsAllow() {
        val modestFrame1 = FrameFeatures(
            contentItemId = "reel_modest_9",
            frameTimestampMs = 9000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.94f,
            shoulderUncoveredConfidence = 0.25f,
            hemlineAboveKneeConfidence = 0.20f,
            chestExposureConfidence = 0.30f,
            rearButtocksExposureConfidence = 0.15f,
            singleFrameMovementScore = 0.20f
        )
        val modestFrame2 = modestFrame1.copy(frameTimestampMs = 9900L)
        val out1 = engine.evaluateFrame(modestFrame1, defaultConfig, nowMs = 9010L)
        val out2 = engine.evaluateFrame(modestFrame2, defaultConfig, nowMs = 9910L)

        assertEquals(DecisionState.ALLOW, out1.state)
        assertEquals(DecisionState.ALLOW, out2.state)
        assertEquals(DiagnosticStatus.NO_RULE_MATCHED, out2.diagnosticStatus)
    }

    @Test
    fun shortDressMissingCompositeCondition_returnsAllow() {
        // Shoulder uncovered is high, but hemline is long (below knee) -> Rule A must NOT match
        val frame1 = FrameFeatures(
            contentItemId = "reel_long_evening_gown_10",
            frameTimestampMs = 10000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.93f,
            shoulderUncoveredConfidence = 0.88f,
            hemlineAboveKneeConfidence = 0.35f,
            chestExposureConfidence = 0.45f,
            rearButtocksExposureConfidence = 0.20f
        )
        val frame2 = frame1.copy(frameTimestampMs = 10900L)
        val out1 = engine.evaluateFrame(frame1, defaultConfig, nowMs = 10020L)
        val out2 = engine.evaluateFrame(frame2, defaultConfig, nowMs = 10920L)

        assertEquals(DecisionState.ALLOW, out1.state)
        assertEquals(DecisionState.ALLOW, out2.state)
        assertEquals(DiagnosticStatus.NO_RULE_MATCHED, out2.diagnosticStatus)
    }

    @Test
    fun shortDressMeetingBothShoulderAndHemlineConditions_returnsSkipAfterConfirmation() {
        val frame1 = FrameFeatures(
            contentItemId = "reel_short_dress_11",
            frameTimestampMs = 11000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.95f,
            shoulderUncoveredConfidence = 0.86f,
            hemlineAboveKneeConfidence = 0.84f
        )
        val frame2 = frame1.copy(frameTimestampMs = 11900L)

        val out1 = engine.evaluateFrame(frame1, defaultConfig, nowMs = 11020L)
        assertEquals(DecisionState.PENDING_ANALYSIS, out1.state)

        val out2 = engine.evaluateFrame(frame2, defaultConfig, nowMs = 11920L)
        assertEquals(DecisionState.SKIP, out2.state)
        assertEquals(DiagnosticStatus.RULE_MATCHED_SKIP, out2.diagnosticStatus)
        assertTrue(out2.matchedRules.contains(FilterRuleCategory.RULE_A_SHORT_REVEALING_DRESS))
    }

    @Test
    fun revealingChestRuleMatched_returnsSkip() {
        val frame1 = FrameFeatures(
            contentItemId = "reel_chest_12",
            frameTimestampMs = 12000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.96f,
            chestExposureConfidence = 0.92f
        )
        val frame2 = frame1.copy(frameTimestampMs = 12900L)

        val out1 = engine.evaluateFrame(frame1, defaultConfig, nowMs = 12020L)
        assertEquals(DecisionState.PENDING_ANALYSIS, out1.state)

        val out2 = engine.evaluateFrame(frame2, defaultConfig, nowMs = 12920L)
        assertEquals(DecisionState.SKIP, out2.state)
        assertTrue(out2.matchedRules.contains(FilterRuleCategory.RULE_B_REVEALING_CHEST))
    }

    @Test
    fun rearButtocksRuleMatched_returnsSkip() {
        val frame1 = FrameFeatures(
            contentItemId = "reel_rear_13",
            frameTimestampMs = 13000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.94f,
            rearButtocksExposureConfidence = 0.91f
        )
        val frame2 = frame1.copy(frameTimestampMs = 13900L)

        val out1 = engine.evaluateFrame(frame1, defaultConfig, nowMs = 13020L)
        assertEquals(DecisionState.PENDING_ANALYSIS, out1.state)

        val out2 = engine.evaluateFrame(frame2, defaultConfig, nowMs = 13920L)
        assertEquals(DecisionState.SKIP, out2.state)
        assertTrue(out2.matchedRules.contains(FilterRuleCategory.RULE_C_REAR_BUTTOCKS_PRESENTATION))
    }

    @Test
    fun suggestiveMovementSingleFrame_doesNotSkip_multiFrameConfirmsSkip() {
        val baseMoveFrame = FrameFeatures(
            contentItemId = "reel_dance_14",
            frameTimestampMs = 14000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.93f,
            chestExposureConfidence = 0.65f, // supporting body context (< static 0.78 threshold, >= 0.60 movement context)
            singleFrameMovementScore = 0.85f
        )

        // Frame 1: must NOT skip on single frame
        val out1 = engine.evaluateFrame(baseMoveFrame, defaultConfig, nowMs = 14010L)
        assertEquals(DecisionState.PENDING_ANALYSIS, out1.state)

        // Frame 2: still accumulating multi-frame movement (requiredMovementConfirmFrames = 3)
        val out2 = engine.evaluateFrame(baseMoveFrame.copy(frameTimestampMs = 14850L), defaultConfig, nowMs = 14860L)
        assertEquals(DecisionState.PENDING_ANALYSIS, out2.state)

        // Frame 3: confirms multi-frame suggestive movement -> SKIP
        val out3 = engine.evaluateFrame(baseMoveFrame.copy(frameTimestampMs = 15700L), defaultConfig, nowMs = 15710L)
        assertEquals(DecisionState.SKIP, out3.state)
        assertTrue(out3.matchedRules.contains(FilterRuleCategory.RULE_D_SUGGESTIVE_MOVEMENT))
    }

    @Test
    fun singleUncertainFrame_doesNotTriggerSkip() {
        val uncertainFrame = FrameFeatures(
            contentItemId = "reel_transition_15",
            frameTimestampMs = 15000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.90f,
            chestExposureConfidence = 0.88f
        )
        val nextNormalFrame = FrameFeatures(
            contentItemId = "reel_transition_15",
            frameTimestampMs = 15900L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.90f,
            chestExposureConfidence = 0.25f
        )

        val out1 = engine.evaluateFrame(uncertainFrame, defaultConfig, nowMs = 15010L)
        assertEquals(DecisionState.PENDING_ANALYSIS, out1.state)

        val out2 = engine.evaluateFrame(nextNormalFrame, defaultConfig, nowMs = 15910L)
        assertEquals(DecisionState.ALLOW, out2.state)
        assertEquals(0, engine.staticMatchStreak)
    }

    @Test
    fun previousVideoMatchedSkip_nextVideoNormal_returnsAllow() {
        // Video A triggers a skip
        val videoAFrame1 = FrameFeatures(
            contentItemId = "video_A",
            frameTimestampMs = 20000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.95f,
            chestExposureConfidence = 0.92f
        )
        val videoAFrame2 = videoAFrame1.copy(frameTimestampMs = 20900L)
        engine.evaluateFrame(videoAFrame1, defaultConfig, nowMs = 20010L)
        val outA2 = engine.evaluateFrame(videoAFrame2, defaultConfig, nowMs = 20910L)
        assertEquals(DecisionState.SKIP, outA2.state)

        // User scrolls to Video B (normal video) -> state must reset cleanly
        val videoBFrame1 = FrameFeatures(
            contentItemId = "video_B",
            frameTimestampMs = 21800L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.91f,
            chestExposureConfidence = 0.20f
        )
        val outB1 = engine.evaluateFrame(videoBFrame1, defaultConfig, nowMs = 21810L)
        assertEquals(DecisionState.ALLOW, outB1.state)
        assertEquals(DiagnosticStatus.NO_RULE_MATCHED, outB1.diagnosticStatus)
        assertFalse(engine.skipIssuedForCurrentItem)
    }

    @Test
    fun sameVideoRepeatedCallbacks_issuesSkipOnlyOnce() {
        val frame1 = FrameFeatures(
            contentItemId = "video_repeat_17",
            frameTimestampMs = 30000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.95f,
            chestExposureConfidence = 0.93f
        )
        val frame2 = frame1.copy(frameTimestampMs = 30900L)
        val frame3 = frame1.copy(frameTimestampMs = 31800L)

        engine.evaluateFrame(frame1, defaultConfig, nowMs = 30010L)
        val secondOut = engine.evaluateFrame(frame2, defaultConfig, nowMs = 30910L)
        val thirdOut = engine.evaluateFrame(frame3, defaultConfig, nowMs = 31810L)

        assertEquals(DecisionState.SKIP, secondOut.state)
        // Third callback for the exact same contentItemId must NOT issue a second SKIP
        assertEquals(DecisionState.ALLOW, thirdOut.state)
        assertEquals(DiagnosticStatus.NAVIGATION_VERIFIED, thirdOut.diagnosticStatus)
    }

    @Test
    fun monitoringDisabled_returnsAllowImmediately() {
        val disabledConfig = defaultConfig.copy(monitoringEnabled = false)
        val frame = FrameFeatures(
            contentItemId = "video_disabled_18",
            frameTimestampMs = 40000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.98f,
            chestExposureConfidence = 0.98f
        )
        val out = engine.evaluateFrame(frame, disabledConfig, nowMs = 40010L)
        assertEquals(DecisionState.ALLOW, out.state)
        assertEquals(DiagnosticStatus.WAITING_FOR_FRAMES, out.diagnosticStatus)
    }
}
