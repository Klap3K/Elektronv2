package pl.zse.bydgoszcz.elektron.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.util.Base64
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CredentialCipher {
    fun encrypt(value: String, purpose: String): String
    fun decrypt(value: String, purpose: String): String
}

/** A new random IV for every value; the preference name is authenticated as AAD. */
class KeystoreCredentialCipher : CredentialCipher {
    private fun generateKey(): SecretKey {
        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            (store.getKey(ALIAS, null) as? SecretKey) ?: generateKey()
        } catch (e: Exception) {
            // Na nakładkach OEM (HyperOS, OneUI, MagicOS) po zmianie blokady ekranu
            // lub błędzie sprzętowym: bezpiecznie usuwamy uszkodzony klucz i generujemy nowy.
            runCatching { store.deleteEntry(ALIAS) }
            generateKey()
        }
    }

    private val cipher = AesGcmCredentialCipher(::key)
    override fun encrypt(value: String, purpose: String): String = cipher.encrypt(value, purpose)
    override fun decrypt(value: String, purpose: String): String = cipher.decrypt(value, purpose)

    private companion object {
        const val ALIAS = "elektron.calendar.credentials.v1"
        val keyLock = Any()
    }
}

class AesGcmCredentialCipher(private val keyProvider: () -> SecretKey) : CredentialCipher {
    override fun encrypt(value: String, purpose: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    override fun decrypt(value: String, purpose: String): String {
        val bytes = Base64.getDecoder().decode(value)
        require(bytes.size >= 28) { "Nieprawidłowe zaszyfrowane poświadczenia" }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            throw java.security.GeneralSecurityException("Błąd odszyfrowania poświadczeń Keystore", e)
        }
    }
}

/** DataStore commits this migration atomically before exposing preferences to readers. */
class CredentialMigration(private val cipher: CredentialCipher) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        names.any { currentData[stringPreferencesKey(it)] != null }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val migrated = currentData.toMutablePreferences()
        val edu = currentData[booleanPreferencesKey("vulcan_is_eduvulcan")]
            ?: currentData[stringPreferencesKey("vulcan_rest_url")]?.contains("uczen.") ?: true
        for (name in names) {
            val oldKey = stringPreferencesKey(name)
            val value = migrated[oldKey] ?: continue
            val target = if (edu) when (name) {
                "vulcan_token" -> "vulcan_student_key"
                "vulcan_cert_pem" -> "vulcan_domain"
                "vulcan_thumbprint" -> "vulcan_login_username"
                "vulcan_private_key_pkcs8" -> "vulcan_cookies_header"
                else -> name
            } else name
            val newKey = stringPreferencesKey("encrypted_$target")
            // Never delete plaintext until its encrypted replacement has been produced.
            migrated[newKey] = cipher.encrypt(value, newKey.name)
            migrated.remove(oldKey)
        }
        return migrated
    }

    override suspend fun cleanUp() = Unit

    companion object {
        val names = listOf("calendar_url", "vulcan_token", "vulcan_symbol", "vulcan_pupil_name", "vulcan_school_name",
            "vulcan_rest_url", "vulcan_cert_pem", "vulcan_thumbprint", "vulcan_private_key_pkcs8",
            "vulcan_saved_password")
    }
}
