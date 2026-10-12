package pl.zse.bydgoszcz.elektron.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import pl.zse.bydgoszcz.elektron.data.local.GradeDao
import pl.zse.bydgoszcz.elektron.data.local.GradeEntity
import pl.zse.bydgoszcz.elektron.data.remote.vulcan.VulcanApiClient
import pl.zse.bydgoszcz.elektron.domain.model.StudentGrade
import pl.zse.bydgoszcz.elektron.domain.model.VulcanAccount
import pl.zse.bydgoszcz.elektron.domain.repository.GradeRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GradeRepositoryImpl @Inject constructor(
    private val dao: GradeDao,
    private val vulcanApiClient: VulcanApiClient
) : GradeRepository {

    override val grades: Flow<List<StudentGrade>> = dao.observeAll()
        .map { list -> list.map { it.toDomain() } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    override suspend fun syncFromEduVulcan(account: VulcanAccount): Result<Int> = withContext(Dispatchers.IO) {
        val res = vulcanApiClient.fetchEduVulcanGrades(account)
        res.map { remoteGrades ->
            val manualGrades = dao.getAll().filter { it.id.startsWith("manual_") }
            val entities = (remoteGrades.map { it.toEntity() } + manualGrades).distinctBy { it.id }
            dao.replaceAll(entities)
            remoteGrades.count { it.value.isNotBlank() }
        }
    }

    override suspend fun upsertGrade(grade: StudentGrade) = withContext(Dispatchers.IO) {
        dao.upsert(grade.toEntity())
    }

    override suspend fun deleteGrade(id: String) = withContext(Dispatchers.IO) {
        dao.deleteById(id)
    }

    override suspend fun clearGrades() = withContext(Dispatchers.IO) {
        dao.clear()
    }
}

internal fun GradeEntity.toDomain(): StudentGrade = StudentGrade(
    id = id,
    subject = subject,
    value = value,
    weight = weight,
    category = category,
    description = description,
    teacher = teacher,
    dateText = dateText,
    periodNumber = periodNumber,
    isActivePeriod = isActivePeriod,
    isSuperseded = isSuperseded,
    apiSubjectAverage = apiSubjectAverage,
    proposedGrade = proposedGrade,
    finalGrade = finalGrade,
    updatedAtEpochSeconds = updatedAtEpochSeconds
)

internal fun StudentGrade.toEntity(): GradeEntity = GradeEntity(
    id = id,
    subject = subject,
    value = value,
    weight = weight,
    category = category,
    description = description,
    teacher = teacher,
    dateText = dateText,
    periodNumber = periodNumber,
    isActivePeriod = isActivePeriod,
    isSuperseded = isSuperseded,
    apiSubjectAverage = apiSubjectAverage,
    proposedGrade = proposedGrade,
    finalGrade = finalGrade,
    updatedAtEpochSeconds = updatedAtEpochSeconds
)
