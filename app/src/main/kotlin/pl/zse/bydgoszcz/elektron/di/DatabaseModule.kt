package pl.zse.bydgoszcz.elektron.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import pl.zse.bydgoszcz.elektron.data.local.AppDatabase
import pl.zse.bydgoszcz.elektron.data.local.DatabaseMigrations
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, AppDatabase.NAME)
            // Zmiany schematu przez jawne migracje (patrz DatabaseMigrations) — aktualizacja
            // aplikacji nie kasuje już bazy. Kasowanie zostaje tylko przy downgrade
            // (instalacja starszej wersji na nowszą), gdzie migracji w dół nie piszemy.
            .addMigrations(*DatabaseMigrations.ALL)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides fun provideSchoolClassDao(db: AppDatabase) = db.schoolClassDao()
    @Provides fun provideTeacherDao(db: AppDatabase) = db.teacherDao()
    @Provides fun provideRoomDao(db: AppDatabase) = db.roomDao()
    @Provides fun provideLessonDao(db: AppDatabase) = db.lessonDao()
    @Provides fun provideLessonGroupDao(db: AppDatabase) = db.lessonGroupDao()
    @Provides fun provideSubstitutionDao(db: AppDatabase) = db.substitutionDao()
    @Provides fun provideAnnouncementDao(db: AppDatabase) = db.announcementDao()
    @Provides fun provideNotificationDao(db: AppDatabase) = db.notificationDao()
    @Provides fun provideSyncStateDao(db: AppDatabase) = db.syncStateDao()
    @Provides fun provideSchoolEventDao(db: AppDatabase) = db.schoolEventDao()
    @Provides fun provideUserActivityDao(db: AppDatabase) = db.userActivityDao()
    @Provides fun provideAttendanceDao(db: AppDatabase) = db.attendanceDao()
}
