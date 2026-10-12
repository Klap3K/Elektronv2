package pl.zse.bydgoszcz.elektron.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class UserActivityTest {

    @Test
    fun activityCategory_fromString() {
        assertEquals(ActivityCategory.EXAM, ActivityCategory.fromString("EXAM"))
        assertEquals(ActivityCategory.TRAINING, ActivityCategory.fromString("training"))
        assertEquals(ActivityCategory.OTHER, ActivityCategory.fromString("nieznana"))
    }

    @Test
    fun timeRangeText_formattedCorrectly() {
        val act = UserActivity(
            id = "test-1",
            title = "Test",
            description = null,
            category = ActivityCategory.STUDY,
            date = LocalDate.of(2026, 10, 14),
            timeFrom = LocalTime.of(16, 0),
            timeTo = LocalTime.of(17, 30),
            isCompleted = false,
            createdAtEpochSeconds = 123456789L
        )
        assertEquals("16:00–17:30", act.timeRangeText)
        assertFalse(act.isCompleted)
    }
}
