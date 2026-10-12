package pl.zse.bydgoszcz.elektron.presentation.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.zse.bydgoszcz.elektron.domain.model.UserActivity
import pl.zse.bydgoszcz.elektron.domain.repository.UserActivityRepository
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class UserActivityViewModel @Inject constructor(
    private val repo: UserActivityRepository
) : ViewModel() {

    fun activitiesForDate(date: LocalDate): Flow<List<UserActivity>> =
        repo.observeForDate(date)

    fun activitiesForRange(from: LocalDate, to: LocalDate): Flow<List<UserActivity>> =
        repo.observeForRange(from, to)

    fun addActivity(activity: UserActivity) {
        viewModelScope.launch {
            repo.save(activity)
        }
    }

    fun deleteActivity(id: String) {
        viewModelScope.launch {
            repo.delete(id)
        }
    }

    fun toggleCompleted(id: String, completed: Boolean) {
        viewModelScope.launch {
            repo.setCompleted(id, completed)
        }
    }
}
