package com.instaguard.app.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests verifying the 5-Question Alarm Dismissal Engine (v15.4).
 * Validates zero duplicates per session, strict skip rules, answer verification,
 * and persistent exclusion of skipped/answered question IDs.
 */
class AlarmQuestionEngineTest {

    private lateinit var engine: AlarmQuestionEngine

    @Before
    fun setUp() {
        engine = AlarmQuestionEngine()
    }

    @Test
    fun testFiveQuestionsDrawnWithZeroDuplicates() {
        val questions = mutableListOf<AlarmQuestion>()
        val seenIds = HashSet<String>()

        for (i in 1..5) {
            val q = engine.nextQuestion()
            assertNotNull("Question should not be null", q)
            assertTrue("Question ID should not be blank", q.id.isNotBlank())
            assertTrue("Question text should not be blank", q.questionText.isNotBlank())
            assertFalse(
                "Duplicate question detected in same 5-question session: ${q.id}",
                seenIds.contains(q.id)
            )
            seenIds.add(q.id)
            questions.add(q)
        }

        assertEquals("Exactly 5 questions must be drawn", 5, questions.size)
        assertEquals("All 5 question IDs must be completely unique", 5, seenIds.size)
        assertEquals("Engine session must track 5 used questions", 5, engine.getUsedCount())
    }

    @Test
    fun testMathQuestionsCannotChangeOrSkip() {
        for (i in 1..20) {
            val mathQ = engine.generateUniqueMathQuestion()
            assertEquals("Category must be MATH", QuestionCategory.MATH, mathQ.category)
            assertFalse(
                "Math questions must have canChangeOrSkip == false (Rule 3)",
                mathQ.canChangeOrSkip
            )
            assertTrue(
                "Math question ID must start with MATH_",
                mathQ.id.startsWith("MATH_")
            )
            assertEquals("🧮 MATH CHALLENGE", mathQ.categoryBadgeText)
        }
    }

    @Test
    fun testCityCountryGkQuestionsCanChangeOrSkip() {
        for (i in 1..10) {
            val gkQ = engine.nextUnusedGkOrRandomQuestion()
            if (gkQ.category == QuestionCategory.CITY_COUNTRY_GK) {
                assertTrue(
                    "City/Country/GK questions must have canChangeOrSkip == true (Rule 4)",
                    gkQ.canChangeOrSkip
                )
                assertEquals("🌍 CITY & COUNTRY GK", gkQ.categoryBadgeText)
                assertNotNull("City/Country/GK questions should provide 4 choices", gkQ.choices)
                assertEquals("Must have 4 choices", 4, gkQ.choices?.size)
            }
        }
    }

    @Test
    fun testSkippingFifteenQuestionsNeverReturnsPreviouslySeenId() {
        val seenIds = HashSet<String>()

        // Simulate drawing and skipping 15 questions sequentially
        for (i in 1..15) {
            val q = engine.nextUnusedGkOrRandomQuestion()
            assertFalse(
                "Skipped question ${q.id} was returned again! Rule 5 violated.",
                seenIds.contains(q.id)
            )
            seenIds.add(q.id)
        }

        assertEquals("Must have drawn 15 unique questions", 15, seenIds.size)
        assertEquals("Engine session must have recorded 15 used IDs", 15, engine.getUsedCount())
    }

    @Test
    fun testAnswerCheckingLogic() {
        // Test Math answer verification
        val mathQ = AlarmQuestion(
            id = "MATH_TEST_1",
            category = QuestionCategory.MATH,
            categoryBadgeText = "🧮 MATH CHALLENGE",
            questionText = "(10 × 5) + 5 = ?",
            acceptedAnswers = listOf("55"),
            choices = null
        )
        assertTrue(engine.checkAnswer(mathQ, "55"))
        assertTrue(engine.checkAnswer(mathQ, " 55 "))
        assertFalse(engine.checkAnswer(mathQ, "50"))
        assertFalse(engine.checkAnswer(mathQ, ""))

        // Test City/Country/GK direct & choice answer verification
        val gkQ = AlarmQuestion(
            id = "CITY_TEST_PK",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "What is the capital city of Pakistan?",
            acceptedAnswers = listOf("islamabad"),
            choices = listOf("Islamabad", "Karachi", "Lahore", "Peshawar")
        )
        assertTrue(engine.checkAnswer(gkQ, "Islamabad"))
        assertTrue(engine.checkAnswer(gkQ, "islamabad"))
        assertTrue(engine.checkAnswer(gkQ, " ISLAMABAD "))
        assertFalse(engine.checkAnswer(gkQ, "Karachi"))
        assertFalse(engine.checkAnswer(gkQ, "Lahore"))
    }
}
