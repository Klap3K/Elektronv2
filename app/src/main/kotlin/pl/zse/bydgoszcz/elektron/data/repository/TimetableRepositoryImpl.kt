package pl.zse.bydgoszcz.elektron.data.repository

import pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable
import pl.zse.bydgoszcz.elektron.domain.model.SchoolPageChangedException
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import pl.zse.bydgoszcz.elektron.data.local.AppDatabase
import pl.zse.bydgoszcz.elektron.data.local.LessonDao
import pl.zse.bydgoszcz.elektron.data.local.LessonGroupDao
import pl.zse.bydgoszcz.elektron.data.local.RoomDao
import pl.zse.bydgoszcz.elektron.data.local.SchoolClassDao
import pl.zse.bydgoszcz.elektron.data.local.SubstitutionDao
import pl.zse.bydgoszcz.elektron.data.local.TeacherDao
import pl.zse.bydgoszcz.elektron.data.mapper.SidebarMapper
import pl.zse.bydgoszcz.elektron.data.mapper.SubstitutionMapper
import pl.zse.bydgoszcz.elektron.data.mapper.TimetableMapper
import pl.zse.bydgoszcz.elektron.data.remote.dto.ClassListItemDto
import pl.zse.bydgoszcz.elektron.data.remote.sources.TimetableSource
import pl.zse.bydgoszcz.elektron.domain.model.Lesson
import pl.zse.bydgoszcz.elektron.domain.model.LessonGroup
import pl.zse.bydgoszcz.elektron.domain.model.SchoolClass
import pl.zse.bydgoszcz.elektron.domain.model.SchoolRoom
import pl.zse.bydgoszcz.elektron.domain.model.Teacher
import pl.zse.bydgoszcz.elektron.domain.repository.TimetableRepository
import java.time.DayOfWeek as JavaDayOfWeek
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimetableRepositoryImpl @Inject constructor(
    private val source: TimetableSource,
    private val classDao: SchoolClassDao,
    private val teacherDao: TeacherDao,
    private val roomDao: RoomDao,
    private val lessonDao: LessonDao,
    private val lessonGroupDao: LessonGroupDao,
    private val substitutionDao: SubstitutionDao,
    private val db: AppDatabase
) : TimetableRepository {

    override suspend fun syncSidebar(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val items = source.fetchSidebar()
            if (items.isEmpty()) return@runCatchingCancellable
            db.withTransaction {
                val classes = classDao.getAll().associateBy { it.id }
                val teachers = teacherDao.getAll().associateBy { it.code }
                val rooms = roomDao.getAll().associateBy { it.id }
                val freshClasses = items.filter { it.kind == ClassListItemDto.Kind.CLASS }.map(SidebarMapper::toClassEntity)
                val freshTeachers = items.filter { it.kind == ClassListItemDto.Kind.TEACHER }.map(SidebarMapper::toTeacherEntity)
                val freshRooms = items.filter { it.kind == ClassListItemDto.Kind.ROOM }.map(SidebarMapper::toRoomEntity)
                classDao.upsertAll(freshClasses.filter { classes[it.id] != it })
                teacherDao.upsertAll(freshTeachers.filter { teachers[it.code] != it })
                roomDao.upsertAll(freshRooms.filter { rooms[it.id] != it })
                // Usunięte ze strony (nowy rok szkolny: absolwenci, odchodzący nauczyciele) znikają
                // z wyboru - dawniej zostawały na zawsze, a wybór takiej klasy kończył się HTTP 404.
                // Pusta grupa na liście to raczej zmiana strony niż brak klas - wtedy nic nie usuwamy.
                // Klasa z zapisanym planem (wybrana) zostaje, dopóki użytkownik nie wybierze innej.
                if (freshClasses.isNotEmpty() && classes.keys.any { id -> freshClasses.none { it.id == id } })
                    classDao.deleteExcept(freshClasses.map { it.id } + lessonDao.classIds())
                if (freshTeachers.isNotEmpty() && teachers.keys.any { code -> freshTeachers.none { it.code == code } })
                    teacherDao.deleteExcept(freshTeachers.map { it.code })
                if (freshRooms.isNotEmpty() && rooms.keys.any { id -> freshRooms.none { it.id == id } })
                    roomDao.deleteExcept(freshRooms.map { it.id })
            }
        }
    }

    override suspend fun syncTimetable(classId: String, anchorDate: LocalDate): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                // Strona szkoły publikuje tylko AKTUALNY plan - nigdy nie zapisujemy go w minionych
                // tygodniach. Dawniej sync tygodnia przewiniętego wstecz (albo odświeżenie na nim)
                // wpisywał bieżący szablon jako plan sprzed miesięcy. Najwcześniej bieżący tydzień.
                // Zawsze od bieżącego tygodnia, także przy odświeżeniu przyszłego: dawniej zapis
                // zaczynał się od oglądanego tygodnia, a koordynator i tak oznaczał plan jako świeży,
                // więc dzisiejszy dzień (widżety, przypomnienia) zostawał ze starą salą na kilka godzin.
                val monday = LocalDate.now().with(JavaDayOfWeek.MONDAY)
                val anchorMonday = maxOf(anchorDate, LocalDate.now()).with(JavaDayOfWeek.MONDAY)
                val weeks = maxOf(WEEKS_AHEAD.toLong(), java.time.temporal.ChronoUnit.WEEKS.between(monday, anchorMonday) + 1)
                val dto = source.fetchTimetable(classId)
                // Zmieniony układ strony: zapisany plan zostaje, ale sync kończy się błędem,
                // żeby użytkownik dostał komunikat zamiast cichego braku aktualizacji.
                if (!dto.layoutOk) throw SchoolPageChangedException("plan lekcji")
                if (dto.lessons.any { cell ->
                    (cell.groups.isNotEmpty() || cell.note != null) &&
                        (runCatching { java.time.LocalTime.parse(cell.timeFrom) }.getOrNull() == null ||
                         runCatching { java.time.LocalTime.parse(cell.timeTo) }.getOrNull() == null ||
                         cell.timeTo <= cell.timeFrom)
                }) throw SchoolPageChangedException("plan lekcji")
                if (dto.lessons.isEmpty()) return@runCatchingCancellable
                // Plan Optivum to szablon tygodniowy. Dawniej zapisywaliśmy go tylko dla
                // tygodnia anchorDate — w weekend cały zapisany tydzień był w przeszłości
                // i Start pokazywał "Brak nadchodzących lekcji". Teraz jeden fetch,
                // zapis na WEEKS_AHEAD tygodni (pokrywa 21-dniowy zakres Startu).
                val lessons = mutableListOf<pl.zse.bydgoszcz.elektron.data.local.LessonEntity>()
                val groups = mutableListOf<pl.zse.bydgoszcz.elektron.data.local.LessonGroupEntity>()
                for (w in 0 until weeks) {
                    val (l, g) = TimetableMapper.toEntities(dto, monday.plusWeeks(w))
                    lessons += l
                    groups += g
                }
                // Są lekcje, ale żadna nie ma czytelnej godziny (mapper je pomija) - to zmiana
                // układu strony, nie pusty plan: zapisany plan zostaje, sync kończy się błędem.
                if (lessons.isEmpty() && dto.lessons.any { it.groups.isNotEmpty() || it.note != null }) {
                    throw SchoolPageChangedException("plan lekcji")
                }
                val toDay = monday.plusWeeks(weeks).minusDays(1).toEpochDay()
                // Nowy plan opublikowany z wyprzedzeniem ("Obowiązuje od: 06.10" w piątek):
                // dni przed tą datą zachowują dotychczasowy plan, a bez niego (pierwsze uruchomienie,
                // wyczyszczone dane) zostają bez planu. Dawniej wtedy wypełniał je nowy plan - uczeń
                // widział przyszłe sale i godziny jako obowiązujące już w tym tygodniu.
                val validFrom = dto.validFrom?.let { runCatching { LocalDate.parse(it, PL_DATE) }.getOrNull() }
                val writeFrom = if (validFrom != null && validFrom > monday) validFrom else monday
                val fromDay = writeFrom.toEpochDay()
                val keptLessons = lessons.filter { it.dateEpochDay >= fromDay }
                val keptIds = keptLessons.mapTo(HashSet()) { it.id }
                val keptGroups = groups.filter { it.lessonId in keptIds }
                // Transakcja: lekcje + grupy zapisywane atomowo, UI nigdy nie zobaczy
                // lekcji bez grup. Grupy kasują się kaskadowo (FK CASCADE).
                db.withTransaction {
                    val oldLessons = lessonDao.getForRange(classId, fromDay, toDay)
                    val oldGroups = if (oldLessons.isEmpty()) emptyList() else
                        lessonGroupDao.getForLessons(oldLessons.map { it.id })
                    if (oldLessons.toSet() != keptLessons.toSet() || oldGroups.toSet() != keptGroups.toSet()) {
                        lessonDao.deleteForRange(classId, fromDay, toDay)
                        lessonDao.upsertAll(keptLessons)
                        lessonGroupDao.upsertAll(keptGroups)
                    }
                }
                // Dawniej stare tygodnie zostawały w bazie na zawsze (tysiące wierszy po roku).
                val cutoff = LocalDate.now().minusWeeks(KEEP_PAST_WEEKS)
                lessonDao.deleteOlderThan(cutoff.toEpochDay())
            }
        }

    override fun observeClasses(): Flow<List<SchoolClass>> =
        classDao.observeAll().map { list -> list.map(SidebarMapper::classToDomain).sortedWith(CLASS_COMPARATOR) }

    override fun observeTeachers(): Flow<List<Teacher>> =
        teacherDao.observeAll().map { list -> list.map(SidebarMapper::teacherToDomain) }

    override fun observeRooms(): Flow<List<SchoolRoom>> =
        roomDao.observeAll().map { list -> list.map(SidebarMapper::roomToDomain) }

    // Bug: dawniej Flow obserwował TYLKO tabelę lessons, a zastępstwa były doczytywane
    // jednorazowo w środku. Nowe zastępstwo (z syncu, z pusha FCM, z panelu dewelopera)
    // nie pojawiało się na planie ani w "Następnej lekcji", dopóki nie zmienił się sam plan.
    // Teraz combine() obserwuje obie tabele. Zakres zastępstw = widoczny zakres dat.
    override fun observeLessons(classId: String, from: LocalDate, to: LocalDate): Flow<List<Lesson>> {
        val fromDay = from.toEpochDay()
        val toDay = to.toEpochDay()
        return combine(
            lessonDao.observeForRange(classId, fromDay, toDay),
            substitutionDao.observeForRange(fromDay, toDay)
        ) { lessonEntities, subEntities ->
            assembleLessons(lessonEntities, subEntities)
        }.flowOn(Dispatchers.Default) // składanie lekcji poza wątkiem UI (płynność pagera)
    }

    override fun observeAllLessons(from: LocalDate, to: LocalDate): Flow<List<Lesson>> {
        val fromDay = from.toEpochDay()
        val toDay = to.toEpochDay()
        return combine(
            lessonDao.observeAllForRange(fromDay, toDay),
            substitutionDao.observeForRange(fromDay, toDay)
        ) { lessonEntities, subEntities ->
            assembleLessons(lessonEntities, subEntities)
        }.flowOn(Dispatchers.Default) // składanie lekcji poza wątkiem UI (płynność pagera)
    }

    private suspend fun assembleLessons(
        lessonEntities: List<pl.zse.bydgoszcz.elektron.data.local.LessonEntity>,
        subEntities: List<pl.zse.bydgoszcz.elektron.data.local.SubstitutionEntity>
    ): List<Lesson> {
        if (lessonEntities.isEmpty()) return emptyList()
        val ids = lessonEntities.map { it.id }
        val groupEntities = lessonGroupDao.getForLessons(ids)
        val groupsByLesson = groupEntities.groupBy { it.lessonId }
        val subs = subEntities.map(SubstitutionMapper::toDomain)
        val raw = lessonEntities.mapNotNull { le ->
            TimetableMapper.toDomain(le, groupsByLesson[le.id].orEmpty(), subs)
        }
        return enrichWithTeacherNames(raw)
    }

    override suspend fun getLessonsOnce(classId: String, from: LocalDate, to: LocalDate): List<Lesson> =
        withContext(Dispatchers.IO) {
            val fromDay = from.toEpochDay()
            val toDay = to.toEpochDay()
            assembleLessons(
                lessonDao.getForRange(classId, fromDay, toDay),
                substitutionDao.getForRange(fromDay, toDay)
            )
        }

    override suspend fun hasLessons(classId: String, from: LocalDate, to: LocalDate): Boolean =
        withContext(Dispatchers.IO) {
            lessonDao.countForRange(classId, from.toEpochDay(), to.toEpochDay()) > 0
        }

    override suspend fun enrichWithTeacherNames(lessons: List<Lesson>): List<Lesson> =
        withContext(Dispatchers.IO) {
            val codes = lessons.flatMap { it.groups }.mapNotNull { it.teacherCode }.distinct()
            if (codes.isEmpty()) return@withContext lessons
            val byCode = teacherDao.getByCodes(codes).associateBy { it.code }
            lessons.map { l ->
                l.copy(groups = l.groups.map { g ->
                    val full = g.teacherCode?.let { byCode[it]?.fullName }
                    if (full == null) g else LessonGroup(
                        g.subject, g.teacherCode, g.teacherUrl, full,
                        g.room, g.roomUrl, g.groupLabel, g.classRef
                    )
                })
            }
        }

    companion object {
        private val PL_DATE = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")
        private const val TAG = "TimetableRepositoryImpl"
        /** Ile tygodni do przodu zapisujemy szablon planu (4 tyg. > 21-dniowy zakres Startu). */
        private const val WEEKS_AHEAD = 4
        /** Ile tygodni wstecz trzymamy plan (przeglądanie poprzednich tygodni w planie). */
        private const val KEEP_PAST_WEEKS = 8L
        private val CLASS_COMPARATOR = Comparator<SchoolClass> { a, b ->
            val (na, la, ra) = classKey(a.fullName)
            val (nb, lb, rb) = classKey(b.fullName)
            when {
                na != nb -> na.compareTo(nb)
                la != lb -> la.compareTo(lb)
                else -> ra.compareTo(rb, ignoreCase = true).takeIf { it != 0 }
                    ?: a.fullName.compareTo(b.fullName, ignoreCase = true)
            }
        }
        private fun classKey(name: String): Triple<Int, Char, String> {
            val m = Regex("^(\\d+)\\s*([A-Za-z])?(?:\\s+(.+))?$").find(name) ?: return Triple(999, 'Z', name)
            val n = m.groupValues[1].toIntOrNull() ?: 999
            val l = m.groupValues[2].uppercase().firstOrNull() ?: 'Z'
            val r = m.groupValues[3]
            return Triple(n, l, r)
        }
    }
}
