package pl.zse.bydgoszcz.elektron.domain.repository

import kotlinx.coroutines.flow.Flow
import pl.zse.bydgoszcz.elektron.domain.model.UserActivity
import java.time.LocalDate

interface UserActivityRepository {
    fun observeAll(): Flow<List<UserActivity>>
    fun observeForDate(date: LocalDate): Flow<List<UserActivity>>
    fun observeForRange(from: LocalDate, to: LocalDate): Flow<List<UserActivity>>
    suspend fun save(activity: UserActivity)
    suspend fun delete(id: String)
    suspend fun setCompleted(id: String, completed: Boolean)
}
