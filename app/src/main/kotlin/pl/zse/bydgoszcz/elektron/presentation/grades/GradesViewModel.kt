package pl.zse.bydgoszcz.elektron.presentation.grades

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.zse.bydgoszcz.elektron.domain.model.EduVulcanSession
import pl.zse.bydgoszcz.elektron.domain.model.GradeCalculator
import pl.zse.bydgoszcz.elektron.domain.model.StudentGrade
import pl.zse.bydgoszcz.elektron.domain.model.SubjectGradesSummary
import pl.zse.bydgoszcz.elektron.domain.model.VulcanAccount
import pl.zse.bydgoszcz.elektron.domain.repository.CalendarRepository
import pl.zse.bydgoszcz.elektron.domain.repository.GradeRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class GradesViewModel @Inject constructor(
    private val gradeRepository: GradeRepository,
    private val calendarRepository: CalendarRepository
) : ViewModel() {

    private val _selectedPeriod = MutableStateFlow<Int?>(null)
    val selectedPeriod: StateFlow<Int?> = _selectedPeriod.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    val vulcanAccount: StateFlow<VulcanAccount?> = calendarRepository.vulcanAccount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allGrades: StateFlow<List<StudentGrade>> = gradeRepository.grades
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val availablePeriods: StateFlow<List<Int>> = combine(allGrades) { (grades) ->
        grades.map { it.periodNumber }.distinct().sorted().ifEmpty { listOf(1) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf(1))

    val subjectSummaries: StateFlow<List<SubjectGradesSummary>> = combine(
        allGrades,
        _selectedPeriod
    ) { grades, period ->
        GradeCalculator.summarizeBySubject(grades, period)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val overallAverage: StateFlow<Double?> = combine(subjectSummaries) { (summaries) ->
        GradeCalculator.overallAverage(summaries)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun selectPeriod(period: Int) {
        _selectedPeriod.value = period
    }

    fun importFromEduVulcan() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            _statusMessage.value = null
            try {
                val account = calendarRepository.vulcanAccount.first()
                if (account == null) {
                    _statusMessage.value = "Najpierw zaloguj się do konta eduVULCAN."
                    return@launch
                }
                // Odśwież sesję i pobierz oceny
                val res = gradeRepository.syncFromEduVulcan(account)
                res.fold(
                    onSuccess = { count ->
                        _statusMessage.value = "Zaimportowano $count ocen z dziennika eduVULCAN."
                    },
                    onFailure = {
                        // Spróbuj pełnej synchronizacji z automatycznym odświeżeniem sesji w CalendarRepository
                        val fullSync = calendarRepository.sync()
                        if (fullSync.isSuccess) {
                            val totalNow = gradeRepository.grades.first().count { g -> g.value.isNotBlank() }
                            _statusMessage.value = "Zsynchronizowano oceny z eduVULCAN (łącznie: $totalNow)."
                        } else {
                            _statusMessage.value = it.localizedMessage ?: "Błąd importu ocen z eduVULCAN"
                        }
                    }
                )
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun connectAndImportEduVulcan(session: EduVulcanSession) {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            _statusMessage.value = "Pobieranie ocen z eduVULCAN..."
            try {
                calendarRepository.connectEduVulcan(session)
                val totalNow = gradeRepository.grades.first().count { it.value.isNotBlank() }
                _statusMessage.value = "Połączono z eduVULCAN i zaimportowano oceny ($totalNow)."
            } catch (e: Exception) {
                _statusMessage.value = e.localizedMessage ?: "Błąd podczas importu ocen"
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun addManualGrade(
        subject: String,
        value: String,
        weight: Double,
        category: String?,
        description: String?
    ) {
        val cleanSubj = subject.trim()
        val cleanVal = value.trim()
        if (cleanSubj.isBlank() || cleanVal.isBlank()) return
        val period = _selectedPeriod.value
            ?: allGrades.value.firstOrNull { it.isActivePeriod }?.periodNumber
            ?: 1
        val todayStr = LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))

        viewModelScope.launch {
            gradeRepository.upsertGrade(
                StudentGrade(
                    id = "manual_${UUID.randomUUID()}",
                    subject = cleanSubj,
                    value = cleanVal,
                    weight = weight.coerceAtLeast(0.5),
                    category = category?.trim()?.takeIf { it.isNotBlank() } ?: "Własny wpis",
                    description = description?.trim()?.takeIf { it.isNotBlank() },
                    teacher = null,
                    dateText = todayStr,
                    periodNumber = period,
                    isActivePeriod = true,
                    isSuperseded = false,
                    apiSubjectAverage = null,
                    proposedGrade = null,
                    finalGrade = null,
                    updatedAtEpochSeconds = System.currentTimeMillis() / 1000
                )
            )
            _statusMessage.value = "Dodano ocenę $cleanVal ($cleanSubj)"
        }
    }

    fun deleteGrade(id: String) {
        viewModelScope.launch {
            gradeRepository.deleteGrade(id)
        }
    }

    fun consumeStatusMessage() {
        _statusMessage.value = null
    }
}
