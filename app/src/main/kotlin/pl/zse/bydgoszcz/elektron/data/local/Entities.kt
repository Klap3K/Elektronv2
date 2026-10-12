package pl.zse.bydgoszcz.elektron.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "school_classes")
data class SchoolClassEntity(
    @PrimaryKey val id: String,
    val fullName: String,
    val shortName: String,
    val url: String
)

@Entity(tableName = "teachers")
data class TeacherEntity(
    @PrimaryKey val code: String,
    val fullName: String,
    val url: String
)

@Entity(tableName = "rooms")
data class RoomEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String
)

@Entity(tableName = "lessons", indices = [Index("classId"), Index("dateEpochDay")])
data class LessonEntity(
    @PrimaryKey val id: String,
    val classId: String,
    val className: String,
    val dateEpochDay: Long,
    val dayOfWeekIso: Int,
    val number: Int,
    val timeFrom: String,
    val timeTo: String,
    val note: String?
)

@Entity(
    tableName = "lesson_groups",
    primaryKeys = ["lessonId", "sortOrder"],
    foreignKeys = [ForeignKey(
        entity = LessonEntity::class,
        parentColumns = ["id"],
        childColumns = ["lessonId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("lessonId")]
)
data class LessonGroupEntity(
    val lessonId: String,
    val sortOrder: Int,
    val subject: String?,
    val teacherCode: String?,
    val teacherUrl: String?,
    val teacherFullName: String?,
    val room: String?,
    val roomUrl: String?,
    val groupLabel: String?,
    val classRef: String?
)

@Entity(tableName = "substitutions", indices = [Index("dateEpochDay"), Index("classShortName")])
data class SubstitutionEntity(
    @PrimaryKey val id: String,
    val dateEpochDay: Long,
    val lessonNumber: Int,
    val classShortName: String,
    val groupNumber: Int?,
    val roomOrInfo: String,
    val substituteTeacher: String?,
    val notes: String?,
    val originalTeacher: String,
    val originalSubject: String? = null
)

@Entity(tableName = "announcements", indices = [Index("publishedAtEpochSeconds")])
data class AnnouncementEntity(
    @PrimaryKey val id: String,
    val title: String,
    val url: String,
    val publishedAtEpochSeconds: Long,
    val excerpt: String?,
    val coverImageUrl: String?,
    val fullHtml: String?,
    val isRead: Boolean,
    val source: String,
    @ColumnInfo(defaultValue = "0") val isFavorite: Boolean = false
)

@Entity(tableName = "notifications", indices = [Index("createdAtEpochSeconds")])
data class NotificationItemEntity(
    @PrimaryKey val id: String,
    val type: String,
    val title: String,
    val body: String,
    val deepLink: String?,
    val createdAtEpochSeconds: Long,
    val readAtEpochSeconds: Long?
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    val lastSyncEpochSeconds: Long,
    val status: String,
    val message: String?
)

@Entity(tableName = "school_events", indices = [Index("dateEpochDay")])
data class SchoolEventEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    val eventType: String,
    val subject: String?,
    val dateEpochDay: Long,
    val timeFrom: String?,
    val timeTo: String?,
    val location: String?,
    val rawSummary: String
)

@Entity(tableName = "user_activities", indices = [Index("dateEpochDay")])
data class UserActivityEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    val category: String, // EXAM, HOMEWORK, STUDY, TUTORING, TRAINING, PROJECT, OTHER
    val dateEpochDay: Long,
    val timeFrom: String?,
    val timeTo: String?,
    val isCompleted: Boolean,
    val createdAtEpochSeconds: Long
)

@Entity(tableName = "attendance_stats")
data class AttendanceEntity(
    @PrimaryKey val subject: String,
    val presentHours: Int,
    val absentHours: Int,
    val excusedHours: Int,
    val lateHours: Int,
    val schoolObligationHours: Int,
    val lastUpdatedEpochSeconds: Long
)

@Entity(tableName = "student_grades", indices = [Index("subject"), Index("periodNumber")])
data class GradeEntity(
    @PrimaryKey val id: String,
    val subject: String,
    val value: String,
    val weight: Double,
    val category: String?,
    val description: String?,
    val teacher: String?,
    val dateText: String?,
    val periodNumber: Int,
    val isActivePeriod: Boolean,
    val isSuperseded: Boolean,
    val apiSubjectAverage: Double?,
    val proposedGrade: String?,
    val finalGrade: String?,
    val updatedAtEpochSeconds: Long
)


