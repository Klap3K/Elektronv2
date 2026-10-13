package pl.zse.bydgoszcz.elektron.domain.repository

import pl.zse.bydgoszcz.elektron.domain.model.AccentSetting
import pl.zse.bydgoszcz.elektron.domain.model.WidgetLook
import pl.zse.bydgoszcz.elektron.domain.model.QuietHours
import pl.zse.bydgoszcz.elektron.domain.model.ReminderSettings
import pl.zse.bydgoszcz.elektron.domain.model.StartScreen
import pl.zse.bydgoszcz.elektron.domain.model.TimetableLook
import pl.zse.bydgoszcz.elektron.domain.model.UpdateChannel
import pl.zse.bydgoszcz.elektron.domain.model.SubjectStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

enum class ThemeMode { SYSTEM, LIGHT, DARK }

interface SettingsRepository {
    val selectedClassId: Flow<String?>
    suspend fun setSelectedClassId(id: String)

    val themeMode: Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)

    val dynamicColor: Flow<Boolean>
    suspend fun setDynamicColor(enabled: Boolean)

    val notificationsSubstitutions: Flow<Boolean>
    suspend fun setNotificationsSubstitutions(enabled: Boolean)

    val notificationsAnnouncements: Flow<Boolean>
    suspend fun setNotificationsAnnouncements(enabled: Boolean)

    val showNextLesson: Flow<Boolean>
    suspend fun setShowNextLesson(enabled: Boolean)

    val showSubstitutions: Flow<Boolean>
    suspend fun setShowSubstitutions(enabled: Boolean)

    val showAnnouncements: Flow<Boolean>
    suspend fun setShowAnnouncements(enabled: Boolean)

    /** Ekran startowy; domyślnie SMART (w godzinach lekcji plan). */
    val startScreen: Flow<StartScreen>
    suspend fun setStartScreen(screen: StartScreen)

    /** Przypomnienia przed lekcją. */
    val reminderSettings: Flow<ReminderSettings>
    suspend fun setReminderSettings(value: ReminderSettings)

    /** Ciche godziny: powiadomienia bez dźwięku i wibracji. */
    val quietHours: Flow<QuietHours>
    suspend fun setQuietHours(value: QuietHours)

    /** Kolor akcentu aplikacji i widżetów. */
    val accent: Flow<AccentSetting>
    suspend fun setAccent(value: AccentSetting)

    val widgetLook: Flow<WidgetLook>
    suspend fun setWidgetLook(value: WidgetLook)

    /** Własne nazwy i kolory przedmiotów (klucz: przedmiot bez sufiksu grupy). */
    val subjectStyles: Flow<Map<String, SubjectStyle>>
    suspend fun setSubjectStyle(subject: String, style: SubjectStyle)

    val timetableLook: Flow<TimetableLook>
    suspend fun setTimetableLook(look: TimetableLook)

    /** Odliczanie do wybranego wydarzenia (np. Wakacje, 100-dniówka, Egzamin zawodowy). */
    val countdownSettings: Flow<pl.zse.bydgoszcz.elektron.domain.model.CountdownSettings>
    suspend fun setCountdownSettings(value: pl.zse.bydgoszcz.elektron.domain.model.CountdownSettings)

    /** Automatyczne wyciszanie telefonu podczas trwania lekcji (Auto DND). */
    val autoDndEnabled: Flow<Boolean>
    suspend fun setAutoDndEnabled(enabled: Boolean)

    /** Kanał aktualizacji z GitHuba; domyślnie stabilny. */
    val updateChannel: Flow<UpdateChannel> get() = flowOf(UpdateChannel.STABLE)
    suspend fun setUpdateChannel(channel: UpdateChannel) {}

    val lastSeenVersionCode: Flow<Int>
    suspend fun setLastSeenVersionCode(code: Int)

    // --- Grupy zajęciowe (per klasa): przedmiot bazowy -> etykieta grupy lub LessonGroups.NONE ---
    fun groupSelections(classId: String): Flow<Map<String, String>>
    /** Wybory dla aktualnie wybranej klasy (pusta mapa, gdy brak klasy). */
    val activeGroupSelections: Flow<Map<String, String>>
    /** choice == null usuwa wybór (= pokazuj wszystkie grupy tego przedmiotu). */
    suspend fun setGroupSelection(classId: String, subject: String, choice: String?)

    /** Klasa, dla której użytkownik przeszedł krok wyboru grup (null = jeszcze nigdy). */
    val groupsConfiguredFor: Flow<String?>
    suspend fun setGroupsConfiguredFor(classId: String)
    /** Atomic production write; failures propagate to the groups editor. */
    suspend fun saveGroupChoices(classId: String, changes: Map<String, String?>) {
        changes.forEach { (subject, choice) -> setGroupSelection(classId, subject, choice) }
        setGroupsConfiguredFor(classId)
    }
}
