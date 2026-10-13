package pl.zse.bydgoszcz.elektron.presentation.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.zse.bydgoszcz.elektron.domain.model.EduVulcanSession
import pl.zse.bydgoszcz.elektron.domain.model.SchoolEvent
import pl.zse.bydgoszcz.elektron.domain.model.VulcanAccount
import pl.zse.bydgoszcz.elektron.domain.model.VulcanRegistrationInput
import pl.zse.bydgoszcz.elektron.domain.repository.CalendarRepository
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val repository: CalendarRepository
) : ViewModel() {

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    private val _isRegistering = MutableStateFlow(false)
    val isRegistering: StateFlow<Boolean> = _isRegistering

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage

    val vulcanAccount: StateFlow<VulcanAccount?> = repository.vulcanAccount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val calendarUrl: StateFlow<String?> = repository.calendarUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val lastSyncEpochSeconds: StateFlow<Long?> = repository.lastSyncEpochSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val lastSyncError: StateFlow<String?> = repository.lastSyncError
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val upcomingEvents: StateFlow<List<SchoolEvent>> = repository.observeUpcomingEvents(limit = 30)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val semesterEvents: StateFlow<List<SchoolEvent>> = repository.observeEventsForRange(
        from = LocalDate.now().minusDays(60),
        to = LocalDate.now().plusDays(150)
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun eventsForDate(date: LocalDate): Flow<List<SchoolEvent>> =
        repository.observeEventsForDate(date)

    fun eventsForRange(from: LocalDate, to: LocalDate): Flow<List<SchoolEvent>> =
        repository.observeEventsForRange(from, to)

    /**
     * Zapisuje sesję eduVULCAN wyodrębnioną przez wbudowane okno logowania (WebView + Login/Hasło)
     * i natychmiast pobiera sprawdziany oraz frekwencję.
     */
    fun connectEduVulcanSession(session: EduVulcanSession) {
        if (_isRegistering.value) return
        _isRegistering.value = true
        viewModelScope.launch {
            try {
                val res = repository.connectEduVulcan(session)
                res.onSuccess { acc ->
                    _userMessage.value = "Połączono z eduVULCAN! Uczeń: ${acc.pupilName}"
                }.onFailure { err ->
                    _userMessage.value = "Błąd zapisu sesji eduVULCAN: ${err.localizedMessage ?: "nieznany błąd"}"
                }
            } finally {
                _isRegistering.value = false
            }
        }
    }

    /**
     * Loguje się hasłem do eduVULCAN w tle i synchronizuje sprawdziany oraz frekwencję.
     */
    fun loginEduVulcanWithPassword(username: String, password: String) {
        if (_isRegistering.value) return
        _isRegistering.value = true
        viewModelScope.launch {
            try {
                val res = repository.loginEduVulcanWithPassword(username, password)
                res.onSuccess { acc ->
                    _userMessage.value = "Zalogowano do eduVULCAN jako: ${acc.pupilName}"
                }.onFailure { err ->
                    _userMessage.value = "Błąd logowania eduVULCAN: ${err.localizedMessage ?: "sprawdź login i hasło"}"
                }
            } finally {
                _isRegistering.value = false
            }
        }
    }

    fun registerVulcan(token: String, symbol: String, pin: String) {
        val input = VulcanRegistrationInput(token = token.trim(), symbol = symbol.trim(), pin = pin.trim())
        executeVulcanRegistration(input)
    }

    fun registerVulcanFromInput(rawText: String) {
        val parsed = VulcanRegistrationInput.parseOrNull(rawText)
        if (parsed != null) {
            executeVulcanRegistration(parsed)
        } else {
            _userMessage.value = "Nie rozpoznano formatu kodu VULCAN."
        }
    }

    private fun executeVulcanRegistration(input: VulcanRegistrationInput) {
        if (_isRegistering.value) return
        _isRegistering.value = true
        viewModelScope.launch {
            try {
                val res = repository.registerVulcan(input)
                res.onSuccess { acc ->
                    _userMessage.value = "Połączono z VULCAN! Zalogowano jako: ${acc.pupilName}"
                }.onFailure { err ->
                    _userMessage.value = "Błąd łączenia z VULCAN: ${err.localizedMessage ?: "sprawdź dane logowania"}"
                }
            } finally {
                _isRegistering.value = false
            }
        }
    }

    fun clearVulcanAccount() {
        viewModelScope.launch {
            repository.clearVulcanAccount()
            _userMessage.value = "Odłączono konto eduVULCAN."
        }
    }

    fun setCalendarUrl(url: String?) {
        viewModelScope.launch {
            repository.setCalendarUrl(url)
            if (!url.isNullOrBlank()) {
                sync()
            }
        }
    }

    fun sync() {
        if (_isSyncing.value) return
        _isSyncing.value = true
        viewModelScope.launch {
            try {
                val result = repository.sync()
                result.onSuccess { count ->
                    _userMessage.value = "Zsynchronizowano $count sprawdzianów oraz statystyki frekwencji."
                }.onFailure { e ->
                    _userMessage.value = "Błąd synchronizacji: ${e.localizedMessage ?: "brak połączenia"}"
                }
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun clearCalendar() {
        viewModelScope.launch {
            repository.clear()
            _userMessage.value = "Wyczyszczono wszystkie wydarzenia i kalendarze."
        }
    }

    fun consumeUserMessage() {
        _userMessage.value = null
    }
}
