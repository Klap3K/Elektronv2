package pl.zse.bydgoszcz.elektron.data.repository

import androidx.datastore.preferences.core.longPreferencesKey
import pl.zse.bydgoszcz.elektron.domain.model.AccentSetting
import pl.zse.bydgoszcz.elektron.domain.model.AccentColor
import java.time.LocalTime
import pl.zse.bydgoszcz.elektron.domain.model.ReminderMode
import pl.zse.bydgoszcz.elektron.domain.model.WidgetLook
import pl.zse.bydgoszcz.elektron.domain.model.QuietHours
import pl.zse.bydgoszcz.elektron.domain.model.ReminderSettings
import pl.zse.bydgoszcz.elektron.domain.model.StartScreen
import pl.zse.bydgoszcz.elektron.domain.model.TimetableLook
import pl.zse.bydgoszcz.elektron.domain.model.UpdateChannel
import pl.zse.bydgoszcz.elektron.domain.model.SubjectStyles
import pl.zse.bydgoszcz.elektron.domain.model.SubjectStyle
import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.catch
import java.io.IOException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import pl.zse.bydgoszcz.elektron.domain.repository.SettingsRepository
import pl.zse.bydgoszcz.elektron.domain.repository.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton

// Uszkodzony plik ustawień (np. przerwany zapis) - zaczynamy od domyślnych zamiast wysypywać
// każdy ekran, widżet i worker czytający ustawienia.
private val Context.elektronSettings: DataStore<Preferences> by preferencesDataStore(
    name = "elektron_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { e ->
        Log.w("SettingsRepository", "Uszkodzony plik ustawień - przywracam domyślne", e)
        emptyPreferences()
    }
)

