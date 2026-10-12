package pl.zse.bydgoszcz.elektron.data.remote.vulcan

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.zse.bydgoszcz.elektron.domain.model.SchoolEventType
import java.time.LocalDate

class VulcanApiClientTest {

    private val client = VulcanApiClient(OkHttpClient())

    @Test
    fun `parseExamsJson parses exams and quizzes with different deadline formats correctly`() {
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

        // Wywołujemy refleksją lub sprawdzamy zachowanie metod pomocniczych
        val parseMethod = VulcanApiClient::class.java.getDeclaredMethod("parseExamsJson", String::class.java).apply {
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
        assertEquals("Fizyka", testClass.subject)
        assertEquals(LocalDate.of(2026, 10, 22), testClass.date)
        assertTrue(testClass.description?.contains("Optyka i soczewki") == true)
    }
}
