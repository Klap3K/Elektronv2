package pl.zse.bydgoszcz.elektron.domain.repository

import kotlinx.coroutines.flow.Flow
import pl.zse.bydgoszcz.elektron.domain.model.StudentGrade
import pl.zse.bydgoszcz.elektron.domain.model.VulcanAccount

interface GradeRepository {
    val grades: Flow<List<StudentGrade>>

    /** Pobiera oceny z dziennika eduVULCAN dla podanego konta i zapisuje w lokalnej bazie Room. */
    suspend fun syncFromEduVulcan(account: VulcanAccount): Result<Int>

    /** Dodaje lub aktualizuje pojedynczą ocenę (np. ręczny wpis lub symulacja średniej). */
    suspend fun upsertGrade(grade: StudentGrade)

    /** Usuwa pojedynczą ocenę po identyfikatorze. */
    suspend fun deleteGrade(id: String)

    /** Czyści wszystkie zapisane oceny. */
    suspend fun clearGrades()
}
