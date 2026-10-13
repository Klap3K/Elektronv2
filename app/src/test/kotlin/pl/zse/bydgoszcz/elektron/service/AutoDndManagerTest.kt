package pl.zse.bydgoszcz.elektron.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoDndManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Reset prefs
        context.getSharedPreferences("elektron_auto_dnd", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun disabledDoesNotSilence() {
        val active = AutoDndManager.evaluate(context, enabled = false, isLessonOngoing = true)
        assertFalse(active)
        assertFalse(AutoDndManager.isSilenced(context))
    }

    @Test
    fun enabledDuringLessonSilencesPhone() {
        val active = AutoDndManager.evaluate(context, enabled = true, isLessonOngoing = true)
        assertTrue(active)
        assertTrue(AutoDndManager.isSilenced(context))
    }

    @Test
    fun lessonEndingRestoresSound() {
        // Trwa lekcja - wycisza
        AutoDndManager.evaluate(context, enabled = true, isLessonOngoing = true)
        assertTrue(AutoDndManager.isSilenced(context))

        // Koniec lekcji (przerwa) - przywraca
        val active = AutoDndManager.evaluate(context, enabled = true, isLessonOngoing = false)
        assertFalse(active)
        assertFalse(AutoDndManager.isSilenced(context))
    }

    @Test
    fun disablingOptionWhileLessonOngoingRestoresSound() {
        // Trwa lekcja - wycisza
        AutoDndManager.evaluate(context, enabled = true, isLessonOngoing = true)
        assertTrue(AutoDndManager.isSilenced(context))

        // Użytkownik wyłącza opcję w ustawieniach - natychmiast przywraca
        val active = AutoDndManager.evaluate(context, enabled = false, isLessonOngoing = true)
        assertFalse(active)
        assertFalse(AutoDndManager.isSilenced(context))
    }
}
