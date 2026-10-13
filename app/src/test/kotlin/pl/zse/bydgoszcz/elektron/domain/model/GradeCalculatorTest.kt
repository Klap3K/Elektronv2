package pl.zse.bydgoszcz.elektron.domain.model

import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.zse.bydgoszcz.elektron.data.remote.vulcan.EduVulcanWebAuthenticator
import pl.zse.bydgoszcz.elektron.data.remote.vulcan.VulcanApiClient

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GradeCalculatorTest {

    @Test
    fun parseNumericGrade_handlesPolishGradeFormats() {
        assertEquals(5.0, GradeCalculator.parseNumericGrade("5")!!, 0.001)
        assertEquals(4.5, GradeCalculator.parseNumericGrade("4+")!!, 0.001)
        assertEquals(3.75, GradeCalculator.parseNumericGrade("4-")!!, 0.001)
        assertEquals(6.0, GradeCalculator.parseNumericGrade("cel")!!, 0.001)
        assertEquals(5.0, GradeCalculator.parseNumericGrade("bdb")!!, 0.001)
        assertEquals(1.0, GradeCalculator.parseNumericGrade("ndst")!!, 0.001)
        assertNull(GradeCalculator.parseNumericGrade("np"))
        assertNull(GradeCalculator.parseNumericGrade("bz"))
        assertNull(GradeCalculator.parseNumericGrade("+"))
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
        assertEquals(4.5, math.average!!, 0.001)
        assertEquals(4.5, GradeCalculator.overallAverage(summaries)!!, 0.001)
    }

    @Test
    fun parseEduVulcanGradesJson_extractsSubjectsAndGrades() {
        val json = """
            {
              "ustawienia": { "isSredniaAndPunkty": true },
              "ocenyPrzedmioty": [
                {
                  "przedmiotNazwa": "Programowanie obiektowe",
                  "srednia": 4.75,
                  "proponowanaOcenaOkresowa": "5",
                  "ocenaOkresowa": null,
                  "kolumnyOcenyCzastkowe": [
                    {
                      "idKolumny": 10,
                      "kategoriaKolumny": "SPR",
                      "nazwaKolumny": "Sprawdzian - klasy i dziedziczenie",
                      "waga": 3.0,
                      "oceny": [
                        {
                          "wpis": "5",
                          "waga": 3.0,
                          "dataOceny": "2026-10-10",
                          "nauczycielImieNazwisko": "mgr inż. Adam Nowak"
                        }
                      ]
                    },
                    {
                      "idKolumny": 11,
                      "kategoriaKolumny": "KART",
                      "nazwaKolumny": "Kartkówka",
                      "waga": 2.0,
                      "oceny": [
                        {
                          "wpis": "4+",
                          "waga": 2.0,
                          "dataOceny": "2026-10-11"
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val client = VulcanApiClient(OkHttpClient())
        val parsed = client.parseEduVulcanGradesJson(
            jsonStr = json,
            periodId = "101",
            periodNumber = 1,
            isActivePeriod = true,
            nowSeconds = 1791840000L
        )
        assertEquals(2, parsed.size)
        assertEquals("Programowanie obiektowe", parsed[0].subject)
        assertEquals("5", parsed[0].value)
        assertEquals(3.0, parsed[0].weight, 0.001)
        assertEquals("SPR", parsed[0].category)
        assertEquals("Sprawdzian - klasy i dziedziczenie", parsed[0].description)
        assertEquals("mgr inż. Adam Nowak", parsed[0].teacher)
        assertEquals("10.10.2026", parsed[0].dateText)
        assertEquals(4.75, parsed[0].apiSubjectAverage!!, 0.001)
        assertEquals("5", parsed[0].proposedGrade)
        assertEquals("4+", parsed[1].value)
        assertEquals(2.0, parsed[1].weight, 0.001)
    }

    @Test
    fun parseProfileTiles_parsesValidEntries() {
        val payload = """
            [
              {"title": "Jan Kowalski - Uczeń ZSE", "subtitle": "ZSE Bydgoszcz", "url": "https://dziennik-uczen.vulcan.net.pl/bydgoszcz/012345"},
              {"title": "Anna Kowalska - Uczeń", "subtitle": "SP 1", "url": "https://uczen.eduvulcan.pl/bydgoszcz/999999"}
            ]
        """.trimIndent()
        val tiles = EduVulcanWebAuthenticator.parseProfileTiles(payload)
        assertEquals(2, tiles.size)
        assertEquals("Jan Kowalski - Uczeń ZSE", tiles[0].title)
        assertEquals("ZSE Bydgoszcz", tiles[0].subtitle)
        assertEquals("https://dziennik-uczen.vulcan.net.pl/bydgoszcz/012345", tiles[0].url)
        assertEquals("Anna Kowalska - Uczeń", tiles[1].title)
    }
}
