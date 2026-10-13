package pl.zse.bydgoszcz.elektron.testutil

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import pl.zse.bydgoszcz.elektron.domain.model.AccentSetting
import pl.zse.bydgoszcz.elektron.domain.model.QuietHours
import pl.zse.bydgoszcz.elektron.domain.model.ReminderSettings
import pl.zse.bydgoszcz.elektron.domain.model.StartScreen
import pl.zse.bydgoszcz.elektron.domain.model.SubjectStyle
import pl.zse.bydgoszcz.elektron.domain.model.TimetableLook
import pl.zse.bydgoszcz.elektron.domain.model.WidgetLook
import pl.zse.bydgoszcz.elektron.domain.repository.SettingsRepository
import pl.zse.bydgoszcz.elektron.domain.repository.ThemeMode

/** Ustawienia w pamięci do testów (klasa, powiadomienia i przypomnienia do zmiany w teście). */
class FakeSettings(
    classId: String? = "o3",
    reminders: ReminderSettings = ReminderSettings()
) : SettingsRepository {
    val classIdFlow = MutableStateFlow(classId)
    val notifySubs = MutableStateFlow(true)
    val notifyAnns = MutableStateFlow(false)
    val reminderFlow = MutableStateFlow(reminders)
    val groupSelectionsFlow = MutableStateFlow<Map<String, String>>(emptyMap())

    override val selectedClassId: Flow<String?> = classIdFlow
    override suspend fun setSelectedClassId(id: String) { classIdFlow.value = id }
    override val themeMode: Flow<ThemeMode> = flowOf(ThemeMode.SYSTEM)
    override suspend fun setThemeMode(mode: ThemeMode) {}
    override val dynamicColor: Flow<Boolean> = flowOf(false)
    override suspend fun setDynamicColor(enabled: Boolean) {}
    override val notificationsSubstitutions: Flow<Boolean> = notifySubs
    override suspend fun setNotificationsSubstitutions(enabled: Boolean) { notifySubs.value = enabled }
    override val notificationsAnnouncements: Flow<Boolean> = notifyAnns
    override suspend fun setNotificationsAnnouncements(enabled: Boolean) {}
    override val showNextLesson: Flow<Boolean> = flowOf(true)
    override suspend fun setShowNextLesson(enabled: Boolean) {}
    override val showSubstitutions: Flow<Boolean> = flowOf(true)
    override suspend fun setShowSubstitutions(enabled: Boolean) {}
    override val showAnnouncements: Flow<Boolean> = flowOf(true)
    override suspend fun setShowAnnouncements(enabled: Boolean) {}
    override val startScreen: Flow<StartScreen> = flowOf(StartScreen.SMART)
    override suspend fun setStartScreen(screen: StartScreen) {}
    override val reminderSettings: Flow<ReminderSettings> = reminderFlow
    override suspend fun setReminderSettings(value: ReminderSettings) { reminderFlow.value = value }
    override val quietHours: Flow<QuietHours> = flowOf(QuietHours())
    override suspend fun setQuietHours(value: QuietHours) {}
    override val accent: Flow<AccentSetting> = flowOf(AccentSetting())
    override suspend fun setAccent(value: AccentSetting) {}
    override val widgetLook: Flow<WidgetLook> = flowOf(WidgetLook())
    override suspend fun setWidgetLook(value: WidgetLook) {}
    override val subjectStyles: Flow<Map<String, SubjectStyle>> = flowOf(emptyMap())
    override suspend fun setSubjectStyle(subject: String, style: SubjectStyle) {}
    override val timetableLook: Flow<TimetableLook> = flowOf(TimetableLook())
    override suspend fun setTimetableLook(look: TimetableLook) {}
    override val lastSeenVersionCode: Flow<Int> = flowOf(0)
    override suspend fun setLastSeenVersionCode(code: Int) {}
    val countdownFlow = MutableStateFlow(pl.zse.bydgoszcz.elektron.domain.model.CountdownSettings())
    val autoDndFlow = MutableStateFlow(false)

    override val countdownSettings: Flow<pl.zse.bydgoszcz.elektron.domain.model.CountdownSettings> = countdownFlow
    override suspend fun setCountdownSettings(value: pl.zse.bydgoszcz.elektron.domain.model.CountdownSettings) { countdownFlow.value = value }
    override val autoDndEnabled: Flow<Boolean> = autoDndFlow
    override suspend fun setAutoDndEnabled(enabled: Boolean) { autoDndFlow.value = enabled }

    val liveBellFlow = MutableStateFlow(true)
    val dashboardCardsOrderFlow = MutableStateFlow<String?>(null)

    override val liveBellNotificationEnabled: Flow<Boolean> = liveBellFlow
    override suspend fun setLiveBellNotificationEnabled(enabled: Boolean) { liveBellFlow.value = enabled }
    override val dashboardCardsOrder: Flow<String?> = dashboardCardsOrderFlow
    override suspend fun setDashboardCardsOrder(order: String) { dashboardCardsOrderFlow.value = order }

    val attendanceTargetPercentFlow = MutableStateFlow(75)
    override val attendanceTargetPercent: Flow<Int> = attendanceTargetPercentFlow
    override suspend fun setAttendanceTargetPercent(percent: Int) { attendanceTargetPercentFlow.value = percent }

    override fun groupSelections(classId: String): Flow<Map<String, String>> = groupSelectionsFlow
    override val activeGroupSelections: Flow<Map<String, String>> = groupSelectionsFlow
    override suspend fun setGroupSelection(classId: String, subject: String, choice: String?) {}
    override val groupsConfiguredFor: Flow<String?> = flowOf(classId)
    override suspend fun setGroupsConfiguredFor(classId: String) {}
}
