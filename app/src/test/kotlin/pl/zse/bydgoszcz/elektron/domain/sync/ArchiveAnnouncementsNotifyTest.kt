package pl.zse.bydgoszcz.elektron.domain.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.zse.bydgoszcz.elektron.data.remote.dto.ArchiveItemDto
import pl.zse.bydgoszcz.elektron.data.remote.dto.ClassListItemDto
import pl.zse.bydgoszcz.elektron.data.remote.dto.LessonCellDto
import pl.zse.bydgoszcz.elektron.data.remote.dto.LessonGroupDto
import pl.zse.bydgoszcz.elektron.data.remote.dto.RssItemDto
import pl.zse.bydgoszcz.elektron.data.remote.dto.SubstitutionDto
import pl.zse.bydgoszcz.elektron.data.remote.dto.TimetableDto
import pl.zse.bydgoszcz.elektron.data.remote.sources.AnnouncementsSource
import pl.zse.bydgoszcz.elektron.data.remote.sources.SubstitutionsPage
import pl.zse.bydgoszcz.elektron.data.remote.sources.SubstitutionsSource
import pl.zse.bydgoszcz.elektron.data.remote.sources.TimetableSource
import pl.zse.bydgoszcz.elektron.data.repository.inMemoryDb
import pl.zse.bydgoszcz.elektron.domain.model.Announcement
import pl.zse.bydgoszcz.elektron.domain.model.SyncOutcome
import pl.zse.bydgoszcz.elektron.domain.model.Substitution
import pl.zse.bydgoszcz.elektron.testutil.FakeSettings
import pl.zse.bydgoszcz.elektron.testutil.TestCoordinator
import pl.zse.bydgoszcz.elektron.work.NotificationSink
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Wpisy doczytane z archiwum ("Pokaż więcej") nie są nowymi ogłoszeniami. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchiveAnnouncementsNotifyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val posted = mutableListOf<String>()

    private val sink = object : NotificationSink {
        override suspend fun postSubstitution(sub: Substitution, originalSubject: String?) {}
        override suspend fun postAnnouncement(ann: Announcement) { posted += ann.id }
        override suspend fun postGeneric(title: String, body: String, deepLink: String?) {}
    }

    private val school = object : TimetableSource {
        override suspend fun fetchSidebar() = emptyList<ClassListItemDto>()
        override suspend fun fetchTimetable(classId: String) = TimetableDto(classId, "1D", null, null,
            listOf(LessonCellDto(1, "08:00", "08:45", 1, listOf(LessonGroupDto("mat", "Ch", null, "105", null, null, null)), null)))
    }

    private val subs = object : SubstitutionsSource {
        private val raw = LocalDate.now().plusDays(1).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        override suspend fun fetchSubstitutions() = emptyList<SubstitutionDto>()
        override suspend fun fetchPage() = SubstitutionsPage(setOf(raw), emptyList())
    }

    private val anns = object : AnnouncementsSource {
        override suspend fun fetchNewsFeed() = listOf(RssItemDto("https://zse.bydgoszcz.pl/nowy", "Nowy",
            "https://zse.bydgoszcz.pl/nowy", "Mon, 28 Sep 2026 10:00:00 +0200", "Opis", RssItemDto.Source.RSS_NEWS))
        override suspend fun fetchLatestFeed() = emptyList<RssItemDto>()
        override suspend fun fetchArticleHtml(url: String): String? = null
        override suspend fun fetchArchivePage(page: Int) =
            if (page == 1) listOf(ArchiveItemDto("https://zse.bydgoszcz.pl/stary", "Stary", null, null)) else emptyList()
        override suspend fun fetchArticleDate(url: String): Instant? = Instant.parse("2025-01-10T10:00:00Z")
    }

    @Test fun archivePageDoesNotNotifyOnNextSync() = runBlocking {
        val db = inMemoryDb()
        try {
            val settings = FakeSettings(classId = "o3").apply { notifyAnns.value = true }
            val t = TestCoordinator(context, db, settings, school, subs, anns, sink)
            // Pierwsza synchronizacja tylko zapisuje stan.
            t.coordinator.sync(SyncRequest.full())
            assertTrue(posted.isEmpty())

            assertEquals(1, t.announcementsRepo.loadOlder().getOrThrow().added)
            // Kolejna synchronizacja samych zastępstw przegląda całą tabelę ogłoszeń.
            val outcome = t.coordinator.sync(SyncRequest.of(SyncOutcome.SUBSTITUTIONS))
            assertTrue(outcome.failures.isEmpty())
            assertTrue("stare ogłoszenie z archiwum powiadomione jako nowe: $posted", posted.isEmpty())
        } finally {
            db.close()
        }
    }
}
