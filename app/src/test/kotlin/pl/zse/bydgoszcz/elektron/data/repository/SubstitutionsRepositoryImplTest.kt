package pl.zse.bydgoszcz.elektron.data.repository

import pl.zse.bydgoszcz.elektron.data.remote.sources.SubstitutionsPage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.zse.bydgoszcz.elektron.data.local.AppDatabase
import pl.zse.bydgoszcz.elektron.data.remote.dto.SubstitutionDto
import pl.zse.bydgoszcz.elektron.data.remote.sources.SubstitutionsSource
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubstitutionsRepositoryImplTest {

    private class FakeSource(var items: List<SubstitutionDto> = emptyList()) : SubstitutionsSource {
        override suspend fun fetchSubstitutions() = items
    }

    private val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val today = LocalDate.now()
    private lateinit var db: AppDatabase
    private val source = FakeSource()
    private lateinit var repo: SubstitutionsRepositoryImpl

    private fun dto(date: LocalDate, lesson: Int) =
        SubstitutionDto(date.format(fmt), "Y.Nauczyciel", lesson, "2D", null, "316", "X.Zastępca", null)

    @Before fun setUp() {
        db = inMemoryDb()
        repo = SubstitutionsRepositoryImpl(source, db.substitutionDao(), db)
    }

    @After fun tearDown() = db.close()

    @Test
    fun cancelledSubstitutionDisappearsAfterSync() = runTest {
        // Bug audytu #6: zastępstwo skasowane na stronie szkoły zostawało w aplikacji.
        source.items = listOf(dto(today.plusDays(1), 1), dto(today.plusDays(1), 2))
        repo.syncAll()
        source.items = listOf(dto(today.plusDays(1), 2))
        repo.syncAll()
        assertEquals(listOf(2), repo.getAllFrom(today).map { it.lessonNumber })
    }

    @Test
    fun pastSubstitutionsSurviveSync() = runTest {
        source.items = listOf(dto(today.minusDays(1), 3), dto(today.plusDays(1), 1))
        repo.syncAll()
        source.items = listOf(dto(today.plusDays(1), 1))
        repo.syncAll()
        assertTrue(repo.getAllFrom(today.minusDays(1)).any { it.date == today.minusDays(1) })
    }

    @Test
    fun emptyFetchDoesNotWipeData() = runTest {
        // Pusta odpowiedź (np. awaria strony) nie może skasować znanych zastępstw.
        source.items = listOf(dto(today.plusDays(1), 1))
        repo.syncAll()
        source.items = emptyList()
        repo.syncAll()
        assertEquals(1, repo.getAllFrom(today).size)
    }

    @Test
    fun todaySurvivesWhenSchoolPublishesTomorrow() = runTest {
        // Szkoła publikuje jutrzejsze zastępstwa w trakcie dzisiejszych lekcji (strona pokazuje
        // już tylko jutro) - dzisiejsze muszą zostać w planie.
        source.items = listOf(dto(today, 5))
        repo.syncAll()
        source.items = listOf(dto(today.plusDays(1), 1))
        repo.syncAll()
        assertEquals(listOf(today, today.plusDays(1)), repo.getAllFrom(today).map { it.date })
    }

    @Test
    fun dayWithAllSubstitutionsCancelledIsCleared() = runTest {
        // Strona z nagłówkiem dnia, ale bez wpisów - szkoła odwołała wszystkie zastępstwa.
        source.items = listOf(dto(today.plusDays(1), 1))
        repo.syncAll()
        val emptyDay = object : SubstitutionsSource {
            override suspend fun fetchSubstitutions() = emptyList<SubstitutionDto>()
            override suspend fun fetchPage() = SubstitutionsPage(setOf(today.plusDays(1).format(fmt)), emptyList())
        }
        SubstitutionsRepositoryImpl(emptyDay, db.substitutionDao(), db).syncAll()
        assertTrue(repo.getAllFrom(today).isEmpty())
    }

    private fun realPage(html: String): SubstitutionsPage {
        val doc = org.jsoup.Jsoup.parse(html, "https://zastepstwa.zse.bydgoszcz.pl/")
        val parsed = pl.zse.bydgoszcz.elektron.data.remote.parser.ZastepstwaParser.parseDetailed(doc)
        return SubstitutionsPage(pl.zse.bydgoszcz.elektron.data.remote.parser.ZastepstwaParser.pageDates(doc),
            parsed.items, parsed.incompleteDates)
    }

    @Test
    fun unreadableEntryKeepsPreviouslySavedDay() = runTest {
        // Zapisana strona z 01.10.2026, potem ta sama strona z celowo uszkodzonym wpisem 1D
        // (nietypowy zapis klasy). Dawniej dzień był czyszczony, a wpis 1D znikał z aplikacji.
        // Data strony przesunięta na dziś - starsze niż 60 dni wpisy repozytorium sprząta.
        val html = javaClass.getResourceAsStream("/zastepstwa/2026-10-01.html")!!.use { it.readBytes().toString(Charsets.UTF_8) }
            .replace("01.10.2026", today.format(fmt))
        var page = realPage(html)
        val pageSource = object : SubstitutionsSource {
            override suspend fun fetchSubstitutions() = page.items
            override suspend fun fetchPage() = page
        }
        val repo = SubstitutionsRepositoryImpl(pageSource, db.substitutionDao(), db)
        val day = today
        repo.syncAll()
        assertEquals(32, repo.getAllFrom(day).count { it.date == day })

        page = realPage(html.replace("1 D(2) - Zajęcia Świetlicowe", "1Dg2 - Zajęcia Świetlicowe"))
        assertTrue(repo.syncAll().exceptionOrNull() is pl.zse.bydgoszcz.elektron.domain.model.IncompleteSchoolDataException)
        val after = repo.getAllFrom(day).filter { it.date == day }
        assertEquals(32, after.size)
        assertTrue(after.any { it.classShortName == "1D" && it.lessonNumber == 7 })
    }

    @Test
    fun completeDayIsStillReplaced() = runTest {
        // Kontrola: przy komplecie wpisów dzień nadal jest zastępowany (odwołane znikają).
        val d = today.plusDays(1)
        source.items = listOf(dto(d, 1), dto(d, 2))
        repo.syncAll()
        val pageSource = object : SubstitutionsSource {
            override suspend fun fetchSubstitutions() = listOf(dto(d, 2))
            override suspend fun fetchPage() = SubstitutionsPage(setOf(d.format(fmt)), listOf(dto(d, 2)), incompleteDates = setOf(d.format(fmt)))
        }
        // Niekompletny dzień: nic nie kasujemy.
        SubstitutionsRepositoryImpl(pageSource, db.substitutionDao(), db).syncAll()
        assertEquals(listOf(1, 2), repo.getAllFrom(d).map { it.lessonNumber })
        // Kompletny: zastąpiony.
        source.items = listOf(dto(d, 2))
        repo.syncAll()
        assertEquals(listOf(2), repo.getAllFrom(d).map { it.lessonNumber })
    }

    @Test
    fun correctedEntryReplacesThePreviousOneEvenOnAnIncompleteDay() = runTest {
        val d = today.plusDays(1)
        source.items = listOf(dto(d, 1), dto(d, 4))
        repo.syncAll()
        // Szkoła zmienia salę w lekcji 4, a inny wiersz dnia jest nieczytelny (dzień niekompletny).
        val moved = dto(d, 4).copy(roomOrInfo = "214")
        val pageSource = object : SubstitutionsSource {
            override suspend fun fetchSubstitutions() = listOf(moved)
            override suspend fun fetchPage() = SubstitutionsPage(setOf(d.format(fmt)), listOf(moved), incompleteDates = setOf(d.format(fmt)))
        }
        SubstitutionsRepositoryImpl(pageSource, db.substitutionDao(), db).syncAll()
        val saved = repo.getAllFrom(d)
        assertEquals(listOf(1, 4), saved.map { it.lessonNumber })   // lekcja 1 (nieodczytana) zostaje
        assertEquals("214", saved.single { it.lessonNumber == 4 }.roomOrInfo)
    }

    @Test
    fun onlyDaysShownOnThePageAreReplaced() = runTest {
        // Strona pokazała pojutrze, potem wróciła do jutra - zastępstwa z pojutrza zostają (bez dowodu odwołania).
        source.items = listOf(dto(today.plusDays(2), 1))
        repo.syncAll()
        source.items = listOf(dto(today.plusDays(1), 1))
        repo.syncAll()
        assertEquals(listOf(today.plusDays(1), today.plusDays(2)), repo.getAllFrom(today).map { it.date })
        // Strona przechodzi na kolejny dzień - wcześniejsze zostają.
        source.items = listOf(dto(today.plusDays(3), 2))
        repo.syncAll()
        assertEquals(listOf(today.plusDays(1), today.plusDays(2), today.plusDays(3)), repo.getAllFrom(today).map { it.date })
        // Szkoła wraca do dzisiejszej strony (nagłe zastępstwo) - przyszłe dni zostają.
        source.items = listOf(dto(today, 5))
        repo.syncAll()
        assertEquals(listOf(today, today.plusDays(1), today.plusDays(2), today.plusDays(3)), repo.getAllFrom(today).map { it.date })
        // Nieodświeżona strona z minionym dniem też niczego nie usuwa.
        source.items = listOf(dto(today.minusDays(1), 4))
        repo.syncAll()
        assertEquals(listOf(today.minusDays(1), today, today.plusDays(1), today.plusDays(2), today.plusDays(3)), repo.getAllFrom(today.minusDays(1)).map { it.date })
    }

    @Test
    fun joinedLessonWithoutClassIsAssignedToTheTeachersGroupInTheStoredPlan() = runTest {
        // 9.10.2026: "Wychowanie fizyczne - Zajęcia Świetlicowe" - zajęcia łączone, bez klasy na stronie.
        val d = today.plusDays(1)
        val lessonId = "o3|${d.toEpochDay()}|3"
        db.teacherDao().upsertAll(listOf(
            pl.zse.bydgoszcz.elektron.data.local.TeacherEntity("Cz", "D.Czyżewski", "https://plan.zse.bydgoszcz.pl/plany/n9.html"),
            pl.zse.bydgoszcz.elektron.data.local.TeacherEntity("Ch", "M.Chabowski", "https://plan.zse.bydgoszcz.pl/plany/n1.html")))
        db.lessonDao().upsertAll(listOf(pl.zse.bydgoszcz.elektron.data.local.LessonEntity(
            lessonId, "o3", "1D 1D PBŚ", d.toEpochDay(), d.dayOfWeek.value, 3, "09:50", "10:35", null)))
        db.lessonGroupDao().upsertAll(listOf(
            pl.zse.bydgoszcz.elektron.data.local.LessonGroupEntity(lessonId, 0, "wf-1/2", "Ch", null, null, "sg", null, null, null),
            pl.zse.bydgoszcz.elektron.data.local.LessonGroupEntity(lessonId, 1, "wf-2/2", "Cz", null, null, "sg", null, null, null)))
        val joined = SubstitutionDto(d.format(fmt), "Dariusz Czyżewski", 3, "", null, "Zajęcia Świetlicowe", null, null, subject = "Wychowanie fizyczne")
        val pageSource = object : SubstitutionsSource {
            override suspend fun fetchSubstitutions() = emptyList<SubstitutionDto>()
            override suspend fun fetchPage() = SubstitutionsPage(setOf(d.format(fmt)), emptyList(), classless = listOf(joined))
        }
        assertTrue(SubstitutionsRepositoryImpl(pageSource, db.substitutionDao(), db).syncAll().isSuccess)
        val saved = repo.getAllFrom(d).single()
        assertEquals("1D", saved.classShortName)
        assertEquals(2, saved.groupNumber)
        assertEquals(3, saved.lessonNumber)
        assertEquals("Zajęcia Świetlicowe", saved.roomOrInfo)
        assertEquals("Dariusz Czyżewski", saved.originalTeacher)
    }

    @Test
    fun joinedLessonIsKeptWhenThePlanIsMissing() = runTest {
        // Plan wyczyszczony (np. reset danych, plan jeszcze się nie pobrał): zapisany wcześniej wpis
        // zajęć łączonych zostaje, zamiast zniknąć i wrócić później jako "nowe" zastępstwo.
        val d = today.plusDays(1)
        val saved = SubstitutionDto(d.format(fmt), "Dariusz Czyżewski", 3, "1D", 2, "Zajęcia Świetlicowe", null, null)
        source.items = listOf(saved)
        assertTrue(repo.syncAll().isSuccess)
        val joined = saved.copy(classShortName = "", groupNumber = null, subject = "Wychowanie fizyczne")
        val pageSource = object : SubstitutionsSource {
            override suspend fun fetchSubstitutions() = emptyList<SubstitutionDto>()
            override suspend fun fetchPage() = SubstitutionsPage(setOf(d.format(fmt)), emptyList(), classless = listOf(joined))
        }
        assertTrue(SubstitutionsRepositoryImpl(pageSource, db.substitutionDao(), db).syncAll().isSuccess)
        assertEquals(listOf("1D"), repo.getAllFrom(d).map { it.classShortName })
    }

    private fun watchWrites(table: String) {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE IF NOT EXISTS audit_writes (value INTEGER)")
        for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
            sql.execSQL("CREATE TRIGGER audit_${table}_$operation AFTER $operation ON $table BEGIN INSERT INTO audit_writes VALUES (1); END")
        }
    }

    @Test fun identicalSyncDoesNotRewriteTables() = runTest {
        source.items = listOf(dto(today, 1))
        assertTrue(repo.syncAll().isSuccess)
        watchWrites("substitutions")
        assertTrue(repo.syncAll().isSuccess)
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM audit_writes").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
    }
}
