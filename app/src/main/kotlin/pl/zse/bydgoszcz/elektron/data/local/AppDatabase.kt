package pl.zse.bydgoszcz.elektron.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        SchoolClassEntity::class,
        TeacherEntity::class,
        RoomEntity::class,
        LessonEntity::class,
        LessonGroupEntity::class,
        SubstitutionEntity::class,
        AnnouncementEntity::class,
        NotificationItemEntity::class,
        SyncStateEntity::class,
        SchoolEventEntity::class,
        UserActivityEntity::class,
        AttendanceEntity::class
    ],
    version = DATABASE_VERSION,
    // Eksport schematu do app/schemas/ — podstawa pod przyszłe Migration zamiast
    // fallbackToDestructiveMigration(). Pliki JSON warto commitować.
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun schoolClassDao(): SchoolClassDao
    abstract fun teacherDao(): TeacherDao
    abstract fun roomDao(): RoomDao
    abstract fun lessonDao(): LessonDao
    abstract fun lessonGroupDao(): LessonGroupDao
    abstract fun substitutionDao(): SubstitutionDao
    abstract fun announcementDao(): AnnouncementDao
    abstract fun notificationDao(): NotificationDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun schoolEventDao(): SchoolEventDao
    abstract fun userActivityDao(): UserActivityDao
    abstract fun attendanceDao(): AttendanceDao

    companion object { const val NAME = "elektron.db" }
}
