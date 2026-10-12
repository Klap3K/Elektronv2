package pl.zse.bydgoszcz.elektron.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import pl.zse.bydgoszcz.elektron.domain.model.ClassroomDuty
import pl.zse.bydgoszcz.elektron.domain.repository.DutyRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dutyPreferences: DataStore<Preferences> by preferencesDataStore(name = "duty_preferences")

@Singleton
class DutyRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : DutyRepository {

    private val store = context.dutyPreferences

    private object Keys {
        val STUDENT_NUMBER = intPreferencesKey("student_number")
        val CLASS_SIZE = intPreferencesKey("class_size")
    }

    override val studentNumber: Flow<Int?> = store.data.map { prefs ->
        prefs[Keys.STUDENT_NUMBER]
    }.distinctUntilChanged()

    override val classSize: Flow<Int> = store.data.map { prefs ->
        prefs[Keys.CLASS_SIZE] ?: 30
    }.distinctUntilChanged()

    override fun observeDuty(date: LocalDate): Flow<ClassroomDuty> =
        combine(studentNumber, classSize) { number, size ->
            ClassroomDuty.calculate(date = date, studentNumber = number, classSize = size)
        }.distinctUntilChanged()

    override suspend fun setStudentNumber(number: Int?) {
        store.edit { prefs ->
            if (number != null && number > 0) {
                prefs[Keys.STUDENT_NUMBER] = number
            } else {
                prefs.remove(Keys.STUDENT_NUMBER)
            }
        }
    }

    override suspend fun setClassSize(size: Int) {
        store.edit { prefs ->
            prefs[Keys.CLASS_SIZE] = size.coerceIn(1, 60)
        }
    }
}
