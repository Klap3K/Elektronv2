package pl.zse.bydgoszcz.elektron.work

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import pl.zse.bydgoszcz.elektron.domain.model.Announcement
import pl.zse.bydgoszcz.elektron.domain.model.AnnouncementSource
import pl.zse.bydgoszcz.elektron.domain.repository.AnnouncementsRepository
import pl.zse.bydgoszcz.elektron.domain.repository.NotificationsRepository
import pl.zse.bydgoszcz.elektron.domain.repository.SettingsRepository
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/** Payload processing runs in WorkManager, beyond the FCM callback lifetime. */
class PushMessageHandler @Inject constructor() {
    @Inject lateinit var coordinator: pl.zse.bydgoszcz.elektron.domain.sync.SyncCoordinator
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var timetableRepo: pl.zse.bydgoszcz.elektron.domain.repository.TimetableRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var announcementsRepo: AnnouncementsRepository
    @Inject lateinit var notificationsRepo: NotificationsRepository
    @Inject lateinit var sink: NotificationSink

    suspend fun handle(data: Map<String, String>) = withContext(Dispatchers.IO) {
        val type = data["type"] ?: return@withContext
        when (type) {
            "substitution" -> handleSubstitution(data)
            "announcement" -> handleAnnouncement(data)
            else -> Log.w(TAG, "Nieznany typ push: $type")
        }
    }

    private suspend fun handleSubstitution(data: Map<String, String>) {
        if (data["id"].isNullOrBlank() || data["classShortName"].isNullOrBlank()) return
        val date = runCatching { LocalDate.parse(data["date"]) }.getOrNull() ?: return
        if ((data["lessonNumber"]?.toIntOrNull() ?: return) !in 0..12) return
        if (date < LocalDate.now().minusDays(1)) return
        val selected = settings.selectedClassId.first() ?: return
        val short = timetableRepo.observeClasses().first().firstOrNull { it.id == selected }?.shortName ?: return
        if (!data.getValue("classShortName").equals(short, ignoreCase = true)) return
        if (data.getValue("id") in notificationsRepo.getSeenSubstitutionIds()) return
        // FCM może dostarczyć stary wpis już usunięty ze strony. Payload jest sygnałem do
        // sprawdzenia, a nie źródłem prawdy; zapis, powiadomienia i alarmy wykonuje koordynator.
        val outcome = kotlinx.coroutines.withTimeoutOrNull(8_000) {
            coordinator.sync(pl.zse.bydgoszcz.elektron.domain.sync.SyncRequest.of(
                pl.zse.bydgoszcz.elektron.domain.model.SyncOutcome.SUBSTITUTIONS))
        }
        // Niepełna strona (kilka nieczytelnych wierszy) jest już zapisana - bez ponownego pobierania.
        if (outcome == null || outcome.shouldRetry) syncScheduler.syncSubstitutionsNow()
    }

    private suspend fun handleAnnouncement(data: Map<String, String>) {
        val id = data["id"] ?: return
        val title = data["title"] ?: return
        val url = data["url"] ?: return
        val publishedAt = runCatching { Instant.parse(data["publishedAt"]) }.getOrDefault(Instant.now())
        val source = runCatching { AnnouncementSource.valueOf(data["source"] ?: "") }.getOrDefault(AnnouncementSource.RSS_NEWS)
        val ann = Announcement(
            id = id, title = title, url = url, publishedAt = publishedAt,
            excerpt = data["excerpt"], coverImageUrl = data["coverImageUrl"],
            fullHtml = null, source = source
        )
        announcementsRepo.upsertOne(ann)

        if (!settings.notificationsAnnouncements.first()) return

        notificationsRepo.withNotifyLock {
            val seen = notificationsRepo.getSeenAnnouncementIds()
            if (id in seen) return@withNotifyLock
            sink.postAnnouncement(ann)
            notificationsRepo.setSeenAnnouncementIds(seen + id)
        }
    }

    companion object { private const val TAG = "ElektronFcmService" }
}
