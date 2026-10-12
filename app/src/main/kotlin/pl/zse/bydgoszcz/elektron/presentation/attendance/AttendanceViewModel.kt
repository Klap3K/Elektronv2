package pl.zse.bydgoszcz.elektron.presentation.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.zse.bydgoszcz.elektron.domain.model.OverallAttendance
import pl.zse.bydgoszcz.elektron.domain.model.SubjectAttendance
import pl.zse.bydgoszcz.elektron.domain.repository.AttendanceRepository
import javax.inject.Inject

@HiltViewModel
class AttendanceViewModel @Inject constructor(
    private val repo: AttendanceRepository
) : ViewModel() {

    private val simulatedMissed = MutableStateFlow(0)
    private val selectedSubjectForSimulation = MutableStateFlow<String?>(null)

    val overallAttendance: StateFlow<OverallAttendance?> = repo.observeOverall()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    data class SimulationState(
        val simulatedMissedHours: Int = 0,
        val simulatedSubject: String? = null,
        val simulatedOverallPercentage: Float? = null,
        val simulatedSubjectPercentage: Float? = null
    )

    val simulationState: StateFlow<SimulationState> = combine(
        repo.observeOverall(),
        simulatedMissed,
        selectedSubjectForSimulation
    ) { overall, missed, subjName ->
        val subj = overall.subjects.firstOrNull { it.subject == subjName }
        val simOverall = overall.simulate(simulatedAbsentHours = missed)
        val simSubj = subj?.simulate(simulatedAbsentHours = missed)
        SimulationState(
            simulatedMissedHours = missed,
            simulatedSubject = subjName,
            simulatedOverallPercentage = simOverall,
            simulatedSubjectPercentage = simSubj
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SimulationState())

    init {
        viewModelScope.launch {
            repo.sync()
        }
    }

    fun setSimulatedMissed(hours: Int) {
        simulatedMissed.value = hours.coerceIn(0, 100)
    }

    fun selectSubjectForSimulation(subject: String?) {
        selectedSubjectForSimulation.value = subject
    }

    fun refresh() {
        viewModelScope.launch {
            repo.sync()
        }
    }
}
