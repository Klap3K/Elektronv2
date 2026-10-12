package pl.zse.bydgoszcz.elektron.data.remote.vulcan

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import pl.zse.bydgoszcz.elektron.domain.model.SchoolEvent
import pl.zse.bydgoszcz.elektron.domain.model.SchoolEventType
import pl.zse.bydgoszcz.elektron.domain.model.StudentGrade
import pl.zse.bydgoszcz.elektron.domain.model.SubjectAttendance
import pl.zse.bydgoszcz.elektron.domain.model.VulcanAccount
import pl.zse.bydgoszcz.elektron.domain.model.VulcanRegistrationInput
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wyjątek oznaczający wygaśnięcie ciasteczek sesyjnych eduVULCAN (wymagane ciche odświeżenie sesji).
 */
class EduVulcanSessionExpiredException(message: String) : IOException(message)

/**
 * Klient API eduVULCAN (uczen.eduvulcan.pl) oraz starszego API mobilnego VULCAN UONET+ (Hebe).
 * Obsługuje pobieranie sprawdzianów, kartkówek, zadań domowych oraz statystyk frekwencji.
 */
@Singleton
class VulcanApiClient @Inject constructor(
    private val okHttp: OkHttpClient
) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Pobiera sprawdziany, kartkówki i zadania domowe z konta eduVULCAN (lub starszego Hebe).
     */
    fun fetchEvents(account: VulcanAccount): Result<List<SchoolEvent>> {
        return if (account.isEduVulcan || account.restUrl.contains("uczen.")) {
            fetchEduVulcanEvents(account)
        } else {
            fetchHebeEvents(account)
        }
    }

    /**
     * Pobiera sprawdziany, kartkówki, klasówki i zadania domowe z portalu uczen.eduvulcan.pl.
     */
    fun fetchEduVulcanEvents(account: VulcanAccount): Result<List<SchoolEvent>> {
        val domain = account.domain.ifBlank { "eduvulcan.pl" }
        val symbol = account.symbol.trim()
        val key = account.studentKey.ifBlank { account.token }.trim()
        val baseUrl = "https://uczen.$domain/$symbol"

        val cookies = buildMergedCookies(domain, symbol, account.cookiesHeader)
        if (cookies.isBlank()) {
            return Result.failure(EduVulcanSessionExpiredException("Brak ciasteczek sesji eduVULCAN."))
        }

        try {
            // 1. Odświeżenie kontekstu na serwerze uczen.eduvulcan.pl (zapobiega błędom HTTP 403)
            warmUpAndVerifyContext(baseUrl, cookies)

            // 2. Pobranie listy sprawdzianów i zadań domowych z okna [-14 dni .. +65 dni]
            val now = LocalDate.now()
            val dataOd = "${now.minusDays(14)}T00:00:00.000Z"
            val dataDo = "${now.plusDays(65)}T23:59:59.999Z"

            val listUrl = "$baseUrl/api/SprawdzianyZadaniaDomowe".toHttpUrlOrNull()!!
                .newBuilder()
                .addQueryParameter("key", key)
                .addQueryParameter("dataOd", dataOd)
                .addQueryParameter("dataDo", dataDo)
                .build()

            val listReq = Request.Builder()
                .url(listUrl)
                .get()
                .header("Cookie", cookies)
                .header("User-Agent", EduVulcanWebAuthenticator.USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$baseUrl/App")
                .build()

            val listRes = okHttp.newCall(listReq).execute()
            val listCode = listRes.code
            val listBody = listRes.body?.string().orEmpty()

            if (listCode == 401 || listCode == 403 || isLoginRedirect(listRes.request.url.toString(), listBody)) {
                return Result.failure(EduVulcanSessionExpiredException("Sesja eduVULCAN wygasła (HTTP $listCode)."))
            }
            if (!listRes.isSuccessful) {
                return Result.failure(IOException("Błąd pobierania terminarza eduVULCAN (HTTP $listCode)"))
            }

            val trimmed = listBody.trim()
            if (trimmed.isBlank()) {
                return Result.success(emptyList())
            }

            // Obsługa zarówno tablicy JSON z eduVULCAN, jak i starszego formatu obiektowego
            if (trimmed.startsWith("{")) {
                return Result.success(parseLegacyOrGroupedExamsJson(trimmed))
            }

            val itemsArray = runCatching { JSONArray(trimmed) }.getOrNull()
                ?: return Result.failure(EduVulcanSessionExpiredException("Nieprawidłowa odpowiedź terminarza eduVULCAN."))

            val events = mutableListOf<SchoolEvent>()

            for (i in 0 until itemsArray.length()) {
                val item = itemsArray.optJSONObject(i) ?: continue
                val itemId = item.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: continue
                val typInt = item.optInt("typ", 1)

                // Pobieramy szczegóły wpisu (SprawdzianSzczegoly / ZadanieDomoweSzczegoly)
                val detailEndpoint = if (typInt == 4) "ZadanieDomoweSzczegoly" else "SprawdzianSzczegoly"
                val detailJson = fetchDetailJson(baseUrl, detailEndpoint, key, itemId, cookies)

                val dataRaw = detailJson?.optString("terminOdpowiedzi")?.takeIf { it.isNotBlank() && it != "null" }
                    ?: item.optString("terminOdpowiedzi").takeIf { it.isNotBlank() && it != "null" }
                    ?: detailJson?.optString("data")?.takeIf { it.isNotBlank() && it != "null" }
                    ?: item.optString("data")

                val date = parseDateOrNull(dataRaw) ?: continue

                val subject = (detailJson?.optString("przedmiotNazwa")?.takeIf { it.isNotBlank() && it != "null" }
                    ?: item.optString("przedmiotNazwa").takeIf { it.isNotBlank() && it != "null" }
                    ?: "").trim()

                val teacher = (detailJson?.optString("nauczycielImieNazwisko")?.takeIf { it.isNotBlank() && it != "null" }
                    ?: item.optString("nauczycielImieNazwisko").takeIf { it.isNotBlank() && it != "null" }
                    ?: "").trim()

                val rawOpis = detailJson?.optString("opis")?.takeIf { it.isNotBlank() && it != "null" }
                    ?: detailJson?.optString("temat")?.takeIf { it.isNotBlank() && it != "null" }
                    ?: detailJson?.optString("tresc")?.takeIf { it.isNotBlank() && it != "null" }
                    ?: item.optString("opis").takeIf { it.isNotBlank() && it != "null" }
                    ?: item.optString("temat").takeIf { it.isNotBlank() && it != "null" }
                    ?: ""

                val cleanOpis = stripHtml(rawOpis)

                val (typeLabel, eventType) = when (typInt) {
                    1 -> "Sprawdzian" to SchoolEventType.EXAM
                    2 -> "Kartkówka" to SchoolEventType.QUIZ
                    3 -> "Klasówka" to SchoolEventType.EXAM
                    4 -> "Zadanie domowe" to SchoolEventType.HOMEWORK
                    else -> "Wydarzenie" to SchoolEventType.OTHER
                }

                val title = if (subject.isNotBlank()) "$typeLabel: $subject" else typeLabel
                val fullDescription = buildString {
                    if (cleanOpis.isNotBlank()) append(cleanOpis)
                    if (teacher.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append("Nauczyciel: ").append(teacher)
                    }
                }

                events.add(
                    SchoolEvent(
                        id = "eduvulcan_$itemId",
                        title = title,
                        description = fullDescription.ifBlank { null },
                        type = eventType,
                        subject = subject.ifBlank { null },
                        date = date,
                        timeFrom = null,
                        timeTo = null,
                        location = null,
                        rawSummary = "$title $subject $cleanOpis"
                    )
                )
            }

            return Result.success(events.distinctBy { it.id })
        } catch (e: EduVulcanSessionExpiredException) {
            return Result.failure(e)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    /**
     * Pobiera statystyki frekwencji z podziałem na przedmioty z portalu uczen.eduvulcan.pl
     * dla Strażnika Frekwencji.
     */
    fun fetchEduVulcanAttendance(account: VulcanAccount): Result<List<SubjectAttendance>> {
        val domain = account.domain.ifBlank { "eduvulcan.pl" }
        val symbol = account.symbol.trim()
        val key = account.studentKey.ifBlank { account.token }.trim()
        val baseUrl = "https://uczen.$domain/$symbol"

        val cookies = buildMergedCookies(domain, symbol, account.cookiesHeader)
        if (cookies.isBlank()) {
            return Result.failure(EduVulcanSessionExpiredException("Brak ciasteczek sesji eduVULCAN."))
        }

        try {
            warmUpAndVerifyContext(baseUrl, cookies)

            // 1. Pobierz listę przedmiotów ucznia (/api/Przedmioty?key=...)
            val subjUrl = "$baseUrl/api/Przedmioty".toHttpUrlOrNull()!!
                .newBuilder()
                .addQueryParameter("key", key)
                .build()

            val subjReq = Request.Builder()
                .url(subjUrl)
                .get()
                .header("Cookie", cookies)
                .header("User-Agent", EduVulcanWebAuthenticator.USER_AGENT)
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$baseUrl/App")
                .build()

            val subjRes = okHttp.newCall(subjReq).execute()
            val subjBody = subjRes.body?.string().orEmpty()
            if (subjRes.code == 401 || subjRes.code == 403 || isLoginRedirect(subjRes.request.url.toString(), subjBody)) {
                return Result.failure(EduVulcanSessionExpiredException("Sesja eduVULCAN wygasła podczas pobierania frekwencji."))
            }
            if (!subjRes.isSuccessful) {
                return Result.failure(IOException("Błąd pobierania przedmiotów z eduVULCAN (HTTP ${subjRes.code})"))
            }

            val subjArray = runCatching { JSONArray(subjBody.trim()) }.getOrNull()
                ?: return Result.failure(IOException("Nieprawidłowy format listy przedmiotów eduVULCAN"))

            val nowSeconds = System.currentTimeMillis() / 1000
            val resultList = mutableListOf<SubjectAttendance>()

            for (i in 0 until subjArray.length()) {
                val subjObj = subjArray.optJSONObject(i) ?: continue
                val subjId = subjObj.optInt("id", -1)
                val subjName = subjObj.optString("nazwa").trim()
                if (subjId == -1 || subjName.isBlank()) continue

                val statUrl = "$baseUrl/api/FrekwencjaStatystyki".toHttpUrlOrNull()!!
                    .newBuilder()
                    .addQueryParameter("key", key)
                    .addQueryParameter("idPrzedmiot", subjId.toString())
                    .build()

                val statReq = Request.Builder()
                    .url(statUrl)
                    .get()
                    .header("Cookie", cookies)
                    .header("User-Agent", EduVulcanWebAuthenticator.USER_AGENT)
                    .header("Accept", "application/json")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", "$baseUrl/App")
                    .build()

                val statRes = runCatching { okHttp.newCall(statReq).execute() }.getOrNull() ?: continue
                if (!statRes.isSuccessful) continue
                val statBody = statRes.body?.string().orEmpty().trim()
                if (statBody.isBlank()) continue

                val statJson = runCatching { JSONObject(statBody) }.getOrNull() ?: continue
                if (statJson.isNull("podsumowanie")) continue

                val rows = statJson.optJSONArray("statystyki") ?: continue
                var present = 0
                var absent = 0
                var excused = 0
                var late = 0
                var obligation = 0

                for (r in 0 until rows.length()) {
                    val row = rows.optJSONObject(r) ?: continue
                    val kat = row.optInt("kategoriaFrekwencji", 0)
                    var count = row.optInt("razem", -1)
                    if (count < 0) {
                        count = 0
                        val okresy = row.optJSONArray("okresy")
                        if (okresy != null) {
                            for (o in 0 until okresy.length()) {
                                count += okresy.optInt(o, 0)
                            }
                        }
                    }

                    when (kat) {
                        1 -> present += count
                        2 -> absent += count
                        3 -> excused += count
                        4, 5 -> late += count
                        6, 7 -> obligation += count
                    }
                }

                val total = present + absent + excused + late + obligation
                if (total > 0) {
                    resultList.add(
                        SubjectAttendance(
                            subject = subjName,
                            presentHours = present,
                            absentHours = absent,
                            excusedHours = excused,
                            lateHours = late,
                            schoolObligationHours = obligation,
                            lastUpdatedEpochSeconds = nowSeconds
                        )
                    )
                }
            }

            return Result.success(resultList)
        } catch (e: EduVulcanSessionExpiredException) {
            return Result.failure(e)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    /**
     * Pobiera oceny cząstkowe, proponowane i okresowe oraz średnie przedmiotów z portalu uczen.eduvulcan.pl.
     */
    fun fetchEduVulcanGrades(account: VulcanAccount): Result<List<StudentGrade>> {
        val domain = account.domain.ifBlank { "eduvulcan.pl" }
        val symbol = account.symbol.trim()
        val key = account.studentKey.ifBlank { account.token }.trim()
        val baseUrl = "https://uczen.$domain/$symbol"

        val cookies = buildMergedCookies(domain, symbol, account.cookiesHeader)
        if (cookies.isBlank()) {
            return Result.failure(EduVulcanSessionExpiredException("Brak ciasteczek sesji eduVULCAN."))
        }

        try {
            val contextJson = warmUpAndVerifyContext(baseUrl, cookies)

            // Wyznacz idDziennik z konta lub z /api/Context
            var idDziennik = account.pupilId
            if (idDziennik <= 0) {
                val uczniowie = contextJson.optJSONArray("uczniowie")
                if (uczniowie != null) {
                    for (i in 0 until uczniowie.length()) {
                        val u = uczniowie.optJSONObject(i) ?: continue
                        if (u.optString("key") == key || idDziennik <= 0) {
                            val idDz = u.optInt("idDziennik", 0)
                            if (idDz > 0) {
                                idDziennik = idDz
                                if (u.optString("key") == key) break
                            }
                        }
                    }
                }
            }

            if (idDziennik <= 0) {
                return Result.failure(IOException("Brak identyfikatora dziennika (idDziennik) dla tego ucznia w eduVULCAN."))
            }

            // 1. Pobierz okresy klasyfikacyjne (/api/OkresyKlasyfikacyjne?key=...&idDziennik=...)
            val periodsUrl = "$baseUrl/api/OkresyKlasyfikacyjne".toHttpUrlOrNull()!!
                .newBuilder()
                .addQueryParameter("key", key)
                .addQueryParameter("idDziennik", idDziennik.toString())
                .build()

            val periodsReq = Request.Builder()
                .url(periodsUrl)
                .get()
                .header("Cookie", cookies)
                .header("User-Agent", EduVulcanWebAuthenticator.USER_AGENT)
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$baseUrl/App")
                .build()

            val periodsRes = okHttp.newCall(periodsReq).execute()
            val periodsBody = periodsRes.body?.string().orEmpty().trim()
            if (periodsRes.code == 401 || periodsRes.code == 403 || isLoginRedirect(periodsRes.request.url.toString(), periodsBody)) {
                return Result.failure(EduVulcanSessionExpiredException("Sesja eduVULCAN wygasła podczas pobierania okresów klasyfikacyjnych."))
            }
            if (!periodsRes.isSuccessful) {
                return Result.failure(IOException("Błąd pobierania okresów klasyfikacyjnych (HTTP ${periodsRes.code})"))
            }

            val periodsArray = runCatching { JSONArray(periodsBody) }.getOrNull()
                ?: return Result.failure(IOException("Nieprawidłowy format okresów klasyfikacyjnych eduVULCAN"))

            if (periodsArray.length() == 0) {
                return Result.success(emptyList())
            }

            val today = LocalDate.now()
            var activePeriodId: String? = null
            for (i in 0 until periodsArray.length()) {
                val p = periodsArray.optJSONObject(i) ?: continue
                val pId = p.opt("id")?.toString() ?: continue
                val fromDate = parseDateOrNull(p.optString("dataOd"))
                val toDate = parseDateOrNull(p.optString("dataDo"))
                if (fromDate != null && toDate != null && !today.isBefore(fromDate) && !today.isAfter(toDate)) {
                    activePeriodId = pId
                    break
                }
            }
            if (activePeriodId == null) {
                activePeriodId = periodsArray.optJSONObject(periodsArray.length() - 1)?.opt("id")?.toString()
            }

            val nowSeconds = System.currentTimeMillis() / 1000
            val allGrades = mutableListOf<StudentGrade>()

            // 2. Dla każdego okresu klasyfikacyjnego pobierz oceny (/api/Oceny?key=...&idOkresKlasyfikacyjny=...)
            for (i in 0 until periodsArray.length()) {
                val p = periodsArray.optJSONObject(i) ?: continue
                val periodId = p.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: continue
                val periodNum = p.optInt("numerOkresu", i + 1)
                val isActive = periodId == activePeriodId

                val gradesUrl = "$baseUrl/api/Oceny".toHttpUrlOrNull()!!
                    .newBuilder()
                    .addQueryParameter("key", key)
                    .addQueryParameter("idOkresKlasyfikacyjny", periodId)
                    .build()

                val gradesReq = Request.Builder()
                    .url(gradesUrl)
                    .get()
                    .header("Cookie", cookies)
                    .header("User-Agent", EduVulcanWebAuthenticator.USER_AGENT)
                    .header("Accept", "application/json")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", "$baseUrl/App")
                    .build()

                val gradesRes = runCatching { okHttp.newCall(gradesReq).execute() }.getOrNull() ?: continue
                if (!gradesRes.isSuccessful) continue
                val gradesBody = gradesRes.body?.string().orEmpty().trim()
                if (gradesBody.isBlank()) continue

                val parsedPeriodGrades = parseEduVulcanGradesJson(
                    jsonStr = gradesBody,
                    periodId = periodId,
                    periodNumber = periodNum,
                    isActivePeriod = isActive,
                    nowSeconds = nowSeconds
                )
                allGrades.addAll(parsedPeriodGrades)
            }

            return Result.success(allGrades)
        } catch (e: EduVulcanSessionExpiredException) {
            return Result.failure(e)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    internal fun parseEduVulcanGradesJson(
        jsonStr: String,
        periodId: String,
        periodNumber: Int,
        isActivePeriod: Boolean,
        nowSeconds: Long
    ): List<StudentGrade> {
        val root = runCatching { JSONObject(jsonStr) }.getOrNull() ?: return emptyList()
        val result = mutableListOf<StudentGrade>()

        val srednieWlaczone = root.optJSONObject("ustawienia")?.optBoolean("isSredniaAndPunkty", true) ?: true

        // Wariant 1: uczen.eduvulcan.pl ("ocenyPrzedmioty")
        val przedmiotyArr = root.optJSONArray("ocenyPrzedmioty")
            ?: root.optJSONObject("data")?.optJSONArray("Oceny")

        if (przedmiotyArr == null) return emptyList()

        for (s in 0 until przedmiotyArr.length()) {
            val pItem = przedmiotyArr.optJSONObject(s) ?: continue
            val subjectName = pItem.optString("przedmiotNazwa")
                .ifBlank { pItem.optString("Przedmiot") }
                .trim()
                .ifBlank { "Inne" }

            val rawAvg = pItem.opt("srednia") ?: pItem.opt("Srednia")
            val apiAvg = if (srednieWlaczone && rawAvg != null && rawAvg != JSONObject.NULL) {
                rawAvg.toString().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 }
            } else null

            val proposed = pItem.optString("proponowanaOcenaOkresowa")
                .ifBlank { pItem.optString("ProponowanaOcenaRoczna") }
                .trim()
                .takeIf { it.isNotBlank() && it != "null" }

            val finalG = pItem.optString("ocenaOkresowa")
                .ifBlank { pItem.optString("OcenaRoczna") }
                .trim()
                .takeIf { it.isNotBlank() && it != "null" }

            var addedForSubject = 0

            // Nowy układ: kolumnyOcenyCzastkowe -> oceny
            val kolumny = pItem.optJSONArray("kolumnyOcenyCzastkowe")
            if (kolumny != null) {
                for (k in 0 until kolumny.length()) {
                    val kol = kolumny.optJSONObject(k) ?: continue
                    val idKolumny = kol.opt("idKolumny")?.toString().orEmpty().ifBlank { "$k" }
                    val kat = kol.optString("kategoriaKolumny").trim().takeIf { it.isNotBlank() && it != "null" }
                    val nazwaKol = kol.optString("nazwaKolumny").trim().takeIf { it.isNotBlank() && it != "null" }
                    val colWeight = kol.optDouble("waga", 1.0).let { if (it.isNaN() || it <= 0.0) 1.0 else it }

                    val ocenyArr = kol.optJSONArray("oceny") ?: continue
                    for (oIdx in 0 until ocenyArr.length()) {
                        val o = ocenyArr.optJSONObject(oIdx) ?: continue
                        val wpis = o.optString("wpis").trim()
                        if (wpis.isBlank() || wpis == "null") continue

                        val rawDate = o.optString("dataOceny").trim().takeIf { it.isNotBlank() && it != "null" }
                        val formattedDate = parseDateOrNull(rawDate)?.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                            ?: rawDate

                        val weight = o.optDouble("waga", colWeight).let { if (it.isNaN() || it <= 0.0) colWeight else it }
                        val teacher = o.optString("nauczycielImieNazwisko")
                            .ifBlank { o.optString("nauczyciel") }
                            .trim()
                            .takeIf { it.isNotBlank() && it != "null" }
                        val superseded = !o.isNull("idOcenaPoprawiona") &&
                            o.optString("idOcenaPoprawiona").let { it.isNotBlank() && it != "null" && it != "0" }

                        result.add(
                            StudentGrade(
                                id = "ev_${periodId}_${subjectName}_${idKolumny}_${oIdx}_${wpis}",
                                subject = subjectName,
                                value = wpis,
                                weight = weight,
                                category = kat,
                                description = nazwaKol,
                                teacher = teacher,
                                dateText = formattedDate,
                                periodNumber = periodNumber,
                                isActivePeriod = isActivePeriod,
                                isSuperseded = superseded,
                                apiSubjectAverage = apiAvg,
                                proposedGrade = proposed,
                                finalGrade = finalG,
                                updatedAtEpochSeconds = nowSeconds
                            )
                        )
                        addedForSubject++
                    }
                }
            }

            // Starszy układ: OcenyCzastkowe bezpośrednio na przedmiocie
            val ocenyCzastkowe = pItem.optJSONArray("OcenyCzastkowe")
            if (ocenyCzastkowe != null) {
                for (oIdx in 0 until ocenyCzastkowe.length()) {
                    val o = ocenyCzastkowe.optJSONObject(oIdx) ?: continue
                    val wpis = o.optString("Wpis").trim()
                    if (wpis.isBlank() || wpis == "null") continue
                    val weight = o.optDouble("Waga", 1.0).let { if (it.isNaN() || it <= 0.0) 1.0 else it }
                    val kat = o.optString("KodKolumny").trim().takeIf { it.isNotBlank() && it != "null" }
                    val desc = o.optString("NazwaKolumny").trim().takeIf { it.isNotBlank() && it != "null" }
                    val teacher = o.optString("Nauczyciel").trim().takeIf { it.isNotBlank() && it != "null" }
                    val rawDate = o.optString("DataOceny").trim().takeIf { it.isNotBlank() && it != "null" }

                    result.add(
                        StudentGrade(
                            id = "ev_${periodId}_${subjectName}_legacy_${oIdx}_${wpis}",
                            subject = subjectName,
                            value = wpis,
                            weight = weight,
                            category = kat,
                            description = desc,
                            teacher = teacher,
                            dateText = rawDate,
                            periodNumber = periodNumber,
                            isActivePeriod = isActivePeriod,
                            isSuperseded = false,
                            apiSubjectAverage = apiAvg,
                            proposedGrade = proposed,
                            finalGrade = finalG,
                            updatedAtEpochSeconds = nowSeconds
                        )
                    )
                    addedForSubject++
                }
            }

            // Jeśli przedmiot ma ocenę proponowaną/roczną lub średnią, ale brak ocen cząstkowych
            if (addedForSubject == 0 && (proposed != null || finalG != null || apiAvg != null)) {
                result.add(
                    StudentGrade(
                        id = "ev_${periodId}_${subjectName}_summary",
                        subject = subjectName,
                        value = "",
                        weight = 0.0,
                        category = null,
                        description = null,
                        teacher = null,
                        dateText = null,
                        periodNumber = periodNumber,
                        isActivePeriod = isActivePeriod,
                        isSuperseded = false,
                        apiSubjectAverage = apiAvg,
                        proposedGrade = proposed,
                        finalGrade = finalG,
                        updatedAtEpochSeconds = nowSeconds
                    )
                )
            }
        }

        return result
    }

    private fun warmUpAndVerifyContext(baseUrl: String, cookies: String): JSONObject {
        val ctxReq = Request.Builder()
            .url("$baseUrl/api/Context")
            .get()
            .header("Cookie", cookies)
            .header("User-Agent", EduVulcanWebAuthenticator.USER_AGENT)
            .header("Accept", "application/json")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        val res = okHttp.newCall(ctxReq).execute()
        val body = res.body?.string().orEmpty()
        if (res.code == 401 || res.code == 403 || isLoginRedirect(res.request.url.toString(), body)) {
            throw EduVulcanSessionExpiredException("Sesja eduVULCAN wygasła – wymagane ponowne logowanie.")
        }
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (json == null || !json.has("uczniowie")) {
            throw EduVulcanSessionExpiredException("Sesja eduVULCAN jest nieaktywna.")
        }
        return json
    }

    private fun fetchDetailJson(
        baseUrl: String,
        endpoint: String,
        key: String,
        itemId: String,
        cookies: String
    ): JSONObject? {
        return runCatching {
            val url = "$baseUrl/api/$endpoint".toHttpUrlOrNull()!!
                .newBuilder()
                .addQueryParameter("key", key)
                .addQueryParameter("id", itemId)
                .build()

            val req = Request.Builder()
                .url(url)
                .get()
                .header("Cookie", cookies)
                .header("User-Agent", EduVulcanWebAuthenticator.USER_AGENT)
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$baseUrl/App")
                .build()

            val res = okHttp.newCall(req).execute()
            if (res.isSuccessful) {
                val body = res.body?.string().orEmpty().trim()
                if (body.startsWith("{")) JSONObject(body) else null
            } else null
        }.getOrNull()
    }

    private fun buildMergedCookies(domain: String, symbol: String, savedCookiesHeader: String): String {
        val liveCookies = runCatching { EduVulcanWebAuthenticator.collectCookiesHeader(domain, symbol) }.getOrDefault("")
        if (liveCookies.isBlank()) return savedCookiesHeader
        if (savedCookiesHeader.isBlank()) return liveCookies

        val map = LinkedHashMap<String, String>()
        for (source in listOf(savedCookiesHeader, liveCookies)) {
            source.split(";").forEach { part ->
                val trimmed = part.trim()
                val eq = trimmed.indexOf('=')
                if (eq > 0) {
                    map[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
                }
            }
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun isLoginRedirect(finalUrl: String, body: String): Boolean {
        val lowerUrl = finalUrl.lowercase(Locale.ROOT)
        if (lowerUrl.contains("/logowanie") || lowerUrl.contains("login")) return true
        if (body.contains("id=\"UserName\"") || body.contains("name=\"UserName\"")) return true
        return false
    }

    private fun stripHtml(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|li|tr)>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * Starsza metoda rejestracji przez Token/Symbol/PIN (zachowana dla kompatybilności wstecznej).
     */
    fun register(input: VulcanRegistrationInput): Result<VulcanAccount> {
        val cleanToken = input.token.trim()
        val cleanSymbol = input.symbol.trim()
        val cleanPin = input.pin.trim()

        if (cleanToken.isBlank() || cleanSymbol.isBlank() || cleanPin.isBlank()) {
            return Result.failure(IllegalArgumentException("Token, Symbol i PIN nie mogą być puste"))
        }

        val keyPair = VulcanCrypto.generateKeyAndCertificate()

        val requestBodyJson = JSONObject().apply {
            put("pin", cleanPin)
            put("token", cleanToken)
            put("symbol", cleanSymbol)
            put("deviceModel", "Elektron-Vulcan (Android)")
            put("certificate", keyPair.certificatePem)
            put("certificateType", "X509")
            put("certificateThumbprint", keyPair.certificateThumbprintHex)
            put("selfSigned", true)
        }.toString()

        val candidateUrls = listOf(
            "https://uonetplus-komunikacja.vulcan.net.pl/$cleanSymbol/api/mobile/register/new",
            "https://uonetplus-komunikacja.vulcan.net.pl/$cleanSymbol/mobile-api/v1/register",
            "https://api.vulcan.net.pl/powiat/api/mobile/register/new",
            "https://api.vulcan.net.pl/$cleanSymbol/api/mobile/register/new"
        )

        var lastError: Exception? = null

        for (url in candidateUrls) {
            try {
                val nowGmt = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC))
                val request = Request.Builder()
                    .url(url)
                    .post(requestBodyJson.toRequestBody(jsonMediaType))
                    .header("Accept", "application/json")
                    .header("vDate", nowGmt)
                    .header("vOS", "Android")
                    .header("vDeviceModel", "Elektron-Vulcan (Android)")
                    .build()

                val response = okHttp.newCall(request).execute()
                val code = response.code
                val bodyStr = response.body?.string().orEmpty()

                if (response.isSuccessful && bodyStr.isNotBlank()) {
                    val json = JSONObject(bodyStr)
                    val status = json.optInt("Status", -1)
                    if (status == 0) {
                        val account = parseRegistrationEnvelope(json, input, keyPair)
                        return Result.success(account)
                    } else {
                        val message = json.optString("Message").ifBlank { "Błąd rejestracji VULCAN (Status $status)" }
                        lastError = IOException(message)
                    }
                } else if (code == 400 || code == 401 || code == 403) {
                    val message = runCatching { JSONObject(bodyStr).optString("Message") }.getOrNull()
                        ?: "Błędny token, symbol lub PIN (HTTP $code)"
                    lastError = IOException(message)
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        return Result.failure(lastError ?: IOException("Nie udało się połączyć z serwerem VULCAN."))
    }

    private fun fetchHebeEvents(account: VulcanAccount): Result<List<SchoolEvent>> {
        val baseRestUrl = account.restUrl.trimEnd('/')
        val endpoints = listOf(
            "api/mobile/exam/byPupil?pupilId=${account.pupilId}",
            "api/mobile/schedule/exam?pupilId=${account.pupilId}"
        )

        val allEvents = mutableListOf<SchoolEvent>()
        val privateKey = runCatching { VulcanCrypto.parsePrivateKey(account.privateKeyPkcs8Base64) }
            .getOrElse { return Result.failure(it) }

        var fetchedAny = false
        var lastException: Exception? = null

        for (endpoint in endpoints) {
            val fullUrl = "$baseRestUrl/$endpoint"
            val canonicalPath = endpoint.substringBefore('?')

            try {
                val nowGmt = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC))
                val stringToSign = "$canonicalPath\n\n$nowGmt"
                val signature = VulcanCrypto.sign(privateKey, stringToSign)

                val request = Request.Builder()
                    .url(fullUrl)
                    .get()
                    .header("Accept", "application/json")
                    .header("vDate", nowGmt)
                    .header("vOS", "Android")
                    .header("vDeviceModel", "Elektron-Vulcan (Android)")
                    .header("vCanonicalUrl", canonicalPath)
                    .header("Signature", signature)
                    .build()

                val response = okHttp.newCall(request).execute()
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string().orEmpty()
                    if (bodyStr.isNotBlank()) {
                        val events = parseLegacyOrGroupedExamsJson(bodyStr)
                        allEvents.addAll(events)
                        fetchedAny = true
                    }
                }
            } catch (e: Exception) {
                lastException = e
            }
        }

        if (fetchedAny) {
            return Result.success(allEvents.distinctBy { it.id })
        }
        if (lastException != null) {
            return Result.failure(lastException)
        }
        return Result.success(emptyList())
    }

    private fun parseRegistrationEnvelope(
        json: JSONObject,
        input: VulcanRegistrationInput,
        keyPair: VulcanCrypto.KeyCertificatePair
    ): VulcanAccount {
        var envelopeObj: JSONObject? = null
        if (json.has("Envelope")) {
            val env = json.get("Envelope")
            if (env is JSONObject) {
                envelopeObj = env
            } else if (env is JSONArray && env.length() > 0) {
                envelopeObj = env.getJSONObject(0)
            }
        }

        val restUrl = envelopeObj?.optString("RestURL")
            ?.takeIf { it.isNotBlank() }
            ?: "https://uonetplus-uczen.vulcan.net.pl/${input.symbol.trim()}/"

        val pupilObj = envelopeObj?.optJSONObject("Pupil")
        val pupilId = pupilObj?.optInt("Id", 0) ?: envelopeObj?.optInt("PupilId", 0) ?: 0
        val firstName = pupilObj?.optString("FirstName").orEmpty()
        val lastName = pupilObj?.optString("LastName").orEmpty()
        val pupilName = if (firstName.isNotBlank() || lastName.isNotBlank()) {
            "$firstName $lastName".trim()
        } else {
            "Uczeń"
        }

        val unitObj = envelopeObj?.optJSONObject("ConstituentUnit")
            ?: envelopeObj?.optJSONObject("Unit")
        val schoolName = unitObj?.optString("Name")
            ?.ifBlank { unitObj.optString("Short") }
            ?: input.symbol.trim()

        return VulcanAccount(
            token = input.token.trim(),
            symbol = input.symbol.trim(),
            pin = input.pin.trim(),
            pupilId = pupilId,
            pupilName = pupilName,
            schoolName = schoolName,
            restUrl = restUrl,
            certificatePem = keyPair.certificatePem,
            certificateThumbprint = keyPair.certificateThumbprintHex,
            privateKeyPkcs8Base64 = keyPair.privateKeyPkcs8Base64,
            isEduVulcan = false
        )
    }

    private fun parseLegacyOrGroupedExamsJson(jsonString: String): List<SchoolEvent> {
        val events = mutableListOf<SchoolEvent>()
        val root = runCatching { JSONObject(jsonString) }.getOrNull() ?: return emptyList()

        // Obsługa SprawdzianyGroupedByDayList (UONET+)
        if (root.has("data") && root.get("data") is JSONArray) {
            val dataArr = root.getJSONArray("data")
            for (d in 0 until dataArr.length()) {
                val wrapper = dataArr.optJSONObject(d) ?: continue
                val grouped = wrapper.optJSONArray("SprawdzianyGroupedByDayList")
                if (grouped != null) {
                    for (g in 0 until grouped.length()) {
                        val dayObj = grouped.optJSONObject(g) ?: continue
                        val date = parseDateOrNull(dayObj.optString("Data")) ?: continue
                        val exams = dayObj.optJSONArray("Sprawdziany") ?: continue
                        for (e in 0 until exams.length()) {
                            val ex = exams.optJSONObject(e) ?: continue
                            val id = ex.optString("Id").ifBlank { "${date}_$e" }
                            val subject = ex.optString("Nazwa")
                            val teacher = ex.optString("Pracownik")
                            val opis = ex.optString("Opis")
                            val rodzaj = ex.optInt("Rodzaj", 1)
                            val (label, type) = when (rodzaj) {
                                1 -> "Sprawdzian" to SchoolEventType.EXAM
                                2 -> "Kartkówka" to SchoolEventType.QUIZ
                                3 -> "Praca klasowa" to SchoolEventType.EXAM
                                else -> "Sprawdzian" to SchoolEventType.EXAM
                            }
                            val title = if (subject.isNotBlank()) "$label: $subject" else label
                            val desc = buildString {
                                if (opis.isNotBlank()) append(opis)
                                if (teacher.isNotBlank()) {
                                    if (isNotEmpty()) append("\n")
                                    append("Nauczyciel: ").append(teacher)
                                }
                            }
                            events.add(
                                SchoolEvent(
                                    id = "vulcan_$id",
                                    title = title,
                                    description = desc.ifBlank { null },
                                    type = type,
                                    subject = subject.ifBlank { null },
                                    date = date,
                                    timeFrom = null,
                                    timeTo = null,
                                    location = null,
                                    rawSummary = "$title $subject $opis"
                                )
                            )
                        }
                    }
                    return events
                }
            }
        }

        val envelopeArray: JSONArray = when {
            root.has("Envelope") && root.get("Envelope") is JSONArray -> root.getJSONArray("Envelope")
            root.has("Envelope") && root.get("Envelope") is JSONObject -> JSONArray().put(root.getJSONObject("Envelope"))
            root.has("Data") && root.get("Data") is JSONArray -> root.getJSONArray("Data")
            else -> JSONArray()
        }

        for (i in 0 until envelopeArray.length()) {
            val item = envelopeArray.optJSONObject(i) ?: continue
            val id = item.optString("Id").ifBlank { "vulcan_exam_$i" }
            val subject = when {
                item.has("Subject") && item.get("Subject") is JSONObject -> {
                    val sObj = item.getJSONObject("Subject")
                    sObj.optString("Name").ifBlank { sObj.optString("Code") }
                }
                item.has("Subject") -> item.optString("Subject")
                else -> ""
            }
            val typeStr = item.optString("Type").ifBlank { item.optString("TypeName") }
            val eventType = mapEventType(typeStr)
            val content = item.optString("Content")
                .ifBlank { item.optString("Topic") }
                .ifBlank { item.optString("Description") }

            val teacher = when {
                item.has("Creator") && item.get("Creator") is JSONObject -> item.getJSONObject("Creator").optString("DisplayName")
                item.has("Teacher") && item.get("Teacher") is JSONObject -> item.getJSONObject("Teacher").optString("DisplayName")
                item.has("Creator") -> item.optString("Creator")
                else -> ""
            }

            var date: LocalDate? = null
            var time: LocalTime? = null

            if (item.has("Deadline") && item.get("Deadline") is JSONObject) {
                val dObj = item.getJSONObject("Deadline")
                date = parseDateOrNull(dObj.optString("Date"))
                time = parseTimeOrNull(dObj.optString("Time"))
            } else if (item.has("Deadline")) {
                date = parseDateOrNull(item.optString("Deadline"))
            }
            if (date == null && item.has("Date")) {
                date = parseDateOrNull(item.optString("Date"))
            }
            if (date == null) continue

            val title = buildString {
                if (typeStr.isNotBlank()) append(typeStr)
                if (subject.isNotBlank()) {
                    if (isNotEmpty()) append(": ")
                    append(subject)
                }
                if (isEmpty()) append("Wydarzenie szkolne")
            }

            val fullDescription = buildString {
                if (content.isNotBlank()) append(content)
                if (teacher.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append("Nauczyciel: ").append(teacher)
                }
            }

            events.add(
                SchoolEvent(
                    id = "vulcan_$id",
                    title = title,
                    description = fullDescription.ifBlank { null },
                    type = eventType,
                    subject = subject.ifBlank { null },
                    date = date,
                    timeFrom = time,
                    timeTo = null,
                    location = null,
                    rawSummary = "$title $subject $content"
                )
            )
        }

        return events
    }

    private fun mapEventType(typeStr: String): SchoolEventType {
        val lower = typeStr.lowercase(Locale.getDefault())
        return when {
            lower.contains("kartk") -> SchoolEventType.QUIZ
            lower.contains("sprawdzian") || lower.contains("klasow") || lower.contains("test") || lower.contains("egzamin") -> SchoolEventType.EXAM
            lower.contains("domow") || lower.contains("zadanie") -> SchoolEventType.HOMEWORK
            else -> SchoolEventType.OTHER
        }
    }

    private fun parseDateOrNull(str: String?): LocalDate? {
        if (str.isNullOrBlank()) return null
        val clean = str.substringBefore('T').substringBefore(' ').trim()
        val formatters = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ofPattern("d.MM.yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd")
        )
        for (fmt in formatters) {
            val date = runCatching { LocalDate.parse(clean, fmt) }.getOrNull()
            if (date != null) return date
        }
        return null
    }

    private fun parseTimeOrNull(str: String?): LocalTime? {
        if (str.isNullOrBlank()) return null
        val clean = str.trim().substringBefore('Z')
        val formatters = listOf(
            DateTimeFormatter.ofPattern("HH:mm:ss"),
            DateTimeFormatter.ofPattern("H:mm:ss"),
            DateTimeFormatter.ofPattern("HH:mm"),
            DateTimeFormatter.ofPattern("H:mm")
        )
        for (fmt in formatters) {
            val time = runCatching { LocalTime.parse(clean, fmt) }.getOrNull()
            if (time != null) return time
        }
        return null
    }
}
