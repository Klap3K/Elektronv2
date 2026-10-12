package pl.zse.bydgoszcz.elektron.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pl.zse.bydgoszcz.elektron.data.local.UserActivityDao
import pl.zse.bydgoszcz.elektron.data.local.UserActivityEntity
import pl.zse.bydgoszcz.elektron.domain.model.ActivityCategory
import pl.zse.bydgoszcz.elektron.domain.model.UserActivity
import pl.zse.bydgoszcz.elektron.domain.repository.UserActivityRepository
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserActivityRepositoryImpl @Inject constructor(
    private val dao: UserActivityDao
) : UserActivityRepository {

    override fun observeAll(): Flow<List<UserActivity>> =
        dao.getAll().map { list -> list.map { it.toDomain() } }

    override fun observeForDate(date: LocalDate): Flow<List<UserActivity>> =
        dao.getByDate(date.toEpochDay()).map { list -> list.map { it.toDomain() } }

    override fun observeForRange(from: LocalDate, to: LocalDate): Flow<List<UserActivity>> =
        dao.getByRange(from.toEpochDay(), to.toEpochDay()).map { list -> list.map { it.toDomain() } }

    override suspend fun save(activity: UserActivity) {
        dao.upsert(activity.toEntity())
    }

    override suspend fun delete(id: String) {
        dao.delete(id)
    }

    override suspend fun setCompleted(id: String, completed: Boolean) {
        dao.setCompleted(id, completed)
    }

    private fun UserActivityEntity.toDomain(): UserActivity =
        UserActivity(
            id = id,
            title = title,
            description = description,
            category = ActivityCategory.fromString(category),
            date = LocalDate.ofEpochDay(dateEpochDay),
            timeFrom = timeFrom?.takeIf { it.isNotBlank() }?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
            timeTo = timeTo?.takeIf { it.isNotBlank() }?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
            isCompleted = isCompleted,
            createdAtEpochSeconds = createdAtEpochSeconds
        )

    private fun UserActivity.toEntity(): UserActivityEntity =
        UserActivityEntity(
            id = id,
            title = title,
            description = description,
            category = category.name,
            dateEpochDay = date.toEpochDay(),
            timeFrom = timeFrom?.toString(),
            timeTo = timeTo?.toString(),
            isCompleted = isCompleted,
            createdAtEpochSeconds = createdAtEpochSeconds
        )
}
