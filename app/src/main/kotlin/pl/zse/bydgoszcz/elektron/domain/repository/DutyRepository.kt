package pl.zse.bydgoszcz.elektron.domain.repository

import kotlinx.coroutines.flow.Flow
import pl.zse.bydgoszcz.elektron.domain.model.ClassroomDuty
import java.time.LocalDate

interface DutyRepository {
    val studentNumber: Flow<Int?>
    val classSize: Flow<Int>
    fun observeDuty(date: LocalDate = LocalDate.now()): Flow<ClassroomDuty>
    suspend fun setStudentNumber(number: Int?)
    suspend fun setClassSize(size: Int)
}
