package pl.zse.bydgoszcz.elektron.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import pl.zse.bydgoszcz.elektron.data.repository.AnnouncementsRepositoryImpl
import pl.zse.bydgoszcz.elektron.data.repository.NotificationsRepositoryImpl
import pl.zse.bydgoszcz.elektron.data.repository.SettingsRepositoryImpl
import pl.zse.bydgoszcz.elektron.data.repository.SubstitutionsRepositoryImpl
import pl.zse.bydgoszcz.elektron.data.repository.TimetableRepositoryImpl
import pl.zse.bydgoszcz.elektron.domain.repository.AnnouncementsRepository
import pl.zse.bydgoszcz.elektron.domain.repository.NotificationsRepository
import pl.zse.bydgoszcz.elektron.domain.repository.SettingsRepository
import pl.zse.bydgoszcz.elektron.domain.repository.SubstitutionsRepository
import pl.zse.bydgoszcz.elektron.domain.repository.TimetableRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds @Singleton
    abstract fun bindTimetableRepository(impl: TimetableRepositoryImpl): TimetableRepository

    @Binds @Singleton
    abstract fun bindSubstitutionsRepository(impl: SubstitutionsRepositoryImpl): SubstitutionsRepository

    @Binds @Singleton
    abstract fun bindAnnouncementsRepository(impl: AnnouncementsRepositoryImpl): AnnouncementsRepository

    @Binds @Singleton
    abstract fun bindUpdateRepository(impl: pl.zse.bydgoszcz.elektron.data.update.UpdateRepositoryImpl): pl.zse.bydgoszcz.elektron.domain.repository.UpdateRepository

    @Binds @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds @Singleton
    abstract fun bindNotificationsRepository(impl: NotificationsRepositoryImpl): NotificationsRepository

    @Binds @Singleton
    abstract fun bindCalendarRepository(impl: pl.zse.bydgoszcz.elektron.data.repository.CalendarRepositoryImpl): pl.zse.bydgoszcz.elektron.domain.repository.CalendarRepository

    @Binds @Singleton
    abstract fun bindUserActivityRepository(impl: pl.zse.bydgoszcz.elektron.data.repository.UserActivityRepositoryImpl): pl.zse.bydgoszcz.elektron.domain.repository.UserActivityRepository

    @Binds @Singleton
    abstract fun bindAttendanceRepository(impl: pl.zse.bydgoszcz.elektron.data.repository.AttendanceRepositoryImpl): pl.zse.bydgoszcz.elektron.domain.repository.AttendanceRepository

    @Binds @Singleton
    abstract fun bindDutyRepository(impl: pl.zse.bydgoszcz.elektron.data.repository.DutyRepositoryImpl): pl.zse.bydgoszcz.elektron.domain.repository.DutyRepository
}
