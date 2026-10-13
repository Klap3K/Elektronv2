package pl.zse.bydgoszcz.elektron.data.remote.vulcan

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.zse.bydgoszcz.elektron.domain.model.SchoolEventType
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VulcanApiClientTest {

    private val client = VulcanApiClient(OkHttpClient())

    @Test
    fun `parseEduVulcanEventsJson parses exams, class tests, quizzes and homework without ID collision or MinValue date bug`() {
        val sampleTermJson = """
        [
          {
            "id": 501,
            "typ": 1,
            "przedmiotNazwa": "Matematyka",
            "data": "2026-10-20T00:00:00",
            "terminOdpowiedzi": "0001-01-01T00:00:00",
            "opis": "Równania i nierówności kwadratowe",
            "prowadzacy": "Jan Kowalski"
          },
          {
            "id": 502,
            "typ": 3,
            "przedmiotNazwa": "Język polski",
            "data": "2026-10-22T00:00:00",
            "terminOdpowiedzi": "0001-01-01T00:00:00",
            "temat": "Praca klasowa z epoki Romantyzmu",
            "nauczyciel": "Anna Nowak"
          },
          {
            "id": 503,
            "typ": 2,
            "przedmiotNazwa": "Język angielski",
            "data": "2026-10-23T00:00:00",
            "opis": "Vocabulary Unit 3"
          },
          {
            "id": 501,
            "typ": 4,
            "przedmiotNazwa": "Fizyka",
            "data": "2026-10-18T00:00:00",
            "terminOdpowiedzi": "2026-10-24T00:00:00",
            "opis": "Zadania 1-5 str. 44"
          }
        ]
        """.trimIndent()

        val events = client.parseEduVulcanEventsJson(sampleTermJson)!!

        // Wszystkie 4 wpisy powinny zostać zachowane (nawet gdy Sprawdzian i Zadanie domowe mają to samo id=501)
        assertEquals(4, events.size)

        val exam = events[0]
        assertEquals(SchoolEventType.EXAM, exam.type)
        assertEquals("Sprawdzian", exam.badgeLabel())
        assertEquals("Matematyka", exam.subject)
        assertEquals(LocalDate.of(2026, 10, 20), exam.date)
        assertTrue(exam.description?.contains("Równania i nierówności") == true)

        val classTest = events[1]
        assertEquals(SchoolEventType.EXAM, classTest.type)
        assertEquals("Praca klasowa", classTest.badgeLabel())
        assertEquals("Język polski", classTest.subject)
        assertEquals(LocalDate.of(2026, 10, 22), classTest.date)
        assertTrue(classTest.description?.contains("Romantyzmu") == true)

        val quiz = events[2]
        assertEquals(SchoolEventType.QUIZ, quiz.type)
        assertEquals("Kartkówka", quiz.badgeLabel())
        assertEquals("Język angielski", quiz.subject)
        assertEquals(LocalDate.of(2026, 10, 23), quiz.date)

        val homework = events[3]
        assertEquals(SchoolEventType.HOMEWORK, homework.type)
        assertEquals("Zad. domowe", homework.badgeLabel())
        assertEquals("Fizyka", homework.subject)
        assertEquals(LocalDate.of(2026, 10, 24), homework.date)

        assertNotEquals(exam.id, homework.id)
    }

    @Test
    fun `parseLegacyOrGroupedExamsJson parses exams and quizzes with different deadline formats correctly`() {
        val sampleResponseJson = """
        {
          "Status": 0,
          "Envelope": [
            {
              "Id": 101,
              "Subject": { "Id": 1, "Name": "Matematyka", "Code": "MAT" },
              "Type": "Sprawdzian",
              "Content": "Funkcja kwadratowa i postać kanoniczna",
              "Deadline": { "Date": "2026-10-16", "Time": "10:35:00" },
              "Creator": { "DisplayName": "Anna Kowalska" }
            },
            {
              "Id": 102,
              "Subject": { "Id": 2, "Name": "Język angielski", "Code": "ANG" },
              "Type": "Kartkówka",
              "Content": "Słownictwo unit 4: Technology",
              "Deadline": "2026-10-19",
              "Teacher": { "DisplayName": "John Smith" }
            },
            {
              "Id": 103,
              "Subject": "Fizyka",
              "Type": "Praca klasowa",
              "Description": "Optyka i soczewki",
              "Deadline": { "Date": "2026-10-22" },
              "Creator": "Piotr Nowak"
            }
          ]
        }
        """.trimIndent()

        val parseMethod = VulcanApiClient::class.java.getDeclaredMethod("parseLegacyOrGroupedExamsJson", String::class.java).apply {
            isAccessible = true
        }

        @Suppress("UNCHECKED_CAST")
        val events = parseMethod.invoke(client, sampleResponseJson) as List<pl.zse.bydgoszcz.elektron.domain.model.SchoolEvent>

        assertEquals(3, events.size)

        // 1. Sprawdzian
        val exam = events[0]
        assertEquals("vulcan_101", exam.id)
        assertEquals(SchoolEventType.EXAM, exam.type)
        assertEquals("Matematyka", exam.subject)
        assertEquals(LocalDate.of(2026, 10, 16), exam.date)
        assertNotNull(exam.timeFrom)
        assertEquals(10, exam.timeFrom?.hour)
        assertEquals(35, exam.timeFrom?.minute)
        assertTrue(exam.description?.contains("Anna Kowalska") == true)

        // 2. Kartkówka
        val quiz = events[1]
        assertEquals("vulcan_102", quiz.id)
        assertEquals(SchoolEventType.QUIZ, quiz.type)
        assertEquals("Język angielski", quiz.subject)
        assertEquals(LocalDate.of(2026, 10, 19), quiz.date)
        assertTrue(quiz.description?.contains("John Smith") == true)

        // 3. Praca klasowa
        val testClass = events[2]
        assertEquals("vulcan_103", testClass.id)
        assertEquals(SchoolEventType.EXAM, testClass.type)
        assertEquals("Praca klasowa", testClass.badgeLabel())
        assertEquals("Fizyka", testClass.subject)
        assertEquals(LocalDate.of(2026, 10, 22), testClass.date)
        assertTrue(testClass.description?.contains("Optyka i soczewki") == true)
    }
}
