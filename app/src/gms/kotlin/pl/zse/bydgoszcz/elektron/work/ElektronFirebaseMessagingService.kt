package pl.zse.bydgoszcz.elektron.work

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import pl.zse.bydgoszcz.elektron.domain.model.Announcement
import pl.zse.bydgoszcz.elektron.domain.model.AnnouncementSource
import pl.zse.bydgoszcz.elektron.domain.repository.AnnouncementsRepository
import pl.zse.bydgoszcz.elektron.domain.repository.NotificationsRepository
import pl.zse.bydgoszcz.elektron.domain.repository.SettingsRepository
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/**
 * Funkcja #7: odbiornik pushy z zewnętrznego "watchera" (GitHub Actions + FCM Topics —
 * patrz PORADNIK_PUSH.md). Appka NIE ma własnego backendu — to jedyny element sieciowy,
 * który przychodzi z zewnątrz zamiast być przez appkę pobierany.
 *
 * Watcher publikuje na temat "elektron-subs-zse-bydgoszcz" / "elektron-anns-zse-bydgoszcz"
 * z pełnymi danymi w payloadzie (data message, nie "notification") — appka sama decyduje,
 * czy to jej dotyczy (klasa) i czy w ogóle pokazać powiadomienie (przełączniki w
 * Ustawieniach), tak samo jak przy zwykłym SyncWorker.
 *
 * Idempotentność: sprawdzamy "seen" set (pod tą samą blokadą co SyncWorker — patrz
 * NotificationsRepository.withNotifyLock) zanim pokażemy powiadomienie — jeśli periodyczny
 * SyncWorker (15 min) już wykrył to samo zastępstwo niezależnie, push go nie zduplikuje.
 */
@AndroidEntryPoint
class ElektronFirebaseMessagingService : FirebaseMessagingService() {

    @Inject lateinit var coordinator: pl.zse.bydgoszcz.elektron.domain.sync.SyncCoordinator
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var timetableRepo: pl.zse.bydgoszcz.elektron.domain.repository.TimetableRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var announcementsRepo: AnnouncementsRepository
    @Inject lateinit var notificationsRepo: NotificationsRepository
    @Inject lateinit var sink: NotificationSink

    // Odbiornik działa na wątku biblioteki FCM. Sprawdzenie zastępstw ma limit 8 s;
    // przy braku połączenia lub przekroczeniu limitu WorkManager kontynuuje później.
    // Payload zastępstwa nigdy nie nadpisuje nowszego odczytu strony szkoły.
    override fun onMessageReceived(message: RemoteMessage) = runBlocking(Dispatchers.IO) {
        val data = message.data
        val type = data["type"] ?: return@runBlocking
        try {
            when (type) {
                "substitution" -> handleSubstitution(data)
                "announcement" -> handleAnnouncement(data)
                else -> Log.w(TAG, "Nieznany typ push: $type")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Błąd przetwarzania push", e)
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
