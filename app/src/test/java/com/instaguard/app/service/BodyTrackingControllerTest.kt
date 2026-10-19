package com.instaguard.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive Automated Regression Test Suite for Real-Time Body Tracking,
 * Motion Smoothing, Coordinate Mapping, and Filtering Decision Logic (Phase 13).
 *
 * Validates all 18 specified scenarios:
 * 1.  Person walks from left to right (lateral velocity & coordinate tracking)
 * 2.  Person walks from right to left (negative lateral velocity)
 * 3.  Person moves closer to camera (bounding box expansion)
 * 4.  Person moves farther away (bounding box contraction)
 * 5.  Person leans forward and straightens up (torso vector adaptation)
 * 6.  Person turns sideways (lateral width narrowing & trackId preservation)
 * 7.  Person raises their arms (bounding region expansion)
 * 8.  Subject briefly becomes partially obscured (coasting prediction <= 850ms)
 * 9.  Subject temporarily leaves frame (transitions to TRACKING_LOST)
 * 10. New subject enters frame (distinct trackId association)
 * 11. Application changes to a new video (state purge on contentItem transition)
 * 12. Inference results arrive out of order (duplicate / out-of-order rejection)
 * 13. Tracking confidence decreases (TRACKING_CONFIDENCE_LOW lifecycle state)
 * 14. Screen rotation & aspect ratio changes via DetectionCoordinateMapper
 * 15. Overlay is turned OFF (hides boxes, background filter continues)
 * 16. Frame capture temporarily stops (graceful coasting then clearance)
 * 17. Clothing classifier unavailable (reports missing model, fails open)
 * 18. Normal video without configured filter match (continues playing, ALLOW)
 */
class BodyTrackingControllerTest {

    private lateinit var tracker: BodyTrackingController
    private lateinit var decisionEngine: VisualFilterDecisionEngine
    private val defaultConfig = FilterConfig()

    @Before
    fun setUp() {
        tracker = BodyTrackingController(
            maxCoastingAgeMs = 850L,
            staleTimeoutMs = 1200L,
            velocityDampingFactor = 0.0035f
        )
        decisionEngine = VisualFilterDecisionEngine(defaultConfig)
    }

    // =========================================================================
    // Scenario 1: Person walks from left to right
    // =========================================================================
    @Test
    fun scenario01_personWalksLeftToRight_tracksCoordinatesAndPositiveVelocity() {
        val itemId = "reel_walk_ltr_01"

        // Frame 1 at t=100ms
        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 100L,
            personBox = NormalizedBox(1, "PERSON", 0.90f, 0.15f, 0.20f, 0.35f, 0.80f, 100L),
            detectionConfidence = 0.90f,
            nowMs = 100L
        )

