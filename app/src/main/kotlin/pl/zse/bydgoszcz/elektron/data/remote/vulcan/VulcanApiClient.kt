package pl.zse.bydgoszcz.elektron.data.remote.vulcan

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import pl.zse.bydgoszcz.elektron.domain.model.SchoolEvent
import pl.zse.bydgoszcz.elektron.domain.model.SchoolEventType
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
 * Klient API VULCAN UONET+ / eduVULCAN (Hebe Mobile API).
 * Obsługuje rejestrację urządzenia (Token, Symbol, PIN) oraz pobieranie sprawdzianów i terminarza.
 */
@Singleton
class VulcanApiClient @Inject constructor(
    private val okHttp: OkHttpClient
) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Rejestruje nowe urządzenie w systemie VULCAN.
     * Generuje parę kluczy i certyfikat, odpytuje endpointy rejestracyjne VULCAN i zwraca [VulcanAccount].
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
            put("deviceModel", "eLektron (Android)")
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
                    .header("vDeviceModel", "eLektron (Android)")
                    .build()

                val response = okHttp.newCall(request).execute()
                val code = response.code
                val bodyStr = response.body?.string().orEmpty()

                if (response.isSuccessful && bodyStr.isNotBlank()) {
                    val json = JSONObject(bodyStr)
                    val status = json.optInt("Status", -1)
                    if (status == 0) {
                        val account = parseRegistrationEnvelope(
                            json = json,
                            input = input,
                            keyPair = keyPair
                        )
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

        return Result.failure(lastError ?: IOException("Nie udało się połączyć z serwerem VULCAN. Sprawdź Symbol, Token i PIN."))
    }

    /**
     * Pobiera sprawdziany, kartkówki i wydarzenia szkolne z zarejestrowanego konta VULCAN.
     */
    fun fetchEvents(account: VulcanAccount): Result<List<SchoolEvent>> {
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
                // Ciąg do podpisu w protokole Hebe: canonicalPath + "\n\n" + vDate
                val stringToSign = "$canonicalPath\n\n$nowGmt"
                val signature = VulcanCrypto.sign(privateKey, stringToSign)

                val request = Request.Builder()
                    .url(fullUrl)
                    .get()
                    .header("Accept", "application/json")
                    .header("vDate", nowGmt)
                    .header("vOS", "Android")
                    .header("vDeviceModel", "eLektron (Android)")
                    .header("vCanonicalUrl", canonicalPath)
                    .header("Signature", signature)
                    .build()

                val response = okHttp.newCall(request).execute()
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string().orEmpty()
                    if (bodyStr.isNotBlank()) {
                        val events = parseExamsJson(bodyStr)
                        allEvents.addAll(events)
                        fetchedAny = true
                    }
                }
            } catch (e: Exception) {
                lastException = e
            }
        }

        if (fetchedAny) {
            // Usuwamy duplikaty po ID
            val distinctEvents = allEvents.distinctBy { it.id }
            return Result.success(distinctEvents)
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
            privateKeyPkcs8Base64 = keyPair.privateKeyPkcs8Base64
        )
    }

    private fun parseExamsJson(jsonString: String): List<SchoolEvent> {
        val events = mutableListOf<SchoolEvent>()
        val root = runCatching { JSONObject(jsonString) }.getOrNull() ?: return emptyList()

        val envelopeArray: JSONArray = when {
            root.has("Envelope") && root.get("Envelope") is JSONArray -> root.getJSONArray("Envelope")
            root.has("Envelope") && root.get("Envelope") is JSONObject -> JSONArray().put(root.getJSONObject("Envelope"))
            root.has("Data") && root.get("Data") is JSONArray -> root.getJSONArray("Data")
            else -> JSONArray()
        }

        for (i in 0 until envelopeArray.length()) {
            val item = envelopeArray.optJSONObject(i) ?: continue

            val id = item.optString("Id").ifBlank { "vulcan_exam_$i" }

            // Przedmiot
            val subject = when {
                item.has("Subject") && item.get("Subject") is JSONObject -> {
                    val sObj = item.getJSONObject("Subject")
                    sObj.optString("Name").ifBlank { sObj.optString("Code") }
                }
                item.has("Subject") -> item.optString("Subject")
                else -> ""
            }

            // Typ wydarzenia (Sprawdzian, Kartkówka, Praca klasowa, itp.)
            val typeStr = item.optString("Type").ifBlank { item.optString("TypeName") }
            val eventType = mapEventType(typeStr)

            // Opis / Treść
            val content = item.optString("Content")
                .ifBlank { item.optString("Topic") }
                .ifBlank { item.optString("Description") }

            // Nauczyciel
            val teacher = when {
                item.has("Creator") && item.get("Creator") is JSONObject -> {
                    item.getJSONObject("Creator").optString("DisplayName")
                }
                item.has("Teacher") && item.get("Teacher") is JSONObject -> {
                    item.getJSONObject("Teacher").optString("DisplayName")
                }
                item.has("Creator") -> item.optString("Creator")
                else -> ""
            }

            // Data i godzina
            var date: LocalDate? = null
            var time: LocalTime? = null

            if (item.has("Deadline") && item.get("Deadline") is JSONObject) {
                val dObj = item.getJSONObject("Deadline")
                val dateStr = dObj.optString("Date")
                date = parseDateOrNull(dateStr)
                val timeStr = dObj.optString("Time")
                time = parseTimeOrNull(timeStr)
            } else if (item.has("Deadline")) {
                date = parseDateOrNull(item.optString("Deadline"))
            }

            if (date == null && item.has("Date")) {
                date = parseDateOrNull(item.optString("Date"))
            }

            if (date == null) {
                // Pomijamy jeśli brak daty
                continue
            }

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

            val rawSummary = "$title $subject $content"

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
                    rawSummary = rawSummary
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

    private fun parseDateOrNull(str: String): LocalDate? {
        if (str.isBlank()) return null
        val clean = str.substringBefore('T').trim()
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

    private fun parseTimeOrNull(str: String): LocalTime? {
        if (str.isBlank()) return null
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
