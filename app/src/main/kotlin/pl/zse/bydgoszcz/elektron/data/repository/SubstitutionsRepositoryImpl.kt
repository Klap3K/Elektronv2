package pl.zse.bydgoszcz.elektron.data.repository

import pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import pl.zse.bydgoszcz.elektron.data.local.AppDatabase
import pl.zse.bydgoszcz.elektron.data.local.SubstitutionDao
import pl.zse.bydgoszcz.elektron.data.local.SubstitutionEntity
import pl.zse.bydgoszcz.elektron.data.mapper.SubstitutionMapper
import pl.zse.bydgoszcz.elektron.data.remote.sources.SubstitutionsSource
import pl.zse.bydgoszcz.elektron.domain.model.Substitution
import pl.zse.bydgoszcz.elektron.domain.repository.SubstitutionsRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubstitutionsRepositoryImpl @Inject constructor(
    private val source: SubstitutionsSource,
    private val dao: SubstitutionDao,
    private val db: AppDatabase
) : SubstitutionsRepository {

    override suspend fun syncAll(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val page = source.fetchPage()
            // Zajęcia łączone (wpis bez klasy) - klasa i grupa z zapisanego planu; bez dopasowania pomijane.
            var classlessFailed = false
            // Dni wpisów bez klasy, dla których nie ma zapisanego planu (np. plan jeszcze się nie pobrał po
            // wyczyszczeniu danych) - nie da się ich przypisać, więc te dni traktujemy jak niekompletne.
            // Dawniej wpisy po prostu znikały, a po pobraniu planu wracały jako "nowe" zastępstwa.
            var classlessNoPlan: Set<Long> = emptySet()
            val joined = if (page.classless.isEmpty()) emptyList() else runCatchingCancellable {
                val (resolved, noPlan) = resolveClassless(page.classless)
                classlessNoPlan = noPlan
                resolved
            }.onFailure { classlessFailed = true; Log.w(TAG, "Nie udało się przypisać zastępstw bez klasy", it) }.getOrDefault(emptyList())
            val entities = (page.items + joined).mapNotNull(SubstitutionMapper::toEntity)
            // Dni pokazane na stronie (z nagłówków "Zastępstwa w dniu" - także dni bez wpisów).
            val pageDays = (page.dates.mapNotNull { runCatching { LocalDate.parse(it, PL_DATE) }.getOrNull() }
                .map { it.toEpochDay() } + entities.map { it.dateEpochDay }).toSet()
            if (pageDays.isEmpty()) {
                // Brak strony / nierozpoznana strona (awaria) - nic nie ruszamy.
                Log.w(TAG, "Pusto — nie nadpisuję")
                return@runCatchingCancellable
            }
            // Strona szkoły pokazuje PEŁNY komplet zastępstw dla swoich dni - te dni zastępujemy
            // (skasowane/odwołane zastępstwa znikają, także gdy szkoła odwoła wszystkie).
            // Dawniej kasowane było wszystko od dziś: gdy szkoła opublikowała zastępstwa na
            // jutro w trakcie dzisiejszych lekcji, dzisiejsze znikały z planu.
            // Dzień, z którego parser nie odczytał wszystkich wpisów (np. nietypowy zapis klasy):
            // NIE kasujemy go - dotychczasowe wpisy zostają, odczytane są tylko dopisywane.
            // Dawniej taki dzień był czyszczony i poprawne wcześniej zastępstwa znikały.
            // Nieudane przypisanie wpisów bez klasy (np. błąd odczytu planu): ich dni jak niekompletne -
            // wcześniej przypisane zastępstwa zajęć łączonych nie znikają (i nie wracają jako "nowe").
            val incompleteDays = (page.incompleteDates + if (classlessFailed) page.classless.map { it.dateRaw } else emptyList())
                .mapNotNull { runCatching { LocalDate.parse(it, PL_DATE) }.getOrNull()?.toEpochDay() }.toSet() + classlessNoPlan
            if (incompleteDays.isNotEmpty()) {
                Log.w(TAG, "Niekompletnie odczytane dni ${page.incompleteDates} - zostawiam dotychczasowe wpisy")
            }
            db.withTransaction {
                // Zastępowane są tylko dni pokazane na stronie. Dzień późniejszy niż na stronie zostaje: szkoła
                // sama wybiera dzień eksportu i powrót do wcześniejszej daty nie oznacza odwołania późniejszej
                // (dawniej był usuwany - uczeń tracił prawdziwe zastępstwa, także w kopii offline).
                val byDay = entities.groupBy { it.dateEpochDay }
                for (day in pageDays) {
                    val old = dao.getForRange(day, day)
                    val fresh = byDay[day].orEmpty()
                    if (day in incompleteDays) {
                        val oldById = old.associateBy { it.id }
                        // Poprawiony wpis (inna sala/zastępca = inne id) zastępuje poprzedni z tej samej
                        // lekcji - dawniej zostawały oba i plan mógł pokazać starą salę.
                        val freshIds = fresh.mapTo(HashSet()) { it.id }
                        val freshSlots = fresh.mapTo(HashSet(), ::slot)
                        val replaced = old.filter { it.id !in freshIds && slot(it) in freshSlots }
                        if (replaced.isNotEmpty()) dao.deleteByIds(replaced.map { it.id })
                        dao.upsertAll(fresh.filter { oldById[it.id] != it })
                    } else if (old.toSet() != fresh.toSet()) {
                        dao.deleteForDay(day)
                        dao.upsertAll(fresh)
                    }
                }
            }
            dao.deleteOlderThan(LocalDate.now().minusDays(60).toEpochDay())
            if (page.incompleteDates.isNotEmpty()) throw pl.zse.bydgoszcz.elektron.domain.model.IncompleteSchoolDataException()
        }
    }

    /** Wpisy bez klasy dopasowane do lekcji zapisanego planu (ten sam dzień, numer i nauczyciel grupy). */
    /** Wynik: przypisane wpisy i dni bez zapisanego planu albo listy nauczycieli (nie dało się ich ocenić). */
    private suspend fun resolveClassless(entries: List<pl.zse.bydgoszcz.elektron.data.remote.dto.SubstitutionDto>):
        Pair<List<pl.zse.bydgoszcz.elektron.data.remote.dto.SubstitutionDto>, Set<Long>> {
        val days = entries.mapNotNull { runCatching { LocalDate.parse(it.dateRaw, PL_DATE) }.getOrNull()?.toEpochDay() }
        if (days.isEmpty()) return emptyList<pl.zse.bydgoszcz.elektron.data.remote.dto.SubstitutionDto>() to emptySet()
        val lessonDao = db.lessonDao()
        val lessons = lessonDao.classIds().flatMap { lessonDao.getForRange(it, days.min(), days.max()) }
        val teachers = db.teacherDao().getAll().associate { it.code to it.fullName }
        val daysWithPlan = lessons.mapTo(HashSet()) { it.dateEpochDay }
        val noPlan = if (teachers.isEmpty()) days.toSet() else days.filterTo(HashSet()) { it !in daysWithPlan }
        if (lessons.isEmpty()) return emptyList<pl.zse.bydgoszcz.elektron.data.remote.dto.SubstitutionDto>() to noPlan
        val groups = db.lessonGroupDao().getForLessons(lessons.map { it.id }).groupBy { it.lessonId }
        val slots = lessons.flatMap { lesson ->
            val dateRaw = LocalDate.ofEpochDay(lesson.dateEpochDay).format(PL_DATE)
            val classShort = pl.zse.bydgoszcz.elektron.domain.model.ClassNames.shortName(lesson.className)
            groups[lesson.id].orEmpty().map { g ->
                pl.zse.bydgoszcz.elektron.data.mapper.ClasslessSubstitutions.Slot(dateRaw, lesson.number, classShort, g.subject,
                    g.teacherCode?.let(teachers::get) ?: g.teacherFullName, g.classRef)
            }
        }
        return pl.zse.bydgoszcz.elektron.data.mapper.ClasslessSubstitutions.resolve(entries, slots, teachers.values)
            .also { Log.i(TAG, "Zastępstwa bez klasy: ${entries.size}, przypisane: ${it.size}") } to noPlan
    }

    /** Ta sama lekcja tego samego nieobecnego nauczyciela - jeden wpis na stronie szkoły. */
    private fun slot(e: pl.zse.bydgoszcz.elektron.data.local.SubstitutionEntity) =
        listOf(e.lessonNumber, e.classShortName.uppercase(), e.groupNumber ?: 0, e.originalTeacher)

    override suspend fun deleteSimulated(): Int = withContext(Dispatchers.IO) { dao.deleteDevEntries() }

    override fun observeForDay(day: LocalDate): Flow<List<Substitution>> =
        dao.observeForDay(day.toEpochDay()).map { it.map(SubstitutionMapper::toDomain) }

    override fun observeFrom(day: LocalDate): Flow<List<Substitution>> =
        dao.observeFromDay(day.toEpochDay()).map { it.map(SubstitutionMapper::toDomain) }.flowOn(Dispatchers.Default)

    override suspend fun getForClassAndDay(classShortName: String, day: LocalDate): List<Substitution> =
        withContext(Dispatchers.IO) {
            dao.getForClassAndDay(classShortName, day.toEpochDay()).map(SubstitutionMapper::toDomain)
        }

    override suspend fun getAllFrom(day: LocalDate): List<Substitution> =
        withContext(Dispatchers.IO) {
            dao.getFromDay(day.toEpochDay()).map(SubstitutionMapper::toDomain)
        }

    override suspend fun upsertOne(sub: Substitution) = withContext(Dispatchers.IO) {
        dao.upsertAll(listOf(SubstitutionEntity(
            id = sub.id,
            dateEpochDay = sub.date.toEpochDay(),
            lessonNumber = sub.lessonNumber,
            classShortName = sub.classShortName,
            groupNumber = sub.groupNumber,
            roomOrInfo = sub.roomOrInfo,
            substituteTeacher = sub.substituteTeacher,
            notes = sub.notes,
            originalTeacher = sub.originalTeacher,
            originalSubject = sub.originalSubject
        )))
    }

    companion object {
        private const val TAG = "SubstitutionsRepositoryImpl"
        private val PL_DATE = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}
