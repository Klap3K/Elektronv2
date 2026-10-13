package pl.zse.bydgoszcz.elektron.work

import pl.zse.bydgoszcz.elektron.domain.model.ReminderSettings
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.zse.bydgoszcz.elektron.data.local.AppDatabase
import pl.zse.bydgoszcz.elektron.data.remote.dto.ArchiveItemDto
import pl.zse.bydgoszcz.elektron.data.remote.dto.ClassListItemDto
import pl.zse.bydgoszcz.elektron.data.remote.dto.LessonCellDto
import pl.zse.bydgoszcz.elektron.data.remote.dto.LessonGroupDto
import pl.zse.bydgoszcz.elektron.data.remote.dto.RssItemDto
import pl.zse.bydgoszcz.elektron.data.remote.dto.SubstitutionDto
import pl.zse.bydgoszcz.elektron.data.remote.dto.TimetableDto
import pl.zse.bydgoszcz.elektron.data.remote.sources.AnnouncementsSource
import pl.zse.bydgoszcz.elektron.data.remote.sources.SubstitutionsSource
import pl.zse.bydgoszcz.elektron.data.remote.sources.TimetableSource
import pl.zse.bydgoszcz.elektron.data.repository.NotificationsRepositoryImpl
import pl.zse.bydgoszcz.elektron.data.repository.inMemoryDb
import pl.zse.bydgoszcz.elektron.domain.model.Announcement
import pl.zse.bydgoszcz.elektron.domain.model.Substitution
import pl.zse.bydgoszcz.elektron.testutil.FakeSettings
import pl.zse.bydgoszcz.elektron.domain.model.ReminderMode
import android.app.AlarmManager
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.robolectric.Shadows.shadowOf
import pl.zse.bydgoszcz.elektron.testutil.TestCoordinator
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * SyncWorker od początku do końca: prawdziwa baza, repozytoria i SyncCoordinator;
 * podstawione tylko źródła sieciowe, ustawienia i odbiornik powiadomień.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncWorkerTest {

    // --- Podstawione zależności ---

    private class Timetable(var error: Exception? = null) : TimetableSource {
        override suspend fun fetchSidebar() = listOf(
            ClassListItemDto("o3", ClassListItemDto.Kind.CLASS, "1D 1D PBŚ", "1D", "https://plan.zse.bydgoszcz.pl/plany/o3.html")
        )
        override suspend fun fetchTimetable(classId: String): TimetableDto {
            error?.let { throw it }
            return TimetableDto(classId, "1D 1D PBŚ", null, null,
                listOf(LessonCellDto(2, "08:55", "09:40", 1, listOf(LessonGroupDto("mat", "Ch", null, "105", null, null, null)), null)))
        }
    }

    private class Subs(var items: List<SubstitutionDto> = emptyList(), var error: Exception? = null) : SubstitutionsSource {
        override suspend fun fetchSubstitutions(): List<SubstitutionDto> { error?.let { throw it }; return items }
    }

    private class Anns : AnnouncementsSource {
        override suspend fun fetchNewsFeed() = emptyList<RssItemDto>()
        override suspend fun fetchLatestFeed() = emptyList<RssItemDto>()
        override suspend fun fetchArticleHtml(url: String): String? = null
        override suspend fun fetchArchivePage(page: Int) = emptyList<ArchiveItemDto>()
        override suspend fun fetchArticleDate(url: String): Instant? = null
    }

    private class RecordingSink : NotificationSink {
        val substitutions = mutableListOf<Substitution>()
        override suspend fun postSubstitution(sub: Substitution, originalSubject: String?) { substitutions += sub }
        override suspend fun postAnnouncement(ann: Announcement) {}
        override suspend fun postGeneric(title: String, body: String, deepLink: String?) {}
    }

    // --- Środowisko ---

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val tomorrow = LocalDate.now().plusDays(1)
    private lateinit var db: AppDatabase
    private val subsSource = Subs()
    private val timetableSource = Timetable()
    private val sink = RecordingSink()
    private val settings = FakeSettings()

    private fun sub(lesson: Int, cls: String = "1D") =
        SubstitutionDto(tomorrow.format(fmt), "Y.Nauczyciel", lesson, cls, null, "316", "X.Zastępca", null)

    @Before fun setUp() { db = inMemoryDb() }
    @After fun tearDown() = db.close()

    private lateinit var notificationsRepo: NotificationsRepositoryImpl

    private fun runWorker(background: Boolean = false, time: java.time.LocalTime? = null): ListenableWorker.Result {
        val t = TestCoordinator(context, db, settings, timetableSource, subsSource, Anns(), sink)
        time?.let { at -> t.coordinator.localTime = { at } }
        notificationsRepo = t.notificationsRepo
        val fakeUpdateRepo = object : pl.zse.bydgoszcz.elektron.domain.repository.UpdateRepository {
            override val availableUpdate = kotlinx.coroutines.flow.flowOf(null)
            override suspend fun check(force: Boolean) = Result.success(null)
            override suspend fun dismiss(versionName: String) {}
        }
        val dndScheduler = pl.zse.bydgoszcz.elektron.service.AutoDndScheduler(context, settings, t.timetableRepo)
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                SyncWorker(appContext, workerParameters, t.coordinator, t.notificationsRepo, fakeUpdateRepo, dndScheduler)
        }
        val worker = TestListenableWorkerBuilder<SyncWorker>(context).setWorkerFactory(factory)
            .setInputData(androidx.work.workDataOf("background" to background)).build()
        return runBlocking { worker.doWork() }
    }

    @Test
    fun firstSyncOnlyRecordsStateWithoutNotifications() {
        subsSource.items = listOf(sub(1))
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertTrue("Pierwszy sync nie może zalać powiadomieniami", sink.substitutions.isEmpty())
    }

    @Test
    fun newSubstitutionForOwnClassNotifiesOnce() {
        subsSource.items = listOf(sub(1))
        runWorker()                                            // stan startowy
        subsSource.items = listOf(sub(1), sub(2), sub(3, cls = "2A"))
        runWorker()
        assertEquals(listOf(2), sink.substitutions.map { it.lessonNumber })   // 2A pominięte
        runWorker()                                            // to samo jeszcze raz
        assertEquals("Bez duplikatów przy kolejnym syncu", 1, sink.substitutions.size)
    }

    @Test
    fun disabledNotificationsPostNothing() {
        subsSource.items = listOf(sub(1))
        runWorker()
        settings.notifySubs.value = false
        subsSource.items = listOf(sub(1), sub(2))
        runWorker()
        assertTrue(sink.substitutions.isEmpty())
    }

    @Test
    fun lastSyncIsNotSetWhenOnlyClassListAndAnnouncementsLoaded() {
        // Plan i zastępstwa nie odpowiadają, lista klas i RSS tak: to nie jest "zsynchronizowano
        // teraz" - dawniej znikał baner o nieaktualnych danych.
        timetableSource.error = java.io.IOException("Plan lekcji: HTTP 503")
        subsSource.error = java.io.IOException("Zastępstwa: HTTP 503")
        runWorker()
        assertNull(runBlocking { notificationsRepo.getLastSyncAt() })
        assertNotNull(runBlocking { notificationsRepo.observeLastSyncError().first() })
    }

    @Test
    fun reminderIsScheduledBeforeWorkerFinishes() {
        // Dawniej przypomnienie ustawiało się w tle już po zakończeniu workera - system mógł
        // zabić proces wcześniej. Alarm musi być ustawiony, gdy doWork() zwraca wynik.
        settings.reminderFlow.value = ReminderSettings(ReminderMode.EVERY, 10)
        runWorker()
        val am = context.getSystemService(AlarmManager::class.java)
        assertNotNull(shadowOf(am).nextScheduledAlarm)
    }

    @Test
    fun nightRunWithNothingDueSucceedsInsteadOfRetrying() {
        subsSource.items = listOf(sub(1))
        runWorker()
        // Wszystko świeże, w nocy nic nie jest do sprawdzenia: sukces, nie ponowienia co 30 s.
        assertEquals(ListenableWorker.Result.success(), runWorker(background = true, time = java.time.LocalTime.of(23, 30)))
    }
}
