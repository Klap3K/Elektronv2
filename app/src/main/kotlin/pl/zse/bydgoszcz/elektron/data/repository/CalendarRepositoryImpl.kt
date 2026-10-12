package pl.zse.bydgoszcz.elektron.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import pl.zse.bydgoszcz.elektron.data.local.SchoolEventDao
import pl.zse.bydgoszcz.elektron.data.local.SchoolEventEntity
import pl.zse.bydgoszcz.elektron.data.remote.parser.IcalParser
import pl.zse.bydgoszcz.elektron.data.remote.vulcan.VulcanApiClient
import pl.zse.bydgoszcz.elektron.domain.model.SchoolEvent
import pl.zse.bydgoszcz.elektron.domain.model.SchoolEventType
import pl.zse.bydgoszcz.elektron.domain.model.VulcanAccount
import pl.zse.bydgoszcz.elektron.domain.model.VulcanRegistrationInput
import pl.zse.bydgoszcz.elektron.domain.repository.CalendarRepository
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

private val Context.calendarPreferences: DataStore<Preferences> by preferencesDataStore(name = "calendar_preferences")

@Singleton
class CalendarRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val schoolEventDao: SchoolEventDao,
    private val okHttp: OkHttpClient,
    private val vulcanApiClient: VulcanApiClient
) : CalendarRepository {

    private val store = context.calendarPreferences

    private object Keys {
        val CALENDAR_URL = stringPreferencesKey("calendar_url")
        val LAST_SYNC_EPOCH = longPreferencesKey("last_sync_epoch")
        val LAST_SYNC_ERROR = stringPreferencesKey("last_sync_error")

        // Poświadczenia i stan konta VULCAN Mobile API (Hebe)
        val VULCAN_TOKEN = stringPreferencesKey("vulcan_token")
        val VULCAN_SYMBOL = stringPreferencesKey("vulcan_symbol")
        val VULCAN_PUPIL_ID = intPreferencesKey("vulcan_pupil_id")
        val VULCAN_PUPIL_NAME = stringPreferencesKey("vulcan_pupil_name")
        val VULCAN_SCHOOL_NAME = stringPreferencesKey("vulcan_school_name")
        val VULCAN_REST_URL = stringPreferencesKey("vulcan_rest_url")
        val VULCAN_CERT_PEM = stringPreferencesKey("vulcan_cert_pem")
        val VULCAN_THUMBPRINT = stringPreferencesKey("vulcan_thumbprint")
        val VULCAN_PRIVATE_KEY_PKCS8 = stringPreferencesKey("vulcan_private_key_pkcs8")
    }

    override val calendarUrl: Flow<String?> = store.data.map { it[Keys.CALENDAR_URL] }.distinctUntilChanged()

    override val vulcanAccount: Flow<VulcanAccount?> = store.data.map { prefs ->
        val token = prefs[Keys.VULCAN_TOKEN] ?: return@map null
        val symbol = prefs[Keys.VULCAN_SYMBOL] ?: return@map null
        val restUrl = prefs[Keys.VULCAN_REST_URL] ?: return@map null
        val certPem = prefs[Keys.VULCAN_CERT_PEM] ?: return@map null
        val privateKey = prefs[Keys.VULCAN_PRIVATE_KEY_PKCS8] ?: return@map null

        VulcanAccount(
            token = token,
            symbol = symbol,
            pin = "", // jednorazowy PIN usunięty po rejestracji ze względów bezpieczeństwa (minimalizacja danych)
            pupilId = prefs[Keys.VULCAN_PUPIL_ID] ?: 0,
            pupilName = prefs[Keys.VULCAN_PUPIL_NAME].orEmpty().ifBlank { "Uczeń" },
            schoolName = prefs[Keys.VULCAN_SCHOOL_NAME].orEmpty().ifBlank { symbol },
            restUrl = restUrl,
            certificatePem = certPem,
            certificateThumbprint = prefs[Keys.VULCAN_THUMBPRINT].orEmpty(),
            privateKeyPkcs8Base64 = privateKey
        )
    }.distinctUntilChanged()

    override suspend fun setCalendarUrl(url: String?) {
        store.edit { prefs ->
            val cleanUrl = url?.trim()?.takeIf { it.isNotBlank() }
            if (cleanUrl != null) {
                prefs[Keys.CALENDAR_URL] = cleanUrl
            } else {
                prefs.remove(Keys.CALENDAR_URL)
            }
            prefs.remove(Keys.LAST_SYNC_ERROR)
        }
    }

    override suspend fun registerVulcan(input: VulcanRegistrationInput): Result<VulcanAccount> = withContext(Dispatchers.IO) {
        val result = vulcanApiClient.register(input)
        result.fold(
            onSuccess = { account ->
                store.edit { prefs ->
                    prefs[Keys.VULCAN_TOKEN] = account.token
                    prefs[Keys.VULCAN_SYMBOL] = account.symbol
                    prefs[Keys.VULCAN_PUPIL_ID] = account.pupilId
                    prefs[Keys.VULCAN_PUPIL_NAME] = account.pupilName
                    prefs[Keys.VULCAN_SCHOOL_NAME] = account.schoolName
                    prefs[Keys.VULCAN_REST_URL] = account.restUrl
                    prefs[Keys.VULCAN_CERT_PEM] = account.certificatePem
                    prefs[Keys.VULCAN_THUMBPRINT] = account.certificateThumbprint
                    prefs[Keys.VULCAN_PRIVATE_KEY_PKCS8] = account.privateKeyPkcs8Base64
                    prefs.remove(Keys.LAST_SYNC_ERROR)
                }
                // Natychmiastowa pierwsza synchronizacja
                sync()
                Result.success(account)
            },
            onFailure = { error ->
                saveSyncError(error.localizedMessage ?: "Błąd rejestracji konta VULCAN")
                Result.failure(error)
            }
        )
    }

    override suspend fun clearVulcanAccount() {
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
                prefs.remove(Keys.LAST_SYNC_ERROR)
            }
            // Jeśli nie ma skonfigurowanego URL iCal, czyścimy też zapisane wydarzenia
            val rawUrl = calendarUrl.first()?.trim()
            if (rawUrl.isNullOrBlank()) {
                schoolEventDao.clear()
            } else {
                sync()
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

    override suspend fun sync(): Result<Int> = withContext(Dispatchers.IO) {
        val currentAccount = vulcanAccount.first()
        val rawUrl = calendarUrl.first()?.trim()

        if (currentAccount == null && rawUrl.isNullOrBlank()) {
            val msg = "Brak skonfigurowanego źródła (podłącz konto VULCAN lub wklej link iCal)"
            saveSyncError(msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        val collectedEvents = mutableListOf<SchoolEvent>()
        var successAny = false
        var lastError: Exception? = null

        // 1. Synchronizacja przez VULCAN API (jeśli konto jest zarejestrowane)
        if (currentAccount != null) {
            val vulcanRes = vulcanApiClient.fetchEvents(currentAccount)
            vulcanRes.fold(
                onSuccess = { events ->
                    collectedEvents.addAll(events)
                    successAny = true
                },
                onFailure = { err ->
                    lastError = err as? Exception ?: IOException(err.message)
                }
            )
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
            val entities = collectedEvents.distinctBy { it.id }.map { it.toEntity() }
            schoolEventDao.replaceAll(entities)

            store.edit { prefs ->
                prefs[Keys.LAST_SYNC_EPOCH] = Instant.now().epochSecond
                prefs.remove(Keys.LAST_SYNC_ERROR)
            }
            Result.success(entities.size)
        } else {
            val errorMsg = lastError?.localizedMessage ?: "Nie udało się zsynchronizować wydarzeń"
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
                .header("User-Agent", "eLektron-CalendarSync")
                .header("Accept", "text/calendar, text/plain, */*")
                .build()

            val response = okHttp.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("Błąd iCal: HTTP ${response.code}"))
            }

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                return@withContext Result.failure(IOException("Pobrany plik iCal jest pusty"))
            }

            val events = IcalParser.parse(body)
            Result.success(events)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            schoolEventDao.clear()
            clearVulcanAccount()
            store.edit { prefs ->
                prefs.remove(Keys.CALENDAR_URL)
                prefs.remove(Keys.LAST_SYNC_EPOCH)
                prefs.remove(Keys.LAST_SYNC_ERROR)
            }
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
}
