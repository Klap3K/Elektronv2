package pl.zse.bydgoszcz.elektron.data.remote.parser

import pl.zse.bydgoszcz.elektron.domain.model.SchoolEvent
import pl.zse.bydgoszcz.elektron.domain.model.SchoolEventType
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

object IcalParser {

    private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HHmmss")

    /**
     * Parsuje treść pliku iCalendar (.ics) i zwraca listę wydarzeń szkolnych.
     */
    fun parse(content: String): List<SchoolEvent> {
        val unfolded = unfoldLines(content)
        val events = mutableListOf<SchoolEvent>()

        var inEvent = false
        var currentProperties = mutableMapOf<String, String>()

        for (line in unfolded) {
            val trimmed = line.trim()
            if (trimmed.equals("BEGIN:VEVENT", ignoreCase = true)) {
                inEvent = true
                currentProperties = mutableMapOf()
            } else if (trimmed.equals("END:VEVENT", ignoreCase = true)) {
                if (inEvent) {
                    parseEvent(currentProperties)?.let { events.add(it) }
                    inEvent = false
                }
            } else if (inEvent && trimmed.isNotEmpty()) {
                val colonIdx = trimmed.indexOf(':')
                if (colonIdx > 0) {
                    val rawKey = trimmed.substring(0, colonIdx)
                    val rawValue = trimmed.substring(colonIdx + 1)
                    val keyName = rawKey.substringBefore(';').uppercase()
                    currentProperties[rawKey] = unescape(rawValue)
                    // Zapisz też pod samą nazwą klucza bez parametrów (np. DTSTART zamiast DTSTART;VALUE=DATE)
                    if (!currentProperties.containsKey(keyName)) {
                        currentProperties[keyName] = unescape(rawValue)
                    }
                }
            }
        }

        return events.sortedWith(compareBy({ it.date }, { it.timeFrom ?: LocalTime.MIN }))
    }

    private fun unfoldLines(content: String): List<String> {
        val rawLines = content.lines()
        val result = mutableListOf<String>()
        val sb = StringBuilder()

        for (line in rawLines) {
            if (line.startsWith(" ") || line.startsWith("\t")) {
                if (sb.isNotEmpty()) {
                    sb.append(line.substring(1))
                }
            } else {
                if (sb.isNotEmpty()) {
                    result.add(sb.toString())
                    sb.setLength(0)
                }
                sb.append(line)
            }
        }
        if (sb.isNotEmpty()) {
            result.add(sb.toString())
        }
        return result
    }

    private fun unescape(value: String): String {
        return value
            .replace("\\n", "\n", ignoreCase = true)
            .replace("\\,", ",")
            .replace("\\;", ";")
            .replace("\\\\", "\\")
    }

    private fun parseEvent(props: Map<String, String>): SchoolEvent? {
        val summary = props["SUMMARY"]?.trim() ?: return null
        val description = props["DESCRIPTION"]?.trim()
        val location = props["LOCATION"]?.trim()
        val uid = props["UID"]?.trim() ?: UUID.randomUUID().toString()

        // Odczyt daty rozpoczęcia
        val dtStartEntry = props.entries.firstOrNull { it.key.startsWith("DTSTART", ignoreCase = true) }
            ?: return null
        val dtStartRaw = dtStartEntry.value.trim()

        val parsedStart = parseDateTime(dtStartRaw) ?: return null
        val date = parsedStart.first
        val timeFrom = parsedStart.second

        val dtEndEntry = props.entries.firstOrNull { it.key.startsWith("DTEND", ignoreCase = true) }
        val timeTo = dtEndEntry?.value?.trim()?.let { parseDateTime(it)?.second }

        val (type, subject) = classifyEvent(summary, description)

        return SchoolEvent(
            id = uid,
            title = summary,
            description = description,
            type = type,
            subject = subject,
            date = date,
            timeFrom = timeFrom,
            timeTo = timeTo,
            location = location,
            rawSummary = summary
        )
    }

