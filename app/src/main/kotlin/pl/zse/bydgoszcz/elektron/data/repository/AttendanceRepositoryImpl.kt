package pl.zse.bydgoszcz.elektron.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import pl.zse.bydgoszcz.elektron.data.local.AttendanceDao
import pl.zse.bydgoszcz.elektron.data.local.AttendanceEntity
import pl.zse.bydgoszcz.elektron.data.remote.vulcan.VulcanApiClient
import pl.zse.bydgoszcz.elektron.domain.model.LessonGroups
import pl.zse.bydgoszcz.elektron.domain.model.OverallAttendance
import pl.zse.bydgoszcz.elektron.domain.model.SubjectAttendance
import pl.zse.bydgoszcz.elektron.domain.repository.AttendanceRepository
import pl.zse.bydgoszcz.elektron.domain.repository.CalendarRepository
import pl.zse.bydgoszcz.elektron.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

import kotlinx.coroutines.flow.distinctUntilChanged
import pl.zse.bydgoszcz.elektron.domain.model.SchoolEvent

@Singleton
class AttendanceRepositoryImpl @Inject constructor(
    private val dao: AttendanceDao,
    private val calendarRepository: CalendarRepository,
    private val vulcanApiClient: VulcanApiClient,
    private val settingsRepository: SettingsRepository
) : AttendanceRepository {

    override fun observeAll(): Flow<List<SubjectAttendance>> =
        combine(
            dao.getAll().map { list -> list.map { it.toDomain() } },
            settingsRepository.activeGroupSelections
        ) { list, groups ->
            val isReligionDisabled = groups.any { (base, choice) ->
                LessonGroups.isReligion(base) && choice == LessonGroups.NONE
            }
            list.filterNot { item ->
                (isReligionDisabled && LessonGroups.isReligion(item.subject)) ||
                    groups.any { (base, choice) ->
                        choice == LessonGroups.NONE && (
                            base.equals(item.subject, ignoreCase = true) ||
                            SchoolEvent.subjectsMatch(item.subject, base)
                        )
                    }
            }
        }.distinctUntilChanged()

    override fun observeOverall(): Flow<OverallAttendance> =
        observeAll().map { OverallAttendance.fromSubjects(it) }

    override suspend fun sync(): Result<Int> = withContext(Dispatchers.IO) {
        // 1. Jeśli użytkownik jest zalogowany do eduVULCAN, pobierz prawdziwe statystyki frekwencji z uczen.eduvulcan.pl
        val account = runCatching { calendarRepository.vulcanAccount.first() }.getOrNull()
        if (account != null && account.isEduVulcan) {
            val res = vulcanApiClient.fetchEduVulcanAttendance(account)
            if (res.isSuccess) {
                val stats = res.getOrDefault(emptyList())
                if (stats.isNotEmpty()) {
                    val current = dao.getAll().first()
                    // Jeśli mamy już pełną listę przedmiotów w bazie, a z API przyszła podejrzanie mała liczba (< 3),
                    // nie usuwaj pozostałych przedmiotów za pomocą replaceAll, lecz zaktualizuj (upsertAll).
                    if (current.size > 3 && stats.size < 3) {
                        dao.upsertAll(stats.map { it.toEntity() })
                    } else {
                        dao.replaceAll(stats.map { it.toEntity() })
                    }
                    return@withContext Result.success(stats.size)
                }
            }
        }

        // 2. Jeśli brak podłączonego konta eduVULCAN i baza jest pusta, zainicjuj danymi demonstracyjnymi,
        // aby kalkulator / symulator w Strażniku Frekwencji działał od razu przed zalogowaniem
        val current = dao.getAll().first()
        if (current.isEmpty()) {
            val nowSeconds = System.currentTimeMillis() / 1000
            val initial = listOf(
                SubjectAttendance("Język polski", presentHours = 24, absentHours = 4, excusedHours = 2, lateHours = 1, schoolObligationHours = 0, lastUpdatedEpochSeconds = nowSeconds),
                SubjectAttendance("Język angielski", presentHours = 28, absentHours = 1, excusedHours = 1, lateHours = 0, schoolObligationHours = 0, lastUpdatedEpochSeconds = nowSeconds),
                SubjectAttendance("Matematyka", presentHours = 30, absentHours = 6, excusedHours = 4, lateHours = 2, schoolObligationHours = 0, lastUpdatedEpochSeconds = nowSeconds),
                SubjectAttendance("Informatyka", presentHours = 32, absentHours = 2, excusedHours = 0, lateHours = 1, schoolObligationHours = 0, lastUpdatedEpochSeconds = nowSeconds),
                SubjectAttendance("Fizyka", presentHours = 14, absentHours = 8, excusedHours = 3, lateHours = 0, schoolObligationHours = 0, lastUpdatedEpochSeconds = nowSeconds),
                SubjectAttendance("Wychowanie fizyczne", presentHours = 20, absentHours = 10, excusedHours = 5, lateHours = 0, schoolObligationHours = 0, lastUpdatedEpochSeconds = nowSeconds),
                SubjectAttendance("Historia", presentHours = 16, absentHours = 2, excusedHours = 1, lateHours = 0, schoolObligationHours = 0, lastUpdatedEpochSeconds = nowSeconds)
            )
            dao.replaceAll(initial.map { it.toEntity() })
            return@withContext Result.success(initial.size)
        }
        Result.success(current.size)
    }

    override suspend fun saveStats(stats: List<SubjectAttendance>) {
        dao.replaceAll(stats.map { it.toEntity() })
    }

    override suspend fun clear() {
        dao.clear()
    }

    private fun AttendanceEntity.toDomain(): SubjectAttendance =
        SubjectAttendance(
            subject = subject,
            presentHours = presentHours,
            absentHours = absentHours,
            excusedHours = excusedHours,
            lateHours = lateHours,
            schoolObligationHours = schoolObligationHours,
            lastUpdatedEpochSeconds = lastUpdatedEpochSeconds
        )

    private fun SubjectAttendance.toEntity(): AttendanceEntity =
        AttendanceEntity(
            subject = subject,
            presentHours = presentHours,
            absentHours = absentHours,
            excusedHours = excusedHours,
            lateHours = lateHours,
            schoolObligationHours = schoolObligationHours,
            lastUpdatedEpochSeconds = lastUpdatedEpochSeconds
        )
}
