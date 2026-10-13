package pl.zse.bydgoszcz.elektron.data.repository

import android.content.Context
import android.webkit.CookieManager
import androidx.datastore.core.DataStore
import pl.zse.bydgoszcz.elektron.data.local.CredentialMigration
import pl.zse.bydgoszcz.elektron.data.local.KeystoreCredentialCipher
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.zse.bydgoszcz.elektron.domain.util.runCatchingCancellable
import okhttp3.OkHttpClient
import okhttp3.Request
import pl.zse.bydgoszcz.elektron.data.local.AttendanceDao
import pl.zse.bydgoszcz.elektron.data.local.AttendanceEntity
import pl.zse.bydgoszcz.elektron.data.local.GradeDao
import pl.zse.bydgoszcz.elektron.data.local.SchoolEventDao
import pl.zse.bydgoszcz.elektron.data.local.SchoolEventEntity
import pl.zse.bydgoszcz.elektron.data.remote.parser.IcalParser
import pl.zse.bydgoszcz.elektron.data.remote.vulcan.EduVulcanSessionExpiredException
import pl.zse.bydgoszcz.elektron.data.remote.vulcan.EduVulcanWebAuthenticator
import pl.zse.bydgoszcz.elektron.data.remote.vulcan.VulcanApiClient
import pl.zse.bydgoszcz.elektron.domain.model.EduVulcanSession
import pl.zse.bydgoszcz.elektron.domain.model.SchoolEvent
import pl.zse.bydgoszcz.elektron.domain.model.SchoolEventType
import pl.zse.bydgoszcz.elektron.domain.model.SubjectAttendance
import pl.zse.bydgoszcz.elektron.domain.model.VulcanAccount
import pl.zse.bydgoszcz.elektron.domain.model.VulcanRegistrationInput
import pl.zse.bydgoszcz.elektron.domain.repository.CalendarRepository
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

private val Context.calendarPreferences: DataStore<Preferences> by preferencesDataStore(
    name = "calendar_preferences",
    produceMigrations = { listOf(CredentialMigration(KeystoreCredentialCipher())) }
)

