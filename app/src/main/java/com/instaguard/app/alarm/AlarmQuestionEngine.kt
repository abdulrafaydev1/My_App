package com.instaguard.app.alarm

import java.util.Random

enum class QuestionCategory {
    MATH,
    CITY_COUNTRY_GK
}

data class AlarmQuestion(
    val id: String,
    val category: QuestionCategory,
    val categoryBadgeText: String,
    val questionText: String,
    val acceptedAnswers: List<String>, // Lowercase trimmed valid answers
    val choices: List<String>? = null  // Optional quick-tap choices for City/Country/GK questions
) {
    val canChangeOrSkip: Boolean
        get() = category == QuestionCategory.CITY_COUNTRY_GK // Rule 3 & 4: Math CANNOT be changed; City/Country/GK CAN be changed!
}

/**
 * Modular Alarm Dismissal Question Engine.
 * Generates a randomized mix of Math questions and City/Country/General Knowledge questions.
 * Enforces zero repetition per session and strict skip/change rules.
 */
class AlarmQuestionEngine {

    private val usedQuestionIds = HashSet<String>()
    private val random = Random()

    private val cityCountryGkPool: List<AlarmQuestion> = listOf(
        AlarmQuestion(
            id = "CITY_PK_CAP",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "What is the capital city of Pakistan?",
            acceptedAnswers = listOf("islamabad"),
            choices = listOf("Islamabad", "Karachi", "Lahore", "Peshawar")
        ),
        AlarmQuestion(
            id = "CITY_PK_LARGEST",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "Which is the largest city of Pakistan by population (City of Lights)?",
            acceptedAnswers = listOf("karachi"),
            choices = listOf("Karachi", "Lahore", "Faisalabad", "Multan")
        ),
        AlarmQuestion(
            id = "COUNTRY_PK_CURR",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "What is the official currency of Pakistan?",
            acceptedAnswers = listOf("rupee", "pakistani rupee", "pkr"),
            choices = listOf("Pakistani Rupee (PKR)", "Dirham", "Riyal", "Taka")
        ),
        AlarmQuestion(
            id = "CITY_SINDH_CAP",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "Which city is the provincial capital of Sindh?",
            acceptedAnswers = listOf("karachi"),
            choices = listOf("Karachi", "Hyderabad", "Sukkur", "Larkana")
        ),
        AlarmQuestion(
            id = "CITY_PUNJAB_CAP",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "Which city is known as the Heart of Pakistan and capital of Punjab?",
            acceptedAnswers = listOf("lahore"),
            choices = listOf("Lahore", "Rawalpindi", "Multan", "Sialkot")
        ),
        AlarmQuestion(
            id = "COUNTRY_PK_MTN",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "Which is the highest mountain peak in Pakistan (2nd highest in the world)?",
            acceptedAnswers = listOf("k2", "mount godwin-austen", "mount godwin austen", "godwin austen"),
            choices = listOf("K2", "Nanga Parbat", "Mount Everest", "Broad Peak")
        ),
        AlarmQuestion(
            id = "COUNTRY_PK_RIVER",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "Which is the longest river in Pakistan?",
            acceptedAnswers = listOf("indus", "indus river", "sindh", "darya e sindh"),
            choices = listOf("Indus River", "Chenab River", "Jhelum River", "Ravi River")
        ),
        AlarmQuestion(
            id = "CITY_PORT_PK",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "Which deep-sea port city is located in Balochistan, Pakistan?",
            acceptedAnswers = listOf("gwadar"),
            choices = listOf("Gwadar", "Quetta", "Turbat", "Sibi")
        ),
        AlarmQuestion(
            id = "CITY_COOL_PK",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "Which famous hill station city is located near Islamabad?",
            acceptedAnswers = listOf("murree"),
            choices = listOf("Murree", "Swat", "Chitral", "Ziarat")
        ),
        AlarmQuestion(
            id = "COUNTRY_SA_CAP",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "What is the capital city of Saudi Arabia?",
            acceptedAnswers = listOf("riyadh"),
            choices = listOf("Riyadh", "Jeddah", "Makkah", "Madinah")
        ),
        AlarmQuestion(
            id = "COUNTRY_TR_CAP",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "What is the capital city of Turkey (Türkiye)?",
            acceptedAnswers = listOf("ankara"),
            choices = listOf("Ankara", "Istanbul", "Antalya", "Izmir")
        ),
        AlarmQuestion(
            id = "COUNTRY_UAE_CAP",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "What is the capital city of the United Arab Emirates (UAE)?",
            acceptedAnswers = listOf("abu dhabi"),
            choices = listOf("Abu Dhabi", "Dubai", "Sharjah", "Ajman")
        ),
        AlarmQuestion(
            id = "COUNTRY_CN_CAP",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "What is the capital city of China?",
            acceptedAnswers = listOf("beijing"),
            choices = listOf("Beijing", "Shanghai", "Shenzhen", "Wuhan")
        ),
        AlarmQuestion(
            id = "COUNTRY_JP_CAP",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "What is the capital city of Japan?",
            acceptedAnswers = listOf("tokyo"),
            choices = listOf("Tokyo", "Osaka", "Kyoto", "Yokohama")
        ),
        AlarmQuestion(
            id = "COUNTRY_UK_CAP",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "What is the capital city of the United Kingdom?",
            acceptedAnswers = listOf("london"),
            choices = listOf("London", "Manchester", "Birmingham", "Edinburgh")
        ),
        AlarmQuestion(
            id = "GK_LARGEST_CONT",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "Which is the largest continent in the world by area and population?",
            acceptedAnswers = listOf("asia"),
            choices = listOf("Asia", "Africa", "Europe", "North America")
        ),
        AlarmQuestion(
            id = "GK_LARGEST_OCEAN",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "Which is the largest and deepest ocean on Earth?",
            acceptedAnswers = listOf("pacific", "pacific ocean"),
            choices = listOf("Pacific Ocean", "Atlantic Ocean", "Indian Ocean", "Arctic Ocean")
        ),
        AlarmQuestion(
            id = "COUNTRY_MY_CAP",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "What is the capital city of Malaysia?",
            acceptedAnswers = listOf("kuala lumpur"),
            choices = listOf("Kuala Lumpur", "Penang", "Johor Bahru", "Malacca")
        ),
        AlarmQuestion(
            id = "COUNTRY_QA_CAP",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "What is the capital city of Qatar?",
            acceptedAnswers = listOf("doha"),
            choices = listOf("Doha", "Al Wakrah", "Lusail", "Dukhan")
        ),
        AlarmQuestion(
            id = "COUNTRY_EG_CAP",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "What is the capital city of Egypt?",
            acceptedAnswers = listOf("cairo"),
            choices = listOf("Cairo", "Alexandria", "Giza", "Luxor")
        ),
        AlarmQuestion(
            id = "CITY_SPORTS_PK",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "Which city in Pakistan is world-famous for manufacturing footballs and sports goods?",
            acceptedAnswers = listOf("sialkot"),
            choices = listOf("Sialkot", "Gujranwala", "Faisalabad", "Wazirabad")
        ),
        AlarmQuestion(
            id = "CITY_TEXTILE_PK",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "Which city is known as the Manchester of Pakistan for its textile industry?",
            acceptedAnswers = listOf("faisalabad"),
            choices = listOf("Faisalabad", "Multan", "Karachi", "Sargodha")
        ),
        AlarmQuestion(
            id = "CITY_SAINTS_PK",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "Which city in Pakistan is famous as the 'City of Saints' (Madinat-ul-Auliya)?",
            acceptedAnswers = listOf("multan"),
            choices = listOf("Multan", "Bahawalpur", "Lahore", "Uch Sharif")
        ),
        AlarmQuestion(
            id = "CITY_KPK_CAP",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "What is the capital city of Khyber Pakhtunkhwa (KPK)?",
            acceptedAnswers = listOf("peshawar"),
            choices = listOf("Peshawar", "Abbottabad", "Mardan", "Kohat")
        ),
        AlarmQuestion(
            id = "CITY_BALOCH_CAP",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "What is the provincial capital city of Balochistan?",
            acceptedAnswers = listOf("quetta"),
            choices = listOf("Quetta", "Gwadar", "Khuzdar", "Chaman")
        ),
        AlarmQuestion(
            id = "COUNTRY_FR_CAP",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "What is the capital city of France?",
            acceptedAnswers = listOf("paris"),
            choices = listOf("Paris", "Marseille", "Lyon", "Nice")
        ),
        AlarmQuestion(
            id = "COUNTRY_DE_CAP",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "What is the capital city of Germany?",
            acceptedAnswers = listOf("berlin"),
            choices = listOf("Berlin", "Munich", "Frankfurt", "Hamburg")
        ),
        AlarmQuestion(
            id = "COUNTRY_IT_CAP",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "What is the capital city of Italy?",
            acceptedAnswers = listOf("rome"),
            choices = listOf("Rome", "Milan", "Venice", "Florence")
        ),
        AlarmQuestion(
            id = "COUNTRY_CA_CAP",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "What is the capital city of Canada?",
            acceptedAnswers = listOf("ottawa"),
            choices = listOf("Ottawa", "Toronto", "Vancouver", "Montreal")
        ),
        AlarmQuestion(
            id = "COUNTRY_AU_CAP",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "What is the capital city of Australia?",
            acceptedAnswers = listOf("canberra"),
            choices = listOf("Canberra", "Sydney", "Melbourne", "Brisbane")
        ),
        AlarmQuestion(
            id = "GK_HIGHEST_MTN",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "Which is the highest mountain peak in the world?",
            acceptedAnswers = listOf("mount everest", "everest"),
            choices = listOf("Mount Everest", "K2", "Kangchenjunga", "Lhotse")
        ),
        AlarmQuestion(
            id = "GK_FASTEST_LAND",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "Which is the fastest land animal on Earth?",
            acceptedAnswers = listOf("cheetah"),
            choices = listOf("Cheetah", "Lion", "Leopard", "Pronghorn")
        ),
        AlarmQuestion(
            id = "GK_RED_PLANET",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "Which planet in our solar system is known as the Red Planet?",
            acceptedAnswers = listOf("mars"),
            choices = listOf("Mars", "Venus", "Jupiter", "Saturn")
        ),
        AlarmQuestion(
            id = "GK_LARGEST_DESERT",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "Which is the largest hot desert in the world?",
            acceptedAnswers = listOf("sahara", "sahara desert"),
            choices = listOf("Sahara Desert", "Arabian Desert", "Gobi Desert", "Kalahari Desert")
        ),
        AlarmQuestion(
            id = "COUNTRY_ES_CAP",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "What is the capital city of Spain?",
            acceptedAnswers = listOf("madrid"),
            choices = listOf("Madrid", "Barcelona", "Seville", "Valencia")
        ),
        AlarmQuestion(
            id = "COUNTRY_ID_CAP",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "What is the capital city of Indonesia?",
            acceptedAnswers = listOf("jakarta"),
            choices = listOf("Jakarta", "Bali", "Surabaya", "Bandung")
        ),
        AlarmQuestion(
            id = "CITY_MANGO_PK",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "Which Pakistani city is renowned as the City of Mangoes?",
            acceptedAnswers = listOf("mirpurkhas", "multan"),
            choices = listOf("Mirpurkhas", "Multan", "Hyderabad", "Sukkur")
        ),
        AlarmQuestion(
            id = "GK_LARGEST_MAMMAL",
            category = QuestionCategory.CITY_COUNTRY_GK,
            categoryBadgeText = "🌍 CITY & COUNTRY GK",
            questionText = "Which is the largest mammal currently living on Earth?",
            acceptedAnswers = listOf("blue whale", "whale"),
            choices = listOf("Blue Whale", "African Elephant", "Fin Whale", "Sperm Whale")
        )
    )