        // Frame 2 at t=250ms (moved right by 0.15)
        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 250L,
            personBox = NormalizedBox(1, "PERSON", 0.92f, 0.30f, 0.20f, 0.50f, 0.80f, 250L),
            detectionConfidence = 0.92f,
            nowMs = 250L
        )

        val boxesAt250 = tracker.getRenderableBoxes(renderNowMs = 250L)
        assertFalse("Renderable boxes should not be empty", boxesAt250.isEmpty())
        val box250 = boxesAt250.first { it.regionLabel == "PERSON" }
        assertEquals("TrackId must be preserved across lateral motion", 1, box250.trackId)

        // At t=350ms (extrapolating 100ms forward with positive vx)
        val extrapolatedBoxes = tracker.getRenderableBoxes(renderNowMs = 350L)
        assertFalse(extrapolatedBoxes.isEmpty())
        val extrapBox = extrapolatedBoxes.first { it.regionLabel == "PERSON" }
        assertTrue(
            "Extrapolated x should be greater than frame 2 position to eliminate trailing lag",
            extrapBox.left > box250.left
        )
    }

    // =========================================================================
    // Scenario 2: Person walks from right to left
    // =========================================================================
    @Test
    fun scenario02_personWalksRightToLeft_tracksCoordinatesAndNegativeVelocity() {
        val itemId = "reel_walk_rtl_02"

        // Frame 1 at t=100ms
        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 100L,
            personBox = NormalizedBox(1, "PERSON", 0.90f, 0.65f, 0.20f, 0.85f, 0.80f, 100L),
            detectionConfidence = 0.90f,
            nowMs = 100L
        )

        // Frame 2 at t=250ms (moved left by 0.15)
        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 250L,
            personBox = NormalizedBox(1, "PERSON", 0.92f, 0.50f, 0.20f, 0.70f, 0.80f, 250L),
            detectionConfidence = 0.92f,
            nowMs = 250L
        )

        val boxesAt250 = tracker.getRenderableBoxes(renderNowMs = 250L)
        val box250 = boxesAt250.first { it.regionLabel == "PERSON" }

        // Extrapolating forward to t=350ms
        val extrapolatedBoxes = tracker.getRenderableBoxes(renderNowMs = 350L)
        val extrapBox = extrapolatedBoxes.first { it.regionLabel == "PERSON" }
        assertTrue(
            "Extrapolated x should be less than frame 2 position for leftward motion",
            extrapBox.left < box250.left
        )
    }

    // =========================================================================
    // Scenario 3: Person moves closer to camera (expansion)
    // =========================================================================
    @Test
    fun scenario03_personMovesCloser_boundingRegionExpands() {
        val itemId = "reel_approach_03"

        // Distant subject
        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 100L,
            personBox = NormalizedBox(1, "PERSON", 0.88f, 0.40f, 0.30f, 0.60f, 0.70f, 100L),
            detectionConfidence = 0.88f,
            nowMs = 100L
        )

        // Approached subject: width increased from 0.20 to 0.40, height from 0.40 to 0.70
        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 300L,
            personBox = NormalizedBox(1, "PERSON", 0.94f, 0.30f, 0.15f, 0.70f, 0.85f, 300L),
            detectionConfidence = 0.94f,
            nowMs = 300L
        )

        val boxes = tracker.getRenderableBoxes(renderNowMs = 300L)
        val personBox = boxes.first { it.regionLabel == "PERSON" }
        assertEquals(1, personBox.trackId)
        assertTrue("Bounding box width must expand", personBox.width() >= 0.38f)
        assertTrue("Bounding box height must expand", personBox.height() >= 0.68f)
    }

    // =========================================================================
    // Scenario 4: Person moves farther away (contraction)
    // =========================================================================
    @Test
    fun scenario04_personMovesFarther_boundingRegionContracts() {
        val itemId = "reel_retreat_04"

        // Close subject
        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 100L,
            personBox = NormalizedBox(1, "PERSON", 0.95f, 0.20f, 0.10f, 0.80f, 0.90f, 100L),
            detectionConfidence = 0.95f,
            nowMs = 100L
        )

        // Subject stepped back
        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 350L,
            personBox = NormalizedBox(1, "PERSON", 0.89f, 0.38f, 0.25f, 0.62f, 0.65f, 350L),
            detectionConfidence = 0.89f,
            nowMs = 350L
        )

        val boxes = tracker.getRenderableBoxes(renderNowMs = 350L)
        val personBox = boxes.first { it.regionLabel == "PERSON" }
        assertEquals(1, personBox.trackId)
        assertTrue("Bounding box width must contract", personBox.width() <= 0.26f)
        assertTrue("Bounding box height must contract", personBox.height() <= 0.42f)
    }

    // =========================================================================
    // Scenario 5: Person leans forward and straightens up
    // =========================================================================
    @Test
    fun scenario05_personLeansForwardAndStraightens_maintainsTrackIdAndTorsoBox() {
        val itemId = "reel_lean_05"

        // Standing upright
        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 100L,
            personBox = NormalizedBox(1, "PERSON", 0.92f, 0.35f, 0.20f, 0.65f, 0.80f, 100L),
            upperTorsoBox = NormalizedBox(1, "UPPER TORSO", 0.88f, 0.37f, 0.35f, 0.63f, 0.60f, 100L),
            faceBox = NormalizedBox(1, "FACE", 0.95f, 0.45f, 0.20f, 0.55f, 0.33f, 100L),
            detectionConfidence = 0.92f,
            nowMs = 100L
        )

        // Leaned forward (head and torso lower, torso expands)
        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 300L,
            personBox = NormalizedBox(1, "PERSON", 0.90f, 0.30f, 0.28f, 0.70f, 0.85f, 300L),
            upperTorsoBox = NormalizedBox(1, "UPPER TORSO", 0.86f, 0.32f, 0.42f, 0.68f, 0.70f, 300L),
            faceBox = NormalizedBox(1, "FACE", 0.91f, 0.45f, 0.28f, 0.55f, 0.40f, 300L),
            detectionConfidence = 0.90f,
            nowMs = 300L
        )

        val boxesLeaned = tracker.getRenderableBoxes(renderNowMs = 300L)
        val torsoBoxLeaned = boxesLeaned.first { it.regionLabel == "UPPER TORSO" }
        assertEquals(1, torsoBoxLeaned.trackId)

        // Straightens back up
        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 500L,
            personBox = NormalizedBox(1, "PERSON", 0.93f, 0.35f, 0.20f, 0.65f, 0.80f, 500L),
            upperTorsoBox = NormalizedBox(1, "UPPER TORSO", 0.89f, 0.37f, 0.35f, 0.63f, 0.60f, 500L),
            faceBox = NormalizedBox(1, "FACE", 0.96f, 0.45f, 0.20f, 0.55f, 0.33f, 500L),
            detectionConfidence = 0.93f,
            nowMs = 500L
        )

        val boxesUpright = tracker.getRenderableBoxes(renderNowMs = 500L)
        val torsoBoxUpright = boxesUpright.first { it.regionLabel == "UPPER TORSO" }
        assertEquals(1, torsoBoxUpright.trackId)
    }

    // =========================================================================
    // Scenario 6: Person turns sideways (width narrows, trackId preserved)
    // =========================================================================
    @Test
    fun scenario06_personTurnsSideways_widthNarrowsAndTrackIdPreserved() {
        val itemId = "reel_turn_06"

        // Frontal view (wide profile)
        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 100L,
            personBox = NormalizedBox(1, "PERSON", 0.91f, 0.30f, 0.20f, 0.70f, 0.80f, 100L),
            faceBox = NormalizedBox(1, "FACE", 0.94f, 0.42f, 0.20f, 0.58f, 0.34f, 100L),
            detectionConfidence = 0.91f,
            nowMs = 100L
        )

        // Sideways profile (width narrows significantly from 0.40 to 0.18, centroid aligned)
        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 300L,
            personBox = NormalizedBox(1, "PERSON", 0.85f, 0.41f, 0.20f, 0.59f, 0.80f, 300L),
            faceBox = NormalizedBox(1, "FACE", 0.82f, 0.44f, 0.20f, 0.56f, 0.34f, 300L),
            detectionConfidence = 0.85f,
            nowMs = 300L
        )

        val boxes = tracker.getRenderableBoxes(renderNowMs = 300L)
        val personBox = boxes.first { it.regionLabel == "PERSON" }
        assertEquals("TrackId must be preserved during turn via centroid proximity", 1, personBox.trackId)
        assertTrue("Bounding box width should narrow on sideways turn", personBox.width() < 0.22f)
    }

    // =========================================================================
    // Scenario 7: Person raises their arms
    // =========================================================================
    @Test
    fun scenario07_personRaisesArms_boundingRegionExpandsAppropriately() {
        val itemId = "reel_arms_07"

        // Arms down
        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 100L,
            personBox = NormalizedBox(1, "PERSON", 0.90f, 0.35f, 0.25f, 0.65f, 0.85f, 100L),
            detectionConfidence = 0.90f,
            nowMs = 100L
        )

        // Arms raised high (box top expands upward and outwards)
        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 280L,
            personBox = NormalizedBox(1, "PERSON", 0.92f, 0.25f, 0.10f, 0.75f, 0.85f, 280L),
            detectionConfidence = 0.92f,
            nowMs = 280L
        )

        val boxes = tracker.getRenderableBoxes(renderNowMs = 280L)
        val personBox = boxes.first { it.regionLabel == "PERSON" }
        assertEquals(1, personBox.trackId)
        assertTrue("Person box top should reach higher when arms are raised", personBox.top <= 0.12f)
        assertTrue("Person box width should accommodate raised arms", personBox.width() >= 0.48f)
    }

    // =========================================================================
    // Scenario 8: Subject briefly becomes partially obscured (coasting prediction)
    // =========================================================================
    @Test
    fun scenario08_briefPartialOcclusion_coastsDeadReckoningWithinGracePeriod() {
        val itemId = "reel_occlusion_08"

        // Initial detection
        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 100L,
            personBox = NormalizedBox(1, "PERSON", 0.92f, 0.35f, 0.20f, 0.65f, 0.80f, 100L),
            detectionConfidence = 0.92f,
            nowMs = 100L
        )

        // Missing detection at t=400ms (gap = 300ms <= 850ms coasting age)
        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 400L,
            personBox = null,
            faceBox = null,
            upperTorsoBox = null,
            detectionConfidence = 0f,
            nowMs = 400L
        )

        // Must transition to COASTING / TRACKING_CONFIDENCE_LOW without wiping the box
        assertEquals(TrackingLifecycleState.TRACKING_CONFIDENCE_LOW, tracker.currentLifecycleState)
        val coastingBoxes = tracker.getRenderableBoxes(renderNowMs = 450L)
        assertFalse("Coasting should keep predicted box visible during brief occlusion", coastingBoxes.isEmpty())
        assertEquals(1, coastingBoxes.first().trackId)
    }

    // =========================================================================
    // Scenario 9: Subject temporarily leaves frame (transitions to TRACKING_LOST)
    // =========================================================================
    @Test
    fun scenario09_subjectLeavesFrame_transitionsToTrackingLostAndClearsBoxes() {
        val itemId = "reel_exit_09"

        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 100L,
            personBox = NormalizedBox(1, "PERSON", 0.90f, 0.35f, 0.20f, 0.65f, 0.80f, 100L),
            detectionConfidence = 0.90f,
            nowMs = 100L
        )

        // Stale timeout at t=1500ms (gap = 1400ms > 1200ms staleTimeout)
        val renderable = tracker.getRenderableBoxes(renderNowMs = 1500L)
        assertTrue("Boxes must be cleanly cleared when subject leaves frame past timeout", renderable.isEmpty())
        assertEquals(TrackingLifecycleState.TRACKING_LOST, tracker.currentLifecycleState)
    }

    // =========================================================================
    // Scenario 10: New subject enters frame (distinct trackId association)
    // =========================================================================
    @Test
    fun scenario10_newSubjectEntersFrame_assignsDistinctTrackId() {
        val itemId = "reel_multi_10"

        // First subject on the far left
        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 100L,
            personBox = NormalizedBox(1, "PERSON", 0.90f, 0.05f, 0.20f, 0.25f, 0.80f, 100L),
            detectionConfidence = 0.90f,
            nowMs = 100L
        )
        val trackId1 = tracker.getRenderableBoxes(100L).first().trackId

        // Second subject appears far to the right (no IoU, centroid distance > 0.40)
        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 300L,
            personBox = NormalizedBox(2, "PERSON", 0.92f, 0.75f, 0.20f, 0.95f, 0.80f, 300L),
            detectionConfidence = 0.92f,
            nowMs = 300L
        )
        val trackId2 = tracker.getRenderableBoxes(300L).first().trackId

        assertNotEquals("Distinct subject must be assigned a new trackId", trackId1, trackId2)
    }

    // =========================================================================
    // Scenario 11: Application changes to a new video
    // =========================================================================
    @Test
    fun scenario11_itemTransition_purgesPreviousTrackingStateImmediately() {
        tracker.updateWithFrameDetection(
            contentItemId = "video_reels_A",
            timestampMs = 100L,
            personBox = NormalizedBox(1, "PERSON", 0.90f, 0.35f, 0.20f, 0.65f, 0.80f, 100L),
            detectionConfidence = 0.90f,
            nowMs = 100L
        )
        assertFalse(tracker.getRenderableBoxes(100L).isEmpty())

        // User scrolled to a new reel
        tracker.onContentItemChanged("video_reels_B")

        assertEquals("video_reels_B", tracker.currentContentItemId)
        assertEquals(TrackingLifecycleState.WAITING_FOR_FRAME, tracker.currentLifecycleState)
        assertTrue("All previous boxes must be purged immediately on item change", tracker.getRenderableBoxes(150L).isEmpty())
    }

    // =========================================================================
    // Scenario 12: Inference results arrive out of order
    // =========================================================================
    @Test
    fun scenario12_outOfOrderFrame_isRejectedAndDoesNotCorruptTracking() {
        val itemId = "reel_ooo_12"

        // Frame at t=500ms
        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 500L,
            personBox = NormalizedBox(1, "PERSON", 0.90f, 0.40f, 0.20f, 0.60f, 0.80f, 500L),
            detectionConfidence = 0.90f,
            nowMs = 500L
        )
        assertEquals(500L, tracker.lastDetectionTimestampMs)

        // Delayed out-of-order frame timestamped t=350ms arrives
        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 350L,
            personBox = NormalizedBox(1, "PERSON", 0.95f, 0.10f, 0.10f, 0.30f, 0.70f, 350L),
            detectionConfidence = 0.95f,
            nowMs = 520L
        )

        // Must reject and retain the latest t=500ms detection
        assertEquals(500L, tracker.lastDetectionTimestampMs)
        val currentBoxes = tracker.getRenderableBoxes(520L)
        assertEquals(1, currentBoxes.size)
        assertTrue("Must retain t=500 position rather than jumping back to t=350", currentBoxes.first().left >= 0.38f)
    }

    // =========================================================================
    // Scenario 13: Tracking confidence decreases
    // =========================================================================
    @Test
    fun scenario13_trackingConfidenceDecreases_transitionsToLowConfidenceState() {
        val itemId = "reel_low_conf_13"

        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 100L,
            personBox = NormalizedBox(1, "PERSON", 0.90f, 0.35f, 0.20f, 0.65f, 0.80f, 100L),
            detectionConfidence = 0.90f,
            nowMs = 100L
        )

        // Frame with missing detection within coasting interval
        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 300L,
            personBox = null,
            faceBox = null,
            upperTorsoBox = null,
            nowMs = 300L
        )

        assertEquals(TrackingLifecycleState.TRACKING_CONFIDENCE_LOW, tracker.currentLifecycleState)
        assertTrue(tracker.getDiagnosticSummary(300L).contains("TRACKING CONFIDENCE LOW"))
    }

    // =========================================================================
    // Scenario 14: Screen rotation & aspect ratio changes via DetectionCoordinateMapper
    // =========================================================================
    @Test
    fun scenario14_screenRotationAndAspectRatio_mapsCorrectlyWithoutDistortion() {
        val normalizedBox = NormalizedBox(
            trackId = 1,
            regionLabel = "PERSON",
            confidence = 0.92f,
            left = 0.25f,
            top = 0.10f,
            right = 0.75f,
            bottom = 0.90f,
            timestampMs = 100L,
            sourceWidth = 1080,
            sourceHeight = 1920
        )

        // Portrait mapping: 1080x2400 display
        val mappedPortrait = DetectionCoordinateMapper.mapNormalizedBoxToView(
            box = normalizedBox,
            viewWidth = 1080,
            viewHeight = 2400
        )
        assertNotNull("Mapped box in portrait must not be null", mappedPortrait)
        assertTrue("Left coordinate must be non-negative", mappedPortrait!!.leftPx >= 0f)
        assertTrue("Width must be proportional to display width", mappedPortrait.width > 200f)

        // Landscape mapping: 2400x1080 display (pillarboxing applied)
        val mappedLandscape = DetectionCoordinateMapper.mapNormalizedBoxToView(
            box = normalizedBox,
            viewWidth = 2400,
            viewHeight = 1080
        )
        assertNotNull("Mapped box in landscape must not be null", mappedLandscape)
        assertTrue("Pillarboxed left must account for horizontal offset", mappedLandscape!!.leftPx > 0f)
        assertTrue("Height must be non-degenerate", mappedLandscape.height > 200f)
    }

    // =========================================================================
    // Scenario 15: Overlay is turned OFF (hides boxes, background filter continues)
    // =========================================================================
    @Test
    fun scenario15_overlayTurnedOff_hidesBoxesWhileFilterLogicContinues() {
        val itemId = "reel_overlay_toggle_15"

        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 100L,
            personBox = NormalizedBox(1, "PERSON", 0.90f, 0.35f, 0.20f, 0.65f, 0.80f, 100L),
            detectionConfidence = 0.90f,
            nowMs = 100L
        )
        assertFalse(tracker.getRenderableBoxes(100L).isEmpty())

        // User turns off scanner outlines
        tracker.isOverlayEnabled = false
        assertTrue("Renderable boxes must be empty when overlay is turned OFF", tracker.getRenderableBoxes(100L).isEmpty())

        // Background decision engine continues functioning normally
        val frame = FrameFeatures(
            contentItemId = itemId,
            frameTimestampMs = 150L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = true,
            personConfidence = 0.92f,
            clothingAnalysisComplete = true,
            clothingClassifierAvailable = false
        )
        val decision = decisionEngine.evaluateFrame(frame, defaultConfig, nowMs = 160L)
        assertEquals("Background decision engine must continue running", DecisionState.ALLOW, decision.state)
    }

    // =========================================================================
    // Scenario 16: Frame capture temporarily stops (graceful coasting then clearance)
    // =========================================================================
    @Test
    fun scenario16_frameCaptureTemporarilyStops_gracefullyCoastsThenClears() {
        val itemId = "reel_stop_16"

        tracker.updateWithFrameDetection(
            contentItemId = itemId,
            timestampMs = 100L,
            personBox = NormalizedBox(1, "PERSON", 0.90f, 0.35f, 0.20f, 0.65f, 0.80f, 100L),
            detectionConfidence = 0.90f,
            nowMs = 100L
        )

        // At t=500ms (gap = 400ms <= 850ms coasting age): predicted boxes provided
        val coastingBoxes = tracker.getRenderableBoxes(renderNowMs = 500L)
        assertFalse("Coasting boxes must be provided during capture pause", coastingBoxes.isEmpty())

        // At t=1500ms (gap = 1400ms > 1200ms stale timeout): cleanly cleared
        val timedOutBoxes = tracker.getRenderableBoxes(renderNowMs = 1500L)
        assertTrue("Boxes must be removed when capture stops past stale timeout", timedOutBoxes.isEmpty())
        assertEquals(TrackingLifecycleState.TRACKING_LOST, tracker.currentLifecycleState)
    }

    // =========================================================================
    // Scenario 17: Clothing classifier unavailable (reports missing model, fails open)
    // =========================================================================
    @Test
    fun scenario17_clothingClassifierUnavailable_reportsMissingModelAndFailsOpen() {
        val frame = FrameFeatures(
            contentItemId = "reel_female_presenting_17",
            frameTimestampMs = 1000L,
            isSupportedVideoFeed = true,
            modelInitialized = true,
            personDetectorAvailable = true,
            clothingClassifierAvailable = false,
            qualifyingPersonDetected = true,
            personConfidence = 0.91f,
            detectedBoxes = listOf(
                NormalizedBox(1, "FACE", 0.94f, 0.40f, 0.15f, 0.60f, 0.35f, 1000L),
                NormalizedBox(1, "UPPER TORSO", 0.88f, 0.30f, 0.35f, 0.70f, 0.75f, 1000L),
                NormalizedBox(1, "PERSON", 0.92f, 0.25f, 0.15f, 0.75f, 0.90f, 1000L)
            ),
            failureReason = "Visible subject detected; trained clothing classifier model (TFLite) not bundled in assets"
        )

        val out = decisionEngine.evaluateFrame(frame, defaultConfig, nowMs = 1050L)

        // Fail-open: Never skip without a trained clothing classifier!
        assertEquals("Decision must be ALLOW when clothing classifier is unavailable", DecisionState.ALLOW, out.state)
        assertEquals(DiagnosticStatus.PERSON_DETECTED, out.diagnosticStatus)
        assertFalse("Active boxes should be passed to HUD for tracking outlines", out.activeBoxes.isEmpty())
        assertTrue(
            "Failure reason must document missing clothing model",
            out.failureReason?.contains("Clothing classifier unavailable") == true ||
                out.failureReason?.contains("not bundled in assets") == true
        )
    }

    // =========================================================================
    // Scenario 18: Normal video without configured filter match (continues playing)
    // =========================================================================
    @Test
    fun scenario18_normalVideoWithoutFilterMatch_continuesPlayingAllow() {
        // Scenery / cooking / automotive video with zero filter rule triggers
        val frame = FrameFeatures(
            contentItemId = "reel_cooking_recipe_18",
            frameTimestampMs = 2000L,
            isSupportedVideoFeed = true,
            qualifyingPersonDetected = false,
            personConfidence = 0.05f
        )

        val out = decisionEngine.evaluateFrame(frame, defaultConfig, nowMs = 2030L)

        assertEquals("Normal non-matching video must be ALLOWed", DecisionState.ALLOW, out.state)
        assertEquals(DiagnosticStatus.NO_QUALIFYING_PERSON_DETECTED, out.diagnosticStatus)
        assertTrue(out.activeBoxes.isEmpty())
        assertEquals(SkipActionState.NO_SKIP_REQUESTED, out.skipActionState)
    }

    // =========================================================================
    // Scenario 19: Dedicated PoseTrackingController tracks ML Kit landmarks
    // =========================================================================
    @Test
    fun scenario19_poseTrackingController_tracksAnatomicalLandmarksAndMaintainsContinuity() {
        val poseTracker = PoseTrackingController(maxCoastingAgeMs = 850L, staleTimeoutMs = 1200L)
        val itemId = "reel_pose_19"

        val mlLandmarks = listOf(
            PoseLandmark(LandmarkType.NOSE, 0.50f, 0.20f, 0.95f),
            PoseLandmark(LandmarkType.LEFT_SHOULDER, 0.40f, 0.35f, 0.92f),
            PoseLandmark(LandmarkType.RIGHT_SHOULDER, 0.60f, 0.35f, 0.92f),
            PoseLandmark(LandmarkType.LEFT_HIP, 0.42f, 0.65f, 0.88f),
            PoseLandmark(LandmarkType.RIGHT_HIP, 0.58f, 0.65f, 0.88f),
            PoseLandmark(LandmarkType.LEFT_KNEE, 0.43f, 0.80f, 0.85f),
            PoseLandmark(LandmarkType.RIGHT_KNEE, 0.57f, 0.80f, 0.85f)
        )

        poseTracker.updateWithPoseDetections(
            contentItemId = itemId,
            timestampMs = 100L,
            landmarks = mlLandmarks,
            personBox = NormalizedBox(1, "PERSON", 0.92f, 0.35f, 0.15f, 0.65f, 0.85f, 100L),
            faceBox = NormalizedBox(1, "FACE", 0.95f, 0.45f, 0.15f, 0.55f, 0.28f, 100L),
            upperTorsoBox = NormalizedBox(1, "UPPER TORSO", 0.90f, 0.38f, 0.32f, 0.62f, 0.68f, 100L),
            detectionConfidence = 0.92f,
            nowMs = 100L
        )

        assertEquals(TrackingLifecycleState.TRACKING_SUBJECT, poseTracker.currentLifecycleState)
        val boxes = poseTracker.getRenderableBoxes(100L)
        assertEquals(3, boxes.size)
        assertTrue(boxes.any { it.regionLabel == "FACE" })
        assertTrue(boxes.any { it.regionLabel == "UPPER TORSO" })
        assertTrue(boxes.any { it.regionLabel == "PERSON" })
    }

    // =========================================================================
    // Scenario 20: PoseTrackingController rejects out-of-order frames
    // =========================================================================
    @Test
    fun scenario20_poseTrackingController_rejectsOutOfOrderFrames() {
        val poseTracker = PoseTrackingController(maxCoastingAgeMs = 850L, staleTimeoutMs = 1200L)
        val itemId = "reel_pose_ooo_20"

        poseTracker.updateWithPoseDetections(
            contentItemId = itemId,
            timestampMs = 400L,
            landmarks = listOf(PoseLandmark(LandmarkType.NOSE, 0.50f, 0.20f, 0.95f)),
            personBox = NormalizedBox(1, "PERSON", 0.90f, 0.40f, 0.20f, 0.60f, 0.80f, 400L),
            detectionConfidence = 0.90f,
            nowMs = 400L
        )
        assertEquals(400L, poseTracker.lastDetectionTimestampMs)

        // Stale out-of-order frame
        poseTracker.updateWithPoseDetections(
            contentItemId = itemId,
            timestampMs = 250L,
            landmarks = listOf(PoseLandmark(LandmarkType.NOSE, 0.20f, 0.20f, 0.90f)),
            personBox = NormalizedBox(1, "PERSON", 0.90f, 0.10f, 0.20f, 0.30f, 0.80f, 250L),
            detectionConfidence = 0.90f,
            nowMs = 420L
        )

        // Retains latest valid timestamp and position
        assertEquals(400L, poseTracker.lastDetectionTimestampMs)
        val boxes = poseTracker.getRenderableBoxes(420L)
        assertEquals(1, boxes.size)
        assertTrue(boxes.first().left >= 0.38f)
    }

    // =========================================================================
    // Scenario 21: PoseTrackingController purges on content transition
    // =========================================================================
    @Test
    fun scenario21_poseTrackingController_purgesOnContentTransition() {
        val poseTracker = PoseTrackingController(maxCoastingAgeMs = 850L, staleTimeoutMs = 1200L)

        poseTracker.updateWithPoseDetections(
            contentItemId = "reel_video_A",
            timestampMs = 100L,
            landmarks = listOf(PoseLandmark(LandmarkType.NOSE, 0.50f, 0.20f, 0.95f)),
            personBox = NormalizedBox(1, "PERSON", 0.90f, 0.40f, 0.20f, 0.60f, 0.80f, 100L),
            detectionConfidence = 0.90f,
            nowMs = 100L
        )
        assertFalse(poseTracker.getRenderableBoxes(100L).isEmpty())

        // User scrolls to video B
        poseTracker.clearTracking()
        assertTrue(poseTracker.getRenderableBoxes(100L).isEmpty())
        assertEquals(TrackingLifecycleState.WAITING_FOR_FRAME, poseTracker.currentLifecycleState)
    }
}
