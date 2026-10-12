package pl.zse.bydgoszcz.elektron.data.remote.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import pl.zse.bydgoszcz.elektron.domain.model.SchoolEventType
import java.time.LocalDate
import java.time.LocalTime

class IcalParserTest {

    @Test
    fun parseSimpleVulcanCalendar() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//VULCAN//UONET+//PL
            BEGIN:VEVENT
            UID:exam-math-123
            DTSTART;VALUE=DATE:20261015
            SUMMARY:Sprawdzian: Matematyka
            DESCRIPTION:Funkcja kwadratowa i równania.\nNauczyciel: Jan Kowalski
            LOCATION:sala 204
            END:VEVENT
            BEGIN:VEVENT
            UID:quiz-eng-456
            DTSTART:20261016T085000
            DTEND:20261016T093500
            SUMMARY:Kartkówka - Język angielski
            DESCRIPTION:Słownictwo unit 3
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcalParser.parse(ics)
        assertEquals(2, events.size)

        val mathExam = events[0]
        assertEquals("exam-math-123", mathExam.id)
        assertEquals(SchoolEventType.EXAM, mathExam.type)
        assertEquals("Matematyka", mathExam.subject)
        assertEquals(LocalDate.of(2026, 10, 15), mathExam.date)
        assertNull(mathExam.timeFrom)
        assertEquals("sala 204", mathExam.location)
        assertEquals("Funkcja kwadratowa i równania.\nNauczyciel: Jan Kowalski", mathExam.description)

        val engQuiz = events[1]
        assertEquals("quiz-eng-456", engQuiz.id)
        assertEquals(SchoolEventType.QUIZ, engQuiz.type)
        assertEquals("Język angielski", engQuiz.subject)
        assertEquals(LocalDate.of(2026, 10, 16), engQuiz.date)
        assertEquals(LocalTime.of(8, 50), engQuiz.timeFrom)
        assertEquals(LocalTime.of(9, 35), engQuiz.timeTo)
    }

    @Test
    fun parseFoldedLines() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:long-desc-1
            DTSTART;VALUE=DATE:20261020
            SUMMARY:Praca klasowa: Język 
             polski
            DESCRIPTION:Bardzo długi opis wydarzenia, który został podzielony
              przez serwer na wiele linii zgodnie z RFC 5545.
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val events = IcalParser.parse(ics)
        assertEquals(1, events.size)
        assertEquals("Praca klasowa: Język polski", events[0].title)
        assertEquals(SchoolEventType.EXAM, events[0].type)
        assertEquals("Język polski", events[0].subject)
        assertEquals(
            "Bardzo długi opis wydarzenia, który został podzielony przez serwer na wiele linii zgodnie z RFC 5545.",
            events[0].description
        )
    }

    @Test
    fun classifyDifferentEventTypes() {
        val (type1, sub1) = IcalParser.classifyEvent("Sprawdzian z historii", null)
        assertEquals(SchoolEventType.EXAM, type1)

        val (type2, sub2) = IcalParser.classifyEvent("Kartkówka: Chemia", null)
        assertEquals(SchoolEventType.QUIZ, type2)
        assertEquals("Chemia", sub2)

        val (type3, sub3) = IcalParser.classifyEvent("Zadanie domowe: Informatyka", null)
        assertEquals(SchoolEventType.HOMEWORK, type3)
        assertEquals("Informatyka", sub3)

        val (type4, _) = IcalParser.classifyEvent("Wyjście do teatru", null)
        assertEquals(SchoolEventType.OTHER, type4)
    }

    @Test
    fun parseUtcDateTimeWithZ() {
        val (date, time) = IcalParser.parseDateTime("20261015T080000Z") ?: throw AssertionError("Expected parsed result")
        assertEquals(LocalDate.of(2026, 10, 15), date)
        assertNotNull(time)
    }
}
