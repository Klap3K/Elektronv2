package pl.zse.bydgoszcz.elektron.domain.repository

import kotlinx.coroutines.flow.Flow
import pl.zse.bydgoszcz.elektron.domain.model.OverallAttendance
import pl.zse.bydgoszcz.elektron.domain.model.SubjectAttendance

interface AttendanceRepository {
    fun observeAll(): Flow<List<SubjectAttendance>>
    fun observeOverall(): Flow<OverallAttendance>
    suspend fun sync(): Result<Int>
    suspend fun saveStats(stats: List<SubjectAttendance>)
    suspend fun clear()
}
