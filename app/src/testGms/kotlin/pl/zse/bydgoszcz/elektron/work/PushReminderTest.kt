package pl.zse.bydgoszcz.elektron.work

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import pl.zse.bydgoszcz.elektron.data.local.*
import pl.zse.bydgoszcz.elektron.data.remote.sources.*
import pl.zse.bydgoszcz.elektron.data.repository.*
import pl.zse.bydgoszcz.elektron.domain.model.*
import pl.zse.bydgoszcz.elektron.testutil.FakeSettings
import pl.zse.bydgoszcz.elektron.widget.WidgetUpdater
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushReminderTest {
    @Test fun delayedPushCannotRestoreCancelledSubstitutionAndUnrelatedClassDoesNotFetch() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val db = inMemoryDb()
        try {
            val day = LocalDate.now().plusDays(1)
            db.schoolClassDao().upsertAll(listOf(SchoolClassEntity("o3", "1D", "1D", "https://plan.zse.bydgoszcz.pl/")))
            db.lessonDao().upsertAll(listOf(LessonEntity("lesson", "o3", "1D", day.toEpochDay(),
                day.dayOfWeek.value, 1, "08:00", "08:45", "matematyka")))
            val settings = FakeSettings(reminders = ReminderSettings(ReminderMode.EVERY, 10))
            settings.notifySubs.value = false
            val source = object : TimetableSource {
                override suspend fun fetchSidebar() = error("Unexpected network")
                override suspend fun fetchTimetable(classId: String) = error("Unexpected network")
            }
            val timetable = TimetableRepositoryImpl(source, db.schoolClassDao(), db.teacherDao(),
                db.roomDao(), db.lessonDao(), db.lessonGroupDao(), db.substitutionDao(), db)
            var pageItems = listOf(pl.zse.bydgoszcz.elektron.data.remote.dto.SubstitutionDto(
                day.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")), "Nauczyciel", 1, "1D", null, "Uczniowie zwolnieni", null, null))
            var calls = 0
            val subsSource = object : SubstitutionsSource {
                override suspend fun fetchSubstitutions() = pageItems
                override suspend fun fetchPage(): SubstitutionsPage {
                    calls++
                    return SubstitutionsPage(setOf(day.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))), pageItems)
                }
            }
            val notifications = NotificationsRepositoryImpl(db.notificationDao(), db.syncStateDao())
            val sink = object : NotificationSink {
                override suspend fun postSubstitution(sub: Substitution, originalSubject: String?) {}
                override suspend fun postAnnouncement(ann: Announcement) {}
                override suspend fun postGeneric(title: String, body: String, deepLink: String?) {}
            }
            val scheduler = LessonReminderScheduler(context, settings, timetable)
            scheduler.reschedule()
            val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
            assertNotNull(alarms.peekNextScheduledAlarm())
            val annSource = object : AnnouncementsSource {
                override suspend fun fetchNewsFeed() = error("Unexpected network")
                override suspend fun fetchLatestFeed() = error("Unexpected network")
                override suspend fun fetchArticleHtml(url: String): String? = null
                override suspend fun fetchArchivePage(page: Int) = emptyList<pl.zse.bydgoszcz.elektron.data.remote.dto.ArchiveItemDto>()
                override suspend fun fetchArticleDate(url: String): java.time.Instant? = null
            }
            val t = pl.zse.bydgoszcz.elektron.testutil.TestCoordinator(context, db, settings, source, subsSource, annSource, sink)
            val service = PushMessageHandler().also {
                it.settings = settings
                it.timetableRepo = timetable
                it.notificationsRepo = notifications
                it.coordinator = t.coordinator
                it.syncScheduler = SyncScheduler(context)
            }
            val message = RemoteMessage.Builder("test").setData(mapOf(
                "type" to "substitution", "id" to pl.zse.bydgoszcz.elektron.data.mapper.SubstitutionMapper.toEntity(pageItems.single())!!.id, "date" to day.toString(),
                "lessonNumber" to "1", "classShortName" to "1D", "roomOrInfo" to "Uczniowie zwolnieni"
            )).build()
            service.handle(message.data)
            assertNull(alarms.peekNextScheduledAlarm())
            // Szkoła odwołała zastępstwo; opóźniony identyczny push nadal mówi "zwolnieni".
            service.handle(message.data)
            assertEquals(1, calls) // duplikat po wspólnym odczycie nie pobiera strony ponownie
            pageItems = emptyList()
            t.coordinator.sync(pl.zse.bydgoszcz.elektron.domain.sync.SyncRequest.of(SyncOutcome.SUBSTITUTIONS))
            service.handle(message.data)
            assertTrue(t.substitutionsRepo.getAllFrom(day).isEmpty())
            assertNotNull(alarms.peekNextScheduledAlarm())
            assertEquals(3, calls)
            service.handle(message.data + ("classShortName" to "2A"))
            assertEquals(3, calls)
        } finally { db.close() }
    }
}
