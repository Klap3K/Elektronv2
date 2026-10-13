package pl.zse.bydgoszcz.elektron.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.Base64
import javax.crypto.KeyGenerator

class CredentialEncryptionTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmCredentialCipher { key }

    @Test fun ciphertextUsesFreshIvAndAuthenticatesItsPurpose() {
        val encrypted = cipher.encrypt("test-secret", "password")
        assertEquals("test-secret", cipher.decrypt(encrypted, "password"))
        assertNotEquals(encrypted, cipher.encrypt("test-secret", "password"))
        assertTrue(runCatching { cipher.decrypt(encrypted, "cookies") }.isFailure)
        val bytes = Base64.getDecoder().decode(encrypted)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertTrue(runCatching { cipher.decrypt(Base64.getEncoder().encodeToString(bytes), "password") }.isFailure)
    }

    @Test fun migrationRemovesPlaintextAndSeparatesEduFields() = runBlocking {
        val legacy = mutablePreferencesOf(
            stringPreferencesKey("vulcan_saved_password") to "legacy-password",
            stringPreferencesKey("vulcan_token") to "student-key",
            stringPreferencesKey("vulcan_private_key_pkcs8") to "session-cookie",
            stringPreferencesKey("vulcan_cert_pem") to "eduvulcan.pl",
            booleanPreferencesKey("vulcan_is_eduvulcan") to true
        )
        val migration = CredentialMigration(cipher)
        val migrated = migration.migrate(legacy)
        assertFalse(migration.shouldMigrate(migrated))
        assertNull(migrated[stringPreferencesKey("vulcan_saved_password")])
        assertNull(migrated[stringPreferencesKey("vulcan_token")])
        val cookieKey = stringPreferencesKey("encrypted_vulcan_cookies_header")
        assertEquals("session-cookie", cipher.decrypt(migrated[cookieKey]!!, cookieKey.name))
        assertNull(migrated[stringPreferencesKey("encrypted_vulcan_private_key_pkcs8")])

        val dir = Files.createTempDirectory("credential-test").toFile()
        val file = dir.resolve("calendar.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope,
                migrations = listOf(object : androidx.datastore.core.DataMigration<androidx.datastore.preferences.core.Preferences> {
                    override suspend fun shouldMigrate(currentData: androidx.datastore.preferences.core.Preferences) = true
                    override suspend fun migrate(currentData: androidx.datastore.preferences.core.Preferences) = migration.migrate(legacy)
                    override suspend fun cleanUp() = Unit
                }), produceFile = { file })
            store.data.first()
            val persisted = file.readBytes().toString(Charsets.UTF_8)
            assertFalse(persisted.contains("legacy-password"))
            assertFalse(persisted.contains("session-cookie"))
            assertFalse(persisted.contains("student-key"))
        } finally {
            scope.cancel()
            file.delete()
            dir.delete()
        }
    }

    @Test fun failureToEncryptDoesNotEraseLegacyValues() = runBlocking {
        val original = mutablePreferencesOf(stringPreferencesKey("vulcan_saved_password") to "password")
        val failing = object : CredentialCipher {
            override fun encrypt(value: String, purpose: String): String = error("Key unavailable")
            override fun decrypt(value: String, purpose: String): String = error("Key unavailable")
        }
        assertTrue(runCatching { CredentialMigration(failing).migrate(original) }.isFailure)
        assertEquals("password", original[stringPreferencesKey("vulcan_saved_password")])
    }

    @Test fun hebeMigrationKeepsCertificateMeaning() = runBlocking {
        val original = mutablePreferencesOf(
            booleanPreferencesKey("vulcan_is_eduvulcan") to false,
            stringPreferencesKey("vulcan_private_key_pkcs8") to "private-key"
        )
        val migrated = CredentialMigration(cipher).migrate(original)
        val target = stringPreferencesKey("encrypted_vulcan_private_key_pkcs8")
        assertEquals("private-key", cipher.decrypt(migrated[target]!!, target.name))
        assertNull(migrated[stringPreferencesKey("vulcan_private_key_pkcs8")])
        assertNull(migrated[stringPreferencesKey("encrypted_vulcan_cookies_header")])
    }
}
