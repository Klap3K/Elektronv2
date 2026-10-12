package pl.zse.bydgoszcz.elektron.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SchoolClassDao {
    @Query("SELECT * FROM school_classes ORDER BY id")
    fun observeAll(): Flow<List<SchoolClassEntity>>

    @Query("SELECT * FROM school_classes ORDER BY id")
    suspend fun getAll(): List<SchoolClassEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SchoolClassEntity>)

    @Query("DELETE FROM school_classes")
    suspend fun clear()

    /** Klasy, których nie ma już na liście szkoły (np. absolwenci po nowym roku szkolnym). */
    @Query("DELETE FROM school_classes WHERE id NOT IN (:keep)")
    suspend fun deleteExcept(keep: List<String>)
}

@Dao
interface TeacherDao {
    @Query("SELECT * FROM teachers")
    suspend fun getAll(): List<TeacherEntity>

    @Query("SELECT * FROM teachers ORDER BY fullName")
    fun observeAll(): Flow<List<TeacherEntity>>

    @Query("SELECT * FROM teachers WHERE code IN (:codes)")
    suspend fun getByCodes(codes: List<String>): List<TeacherEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<TeacherEntity>)

    @Query("DELETE FROM teachers")
    suspend fun clear()

    @Query("DELETE FROM teachers WHERE code NOT IN (:keep)")
    suspend fun deleteExcept(keep: List<String>)
}

@Dao
interface RoomDao {
    @Query("SELECT * FROM rooms")
    suspend fun getAll(): List<RoomEntity>

    @Query("SELECT * FROM rooms ORDER BY name")
    fun observeAll(): Flow<List<RoomEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<RoomEntity>)

    @Query("DELETE FROM rooms")
    suspend fun clear()

    @Query("DELETE FROM rooms WHERE id NOT IN (:keep)")
    suspend fun deleteExcept(keep: List<String>)
}

@Dao
interface LessonDao {
    @Query("SELECT * FROM lessons WHERE classId = :classId AND dateEpochDay BETWEEN :fromDay AND :toDay ORDER BY dateEpochDay, number")
    fun observeForRange(classId: String, fromDay: Long, toDay: Long): Flow<List<LessonEntity>>

    @Query("SELECT * FROM lessons WHERE dateEpochDay BETWEEN :fromDay AND :toDay ORDER BY dateEpochDay, classId, number")
    fun observeAllForRange(fromDay: Long, toDay: Long): Flow<List<LessonEntity>>

    @Query("SELECT * FROM lessons WHERE classId = :classId AND dateEpochDay BETWEEN :fromDay AND :toDay ORDER BY dateEpochDay, number")
    suspend fun getForRange(classId: String, fromDay: Long, toDay: Long): List<LessonEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<LessonEntity>)

    @Query("DELETE FROM lessons WHERE classId = :classId AND dateEpochDay BETWEEN :fromDay AND :toDay")
    suspend fun deleteForRange(classId: String, fromDay: Long, toDay: Long)

    /** Stare tygodnie planu (grupy lekcji usuwają się kaskadowo — FK CASCADE). */
    @Query("DELETE FROM lessons WHERE dateEpochDay < :beforeDay")
    suspend fun deleteOlderThan(beforeDay: Long)

    @Query("DELETE FROM lessons WHERE classId = :classId")
    suspend fun deleteForClass(classId: String)

    @Query("SELECT COUNT(*) FROM lessons WHERE classId = :classId AND dateEpochDay BETWEEN :fromDay AND :toDay")
    suspend fun countForRange(classId: String, fromDay: Long, toDay: Long): Int

    @Query("SELECT DISTINCT classId FROM lessons")
    suspend fun classIds(): List<String>
}

@Dao
interface LessonGroupDao {
    @Query("SELECT * FROM lesson_groups WHERE lessonId IN (:lessonIds) ORDER BY lessonId, sortOrder")
    fun observeForLessons(lessonIds: List<String>): Flow<List<LessonGroupEntity>>

    @Query("SELECT * FROM lesson_groups WHERE lessonId IN (:lessonIds) ORDER BY lessonId, sortOrder")
    suspend fun getForLessons(lessonIds: List<String>): List<LessonGroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<LessonGroupEntity>)

    @Query("DELETE FROM lesson_groups WHERE lessonId IN (:lessonIds)")
    suspend fun deleteForLessons(lessonIds: List<String>)
}