    /**
     * Generates a unique, non-repeating arithmetic challenge.
     * Enforces canChangeOrSkip = false (Rule 3).
     */
    fun generateUniqueMathQuestion(): AlarmQuestion {
        var attempts = 0
        while (attempts < 200) {
            attempts++
            val type = random.nextInt(4)
            val questionText: String
            val answer: Int
            val equationKey: String

            when (type) {
                0 -> {
                    // (A * B) + C
                    val a = 11 + random.nextInt(15) // 11..25
                    val b = 4 + random.nextInt(6)   // 4..9
                    val c = 15 + random.nextInt(35) // 15..49
                    answer = (a * b) + c
                    questionText = "($a × $b) + $c = ?"
                    equationKey = "MUL_ADD_${a}_${b}_${c}"
                }
                1 -> {
                    // (A * B) - C
                    val a = 12 + random.nextInt(14) // 12..25
                    val b = 4 + random.nextInt(6)   // 4..9
                    val c = 10 + random.nextInt(25) // 10..34
                    answer = (a * b) - c
                    questionText = "($a × $b) - $c = ?"
                    equationKey = "MUL_SUB_${a}_${b}_${c}"
                }
                2 -> {
                    // A + B + C
                    val a = 110 + random.nextInt(180) // 110..289
                    val b = 120 + random.nextInt(190) // 120..309
                    val c = 45 + random.nextInt(55)   // 45..99
                    answer = a + b + c
                    questionText = "$a + $b + $c = ?"
                    equationKey = "ADD3_${a}_${b}_${c}"
                }
                else -> {
                    // (A * B) + (C * D)
                    val a = 6 + random.nextInt(7) // 6..12
                    val b = 6 + random.nextInt(7) // 6..12
                    val c = 5 + random.nextInt(6) // 5..10
                    val d = 5 + random.nextInt(6) // 5..10
                    answer = (a * b) + (c * d)
                    questionText = "($a × $b) + ($c × $d) = ?"
                    equationKey = "DOUBLE_MUL_${a}_${b}_${c}_${d}"
                }
            }

            val id = "MATH_${equationKey}"
            if (!usedQuestionIds.contains(id)) {
                usedQuestionIds.add(id)
                return AlarmQuestion(
                    id = id,
                    category = QuestionCategory.MATH,
                    categoryBadgeText = "🧮 MATH CHALLENGE",
                    questionText = questionText,
                    acceptedAnswers = listOf(answer.toString()),
                    choices = null
                )
            }
        }

        // Fallback guaranteed unique by timestamp counter
        val fallbackA = 20 + random.nextInt(30)
        val fallbackB = 15 + random.nextInt(30)
        val fallbackAns = fallbackA + fallbackB
        val fallbackId = "MATH_FALLBACK_${System.nanoTime()}"
        usedQuestionIds.add(fallbackId)
        return AlarmQuestion(
            id = fallbackId,
            category = QuestionCategory.MATH,
            categoryBadgeText = "🧮 MATH CHALLENGE",
            questionText = "$fallbackA + $fallbackB = ?",
            acceptedAnswers = listOf(fallbackAns.toString()),
            choices = null
        )
    }

