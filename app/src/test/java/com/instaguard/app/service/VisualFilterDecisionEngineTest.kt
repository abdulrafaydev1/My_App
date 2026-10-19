package com.instaguard.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // =========================================================================
    // A. Normal-Video Protection Tests (1–12)
    // =========================================================================

    @Test
    fun landscapeWithNoPerson_returnsAllow() {
        val frame = FrameFeatures(
            contentItemId = "reel_landscape_1",
            frameTimestampMs = 1000L,
            isSupportedVideoFeed = true,
            outerBorderSkinRatio = 0.35f,
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
    fun foodOrCookingWithNoPerson_returnsAllow() {
        val frame = FrameFeatures(
            contentItemId = "reel_cooking_4",
            frameTimestampMs = 3500L,
            isSupportedVideoFeed = true,
            outerBorderSkinRatio = 0.42f, // warm bread/curry/wood kitchen tones
            qualifyingPersonDetected = false,
            personConfidence = 0.12f
        )
        val out = engine.evaluateFrame(frame, defaultConfig, nowMs = 3540L)
        assertEquals(DecisionState.ALLOW, out.state)
        assertEquals(DiagnosticStatus.NO_QUALIFYING_PERSON_DETECTED, out.diagnosticStatus)
        assertTrue(out.activeBoxes.isEmpty())
    }

    @Test
    fun gamingScreenRecordingWithNoPerson_returnsAllow() {
        val frame = FrameFeatures(
            contentItemId = "reel_gaming_5",
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
            contentItemId = "reel_male_speaker_6",
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
        val frame1 = FrameFeatures(
            contentItemId = "reel_empty_room_7",
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
        assertFalse(out.state.isSkipAuthorized)
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
    fun personDetectedOnly_withoutClothingModel_returnsAllowAndNeverSkips() {
        val faceBox = DetectionBox(
            label = "FACE",
            confidence = 0.88f,
            normLeft = 0.35f,
            normTop = 0.18f,
            normRight = 0.65f,
            normBottom = 0.42f,
            timestampMs = 9500L
        )
        val frame1 = FrameFeatures(
            contentItemId = "reel_person_only_10",
            frameTimestampMs = 9500L,
            isSupportedVideoFeed = true,
            modelInitialized = true,
            personDetectorAvailable = true,
            clothingClassifierAvailable = false, // Person/face detector only; no clothing model
            qualifyingPersonDetected = true,
            personConfidence = 0.88f,
            chestExposureConfidence = 0.99f, // Even if a caller accidentally sets this, unavailable model blocks skip
            detectedBoxes = listOf(faceBox)
        )
        val frame2 = frame1.copy(frameTimestampMs = 10400L)

        val out1 = engine.evaluateFrame(frame1, defaultConfig, nowMs = 9520L)
        val out2 = engine.evaluateFrame(frame2, defaultConfig, nowMs = 10420L)

        assertEquals(DecisionState.ALLOW, out1.state)
        assertEquals(DecisionState.ALLOW, out2.state)
        assertEquals(DiagnosticStatus.PERSON_DETECTED, out2.diagnosticStatus)
        assertFalse(out2.state.isSkipAuthorized)
        assertEquals(1, out2.activeBoxes.size)
    }

    @Test
    fun missingOrUninitializedModel_returnsErrorAndNeverSkips() {
        val uninitFrame = FrameFeatures(
            contentItemId = "reel_uninit_11",
            frameTimestampMs = 10500L,
            isSupportedVideoFeed = true,
            modelInitialized = false,
            personDetectorAvailable = false,
            clothingClassifierAvailable = false,
            qualifyingPersonDetected = true,
            personConfidence = 0.95f,
            chestExposureConfidence = 0.95f
        )
        val out = engine.evaluateFrame(uninitFrame, defaultConfig, nowMs = 10510L)
        assertEquals(DecisionState.ANALYSIS_ERROR, out.state)
        assertEquals(DiagnosticStatus.ANALYSIS_FAILED, out.diagnosticStatus)
        assertFalse(out.state.isSkipAuthorized)
    }

    @Test
    fun detectorException_returnsAnalysisErrorAndDoesNotSkip() {
        val failedFrame = FrameFeatures(
            contentItemId = "reel_error_12",
            frameTimestampMs = 11000L,
            isSupportedVideoFeed = true,
            analysisFailed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.95f,
            chestExposureConfidence = 0.95f
        )
        val out = engine.evaluateFrame(failedFrame, defaultConfig, nowMs = 11010L)
        assertEquals(DecisionState.ANALYSIS_ERROR, out.state)
        assertEquals(DiagnosticStatus.ANALYSIS_FAILED, out.diagnosticStatus)
        assertFalse(engine.skipIssuedForCurrentItem)
    }

    // =========================================================================
    // B. Temporal and Stale-Result Protection Tests (13–22)
    // =========================================================================

    @Test
    fun singleFramePositive_doesNotSkip() {
        val singlePositive = FrameFeatures(
            contentItemId = "reel_single_pos_13",
            frameTimestampMs = 13000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.94f,
            chestExposureConfidence = 0.91f
        )
        val out = engine.evaluateFrame(singlePositive, defaultConfig, nowMs = 13020L)
        assertEquals(DecisionState.PENDING_ANALYSIS, out.state)
        assertFalse(out.state.isSkipAuthorized)
        assertEquals(1, engine.ruleBStreak)
    }

    @Test
    fun alternatingPositiveAndNegativeFrames_doesNotSkip() {
        val posFrame = FrameFeatures(
            contentItemId = "reel_alt_14",
            frameTimestampMs = 14000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.92f,
            chestExposureConfidence = 0.89f
        )
        val negFrame = posFrame.copy(
            frameTimestampMs = 14800L,
            chestExposureConfidence = 0.20f
        )
        val posFrame2 = posFrame.copy(frameTimestampMs = 15600L)

        val out1 = engine.evaluateFrame(posFrame, defaultConfig, nowMs = 14010L)
        assertEquals(DecisionState.PENDING_ANALYSIS, out1.state)
        assertEquals(1, engine.ruleBStreak)

        val out2 = engine.evaluateFrame(negFrame, defaultConfig, nowMs = 14810L)
        assertEquals(DecisionState.ALLOW, out2.state)
        assertEquals(0, engine.ruleBStreak)

        val out3 = engine.evaluateFrame(posFrame2, defaultConfig, nowMs = 15610L)
        assertEquals(DecisionState.PENDING_ANALYSIS, out3.state)
        assertEquals(1, engine.ruleBStreak)
        assertFalse(out3.state.isSkipAuthorized)
    }

    @Test
    fun ruleAMatchThenRuleCMatchWithoutSameRuleStreak_doesNotSkip() {
        val ruleAFrame = FrameFeatures(
            contentItemId = "reel_mixed_rules_15",
            frameTimestampMs = 15000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.93f,
            shoulderUncoveredConfidence = 0.88f,
            hemlineAboveKneeConfidence = 0.86f,
            rearButtocksExposureConfidence = 0.10f
        )
        val ruleCFrame = FrameFeatures(
            contentItemId = "reel_mixed_rules_15",
            frameTimestampMs = 15900L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.93f,
            shoulderUncoveredConfidence = 0.20f,
            hemlineAboveKneeConfidence = 0.20f,
            rearButtocksExposureConfidence = 0.90f
        )

        val out1 = engine.evaluateFrame(ruleAFrame, defaultConfig, nowMs = 15020L)
        val out2 = engine.evaluateFrame(ruleCFrame, defaultConfig, nowMs = 15920L)

        assertEquals(DecisionState.PENDING_ANALYSIS, out1.state)
        assertEquals(DecisionState.PENDING_ANALYSIS, out2.state)
        assertFalse(out2.state.isSkipAuthorized)
        assertEquals(0, engine.ruleAStreak)
        assertEquals(1, engine.ruleCStreak)
    }

    @Test
    fun staleFrameOlderThanMaxAge_isDroppedAndDoesNotSkip() {
        val staleFrame = FrameFeatures(
            contentItemId = "reel_stale_16",
            frameTimestampMs = 10000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.96f,
            chestExposureConfidence = 0.95f
        )
        // nowMs is 11500L -> age is 1500ms > maxFrameAgeMs (1200ms)
        val out = engine.evaluateFrame(staleFrame, defaultConfig, nowMs = 11500L)
        assertEquals(DecisionState.ALLOW, out.state)
        assertEquals(DiagnosticStatus.WAITING_FOR_FRAMES, out.diagnosticStatus)
        assertFalse(out.state.isSkipAuthorized)
    }

    @Test
    fun previousVideoMatchedSkip_nextVideoNormal_returnsAllow() {
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
        assertEquals(DecisionState.SKIP_CANDIDATE, outA2.state)

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
    fun lateAsyncResultFromPreviousContentItem_isIgnored() {
        // User scrolls to video_new first
        engine.resetForNewItem("video_new")

        // Late async frame arrives from video_old
        val lateOldFrame = FrameFeatures(
            contentItemId = "video_old",
            frameTimestampMs = 25000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.96f,
            chestExposureConfidence = 0.96f
        )
        val out = engine.evaluateFrame(lateOldFrame, defaultConfig, nowMs = 25050L)
        assertEquals(DecisionState.ALLOW, out.state)
        assertEquals(DiagnosticStatus.WAITING_FOR_FRAMES, out.diagnosticStatus)
        assertEquals("video_new", engine.currentItemId)
    }

    @Test
    fun sameVideoRepeatedCallbacks_issuesSkipOnlyOnce() {
        val frame1 = FrameFeatures(
            contentItemId = "video_repeat_19",
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

        assertEquals(DecisionState.SKIP_CANDIDATE, secondOut.state)
        assertEquals(DecisionState.ALLOW, thirdOut.state)
        assertEquals(DiagnosticStatus.NAVIGATION_VERIFIED, thirdOut.diagnosticStatus)
    }

    @Test
    fun skipNavigationFailsOnce_allowsOneControlledRetryOnly() {
        val itemId = "video_retry_20"
        val frame1 = FrameFeatures(
            contentItemId = itemId,
            frameTimestampMs = 35000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.95f,
            chestExposureConfidence = 0.94f
        )
        val frame2 = frame1.copy(frameTimestampMs = 35900L)

        engine.evaluateFrame(frame1, defaultConfig, nowMs = 35010L)
        val skipDecision = engine.evaluateFrame(frame2, defaultConfig, nowMs = 35910L)
        assertEquals(DecisionState.SKIP_CANDIDATE, skipDecision.state)

        // First skip execution
        assertTrue(engine.markSkipExecuted(itemId, nowMs = 35920L))
        assertEquals(SkipActionState.SKIP_ACTION_EXECUTED, engine.skipActionState)

        // First navigation verification fails -> bounded retry becomes available
        val afterFirstFail = engine.reportNavigationResult(itemId, navigationSucceeded = false)
        assertEquals(SkipActionState.BOUNDED_RETRY_AVAILABLE, afterFirstFail)

        // Execute the 1 allowed retry
        assertTrue(engine.markSkipExecuted(itemId, nowMs = 36600L))
        assertEquals(1, engine.retryCountForCurrentItem)

        // Second navigation verification fails -> retries are now exhausted
        val afterSecondFail = engine.reportNavigationResult(itemId, navigationSucceeded = false)
        assertEquals(SkipActionState.RETRY_EXHAUSTED, afterSecondFail)
        assertFalse(engine.markSkipExecuted(itemId, nowMs = 37300L))
    }

    @Test
    fun skipCooldownActive_suppressesImmediateSecondSkip() {
        // Video 1 triggers a skip at t = 40900ms
        val v1f1 = FrameFeatures(
            contentItemId = "video_cool_1",
            frameTimestampMs = 40000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.95f,
            chestExposureConfidence = 0.93f
        )
        val v1f2 = v1f1.copy(frameTimestampMs = 40900L)
        engine.evaluateFrame(v1f1, defaultConfig, nowMs = 40010L)
        val outV1 = engine.evaluateFrame(v1f2, defaultConfig, nowMs = 40910L)
        assertEquals(DecisionState.SKIP_CANDIDATE, outV1.state)

        // Video 2 arrives 400ms later (within 2000ms skipCooldownMs)
        val v2f1 = v1f1.copy(contentItemId = "video_cool_2", frameTimestampMs = 41300L)
        val v2f2 = v1f1.copy(contentItemId = "video_cool_2", frameTimestampMs = 41800L)
        engine.evaluateFrame(v2f1, defaultConfig, nowMs = 41310L)
        val outV2DuringCooldown = engine.evaluateFrame(v2f2, defaultConfig, nowMs = 41810L)

        // Must remain PENDING_ANALYSIS while cooldown is active
        assertEquals(DecisionState.PENDING_ANALYSIS, outV2DuringCooldown.state)
        assertFalse(outV2DuringCooldown.state.isSkipAuthorized)

        // Once 2000ms cooldown has elapsed (t = 43000ms), skip is authorized
        val v2f3 = v1f1.copy(contentItemId = "video_cool_2", frameTimestampMs = 43000L)
        val outV2AfterCooldown = engine.evaluateFrame(v2f3, defaultConfig, nowMs = 43010L)
        assertEquals(DecisionState.SKIP_CANDIDATE, outV2AfterCooldown.state)
    }

    @Test
    fun monitoringDisabled_returnsAllowImmediately() {
        val disabledConfig = defaultConfig.copy(monitoringEnabled = false)
        val frame = FrameFeatures(
            contentItemId = "video_disabled_22",
            frameTimestampMs = 50000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.98f,
            chestExposureConfidence = 0.98f
        )
        val out = engine.evaluateFrame(frame, disabledConfig, nowMs = 50010L)
        assertEquals(DecisionState.ALLOW, out.state)
        assertEquals(DiagnosticStatus.WAITING_FOR_FRAMES, out.diagnosticStatus)
    }

    // =========================================================================
    // C. Positive-Category Tests (23–27)
    // =========================================================================

    @Test
    fun shortDressMissingCompositeCondition_returnsAllow() {
        val frame1 = FrameFeatures(
            contentItemId = "reel_long_evening_gown_23",
            frameTimestampMs = 51000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.93f,
            shoulderUncoveredConfidence = 0.88f,
            hemlineAboveKneeConfidence = 0.35f,
            chestExposureConfidence = 0.45f,
            rearButtocksExposureConfidence = 0.20f
        )
        val frame2 = frame1.copy(frameTimestampMs = 51900L)
        val out1 = engine.evaluateFrame(frame1, defaultConfig, nowMs = 51020L)
        val out2 = engine.evaluateFrame(frame2, defaultConfig, nowMs = 51920L)

        assertEquals(DecisionState.ALLOW, out1.state)
        assertEquals(DecisionState.ALLOW, out2.state)
        assertEquals(DiagnosticStatus.NO_RULE_MATCHED, out2.diagnosticStatus)
    }

    @Test
    fun shortDressMeetingBothShoulderAndHemlineConditions_returnsSkipAfterConfirmation() {
        val frame1 = FrameFeatures(
            contentItemId = "reel_short_dress_24",
            frameTimestampMs = 52000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.95f,
            shoulderUncoveredConfidence = 0.86f,
            hemlineAboveKneeConfidence = 0.84f
        )
        val frame2 = frame1.copy(frameTimestampMs = 52900L)

        val out1 = engine.evaluateFrame(frame1, defaultConfig, nowMs = 52020L)
        assertEquals(DecisionState.PENDING_ANALYSIS, out1.state)

        val out2 = engine.evaluateFrame(frame2, defaultConfig, nowMs = 52920L)
        assertEquals(DecisionState.SKIP_CANDIDATE, out2.state)
        assertEquals(DiagnosticStatus.RULE_MATCHED_SKIP, out2.diagnosticStatus)
        assertTrue(out2.matchedRules.contains(FilterRuleCategory.RULE_A_SHORT_REVEALING_DRESS))
    }

    @Test
    fun revealingChestRuleMatched_returnsSkipAfterConfirmation() {
        val frame1 = FrameFeatures(
            contentItemId = "reel_chest_25",
            frameTimestampMs = 54000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.96f,
            chestExposureConfidence = 0.92f
        )
        val frame2 = frame1.copy(frameTimestampMs = 54900L)

        val out1 = engine.evaluateFrame(frame1, defaultConfig, nowMs = 54020L)
        assertEquals(DecisionState.PENDING_ANALYSIS, out1.state)

        val out2 = engine.evaluateFrame(frame2, defaultConfig, nowMs = 54920L)
        assertEquals(DecisionState.SKIP_CANDIDATE, out2.state)
        assertTrue(out2.matchedRules.contains(FilterRuleCategory.RULE_B_REVEALING_CHEST))
    }

    @Test
    fun rearButtocksRuleMatched_returnsSkipAfterConfirmation() {
        val frame1 = FrameFeatures(
            contentItemId = "reel_rear_26",
            frameTimestampMs = 56000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.94f,
            rearButtocksExposureConfidence = 0.91f
        )
        val frame2 = frame1.copy(frameTimestampMs = 56900L)

        val out1 = engine.evaluateFrame(frame1, defaultConfig, nowMs = 56020L)
        assertEquals(DecisionState.PENDING_ANALYSIS, out1.state)

        val out2 = engine.evaluateFrame(frame2, defaultConfig, nowMs = 56920L)
        assertEquals(DecisionState.SKIP_CANDIDATE, out2.state)
        assertTrue(out2.matchedRules.contains(FilterRuleCategory.RULE_C_REAR_BUTTOCKS_PRESENTATION))
    }

    @Test
    fun suggestiveMovementSingleFrame_doesNotSkip_multiFrameConfirmsSkip() {
        val baseMoveFrame = FrameFeatures(
            contentItemId = "reel_dance_27",
            frameTimestampMs = 58000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.93f,
            chestExposureConfidence = 0.65f,
            singleFrameMovementScore = 0.85f
        )

        val out1 = engine.evaluateFrame(baseMoveFrame, defaultConfig, nowMs = 58010L)
        assertEquals(DecisionState.PENDING_ANALYSIS, out1.state)

        val out2 = engine.evaluateFrame(baseMoveFrame.copy(frameTimestampMs = 58850L), defaultConfig, nowMs = 58860L)
        assertEquals(DecisionState.PENDING_ANALYSIS, out2.state)

        val out3 = engine.evaluateFrame(baseMoveFrame.copy(frameTimestampMs = 59700L), defaultConfig, nowMs = 59710L)
        assertEquals(DecisionState.SKIP_CANDIDATE, out3.state)
        assertTrue(out3.matchedRules.contains(FilterRuleCategory.RULE_D_SUGGESTIVE_MOVEMENT))
    }

    // =========================================================================
    // D. Overlay Tests (28–36)
    // =========================================================================

    @Test
    fun overlayDisabled_clearsBoxesAndStopsDrawing() {
        val controller = ScannerOverlayStateController()
        val box = DetectionBox(
            label = "FACE",
            confidence = 0.85f,
            normLeft = 0.2f,
            normTop = 0.2f,
            normRight = 0.6f,
            normBottom = 0.6f,
            timestampMs = 1000L
        )
        controller.updateState(
            boxes = listOf(box),
            status = DiagnosticStatus.PERSON_DETECTED,
            frameTimestampMs = 1000L,
            contentItemId = "item_1",
            nowMs = 1050L
        )
        assertEquals(1, controller.getRenderableBoxes(nowMs = 1100L).size)

        // Disable overlay
        controller.isOverlayEnabled = false
        assertTrue(controller.getRenderableBoxes(nowMs = 1100L).isEmpty())
    }

    @Test
    fun staleOverlayBoxes_expireAutomatically() {
        val controller = ScannerOverlayStateController(maxBoxAgeMs = 1400L)
        val box = DetectionBox(
            label = "FACE",
            confidence = 0.90f,
            normLeft = 0.25f,
            normTop = 0.15f,
            normRight = 0.55f,
            normBottom = 0.45f,
            timestampMs = 2000L
        )
        controller.updateState(
            boxes = listOf(box),
            status = DiagnosticStatus.PERSON_DETECTED,
            frameTimestampMs = 2000L,
            contentItemId = "item_stale",
            nowMs = 2050L
        )
        assertEquals(1, controller.getRenderableBoxes(nowMs = 3000L).size)

        // After 1400ms (nowMs = 3500L), box must expire automatically
        assertTrue(controller.getRenderableBoxes(nowMs = 3500L).isEmpty())
    }

    @Test
    fun contentItemChange_clearsPreviousOverlayBoxes() {
        val controller = ScannerOverlayStateController()
        val box = DetectionBox(
            label = "FACE",
            confidence = 0.87f,
            normLeft = 0.3f,
            normTop = 0.2f,
            normRight = 0.7f,
            normBottom = 0.5f,
            timestampMs = 5000L
        )
        controller.updateState(
            boxes = listOf(box),
            status = DiagnosticStatus.PERSON_DETECTED,
            frameTimestampMs = 5000L,
            contentItemId = "item_A",
            nowMs = 5020L
        )
        assertEquals(1, controller.getRenderableBoxes(nowMs = 5050L).size)

        controller.onContentItemChanged("item_B")
        assertTrue(controller.getRenderableBoxes(nowMs = 5060L).isEmpty())
    }

    @Test
    fun overlayPaintConfiguration_hasZeroInteriorFillAlpha() {
        val controller = ScannerOverlayStateController()
        assertEquals(0, controller.interiorFillAlpha)
    }

    @Test
    fun normalizedCoordinates_mapCorrectlyToViewBoundsWithAspectRatioOffset() {
        val mapped = CoordinateTransform.mapNormalizedBoxToView(
            normLeft = 0.25f,
            normTop = 0.20f,
            normRight = 0.75f,
            normBottom = 0.60f,
            viewWidth = 1080,
            viewHeight = 2400,
            sourceWidth = 1080,
            sourceHeight = 2400,
            windowOffsetX = 0,
            windowOffsetY = 0
        )
        assertNotNull(mapped)
        assertEquals(270f, mapped!!.left, 0.5f)
        assertEquals(480f, mapped.top, 0.5f)
        assertEquals(810f, mapped.right, 0.5f)
        assertEquals(1440f, mapped.bottom, 0.5f)
    }

    @Test
    fun invalidOrOutOfBoundsCoordinates_areRejectedAndNotDrawn() {
        val nanMapped = CoordinateTransform.mapNormalizedBoxToView(
            normLeft = Float.NaN,
            normTop = 0.2f,
            normRight = 0.6f,
            normBottom = 0.6f,
            viewWidth = 1080,
            viewHeight = 2400
        )
        assertNull(nanMapped)

        val invertedMapped = CoordinateTransform.mapNormalizedBoxToView(
            normLeft = 0.8f,
            normTop = 0.7f,
            normRight = 0.2f,
            normBottom = 0.3f,
            viewWidth = 1080,
            viewHeight = 2400
        )
        assertNull(invertedMapped)
    }

    @Test
    fun confidenceNull_rendersLabelWithoutFakePercentage() {
        val boxWithoutConfidence = DetectionBox(
            label = "PERSON",
            confidence = null,
            normLeft = 0.2f,
            normTop = 0.2f,
            normRight = 0.6f,
            normBottom = 0.8f,
            timestampMs = 1000L
        )
        val header = ScannerOverlayStateController.formatBoxHeaderText(boxWithoutConfidence)
        assertEquals("PERSON", header)
        assertFalse(header.contains("%"))
    }

    @Test
    fun confidenceValid_rendersRealPercentageOnly() {
        val boxWithConfidence = DetectionBox(
            label = "FACE",
            confidence = 0.84f,
            normLeft = 0.3f,
            normTop = 0.2f,
            normRight = 0.6f,
            normBottom = 0.5f,
            timestampMs = 1000L
        )
        val header = ScannerOverlayStateController.formatBoxHeaderText(boxWithConfidence)
        assertEquals("FACE 84%", header)
    }

    @Test
    fun trackingLost_clearsStaleSubjectBoxes() {
        val controller = ScannerOverlayStateController()
        val box = DetectionBox(
            label = "FACE",
            confidence = 0.89f,
            normLeft = 0.3f,
            normTop = 0.2f,
            normRight = 0.6f,
            normBottom = 0.5f,
            timestampMs = 7000L
        )
        controller.updateState(
            boxes = listOf(box),
            status = DiagnosticStatus.PERSON_DETECTED,
            frameTimestampMs = 7000L,
            contentItemId = "item_track_36",
            nowMs = 7010L
        )
        assertEquals(1, controller.getRenderableBoxes(nowMs = 7020L).size)

        // Next update reports TRACKING_LOST -> stale subject boxes must be cleared immediately
        controller.updateState(
            boxes = listOf(box),
            status = DiagnosticStatus.TRACKING_LOST,
            frameTimestampMs = 7500L,
            contentItemId = "item_track_36",
            nowMs = 7510L
        )
        assertTrue(controller.getRenderableBoxes(nowMs = 7520L).isEmpty())
    }
}
