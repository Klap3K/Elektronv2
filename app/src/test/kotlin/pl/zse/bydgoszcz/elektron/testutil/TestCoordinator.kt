package pl.zse.bydgoszcz.elektron.testutil

import android.content.Context
import pl.zse.bydgoszcz.elektron.data.local.AppDatabase
import pl.zse.bydgoszcz.elektron.data.remote.sources.AnnouncementsSource
import pl.zse.bydgoszcz.elektron.data.remote.sources.SubstitutionsSource
import pl.zse.bydgoszcz.elektron.data.remote.sources.TimetableSource
import pl.zse.bydgoszcz.elektron.data.repository.AnnouncementsRepositoryImpl
import pl.zse.bydgoszcz.elektron.data.repository.NotificationsRepositoryImpl
import pl.zse.bydgoszcz.elektron.data.repository.SubstitutionsRepositoryImpl
import pl.zse.bydgoszcz.elektron.data.repository.TimetableRepositoryImpl
import pl.zse.bydgoszcz.elektron.domain.sync.SyncCoordinator
import pl.zse.bydgoszcz.elektron.widget.WidgetUpdater
import pl.zse.bydgoszcz.elektron.work.LessonReminderScheduler
import pl.zse.bydgoszcz.elektron.work.NotificationSink
import pl.zse.bydgoszcz.elektron.work.SubstitutionNotifier

/** Prawdziwe repozytoria na bazie [db] z podstawionymi źródłami sieciowymi + koordynator. */
class TestCoordinator(
    context: Context,
    val db: AppDatabase,
    settings: pl.zse.bydgoszcz.elektron.domain.repository.SettingsRepository,
    timetableSource: TimetableSource,
    substitutionsSource: SubstitutionsSource,
    announcementsSource: AnnouncementsSource,
    sink: NotificationSink
) {
    val timetableRepo = TimetableRepositoryImpl(timetableSource, db.schoolClassDao(), db.teacherDao(), db.roomDao(),
        db.lessonDao(), db.lessonGroupDao(), db.substitutionDao(), db)
    val substitutionsRepo = SubstitutionsRepositoryImpl(substitutionsSource, db.substitutionDao(), db)
    val notificationsRepo = NotificationsRepositoryImpl(db.notificationDao(), db.syncStateDao())
    val announcementsRepo = AnnouncementsRepositoryImpl(announcementsSource, db.announcementDao(), db,
        pl.zse.bydgoszcz.elektron.data.repository.FavoriteAnnouncementsStore(null), notificationsRepo)
    val coordinator = SyncCoordinator(
        settings, timetableRepo, substitutionsRepo, announcementsRepo, notificationsRepo,
        SubstitutionNotifier(settings, timetableRepo, notificationsRepo, sink), sink,
        WidgetUpdater(context), LessonReminderScheduler(context, settings, timetableRepo), db
    ).apply {
        // Stała pora dnia: reguła nocna (BackgroundSyncPolicy) nie może zależeć od godziny uruchomienia testów.
        localTime = { java.time.LocalTime.NOON }
    }
}