@Dao
interface SubstitutionDao {
    @Query("SELECT * FROM substitutions WHERE dateEpochDay = :day ORDER BY lessonNumber, classShortName")
    fun observeForDay(day: Long): Flow<List<SubstitutionEntity>>

    @Query("SELECT * FROM substitutions WHERE dateEpochDay >= :fromDay ORDER BY dateEpochDay, lessonNumber, classShortName")
    fun observeFromDay(fromDay: Long): Flow<List<SubstitutionEntity>>

    @Query("SELECT * FROM substitutions WHERE classShortName = :classShortName AND dateEpochDay = :day ORDER BY lessonNumber")
    suspend fun getForClassAndDay(classShortName: String, day: Long): List<SubstitutionEntity>

    @Query("SELECT * FROM substitutions WHERE dateEpochDay >= :fromDay ORDER BY dateEpochDay, lessonNumber")
    suspend fun getFromDay(fromDay: Long): List<SubstitutionEntity>

    @Query("SELECT * FROM substitutions WHERE dateEpochDay BETWEEN :fromDay AND :toDay ORDER BY dateEpochDay, lessonNumber")
    suspend fun getForRange(fromDay: Long, toDay: Long): List<SubstitutionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SubstitutionEntity>)

    /** Symulowane zastępstwa z trybu dewelopera (id "dev|..."). */
    @Query("DELETE FROM substitutions WHERE substr(id, 1, 4) = 'dev|'")
    suspend fun deleteDevEntries(): Int

    /** Jeden dzień - zastępowany świeżym kompletem z tej samej strony. */
    @Query("DELETE FROM substitutions WHERE dateEpochDay = :day")
    suspend fun deleteForDay(day: Long)

    @Query("DELETE FROM substitutions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)


    @Query("DELETE FROM substitutions WHERE dateEpochDay < :beforeDay")
    suspend fun deleteOlderThan(beforeDay: Long)

    @Query("DELETE FROM substitutions WHERE dateEpochDay >= :fromDay")
    suspend fun deleteFromDay(fromDay: Long)

    @Query("SELECT * FROM substitutions WHERE dateEpochDay BETWEEN :fromDay AND :toDay ORDER BY dateEpochDay, lessonNumber")
    fun observeForRange(fromDay: Long, toDay: Long): Flow<List<SubstitutionEntity>>
}

@Dao
interface AnnouncementDao {
    @Query("SELECT * FROM announcements WHERE isFavorite = 1")
    suspend fun getFavorites(): List<AnnouncementEntity>

    @Query("SELECT id, title, url, publishedAtEpochSeconds, excerpt, coverImageUrl, NULL AS fullHtml, isRead, source, isFavorite FROM announcements ORDER BY publishedAtEpochSeconds DESC")
    fun observeAll(): Flow<List<AnnouncementEntity>>

