package pl.zse.bydgoszcz.elektron.domain.model

import java.time.LocalDate
import java.time.LocalTime

enum class SchoolEventType {
    EXAM,       // Sprawdzian / Praca klasowa / Test
    QUIZ,       // Kartkówka
    HOMEWORK,   // Zadanie domowe / Praca domowa
    OTHER;      // Inne wydarzenie szkolne

    val isAssessment: Boolean
        get() = this == EXAM || this == QUIZ
}

data class SchoolEvent(
    val id: String,
    val title: String,
    val description: String?,
    val type: SchoolEventType,
    val subject: String?,
    val date: LocalDate,
    val timeFrom: LocalTime?,
    val timeTo: LocalTime?,
    val location: String?,
    val rawSummary: String
) {
    /** Krótka etykieta do plakietki w planie lekcji. */
    fun badgeLabel(): String = when (type) {
        SchoolEventType.EXAM -> {
            val combined = "$title $rawSummary".lowercase()
            if (combined.contains("praca klasowa") || combined.contains("klasówk") || combined.contains("klasowk")) {
                "Praca klasowa"
            } else {
                "Sprawdzian"
            }
        }
        SchoolEventType.QUIZ -> "Kartkówka"
        SchoolEventType.HOMEWORK -> "Zad. domowe"
        SchoolEventType.OTHER -> "Wydarzenie"
    }

    /** Czy wydarzenie pasuje do podanej lekcji (po przedmiocie lub godzinie). */
    fun matchesLesson(lesson: Lesson): Boolean {
        if (lesson.date != date) return false

        // Sprawdzenie po przedmiocie, jeśli jest znany
        if (!subject.isNullOrBlank()) {
            val lessonSubjects = lesson.groups.mapNotNull { it.subject }
            if (lessonSubjects.any { subjectsMatch(subject, it) }) {
                return true
            }
        }

        // Sprawdzenie po czasie lekcji, jeśli godziny są określone
        if (timeFrom != null && timeTo != null) {
            return !(timeTo <= lesson.timeFrom || timeFrom >= lesson.timeTo)
        }

        return false
    }

    companion object {
        private val STOP_WORDS = setOf("i", "w", "z", "ze", "do", "na", "od", "dla", "oraz", "po", "o", "u", "r", "p")
        private val LEVEL_PREFIX_REGEX = Regex("^(?:[rp][_.\\s]+|rozsz\\.?\\s*|podst\\.?\\s+)(.+)$", RegexOption.IGNORE_CASE)

        fun normalizeSubject(name: String): String =
            foldPolish(stripGroupAndLevel(name).lowercase())
                .replace(Regex("[^a-z0-9]"), "")
                .trim()

        /**
         * Porównuje pełną nazwę przedmiotu z eduVULCAN (np. "Język angielski", "Pracownia programowania obiektowego")
         * ze skrótem z planu Optivum ZSE Bydgoszcz (np. "j.angielski-1/2", "ang-1/2", "prac.prog.-1/2", "r_matematyka").
         */
        fun subjectsMatch(eventSubject: String, lessonRawSubject: String): Boolean {
            val cleanEvent = stripGroupAndLevel(eventSubject)
            val cleanLesson = stripGroupAndLevel(lessonRawSubject)
            if (cleanEvent.isBlank() || cleanLesson.isBlank()) return false

            val keyEvent = canonicalSubjectKey(cleanEvent)
            val keyLesson = canonicalSubjectKey(cleanLesson)
            if (keyEvent != null && keyLesson != null) {
                return keyEvent == keyLesson
            }
            if (keyEvent != null || keyLesson != null) {
                // Jeden z przedmiotów to znany przedmiot ogólny (np. Język polski), a drugi nie pasuje do tego klucza
                return false
            }

            val normEvent = normalizeSubject(cleanEvent)
            val normLesson = normalizeSubject(cleanLesson)
            if (normEvent.isEmpty() || normLesson.isEmpty()) return false

            if (normEvent == normLesson) return true
            if (minOf(normEvent.length, normLesson.length) >= 5 &&
                (normEvent.contains(normLesson) || normLesson.contains(normEvent))
            ) {
                return true
            }

            // Dopasowanie po rdzeniach słów dla skrótów przedmiotów zawodowych (np. "prac.prog." <-> "Pracownia programowania")
            val eventTokens = tokenize(cleanEvent)
            val lessonTokens = tokenize(cleanLesson)
            if (eventTokens.isEmpty() || lessonTokens.isEmpty()) return false

            val (shorter, longer) = if (lessonTokens.size <= eventTokens.size) {
                lessonTokens to eventTokens
            } else {
                eventTokens to lessonTokens
            }

            if (shorter.size >= 2 && shorter.all { it.length >= 2 }) {
                val usedIndices = mutableSetOf<Int>()
                val allMatched = shorter.all { shortTok ->
                    val idx = longer.indexOfFirstIndexed { i, longTok ->
                        i !in usedIndices && (longTok.startsWith(shortTok) || shortTok.startsWith(longTok))
                    }
                    if (idx >= 0) {
                        usedIndices.add(idx)
                        true
                    } else {
                        false
                    }
                }
                if (allMatched) return true
            }

            return false
        }

        private inline fun <T> List<T>.indexOfFirstIndexed(predicate: (Int, T) -> Boolean): Int {
            for (i in indices) {
                if (predicate(i, this[i])) return i
            }
            return -1
        }

        private fun stripGroupAndLevel(raw: String): String {
            val base = LessonGroups.parse(raw)?.base ?: raw.trim()
            val trimmed = base.trim()
            val match = LEVEL_PREFIX_REGEX.matchEntire(trimmed)
            return (match?.groupValues?.get(1) ?: trimmed).trim()
        }

        private fun tokenize(text: String): List<String> =
            foldPolish(text.lowercase())
                .split(Regex("[^a-z0-9]+"))
                .map { it.trim() }
                .filter { it.length >= 2 && it !in STOP_WORDS }

        private fun foldPolish(s: String): String = buildString(s.length) {
            for (ch in s) {
                append(
                    when (ch) {
                        'ą' -> 'a'
                        'ć' -> 'c'
                        'ę' -> 'e'
                        'ł' -> 'l'
                        'ń' -> 'n'
                        'ó' -> 'o'
                        'ś' -> 's'
                        'ź', 'ż' -> 'z'
                        else -> ch
                    }
                )
            }
        }

        private fun canonicalSubjectKey(raw: String): String? {
            val folded = foldPolish(raw.lowercase()).trim()
            val compact = folded.replace(Regex("[^a-z0-9]"), "")
            val tokens = tokenize(raw)

            return when {
                // Języki obce i polski
                compact in setOf("jpolski", "jpol", "polski", "jezykpolski") ||
                    ("polski" in tokens && tokens.all { it in setOf("jezyk", "j", "polski") }) -> "jezyk_polski"

                compact in setOf("jangielski", "jang", "ang", "angielski", "jezykangielski", "jangzaw", "angzaw", "jezykangielskizawodowy") ||
                    (tokens.any { it.startsWith("ang") } && tokens.all { it in setOf("jezyk", "j", "ang", "angielski", "zaw", "zawodowy") }) -> "jezyk_angielski"

                compact in setOf("jniemiecki", "jniem", "niem", "niemiecki", "jezykniemiecki") ||
                    (tokens.any { it.startsWith("niem") } && tokens.all { it in setOf("jezyk", "j", "niem", "niemiecki", "zaw", "zawodowy") }) -> "jezyk_niemiecki"

                compact in setOf("jhiszpanski", "hiszp", "hiszpanski", "jezykhiszpanski") -> "jezyk_hiszpanski"
                compact in setOf("jrosyjski", "ros", "rosyjski", "jezykrosyjski") -> "jezyk_rosyjski"
                compact in setOf("jfrancuski", "franc", "francuski", "jezykfrancuski") -> "jezyk_francuski"

                // Przedmioty ogólnokształcące
                compact in setOf("matematyka", "mat", "matem") -> "matematyka"
                compact in setOf("fizyka", "fiz") -> "fizyka"
                compact in setOf("chemia", "chem") -> "chemia"
                compact in setOf("biologia", "biol") -> "biologia"
                compact in setOf("geografia", "geogr", "geo") -> "geografia"
                compact in setOf("informatyka", "inf", "inform") -> "informatyka"

                compact in setOf("wf", "wychfiz", "wychowaniefizyczne") ||
                    (tokens.size == 2 && tokens[0].startsWith("wych") && tokens[1].startsWith("fiz")) -> "wf"

                compact in setOf("rel", "relig", "religia") -> "religia"
                compact in setOf("et", "etyka") -> "etyka"

                compact in setOf("hit", "histiteraz", "historiaiterazniejszosc") ||
                    (tokens.any { it.startsWith("hist") } && tokens.any { it.startsWith("teraz") }) -> "hit"

                compact in setOf("historia", "hist") -> "historia"

                compact in setOf("wos", "wiedzaospoleczenstwie") ||
                    (tokens.any { it.startsWith("wiedz") } && tokens.any { it.startsWith("spol") }) -> "wos"

                compact in setOf("edb", "edukacjadlabezpieczenstwa") ||
                    (tokens.any { it.startsWith("eduk") } && tokens.any { it.startsWith("bezp") }) -> "edb"

                compact in setOf("biz", "biznesizarz", "biznesizarzadzanie") ||
                    (tokens.any { it.startsWith("biznes") } && tokens.any { it.startsWith("zarz") }) -> "biz"

                compact in setOf("pp", "podstprzeds", "podstawyprzedsiebiorczosci", "przedsiebiorczosc") -> "pp"

                compact in setOf("gw", "godzwych", "zajzwych", "zajeciazwychowawca", "godzinawychowawcza") ||
                    tokens.any { it.startsWith("wychowaw") } -> "godzina_wychowawcza"

                compact in setOf("filozofia", "filoz") -> "filozofia"
                compact in setOf("plastyka", "plast") -> "plastyka"
                compact in setOf("muzyka", "muz") -> "muzyka"
                compact in setOf("wdz", "wychdozyc", "wychowaniedozyciawrodzinie", "edukacjazdrowotna", "edzdrow") -> "wdz"

                else -> null
            }
        }
    }
}
