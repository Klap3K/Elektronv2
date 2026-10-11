package pl.zse.bydgoszcz.elektron.data.repository

import pl.zse.bydgoszcz.elektron.domain.model.SchoolPageChangedException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.zse.bydgoszcz.elektron.data.local.AppDatabase
import pl.zse.bydgoszcz.elektron.data.remote.dto.ClassListItemDto
import pl.zse.bydgoszcz.elektron.data.remote.dto.LessonCellDto
import pl.zse.bydgoszcz.elektron.data.remote.dto.LessonGroupDto
import pl.zse.bydgoszcz.elektron.data.remote.dto.TimetableDto
import pl.zse.bydgoszcz.elektron.data.remote.sources.TimetableSource
import java.time.DayOfWeek
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimetableRepositoryImplTest {

    private class FakeSource : TimetableSource {
        override suspend fun fetchSidebar() = emptyList<ClassListItemDto>()
        override suspend fun fetchTimetable(classId: String) = TimetableDto(
            classId = classId, className = "1D 1D PBŚ", generatedAt = null, validFrom = null,
            lessons = listOf(LessonCellDto(1, "08:00", "08:45", 1,
                listOf(LessonGroupDto("mat", "Ch", null, "105", null, null, null)), null))
        )
    }

    private lateinit var db: AppDatabase
    private lateinit var repo: TimetableRepositoryImpl
    private val monday = LocalDate.now().with(DayOfWeek.MONDAY)

    @Before fun setUp() {
        db = inMemoryDb()
        repo = TimetableRepositoryImpl(FakeSource(), db.schoolClassDao(), db.teacherDao(), db.roomDao(),
            db.lessonDao(), db.lessonGroupDao(), db.substitutionDao(), db)
    }

    @After fun tearDown() = db.close()

    @Test
    fun templateIsStoredForFourWeeks() = runTest {
        // Plan (szablon tygodniowy) zapisywany na 4 tygodnie — w weekend Start nie może
        // pokazywać "Brak nadchodzących lekcji".
        repo.syncTimetable("o3", monday)
        assertTrue(repo.hasLessons("o3", monday.plusWeeks(3), monday.plusWeeks(3).plusDays(4)))
        assertFalse(repo.hasLessons("o3", monday.plusWeeks(4), monday.plusWeeks(4).plusDays(4)))
    }

    @Test
    fun oldWeeksArePruned() = runTest {
        // Dawniej stare tygodnie zostawały w bazie na zawsze.
        val farBack = monday.minusWeeks(10)
        db.lessonDao().upsertAll(listOf(pl.zse.bydgoszcz.elektron.data.local.LessonEntity(
            id = "o3|${farBack.toEpochDay()}|1", classId = "o3", className = "1D", dateEpochDay = farBack.toEpochDay(),
            dayOfWeekIso = 1, number = 1, timeFrom = "08:00", timeTo = "08:45", note = null
        )))
        repo.syncTimetable("o3", monday)
        assertFalse(repo.hasLessons("o3", farBack, farBack.plusDays(4)))
        assertTrue(repo.hasLessons("o3", monday, monday.plusDays(4)))
    }

    @Test
    fun pastAnchorNeverWritesCurrentPlanIntoPastWeeks() = runTest {
        // Strona szkoły publikuje tylko aktualny plan. Dawniej sync tygodnia przewiniętego
        // wstecz (albo odświeżenie na nim) zapisywał bieżący szablon jako plan sprzed tygodni.
        val pastWeek = monday.minusWeeks(3)
        repo.syncTimetable("o3", pastWeek)
        assertFalse(repo.hasLessons("o3", pastWeek, pastWeek.plusDays(4)))
        assertTrue(repo.hasLessons("o3", monday, monday.plusDays(4)))     // bieżący plan odświeżony
    }

    @Test
    fun refreshingFutureWeekAlsoUpdatesCurrentWeek() = runTest {
        // Koordynator po udanym pobraniu uznaje plan klasy za świeży. Dawniej odświeżenie
        // przyszłego tygodnia zapisywało plan od tamtego tygodnia, a bieżący zostawał stary.
        repoWithPlan("stary", null).syncTimetable("o3", monday)
        repoWithPlan("nowy", null).syncTimetable("o3", monday.plusWeeks(2))
        assertEquals("nowy", subjectOn(monday))
        assertEquals("nowy", subjectOn(monday.plusWeeks(2)))
    }

    @Test
    fun farFutureAnchorIsCoveredTogetherWithCurrentWeeks() = runTest {
        val far = monday.plusWeeks(6)
        repo.syncTimetable("o3", far)
        assertTrue(repo.hasLessons("o3", monday, monday.plusDays(4)))
        assertTrue(repo.hasLessons("o3", far, far.plusDays(4)))
    }

    @Test
    fun refreshOnPastWeekKeepsItsSavedPlan() = runTest {
        // Zapisany wcześniej plan minionego tygodnia (z tamtego czasu) zostaje nietknięty.
        repoWithPlan("stary", null).syncTimetable("o3", monday)
        val lastWeek = monday.minusWeeks(1)
        db.lessonDao().upsertAll(listOf(pl.zse.bydgoszcz.elektron.data.local.LessonEntity(
            id = "o3|${lastWeek.toEpochDay()}|1", classId = "o3", className = "1D", dateEpochDay = lastWeek.toEpochDay(),
            dayOfWeekIso = 1, number = 1, timeFrom = "08:00", timeTo = "08:45", note = null
        )))
        repoWithPlan("nowy", null).syncTimetable("o3", lastWeek)
        assertTrue(repo.getLessonsOnce("o3", lastWeek, lastWeek).single().groups.isEmpty())   // bez nadpisania
        assertEquals("nowy", subjectOn(monday))
    }

    @Test
    fun classNameHasNoDuplicates() = runTest {
        repo.syncTimetable("o3", monday)
        assertEquals("1D PBŚ", repo.getLessonsOnce("o3", monday, monday).single().className)
    }

    @Test
    fun changedPageLayoutKeepsSavedPlanAndFails() = runTest {
        repo.syncTimetable("o3", monday)
        val broken = TimetableRepositoryImpl(object : TimetableSource {
            override suspend fun fetchSidebar() = emptyList<ClassListItemDto>()
            override suspend fun fetchTimetable(classId: String) =
                TimetableDto(classId, classId, null, null, emptyList(), layoutOk = false)
        }, db.schoolClassDao(), db.teacherDao(), db.roomDao(),
            db.lessonDao(), db.lessonGroupDao(), db.substitutionDao(), db)

        val result = broken.syncTimetable("o3", monday)
        assertTrue(result.exceptionOrNull() is SchoolPageChangedException)
        assertTrue(repo.hasLessons("o3", monday, monday.plusDays(4)))   // stary plan nietknięty
    }

    private fun repoWithPlan(subject: String, validFrom: LocalDate?) = TimetableRepositoryImpl(object : TimetableSource {
        override suspend fun fetchSidebar() = emptyList<ClassListItemDto>()
        // Lekcja w poniedziałek i w piątek.
        override suspend fun fetchTimetable(classId: String) = TimetableDto(
            classId = classId, className = "1D 1D PBŚ", generatedAt = null,
            validFrom = validFrom?.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")),
            lessons = listOf(1, 5).map { day ->
                LessonCellDto(1, "08:00", "08:45", day, listOf(LessonGroupDto(subject, "Ch", null, "105", null, null, null)), null)
            })
    }, db.schoolClassDao(), db.teacherDao(), db.roomDao(), db.lessonDao(), db.lessonGroupDao(), db.substitutionDao(), db)

    private suspend fun subjectOn(day: LocalDate) =
        repo.getLessonsOnce("o3", day, day).single().groups.single().subject

    @Test
    fun futurePlanDoesNotReplaceCurrentDays() = runTest {
        // Nowy plan opublikowany z wyprzedzeniem, obowiązuje od piątku tego tygodnia:
        // poniedziałek zostaje ze starego planu, od piątku - nowy.
        repoWithPlan("stary", null).syncTimetable("o3", monday)
        repoWithPlan("nowy", monday.plusDays(4)).syncTimetable("o3", monday)
        assertEquals("stary", subjectOn(monday))
        assertEquals("nowy", subjectOn(monday.plusDays(4)))
        assertEquals("nowy", subjectOn(monday.plusWeeks(1)))
    }

    @Test
    fun futurePlanIsNotShownBeforeItsDateEvenWithEmptyDatabase() = runTest {
        // Pierwsze uruchomienie: brak starego planu - dni przed "Obowiązuje od" zostają bez planu
        // (nie z przyszłymi salami i godzinami), od tej daty - nowy plan.
        repoWithPlan("nowy", monday.plusDays(4)).syncTimetable("o3", monday)
        assertTrue(repo.getLessonsOnce("o3", monday, monday).isEmpty())
        assertEquals("nowy", subjectOn(monday.plusDays(4)))
    }

    @Test
    fun unreadableTimesKeepSavedPlanAndFail() = runTest {
        // Wszystkie lekcje bez czytelnej godziny = zmieniony układ strony. Mapper je pomija,
        // więc bez tej ochrony zapisany plan zostałby skasowany i zastąpiony pustym.
        repo.syncTimetable("o3", monday)
        val broken = TimetableRepositoryImpl(object : TimetableSource {
            override suspend fun fetchSidebar() = emptyList<ClassListItemDto>()
            override suspend fun fetchTimetable(classId: String) = TimetableDto(
                classId = classId, className = "1D 1D PBŚ", generatedAt = null, validFrom = null,
                lessons = listOf(LessonCellDto(1, "", "", 1, listOf(LessonGroupDto("mat", "Ch", null, "105", null, null, null)), null))
            )
        }, db.schoolClassDao(), db.teacherDao(), db.roomDao(), db.lessonDao(), db.lessonGroupDao(), db.substitutionDao(), db)

        val result = broken.syncTimetable("o3", monday)
        assertTrue(result.exceptionOrNull() is SchoolPageChangedException)
        assertTrue(repo.hasLessons("o3", monday, monday.plusDays(4)))
    }

    @Test
    fun storedLessonWithUnreadableTimeIsSkipped() = runTest {
        // Lekcja zapisana dawniej z pustą godziną (pokazywana jako 00:00) - pomijana przy odczycie.
        fun lesson(number: Int, from: String, to: String) = pl.zse.bydgoszcz.elektron.data.local.LessonEntity(
            id = "o3|${monday.toEpochDay()}|$number", classId = "o3", className = "1D", dateEpochDay = monday.toEpochDay(),
            dayOfWeekIso = 1, number = number, timeFrom = from, timeTo = to, note = null
        )
        db.lessonDao().upsertAll(listOf(lesson(1, "08:00", "08:45"), lesson(2, "", ""), lesson(3, "10:00", "xx")))
        assertEquals(listOf(1), repo.getLessonsOnce("o3", monday, monday).map { it.number })
    }

    private fun watchWrites(table: String) {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE IF NOT EXISTS audit_writes (value INTEGER)")
        for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
            sql.execSQL("CREATE TRIGGER audit_${table}_$operation AFTER $operation ON $table BEGIN INSERT INTO audit_writes VALUES (1); END")
        }
    }

    @Test fun classesAndTeachersRemovedFromTheSchoolListDisappearExceptTheStoredClass() = runTest {
        fun cls(id: String, short: String) = ClassListItemDto(id, ClassListItemDto.Kind.CLASS, "$short $short", short, "https://plan.zse.bydgoszcz.pl/plany/$id.html")
        fun teacher(code: String) = ClassListItemDto("n$code", ClassListItemDto.Kind.TEACHER, "Nauczyciel ($code)", code, "https://plan.zse.bydgoszcz.pl/plany/n$code.html")
        var sidebar = listOf(cls("o3", "1D"), cls("o4", "2A"), cls("o45", "4Z"), teacher("Ch"), teacher("Ab"))
        val source = object : TimetableSource by FakeSource() {
            override suspend fun fetchSidebar() = sidebar
        }
        val repo = TimetableRepositoryImpl(source, db.schoolClassDao(), db.teacherDao(), db.roomDao(),
            db.lessonDao(), db.lessonGroupDao(), db.substitutionDao(), db)
        assertTrue(repo.syncSidebar().isSuccess)
        assertTrue(repo.syncTimetable("o4", monday).isSuccess)   // wybrana klasa z zapisanym planem
        // Nowy rok szkolny: bez 4Z, 2A (wybrana) chwilowo poza listą, jeden nauczyciel odszedł.
        sidebar = listOf(cls("o3", "1D"), cls("o50", "1A"), teacher("Ch"))
        assertTrue(repo.syncSidebar().isSuccess)
        assertEquals(listOf("o3", "o4", "o50"), db.schoolClassDao().getAll().map { it.id })
        assertEquals(listOf("Ch"), db.teacherDao().getAll().map { it.code })
        // Lista bez nauczycieli (zmiana strony) nikogo nie usuwa.
        sidebar = listOf(cls("o3", "1D"))
        assertTrue(repo.syncSidebar().isSuccess)
        assertEquals(listOf("Ch"), db.teacherDao().getAll().map { it.code })
    }

    @Test fun identicalSyncDoesNotRewriteTables() = runTest {
        assertTrue(repo.syncTimetable("o3", monday).isSuccess)
        watchWrites("lessons")
        watchWrites("lesson_groups")
        assertTrue(repo.syncTimetable("o3", monday).isSuccess)
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM audit_writes").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
    }
    @Test fun invalidTimeInOneLessonDoesNotDeleteSavedTemplate() = runTest {
        repo.syncTimetable("o3", monday)
        val before = repo.getLessonsOnce("o3", monday, monday.plusWeeks(4))
        val source = object : TimetableSource {
            override suspend fun fetchSidebar() = emptyList<ClassListItemDto>()
            override suspend fun fetchTimetable(classId: String) = TimetableDto(classId, "1D", null, null,
                listOf(LessonCellDto(1, "08:00", "08:45", 1, listOf(LessonGroupDto("mat", null, null, null, null, null, null)), null),
                    LessonCellDto(2, "25:00", "25:45", 1, listOf(LessonGroupDto("fiz", null, null, null, null, null, null)), null)))
        }
        val broken = TimetableRepositoryImpl(source, db.schoolClassDao(), db.teacherDao(), db.roomDao(),
            db.lessonDao(), db.lessonGroupDao(), db.substitutionDao(), db)
        assertTrue(broken.syncTimetable("o3", monday).exceptionOrNull() is SchoolPageChangedException)
        assertEquals(before, repo.getLessonsOnce("o3", monday, monday.plusWeeks(4)))
    }

}