    @Query("SELECT id, title, url, publishedAtEpochSeconds, excerpt, coverImageUrl, NULL AS fullHtml, isRead, source, isFavorite FROM announcements ORDER BY publishedAtEpochSeconds DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<AnnouncementEntity>>

    @Query("SELECT * FROM announcements WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AnnouncementEntity?

    @Query("SELECT * FROM announcements WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<AnnouncementEntity?>

    @Query("UPDATE announcements SET isFavorite = NOT isFavorite WHERE id = :id")
    suspend fun toggleFavorite(id: String)

    @Query("UPDATE announcements SET isFavorite = 0 WHERE isFavorite = 1")
    suspend fun clearFavorites(): Int

    @Query("UPDATE announcements SET fullHtml = :html WHERE id = :id")
    suspend fun updateArticle(id: String, html: String)

    @Query("SELECT * FROM announcements WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<AnnouncementEntity>

    @Query("SELECT url FROM announcements WHERE url IN (:urls)")
    suspend fun existingUrls(urls: List<String>): List<String>

    @Query("SELECT COUNT(*) FROM announcements")
    suspend fun count(): Int

    @Query("SELECT MIN(publishedAtEpochSeconds) FROM announcements")
    suspend fun oldestEpochSeconds(): Long?

    /** Sprzątanie po usuniętym panelu dewelopera (symulowane ogłoszenia "dev_ann_..."). */
    @Query("DELETE FROM announcements WHERE substr(id, 1, 8) = 'dev_ann_'")
    suspend fun deleteDevEntries(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<AnnouncementEntity>)

    @Query("DELETE FROM announcements WHERE publishedAtEpochSeconds < :beforeEpochSeconds AND isFavorite = 0")
    suspend fun deleteOlderThan(beforeEpochSeconds: Long)
}

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications ORDER BY createdAtEpochSeconds DESC")
    fun observeAll(): Flow<List<NotificationItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: NotificationItemEntity)

    @Query("UPDATE notifications SET readAtEpochSeconds = :readAt WHERE id = :id")
    suspend fun markRead(id: String, readAt: Long)

    @Query("SELECT COUNT(*) FROM notifications WHERE readAtEpochSeconds IS NULL")
    fun observeUnreadCount(): Flow<Int>

    @Query("DELETE FROM notifications WHERE createdAtEpochSeconds < :beforeEpochSeconds")
    suspend fun deleteOlderThan(beforeEpochSeconds: Long)

    @Query("UPDATE notifications SET readAtEpochSeconds = :readAt WHERE readAtEpochSeconds IS NULL")
    suspend fun markAllRead(readAt: Long)
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE `key` = :key LIMIT 1")
    fun observe(key: String): Flow<SyncStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity)
}

@Dao
interface SchoolEventDao {
    @Query("SELECT * FROM school_events WHERE dateEpochDay = :dateEpochDay ORDER BY timeFrom ASC, id ASC")
    fun observeForDate(dateEpochDay: Long): Flow<List<SchoolEventEntity>>

    @Query("SELECT * FROM school_events WHERE dateEpochDay BETWEEN :fromDay AND :toDay ORDER BY dateEpochDay ASC, timeFrom ASC, id ASC")
    fun observeForRange(fromDay: Long, toDay: Long): Flow<List<SchoolEventEntity>>

    @Query("SELECT * FROM school_events WHERE dateEpochDay >= :fromDay ORDER BY dateEpochDay ASC, timeFrom ASC, id ASC LIMIT :limit")
    fun observeUpcoming(fromDay: Long, limit: Int): Flow<List<SchoolEventEntity>>

    @Query("SELECT * FROM school_events WHERE dateEpochDay BETWEEN :fromDay AND :toDay ORDER BY dateEpochDay ASC, timeFrom ASC, id ASC")
    suspend fun getForRange(fromDay: Long, toDay: Long): List<SchoolEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SchoolEventEntity>)

    @Query("DELETE FROM school_events")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(items: List<SchoolEventEntity>) {
        clear()
        upsertAll(items)
    }

    @Query("DELETE FROM school_events WHERE dateEpochDay < :beforeDay")
    suspend fun deleteOlderThan(beforeDay: Long)
}

@Dao
interface UserActivityDao {
    @Query("SELECT * FROM user_activities ORDER BY dateEpochDay ASC, timeFrom ASC, createdAtEpochSeconds ASC")
    fun getAll(): Flow<List<UserActivityEntity>>

    @Query("SELECT * FROM user_activities WHERE dateEpochDay = :dateEpochDay ORDER BY timeFrom ASC, createdAtEpochSeconds ASC")
    fun getByDate(dateEpochDay: Long): Flow<List<UserActivityEntity>>

    @Query("SELECT * FROM user_activities WHERE dateEpochDay BETWEEN :fromEpochDay AND :toEpochDay ORDER BY dateEpochDay ASC, timeFrom ASC, createdAtEpochSeconds ASC")
    fun getByRange(fromEpochDay: Long, toEpochDay: Long): Flow<List<UserActivityEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(activity: UserActivityEntity)

    @Query("DELETE FROM user_activities WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE user_activities SET isCompleted = :completed WHERE id = :id")
    suspend fun setCompleted(id: String, completed: Boolean)
}

@Dao
interface AttendanceDao {
    @Query("SELECT * FROM attendance_stats ORDER BY subject ASC")
    fun getAll(): Flow<List<AttendanceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(stats: List<AttendanceEntity>)

    @Query("DELETE FROM attendance_stats")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(stats: List<AttendanceEntity>) {
        clear()
        upsertAll(stats)
    }
}