@Singleton
class SettingsRepositoryImpl internal constructor(
    private val store: DataStore<Preferences>
) : SettingsRepository {

    @Inject constructor(@ApplicationContext context: Context) : this(context.elektronSettings)

    /**
     * Zapis ustawień odporny na błąd pliku (np. brak miejsca na dysku): dawniej IOException
     * z DataStore jest przekazywany wywołującemu. UI zachowuje poprzedni wybór i pokazuje
     * komunikat; nie wolno przedstawiać nieudanego zapisu jako sukcesu.
     */
    private suspend fun safeEdit(transform: suspend (MutablePreferences) -> Unit) {
        store.edit(transform)
    }

    private object Keys {
        val SELECTED_CLASS_ID = stringPreferencesKey("selected_class_id")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        // Nowy klucz (0.5.0): stary "dynamic_color" miał domyślnie true z czasów, gdy opcja
        // była nieaktywna — podpięcie go zmieniłoby wszystkim wygląd bez pytania.
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color_v2")
        val NOTIF_SUBSTITUTIONS = booleanPreferencesKey("notif_substitutions")
        val NOTIF_ANNOUNCEMENTS = booleanPreferencesKey("notif_announcements")
        val SHOW_NEXT_LESSON = booleanPreferencesKey("show_next_lesson")
        val SHOW_SUBSTITUTIONS = booleanPreferencesKey("show_substitutions")
        val SHOW_ANNOUNCEMENTS = booleanPreferencesKey("show_announcements")
        val SMART_START = booleanPreferencesKey("smart_start")
        val START_SCREEN = stringPreferencesKey("start_screen")
        val REMINDER_MODE = stringPreferencesKey("reminder_mode")
        val REMINDER_MINUTES = intPreferencesKey("reminder_minutes")
        val QUIET_ENABLED = booleanPreferencesKey("quiet_enabled")
        val QUIET_FROM = intPreferencesKey("quiet_from_min")
        val QUIET_TO = intPreferencesKey("quiet_to_min")
        val ACCENT = stringPreferencesKey("accent_color")
        val ACCENT_CUSTOM = longPreferencesKey("accent_custom")
        val WIDGET_OPACITY = intPreferencesKey("widget_opacity")
        val WIDGET_TEACHER = booleanPreferencesKey("widget_show_teacher")
        val WIDGET_ROOM = booleanPreferencesKey("widget_show_room")
        val SUBJECT_STYLES = stringPreferencesKey("subject_styles")
        val LOOK_WEEK = booleanPreferencesKey("timetable_week_view")
        val LOOK_ROOM = booleanPreferencesKey("timetable_show_room")
        val LOOK_TEACHER = booleanPreferencesKey("timetable_show_teacher")
        val COUNTDOWN_ENABLED = booleanPreferencesKey("countdown_enabled")
        val COUNTDOWN_TITLE = stringPreferencesKey("countdown_title")
        val COUNTDOWN_TARGET_EPOCH = longPreferencesKey("countdown_target_epoch")
        val AUTO_DND_ENABLED = booleanPreferencesKey("auto_dnd_enabled")
        val LAST_SEEN_VERSION = intPreferencesKey("last_seen_version_code")
        val UPDATE_CHANNEL = stringPreferencesKey("update_channel")
        val GROUPS_CONFIGURED_FOR = stringPreferencesKey("groups_configured_for")
        fun groups(classId: String) = stringPreferencesKey("groups_$classId")
    }

    // Błąd odczytu pliku (IOException) = domyślne ustawienia zamiast awarii kolektora.
    private val prefs: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) {
            Log.w("SettingsRepository", "Nie udało się odczytać ustawień", e)
            emit(emptyPreferences())
        } else throw e
    }

    // distinctUntilChanged: każdy zapis DOWOLNEGO ustawienia emituje nowe Preferences - bez tego
    // klasa "zmieniała się" przy każdym przełączniku i restartowała łańcuchy flatMapLatest
    // (ponowne zapytania do bazy na Starcie i w Planie).
    override val selectedClassId: Flow<String?> = prefs.map { it[Keys.SELECTED_CLASS_ID] }.distinctUntilChanged()
    override suspend fun setSelectedClassId(id: String) {
        safeEdit { it[Keys.SELECTED_CLASS_ID] = id }
    }

    override val themeMode: Flow<ThemeMode> = prefs.map {
        runCatching { ThemeMode.valueOf(it[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
    }.distinctUntilChanged()
    override suspend fun setThemeMode(mode: ThemeMode) {
        safeEdit { it[Keys.THEME_MODE] = mode.name }
    }

    override val dynamicColor: Flow<Boolean> = prefs.map { it[Keys.DYNAMIC_COLOR] ?: false }.distinctUntilChanged()
    override suspend fun setDynamicColor(enabled: Boolean) {
        safeEdit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    override val notificationsSubstitutions: Flow<Boolean> =
        prefs.map { it[Keys.NOTIF_SUBSTITUTIONS] ?: true }.distinctUntilChanged()
    override suspend fun setNotificationsSubstitutions(enabled: Boolean) {
        safeEdit { it[Keys.NOTIF_SUBSTITUTIONS] = enabled }
    }

    override val notificationsAnnouncements: Flow<Boolean> =
        prefs.map { it[Keys.NOTIF_ANNOUNCEMENTS] ?: false }.distinctUntilChanged()
    override suspend fun setNotificationsAnnouncements(enabled: Boolean) {
        safeEdit { it[Keys.NOTIF_ANNOUNCEMENTS] = enabled }
    }

    override val showNextLesson: Flow<Boolean> = prefs.map { it[Keys.SHOW_NEXT_LESSON] ?: true }.distinctUntilChanged()
    override suspend fun setShowNextLesson(enabled: Boolean) {
        safeEdit { it[Keys.SHOW_NEXT_LESSON] = enabled }
    }

    override val showSubstitutions: Flow<Boolean> = prefs.map { it[Keys.SHOW_SUBSTITUTIONS] ?: true }.distinctUntilChanged()
    override suspend fun setShowSubstitutions(enabled: Boolean) {
        safeEdit { it[Keys.SHOW_SUBSTITUTIONS] = enabled }
    }

    override val showAnnouncements: Flow<Boolean> = prefs.map { it[Keys.SHOW_ANNOUNCEMENTS] ?: true }.distinctUntilChanged()
    override suspend fun setShowAnnouncements(enabled: Boolean) {
        safeEdit { it[Keys.SHOW_ANNOUNCEMENTS] = enabled }
    }

    // Bez zapisanego wyboru: z dawnego przełącznika "Otwieraj plan w trakcie lekcji" (0.5).
    override val startScreen: Flow<StartScreen> = prefs.map {
        StartScreen.fromKey(it[Keys.START_SCREEN])
            ?: if (it[Keys.SMART_START] ?: true) StartScreen.SMART else StartScreen.DASHBOARD
    }.distinctUntilChanged()
    override suspend fun setStartScreen(screen: StartScreen) {
        safeEdit { it[Keys.START_SCREEN] = screen.key }
    }

    override val reminderSettings: Flow<ReminderSettings> = prefs.map {
        ReminderSettings(ReminderMode.fromKey(it[Keys.REMINDER_MODE]), it[Keys.REMINDER_MINUTES] ?: 10)
    }.distinctUntilChanged()
    override suspend fun setReminderSettings(value: ReminderSettings) {
        safeEdit {
            it[Keys.REMINDER_MODE] = value.mode.key
            it[Keys.REMINDER_MINUTES] = value.minutesBefore
        }
    }

    override val quietHours: Flow<QuietHours> = prefs.map {
        QuietHours(
            enabled = it[Keys.QUIET_ENABLED] ?: false,
            from = LocalTime.ofSecondOfDay(((it[Keys.QUIET_FROM] ?: (22 * 60)).coerceIn(0, 1439) * 60).toLong()),
            to = LocalTime.ofSecondOfDay(((it[Keys.QUIET_TO] ?: (7 * 60)).coerceIn(0, 1439) * 60).toLong())
        )
    }.distinctUntilChanged()
    override suspend fun setQuietHours(value: QuietHours) {
        safeEdit {
            it[Keys.QUIET_ENABLED] = value.enabled
            it[Keys.QUIET_FROM] = value.from.hour * 60 + value.from.minute
            it[Keys.QUIET_TO] = value.to.hour * 60 + value.to.minute
        }
    }

    override val accent: Flow<AccentSetting> = prefs.map {
        AccentSetting(AccentColor.fromKey(it[Keys.ACCENT]), it[Keys.ACCENT_CUSTOM] ?: AccentSetting.DEFAULT_CUSTOM)
    }.distinctUntilChanged()
    override suspend fun setAccent(value: AccentSetting) {
        safeEdit {
            it[Keys.ACCENT] = value.color.key
            it[Keys.ACCENT_CUSTOM] = value.custom
        }
    }

    override val widgetLook: Flow<WidgetLook> = prefs.map {
        WidgetLook(
            opacity = (it[Keys.WIDGET_OPACITY] ?: 100).coerceIn(0, 100),
            showTeacher = it[Keys.WIDGET_TEACHER] ?: true,
            showRoom = it[Keys.WIDGET_ROOM] ?: true
        )
    }.distinctUntilChanged()
    override suspend fun setWidgetLook(value: WidgetLook) {
        safeEdit {
            it[Keys.WIDGET_OPACITY] = value.opacity
            it[Keys.WIDGET_TEACHER] = value.showTeacher
            it[Keys.WIDGET_ROOM] = value.showRoom
        }
    }

    override val subjectStyles: Flow<Map<String, SubjectStyle>> =
        prefs.map { SubjectStyles.decode(it[Keys.SUBJECT_STYLES]) }.distinctUntilChanged()

    override suspend fun setSubjectStyle(subject: String, style: SubjectStyle) {
        safeEdit {
            val current = SubjectStyles.decode(it[Keys.SUBJECT_STYLES]).toMutableMap()
            if (style.isDefault) current.remove(subject) else current[subject] = style
            it[Keys.SUBJECT_STYLES] = SubjectStyles.encode(current)
        }
    }

    override val timetableLook: Flow<TimetableLook> = prefs.map {
        TimetableLook(
            weekView = it[Keys.LOOK_WEEK] ?: false,
            showRoom = it[Keys.LOOK_ROOM] ?: true,
            showTeacher = it[Keys.LOOK_TEACHER] ?: true
        )
    }.distinctUntilChanged()

    override suspend fun setTimetableLook(look: TimetableLook) {
        safeEdit {
            it[Keys.LOOK_WEEK] = look.weekView
            it[Keys.LOOK_ROOM] = look.showRoom
            it[Keys.LOOK_TEACHER] = look.showTeacher
        }
    }

    override val countdownSettings: Flow<pl.zse.bydgoszcz.elektron.domain.model.CountdownSettings> = prefs.map {
        val enabled = it[Keys.COUNTDOWN_ENABLED] ?: false
        val title = it[Keys.COUNTDOWN_TITLE]?.takeIf { t -> t.isNotBlank() } ?: "Wakacje"
        val epoch = it[Keys.COUNTDOWN_TARGET_EPOCH]
        val targetDate = if (epoch != null) {
            java.time.LocalDate.ofEpochDay(epoch)
        } else {
            pl.zse.bydgoszcz.elektron.domain.model.CountdownSettings.defaultTargetDate()
        }
        pl.zse.bydgoszcz.elektron.domain.model.CountdownSettings(enabled, title, targetDate)
    }.distinctUntilChanged()

    override suspend fun setCountdownSettings(value: pl.zse.bydgoszcz.elektron.domain.model.CountdownSettings) {
        safeEdit {
            it[Keys.COUNTDOWN_ENABLED] = value.enabled
            it[Keys.COUNTDOWN_TITLE] = value.title
            it[Keys.COUNTDOWN_TARGET_EPOCH] = value.targetDate.toEpochDay()
        }
    }

    override val autoDndEnabled: Flow<Boolean> = prefs.map {
        it[Keys.AUTO_DND_ENABLED] ?: false
    }.distinctUntilChanged()

    override suspend fun setAutoDndEnabled(enabled: Boolean) {
        safeEdit {
            it[Keys.AUTO_DND_ENABLED] = enabled
        }
    }

    override val updateChannel: Flow<UpdateChannel> =
        prefs.map { UpdateChannel.fromKey(it[Keys.UPDATE_CHANNEL]) }.distinctUntilChanged()
    override suspend fun setUpdateChannel(channel: UpdateChannel) {
        safeEdit { it[Keys.UPDATE_CHANNEL] = channel.key }
    }

    override val lastSeenVersionCode: Flow<Int> = prefs.map { it[Keys.LAST_SEEN_VERSION] ?: 0 }.distinctUntilChanged()
    override suspend fun setLastSeenVersionCode(code: Int) {
        safeEdit { it[Keys.LAST_SEEN_VERSION] = code }
    }

    // Format: "przedmiot\twybór" w liniach — nazwy przedmiotów z Optivum nie zawierają
    // tabulatora ani nowej linii.
    private fun decodeGroups(raw: String?): Map<String, String> =
        raw?.lineSequence()?.mapNotNull { line ->
            val i = line.indexOf('\t')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }?.toMap() ?: emptyMap()

    private fun encodeGroups(map: Map<String, String>): String =
        map.entries.joinToString("\n") { "${it.key}\t${it.value}" }

    override fun groupSelections(classId: String): Flow<Map<String, String>> =
        prefs.map { decodeGroups(it[Keys.groups(classId)]) }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val activeGroupSelections: Flow<Map<String, String>> =
        selectedClassId.flatMapLatest { cid -> if (cid == null) flowOf(emptyMap()) else groupSelections(cid) }

    override suspend fun setGroupSelection(classId: String, subject: String, choice: String?) {
        safeEdit {
            val current = decodeGroups(it[Keys.groups(classId)]).toMutableMap()
            if (choice == null) current.remove(subject) else current[subject] = choice
            it[Keys.groups(classId)] = encodeGroups(current)
        }
    }

    override val groupsConfiguredFor: Flow<String?> = prefs.map { it[Keys.GROUPS_CONFIGURED_FOR] }.distinctUntilChanged()
    override suspend fun setGroupsConfiguredFor(classId: String) {
        safeEdit { it[Keys.GROUPS_CONFIGURED_FOR] = classId }
    }
    override suspend fun saveGroupChoices(classId: String, changes: Map<String, String?>) {
        store.edit { prefs ->
            val current = decodeGroups(prefs[Keys.groups(classId)]).toMutableMap()
            changes.forEach { (subject, choice) -> if (choice == null) current.remove(subject) else current[subject] = choice }
            prefs[Keys.groups(classId)] = encodeGroups(current)
            prefs[Keys.GROUPS_CONFIGURED_FOR] = classId
        }
    }

}