    /**
     * Draws the next unseen question for the session.
     * Randomly mixes Math and City/Country/GK.
     * Immediately registers the drawn ID into usedQuestionIds.
     */
    fun nextQuestion(forceCategory: QuestionCategory? = null): AlarmQuestion {
        if (forceCategory == QuestionCategory.MATH) {
            return generateUniqueMathQuestion()
        }
        if (forceCategory == QuestionCategory.CITY_COUNTRY_GK) {
            return nextUnusedGkOrRandomQuestion()
        }

        val availableGk = cityCountryGkPool.filter { !usedQuestionIds.contains(it.id) }
        val pickMath = if (availableGk.isEmpty()) true else random.nextBoolean()

        return if (pickMath) {
            generateUniqueMathQuestion()
        } else {
            val chosen = availableGk[random.nextInt(availableGk.size)]
            usedQuestionIds.add(chosen.id)
            chosen
        }
    }

    /**
     * For Rule 4 ("🔄 Change / Skip Question"):
     * Draws another unused City/Country/GK question if available, or falls back to Math.
     * Rule 5 Guarantee: The skipped question's ID remains in usedQuestionIds, so it never recurs.
     */
    fun nextUnusedGkOrRandomQuestion(): AlarmQuestion {
        val availableGk = cityCountryGkPool.filter { !usedQuestionIds.contains(it.id) }
        return if (availableGk.isNotEmpty()) {
            val chosen = availableGk[random.nextInt(availableGk.size)]
            usedQuestionIds.add(chosen.id)
            chosen
        } else {
            generateUniqueMathQuestion()
        }
    }

    /**
     * Verifies if the user's typed or tapped answer is correct.
     */
    fun checkAnswer(question: AlarmQuestion, userAnswer: String): Boolean {
        val normalized = userAnswer.trim().lowercase()
        if (normalized.isEmpty()) return false

        // Check against direct accepted answers
        if (question.acceptedAnswers.any { it.trim().lowercase() == normalized }) {
            return true
        }

        // If user tapped a choice that contains or matches an accepted answer
        val lowerChoiceMatch = question.choices?.find { choice ->
            choice.trim().lowercase() == normalized
        }
        if (lowerChoiceMatch != null) {
            val lowerChoice = lowerChoiceMatch.lowercase()
            return question.acceptedAnswers.any { accepted ->
                lowerChoice.contains(accepted) || accepted.contains(lowerChoice)
            }
        }

        return false
    }

    fun getUsedCount(): Int = usedQuestionIds.size

    fun resetSession() {
        usedQuestionIds.clear()
    }

    fun getAvailableGkCount(): Int = cityCountryGkPool.count { !usedQuestionIds.contains(it.id) }
}
