package pl.zse.bydgoszcz.elektron.domain.model

import java.time.LocalDate
import java.time.LocalTime

enum class ActivityCategory(val label: String, val icon: String) {
    EXAM("Sprawdzian", "📝"),
    HOMEWORK("Zadanie", "📚"),
    STUDY("Nauka", "📖"),
    TUTORING("Korki", "🎓"),
    TRAINING("Trening", "🏋️"),
    PROJECT("Projekt", "📌"),
    OTHER("Inne", "💡");

    companion object {
        fun fromString(value: String): ActivityCategory =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: OTHER
    }
}

data class UserActivity(
    val id: String,
    val title: String,
    val description: String?,
    val category: ActivityCategory,
    val date: LocalDate,
    val timeFrom: LocalTime?,
    val timeTo: LocalTime?,
    val isCompleted: Boolean,
    val createdAtEpochSeconds: Long
) {
    val timeRangeText: String?
        get() = when {
            timeFrom != null && timeTo != null -> "$timeFrom–$timeTo"
            timeFrom != null -> "$timeFrom"
            else -> null
        }
}