    /**
     * Parsuje ciąg znaków daty i opcjonalnego czasu z formatu iCalendar:
     * - 20261015 (sama data)
     * - 20261015T080000 (data i czas lokalny)
     * - 20261015T080000Z (data i czas UTC)
     */
    fun parseDateTime(raw: String): Pair<LocalDate, LocalTime?>? {
        val clean = raw.trim()
        if (clean.length < 8) return null

        val isUtc = clean.endsWith("Z", ignoreCase = true)
        val datePart = clean.substring(0, 8)
        val date = runCatching { LocalDate.parse(datePart, DATE_FORMATTER) }.getOrNull() ?: return null

        if (clean.length >= 15 && clean[8] == 'T') {
            val timePart = clean.substring(9, 15)
            val time = runCatching { LocalTime.parse(timePart, TIME_FORMATTER) }.getOrNull()
            if (time != null && isUtc) {
                val utcDateTime = java.time.LocalDateTime.of(date, time).atZone(java.time.ZoneOffset.UTC)
                val localDateTime = utcDateTime.withZoneSameInstant(java.time.ZoneId.systemDefault())
                return localDateTime.toLocalDate() to localDateTime.toLocalTime()
            }
            return date to time
        }

        return date to null
    }

    /**
     * Klasyfikuje wydarzenie na podstawie tytułu i opisu oraz próbuje wyodrębnić nazwę przedmiotu.
     */
    fun classifyEvent(summary: String, description: String?): Pair<SchoolEventType, String?> {
        val combined = if (description != null) "$summary $description".lowercase() else summary.lowercase()

        val type = when {
            combined.contains("sprawdzian") || combined.contains("praca klasowa") || combined.contains("test") -> SchoolEventType.EXAM
            combined.contains("kartkówk") || combined.contains("kartkowk") -> SchoolEventType.QUIZ
            combined.contains("zadanie domowe") || combined.contains("praca domowa") -> SchoolEventType.HOMEWORK
            else -> SchoolEventType.OTHER
        }

        val subject = extractSubject(summary, description)
        return type to subject
    }

    private fun extractSubject(summary: String, description: String?): String? {
        // VULCAN często generuje SUMMARY w postaci:
        // "Sprawdzian: Matematyka"
        // "Kartkówka - Język polski"
        // "Matematyka - sprawdzian"
        val delimiters = listOf(":", " - ", " – ")
        for (delimiter in delimiters) {
            if (summary.contains(delimiter)) {
                val parts = summary.split(delimiter, limit = 2)
                val part0 = parts[0].trim()
                val part1 = parts[1].trim()

                val isKeyword0 = isEventKeyword(part0)
                val isKeyword1 = isEventKeyword(part1)

                if (isKeyword0 && !isKeyword1) {
                    return cleanSubjectCandidate(part1)
                } else if (!isKeyword0 && isKeyword1) {
                    return cleanSubjectCandidate(part0)
                }
            }
        }

        // Sprawdź czy w opisie jest wzmianka o przedmiocie (np. "Przedmiot: Fizyka")
        if (description != null) {
            val descLines = description.lines()
            for (line in descLines) {
                val lower = line.lowercase().trim()
                if (lower.startsWith("przedmiot:") || lower.startsWith("przedmiot -")) {
                    val candidate = line.substringAfter(':').substringAfter('-').trim()
                    if (candidate.isNotBlank()) return cleanSubjectCandidate(candidate)
                }
            }
        }

        return null
    }

    private fun isEventKeyword(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower in listOf(
            "sprawdzian", "kartkówka", "kartkowka", "praca klasowa",
            "test", "zadanie domowe", "praca domowa", "terminarz"
        )
    }

    private fun cleanSubjectCandidate(text: String): String {
        return text
            .replace(Regex("\\(gr\\.?\\s*\\d+\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(sala\\s*[^)]+\\)", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}
