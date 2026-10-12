package pl.zse.bydgoszcz.elektron.domain.model

import org.junit.Assert.*
import org.junit.Test
import pl.zse.bydgoszcz.elektron.data.remote.vulcan.EduVulcanWebAuthenticator
import pl.zse.bydgoszcz.elektron.data.remote.vulcan.VulcanApiClient

class GradeCalculatorTest {

    @Test
    fun parseNumericValue_handlesPolishGradeFormats() {
        assertEquals(5.0, GradeCalculator.parseNumericValue("5")!!, 0.001)
        assertEquals(4.5, GradeCalculator.parseNumericValue("4+")!!, 0.001)
        assertEquals(3.75, GradeCalculator.parseNumericValue("4-")!!, 0.001)
        assertEquals(4.5, GradeCalculator.parseNumericValue("+4")!!, 0.001)
        assertEquals(3.75, GradeCalculator.parseNumericValue("-4")!!, 0.001)
        assertEquals(6.0, GradeCalculator.parseNumericValue("cel")!!, 0.001)
        assertEquals(5.0, GradeCalculator.parseNumericValue("bdb")!!, 0.001)
        assertEquals(1.0, GradeCalculator.parseNumericValue("ndst")!!, 0.001)
        assertNull(GradeCalculator.parseNumericValue("np"))
        assertNull(GradeCalculator.parseNumericValue("bz"))
        assertNull(GradeCalculator.parseNumericValue("+"))
    }

    @Test
    fun summarizeBySubject_calculatesWeightedAverageAndIgnoresSuperseded() {
        val grades = listOf(
            StudentGrade(
                id = "1",
                subject = "Matematyka",
                value = "5",
                weight = 3.0,
                category = "Sprawdzian",
                description = "Funkcja kwadratowa",
                teacher = "Jan Kowalski",
                dateText = "2026-10-01",
                periodNumber = 1,
                isActivePeriod = true,
                isSuperseded = false
            ),
            StudentGrade(
                id = "2",
                subject = "Matematyka",
                value = "3",
                weight = 1.0,
                category = "Kartkówka",
                description = "Wektory",
                teacher = "Jan Kowalski",
                dateText = "2026-10-05",
                periodNumber = 1,
                isActivePeriod = true,
                isSuperseded = false
            ),
            StudentGrade(
                id = "3",
                subject = "Matematyka",
                value = "1",
                weight = 3.0,
                category = "Sprawdzian (poprawiona)",
                description = "Poprawiona ocena",
                teacher = "Jan Kowalski",
                dateText = "2026-09-20",
                periodNumber = 1,
                isActivePeriod = true,
                isSuperseded = true
            )
        )

        val summaries = GradeCalculator.summarizeBySubject(grades, periodNumber = 1)
        assertEquals(1, summaries.size)
        val math = summaries.first()
        assertEquals("Matematyka", math.subject)
        // (5*3 + 3*1) / (3 + 1) = 18 / 4 = 4.50
        assertEquals(4.5, math.calculatedAverage!!, 0.001)
        assertEquals(4.5, math.effectiveAverage!!, 0.001)

        // Symulacja dodania oceny 2 o wadze 2: (18 + 4) / (4 + 2) = 22 / 6 = 3.666...
        val simulated = GradeCalculator.simulateAverage(math, "2", 2.0)!!
        assertEquals(3.666, simulated, 0.01)
    }

    @Test
    fun parseEduVulcanGradesJson_extractsSubjectsAndGrades() {
        val json = """
            [
              {
                "Przedmiot": "Programowanie obiektowe",
                "Srednia": 4.75,
                "OcenaProponowana": "5",
                "OcenaKoncowa": null,
                "OcenyCzastkowe": [
                  {
                    "Id": 901,
                    "Wpis": "5",
                    "Waga": 3.0,
                    "DataOceny": "2026-10-10T00:00:00",
                    "Nauczyciel": "mgr inż. Adam Nowak",
                    "Kolumna": {
                      "Nazwa": "Sprawdzian - klasy i dziedziczenie",
                      "Kod": "SPR",
                      "Waga": 3.0
                    }
                  },
                  {
                    "Id": 902,
                    "Wpis": "4+",
                    "DataModyfikacji": "2026-10-11",
                    "Kategoria": "Kartkówka",
                    "Waga": 2.0
                  }
                ]
              }
            ]
        """.trimIndent()

        val parsed = VulcanApiClient.parseEduVulcanGradesJson(json, periodNumber = 1, isActivePeriod = true)
        assertEquals(2, parsed.size)
        assertEquals("Programowanie obiektowe", parsed[0].subject)
        assertEquals("5", parsed[0].value)
        assertEquals(3.0, parsed[0].weight, 0.001)
        assertEquals("SPR", parsed[0].category)
        assertEquals("Sprawdzian - klasy i dziedziczenie", parsed[0].description)
        assertEquals("mgr inż. Adam Nowak", parsed[0].teacher)
        assertEquals("2026-10-10", parsed[0].dateText)
        assertEquals(4.75, parsed[0].apiSubjectAverage!!, 0.001)
        assertEquals("5", parsed[0].proposedGrade)
        assertEquals("4+", parsed[1].value)
        assertEquals(2.0, parsed[1].weight, 0.001)
    }

    @Test
    fun parseProfileTiles_parsesValidEntries() {
        val payload = "Jan Kowalski - Uczeń ZSE||https://dziennik-uczen.vulcan.net.pl/bydgoszcz/012345\nAnna Kowalska - Uczeń||https://uczen.eduvulcan.pl/bydgoszcz/999999"
        val tiles = EduVulcanWebAuthenticator.parseProfileTiles(payload)
        assertEquals(2, tiles.size)
        assertEquals("Jan Kowalski - Uczeń ZSE", tiles[0].label)
        assertEquals("https://dziennik-uczen.vulcan.net.pl/bydgoszcz/012345", tiles[0].url)
        assertEquals("Anna Kowalska - Uczeń", tiles[1].label)
    }
}