@Singleton
class CalendarRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val schoolEventDao: SchoolEventDao,
    private val attendanceDao: AttendanceDao,
    private val gradeDao: GradeDao,
    private val okHttp: OkHttpClient,
    private val vulcanApiClient: VulcanApiClient,
    private val eduVulcanAuthenticator: EduVulcanWebAuthenticator
) : CalendarRepository {

    private val store = context.calendarPreferences
    private val accountOperations = Mutex()
    private val calendarHttp = okHttp.newBuilder()
        .apply { interceptors().removeAll { it is okhttp3.logging.HttpLoggingInterceptor } }
        .cache(null).build()
    private val credentialCipher = KeystoreCredentialCipher()
    private fun credential(prefs: Preferences, key: Preferences.Key<String>): String? =
        prefs[key]?.let { credentialCipher.decrypt(it, key.name) }
    private fun encrypt(value: String, key: Preferences.Key<String>): String = credentialCipher.encrypt(value, key.name)

    private object Keys {
        val CALENDAR_URL = stringPreferencesKey("encrypted_calendar_url")
        val LAST_SYNC_EPOCH = longPreferencesKey("last_sync_epoch")
        val LAST_SYNC_ERROR = stringPreferencesKey("last_sync_error")

        // Poświadczenia i stan sesji eduVULCAN / VULCAN
        val VULCAN_TOKEN = stringPreferencesKey("encrypted_vulcan_token")
        val VULCAN_SYMBOL = stringPreferencesKey("encrypted_vulcan_symbol")
        val VULCAN_PUPIL_ID = intPreferencesKey("vulcan_pupil_id")
        val VULCAN_PUPIL_NAME = stringPreferencesKey("encrypted_vulcan_pupil_name")
        val VULCAN_SCHOOL_NAME = stringPreferencesKey("encrypted_vulcan_school_name")
        val VULCAN_REST_URL = stringPreferencesKey("encrypted_vulcan_rest_url")
        val VULCAN_CERT_PEM = stringPreferencesKey("encrypted_vulcan_cert_pem")
        val VULCAN_THUMBPRINT = stringPreferencesKey("encrypted_vulcan_thumbprint")
        val VULCAN_PRIVATE_KEY_PKCS8 = stringPreferencesKey("encrypted_vulcan_private_key_pkcs8")
        val VULCAN_IS_EDUVULCAN = booleanPreferencesKey("vulcan_is_eduvulcan")
        val VULCAN_SAVED_PASSWORD = stringPreferencesKey("encrypted_vulcan_saved_password")
        val EDU_STUDENT_KEY = stringPreferencesKey("encrypted_vulcan_student_key")
        val EDU_DOMAIN = stringPreferencesKey("encrypted_vulcan_domain")
        val EDU_LOGIN = stringPreferencesKey("encrypted_vulcan_login_username")
        val EDU_COOKIES = stringPreferencesKey("encrypted_vulcan_cookies_header")
    }

    override val calendarUrl: Flow<String?> = store.data.map { credential(it, Keys.CALENDAR_URL) }
        .catch { emit(null) }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override val vulcanAccount: Flow<VulcanAccount?> = store.data.map { prefs ->
        val symbol = credential(prefs, Keys.VULCAN_SYMBOL) ?: return@map null
        val restUrl = credential(prefs, Keys.VULCAN_REST_URL) ?: return@map null
        val token = credential(prefs, Keys.VULCAN_TOKEN).orEmpty()
        val certPem = credential(prefs, Keys.VULCAN_CERT_PEM).orEmpty()
        val privateKey = credential(prefs, Keys.VULCAN_PRIVATE_KEY_PKCS8).orEmpty()
        val isEduVulcan = prefs[Keys.VULCAN_IS_EDUVULCAN] ?: restUrl.contains("uczen.")
        val thumbprint = credential(prefs, Keys.VULCAN_THUMBPRINT).orEmpty()

        VulcanAccount(
            token = token,
            symbol = symbol,
            pin = "",
            pupilId = prefs[Keys.VULCAN_PUPIL_ID] ?: 0,
            pupilName = credential(prefs, Keys.VULCAN_PUPIL_NAME).orEmpty().ifBlank { "Uczeń" },
            schoolName = credential(prefs, Keys.VULCAN_SCHOOL_NAME).orEmpty().ifBlank { symbol },
            restUrl = restUrl,
            certificatePem = certPem,
            certificateThumbprint = thumbprint,
            privateKeyPkcs8Base64 = privateKey,
            isEduVulcan = isEduVulcan,
            domain = credential(prefs, Keys.EDU_DOMAIN).orEmpty().ifBlank { "eduvulcan.pl" },
            studentKey = credential(prefs, Keys.EDU_STUDENT_KEY).orEmpty(),
            cookiesHeader = credential(prefs, Keys.EDU_COOKIES).orEmpty(),
            loginUsername = credential(prefs, Keys.EDU_LOGIN).orEmpty()
        )
    }.catch {
        saveSyncError("Nie można odczytać poświadczeń eduVULCAN. Odłącz konto i zaloguj się ponownie.")
        emit(null)
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override suspend fun setCalendarUrl(url: String?) {
        store.edit { prefs ->
            val cleanUrl = url?.trim()?.takeIf { it.isNotBlank() }
            if (cleanUrl != null) {
                prefs[Keys.CALENDAR_URL] = encrypt(cleanUrl, Keys.CALENDAR_URL)
            } else {
                prefs.remove(Keys.CALENDAR_URL)
            }
            prefs.remove(Keys.LAST_SYNC_ERROR)
        }
    }

    override suspend fun connectEduVulcan(session: EduVulcanSession): Result<VulcanAccount> =
        accountOperations.withLock { runCatchingCancellable { connectEduVulcanLocked(session).getOrThrow() } }

    private suspend fun connectEduVulcanLocked(session: EduVulcanSession): Result<VulcanAccount> = withContext(Dispatchers.IO) {
        val account = session.toVulcanAccount()
        saveEduVulcanSessionToPrefs(session, account)
        // Natychmiastowa synchronizacja sprawdzianów oraz frekwencji
        syncLocked()
        Result.success(account)
    }

    override suspend fun loginEduVulcanWithPassword(username: String, password: String): Result<VulcanAccount> =
        accountOperations.withLock { runCatchingCancellable { loginEduVulcanLocked(username, password).getOrThrow() } }

    private suspend fun loginEduVulcanLocked(
        username: String,
        password: String
    ): Result<VulcanAccount> = withContext(Dispatchers.IO) {
        val authRes = eduVulcanAuthenticator.refreshSessionSilently(username.trim(), password)
        authRes.fold(
            onSuccess = { session ->
                connectEduVulcanLocked(session)
            },
            onFailure = { err ->
                val msg = err.localizedMessage ?: "Nie udało się zalogować do eduVULCAN"
                saveSyncError(msg)
                Result.failure(err)
            }
        )
    }

    private suspend fun saveEduVulcanSessionToPrefs(session: EduVulcanSession, account: VulcanAccount) {
        store.edit { prefs ->
            prefs[Keys.EDU_STUDENT_KEY] = encrypt(account.studentKey, Keys.EDU_STUDENT_KEY)
            prefs.remove(Keys.VULCAN_TOKEN)
            prefs.remove(Keys.VULCAN_CERT_PEM)
            prefs.remove(Keys.VULCAN_THUMBPRINT)
            prefs.remove(Keys.VULCAN_PRIVATE_KEY_PKCS8)
            prefs[Keys.VULCAN_SYMBOL] = encrypt(account.symbol, Keys.VULCAN_SYMBOL)
            prefs[Keys.VULCAN_PUPIL_ID] = account.pupilId
            prefs[Keys.VULCAN_PUPIL_NAME] = encrypt(account.pupilName, Keys.VULCAN_PUPIL_NAME)
            prefs[Keys.VULCAN_SCHOOL_NAME] = encrypt(account.schoolName, Keys.VULCAN_SCHOOL_NAME)
            prefs[Keys.VULCAN_REST_URL] = encrypt(account.restUrl, Keys.VULCAN_REST_URL)
            prefs[Keys.EDU_DOMAIN] = encrypt(account.domain, Keys.EDU_DOMAIN)
            prefs[Keys.EDU_LOGIN] = encrypt(session.loginUsername, Keys.EDU_LOGIN)
            prefs[Keys.EDU_COOKIES] = encrypt(account.cookiesHeader, Keys.EDU_COOKIES)
            prefs[Keys.VULCAN_IS_EDUVULCAN] = true
            if (session.loginPassword.isNotBlank()) {
                prefs[Keys.VULCAN_SAVED_PASSWORD] = encrypt(session.loginPassword, Keys.VULCAN_SAVED_PASSWORD)
            } else {
                prefs.remove(Keys.VULCAN_SAVED_PASSWORD)
            }
            prefs.remove(Keys.LAST_SYNC_ERROR)
        }
    }

    override suspend fun registerVulcan(input: VulcanRegistrationInput): Result<VulcanAccount> =
        accountOperations.withLock { runCatchingCancellable { registerVulcanLocked(input).getOrThrow() } }

    private suspend fun registerVulcanLocked(input: VulcanRegistrationInput): Result<VulcanAccount> = withContext(Dispatchers.IO) {
        val result = vulcanApiClient.register(input)
        result.fold(
            onSuccess = { account ->
                store.edit { prefs ->
                    prefs.remove(Keys.EDU_STUDENT_KEY)
                    prefs.remove(Keys.EDU_DOMAIN)
                    prefs.remove(Keys.EDU_LOGIN)
                    prefs.remove(Keys.EDU_COOKIES)
                    prefs.remove(Keys.VULCAN_SAVED_PASSWORD)
                    prefs[Keys.VULCAN_TOKEN] = encrypt(account.token, Keys.VULCAN_TOKEN)
                    prefs[Keys.VULCAN_SYMBOL] = encrypt(account.symbol, Keys.VULCAN_SYMBOL)
                    prefs[Keys.VULCAN_PUPIL_ID] = account.pupilId
                    prefs[Keys.VULCAN_PUPIL_NAME] = encrypt(account.pupilName, Keys.VULCAN_PUPIL_NAME)
                    prefs[Keys.VULCAN_SCHOOL_NAME] = encrypt(account.schoolName, Keys.VULCAN_SCHOOL_NAME)
                    prefs[Keys.VULCAN_REST_URL] = encrypt(account.restUrl, Keys.VULCAN_REST_URL)
                    prefs[Keys.VULCAN_CERT_PEM] = encrypt(account.certificatePem, Keys.VULCAN_CERT_PEM)
                    prefs[Keys.VULCAN_THUMBPRINT] = encrypt(account.certificateThumbprint, Keys.VULCAN_THUMBPRINT)
                    prefs[Keys.VULCAN_PRIVATE_KEY_PKCS8] = encrypt(account.privateKeyPkcs8Base64, Keys.VULCAN_PRIVATE_KEY_PKCS8)
                    prefs[Keys.VULCAN_IS_EDUVULCAN] = false
                    prefs.remove(Keys.LAST_SYNC_ERROR)
                }
                syncLocked()
                Result.success(account)
            },
            onFailure = { error ->
                saveSyncError(error.localizedMessage ?: "Błąd rejestracji konta VULCAN")
                Result.failure(error)
            }
        )
    }

    override suspend fun clearVulcanAccount() = accountOperations.withLock { clearVulcanAccountLocked() }

    private suspend fun clearVulcanAccountLocked() {
        withContext(Dispatchers.IO) {
            store.edit { prefs ->
                prefs.remove(Keys.VULCAN_TOKEN)
                prefs.remove(Keys.VULCAN_SYMBOL)
                prefs.remove(Keys.VULCAN_PUPIL_ID)
                prefs.remove(Keys.VULCAN_PUPIL_NAME)
                prefs.remove(Keys.VULCAN_SCHOOL_NAME)
                prefs.remove(Keys.VULCAN_REST_URL)
                prefs.remove(Keys.VULCAN_CERT_PEM)
                prefs.remove(Keys.VULCAN_THUMBPRINT)
                prefs.remove(Keys.VULCAN_PRIVATE_KEY_PKCS8)
                prefs.remove(Keys.VULCAN_IS_EDUVULCAN)
                prefs.remove(Keys.VULCAN_SAVED_PASSWORD)
                prefs.remove(Keys.EDU_STUDENT_KEY)
                prefs.remove(Keys.EDU_DOMAIN)
                prefs.remove(Keys.EDU_LOGIN)
                prefs.remove(Keys.EDU_COOKIES)
                prefs.remove(Keys.LAST_SYNC_ERROR)
            }
            attendanceDao.clear()
            gradeDao.replaceAll(gradeDao.getAll().filter { it.id.startsWith("manual_") })
            withContext(Dispatchers.Main) {
                runCatching {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        CookieManager.getInstance().removeAllCookies {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                    }
                    CookieManager.getInstance().flush()
                }
            }
            val rawUrl = calendarUrl.first()?.trim()
            if (rawUrl.isNullOrBlank()) {
                schoolEventDao.clear()
            } else {
                syncLocked()
            }
        }
    }

    override val lastSyncEpochSeconds: Flow<Long?> = store.data.map { it[Keys.LAST_SYNC_EPOCH] }.distinctUntilChanged()

    override val lastSyncError: Flow<String?> = store.data.map { it[Keys.LAST_SYNC_ERROR] }.distinctUntilChanged()

    override fun observeEventsForDate(date: LocalDate): Flow<List<SchoolEvent>> =
        schoolEventDao.observeForDate(date.toEpochDay())
            .map { list -> list.map { it.toDomain() } }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    override fun observeEventsForRange(from: LocalDate, to: LocalDate): Flow<List<SchoolEvent>> =
        schoolEventDao.observeForRange(from.toEpochDay(), to.toEpochDay())
            .map { list -> list.map { it.toDomain() } }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    override fun observeUpcomingEvents(limit: Int): Flow<List<SchoolEvent>> =
        schoolEventDao.observeUpcoming(LocalDate.now().toEpochDay(), limit)
            .map { list -> list.map { it.toDomain() } }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    override suspend fun sync(): Result<Int> = accountOperations.withLock {
        runCatchingCancellable { syncLocked().getOrThrow() }
    }

    private suspend fun syncLocked(): Result<Int> = withContext(Dispatchers.IO) {
        var activeAccount = vulcanAccount.first()
        val rawUrl = calendarUrl.first()?.trim()

        if (activeAccount == null && rawUrl.isNullOrBlank()) {
            val msg = "Brak skonfigurowanego źródła (zaloguj się do eduVULCAN lub wklej link iCal)"
            saveSyncError(msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        val collectedEvents = mutableListOf<SchoolEvent>()
        var successAny = false
        var lastError: Exception? = null

        // 1. Synchronizacja przez eduVULCAN / VULCAN API
        if (activeAccount != null) {
            var vulcanRes = vulcanApiClient.fetchEvents(activeAccount)

            // Jeśli sesja eduVULCAN wygasła i mamy zapisane hasło, odświeżamy sesję w tle
            if (vulcanRes.isFailure && vulcanRes.exceptionOrNull() is EduVulcanSessionExpiredException) {
                val prefs = store.data.first()
                val savedUser = credential(prefs, Keys.EDU_LOGIN).orEmpty()
                val savedPass = credential(prefs, Keys.VULCAN_SAVED_PASSWORD).orEmpty()
                if (savedUser.isNotBlank() && savedPass.isNotBlank()) {
                    val refreshed = eduVulcanAuthenticator.refreshSessionSilently(savedUser, savedPass)
                    if (refreshed.isSuccess) {
                        val newSession = refreshed.getOrThrow()
                        val newAccount = newSession.toVulcanAccount()
                        saveEduVulcanSessionToPrefs(newSession, newAccount)
                        activeAccount = newAccount
                        vulcanRes = vulcanApiClient.fetchEvents(newAccount)
                    }
                }
            }

            vulcanRes.fold(
                onSuccess = { events ->
                    collectedEvents.addAll(events)
                    successAny = true
                },
                onFailure = { err ->
                    lastError = err as? Exception ?: IOException(err.message)
                }
            )

            // Równolegle pobieramy statystyki frekwencji oraz oceny z eduVULCAN
            if (activeAccount.isEduVulcan) {
                val attRes = vulcanApiClient.fetchEduVulcanAttendance(activeAccount)
                attRes.onSuccess { stats ->
                    if (stats.isNotEmpty()) {
                        attendanceDao.replaceAll(stats.map { it.toAttendanceEntity() })
                    }
                }

                val gradesRes = vulcanApiClient.fetchEduVulcanGrades(activeAccount)
                gradesRes.onSuccess { remoteGrades ->
                    if (remoteGrades.isNotEmpty()) {
                        val manualGrades = gradeDao.getAll().filter { it.id.startsWith("manual_") }
                        val gradeEntities = (remoteGrades.map { it.toEntity() } + manualGrades).distinctBy { it.id }
                        gradeDao.replaceAll(gradeEntities)
                    }
                }
            }
        }

        // 2. Synchronizacja przez iCal URL (jeśli podano)
        if (!rawUrl.isNullOrBlank()) {
            val icalRes = fetchIcalEvents(rawUrl)
            icalRes.fold(
                onSuccess = { events ->
                    collectedEvents.addAll(events)
                    successAny = true
                },
                onFailure = { err ->
                    if (lastError == null) lastError = err as? Exception ?: IOException(err.message)
                }
            )
        }

        if (successAny) {
            // Zachowujemy również świeże wydarzenia przechwycone z powiadomień zainstalowanej aplikacji eduVULCAN
            val existingNotifEvents = runCatching {
                val today = LocalDate.now().toEpochDay()
                schoolEventDao.getForRange(today, today + 60)
                    .filter { it.id.startsWith("notif_eduvulcan_") }
            }.getOrDefault(emptyList())

            val entities = (collectedEvents.map { it.toEntity() } + existingNotifEvents)
                .distinctBy { it.id }

            schoolEventDao.replaceAll(entities)

            store.edit { prefs ->
                prefs[Keys.LAST_SYNC_EPOCH] = Instant.now().epochSecond
                prefs.remove(Keys.LAST_SYNC_ERROR)
            }
            Result.success(entities.size)
        } else {
            val errorMsg = lastError?.localizedMessage ?: "Nie udało się zsynchronizować danych z eduVULCAN"
            saveSyncError(errorMsg)
            Result.failure(lastError ?: IOException(errorMsg))
        }
    }

    private suspend fun fetchIcalEvents(rawUrl: String): Result<List<SchoolEvent>> = withContext(Dispatchers.IO) {
        val httpUrl = when {
            rawUrl.startsWith("webcal://", ignoreCase = true) -> "https://" + rawUrl.substring(9)
            rawUrl.startsWith("http://", ignoreCase = true) || rawUrl.startsWith("https://", ignoreCase = true) -> rawUrl
            else -> "https://$rawUrl"
        }

        try {
            val request = Request.Builder()
                .url(httpUrl)
                .header("User-Agent", "Elektron-Vulcan-CalendarSync")
                .header("Accept", "text/calendar, text/plain, */*")
                .build()

            calendarHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Błąd iCal: HTTP ${response.code}"))
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@withContext Result.failure(IOException("Pobrany plik iCal jest pusty"))
                }
                Result.success(IcalParser.parse(body))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun clear() = accountOperations.withLock { clearLocked() }

    private suspend fun clearLocked() {
        withContext(Dispatchers.IO) {
            store.edit { prefs ->
                prefs.remove(Keys.CALENDAR_URL)
                prefs.remove(Keys.LAST_SYNC_EPOCH)
                prefs.remove(Keys.LAST_SYNC_ERROR)
            }
            clearVulcanAccountLocked()
            schoolEventDao.clear()
        }
    }

    private suspend fun saveSyncError(message: String) {
        store.edit { prefs ->
            prefs[Keys.LAST_SYNC_ERROR] = message
        }
    }

    private fun SchoolEventEntity.toDomain(): SchoolEvent = SchoolEvent(
        id = id,
        title = title,
        description = description,
        type = runCatching { SchoolEventType.valueOf(eventType) }.getOrDefault(SchoolEventType.OTHER),
        subject = subject,
        date = LocalDate.ofEpochDay(dateEpochDay),
        timeFrom = timeFrom?.let { LocalTime.parse(it) },
        timeTo = timeTo?.let { LocalTime.parse(it) },
        location = location,
        rawSummary = rawSummary
    )

    private fun SchoolEvent.toEntity(): SchoolEventEntity = SchoolEventEntity(
        id = id,
        title = title,
        description = description,
        eventType = type.name,
        subject = subject,
        dateEpochDay = date.toEpochDay(),
        timeFrom = timeFrom?.toString(),
        timeTo = timeTo?.toString(),
        location = location,
        rawSummary = rawSummary
    )

    private fun SubjectAttendance.toAttendanceEntity(): AttendanceEntity = AttendanceEntity(
        subject = subject,
        presentHours = presentHours,
        absentHours = absentHours,
        excusedHours = excusedHours,
        lateHours = lateHours,
        schoolObligationHours = schoolObligationHours,
        lastUpdatedEpochSeconds = lastUpdatedEpochSeconds
    )
}
