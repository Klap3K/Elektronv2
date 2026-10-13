package pl.zse.bydgoszcz.elektron.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log

object AutoDndManager {

    private const val TAG = "AutoDndManager"
    private const val PREFS_NAME = "elektron_auto_dnd"
    private const val KEY_ACTIVE_BY_APP = "active_by_app"
    private const val KEY_PREV_RINGER_MODE = "prev_ringer_mode"
    private const val KEY_PREV_FILTER = "prev_interruption_filter"

    /**
     * Sprawdza, czy aplikacja posiada systemowe uprawnienie do sterowania trybem Nie Przeszkadzać.
     */
    fun isDndPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        return nm?.isNotificationPolicyAccessGranted == true
    }

    /**
     * Otwiera ekran systemowych ustawień dostępu do trybu Nie Przeszkadzać.
     */
    fun openDndSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }

    /**
     * Zwraca informację, czy telefon jest aktualnie wyciszony przez aplikację.
     */
    fun isSilenced(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVE_BY_APP, false)
    }

    /**
     * Sprawdza aktualny stan lekcji i stosuje odpowiedni profil dźwięku.
     * Zwraca true jeśli tryb wyciszenia lekcyjnego jest aktywny i pomyślnie zastosowany.
     */
    fun evaluate(context: Context, enabled: Boolean, isLessonOngoing: Boolean): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val activeByApp = prefs.getBoolean(KEY_ACTIVE_BY_APP, false)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        if (enabled && isLessonOngoing) {
            if (!activeByApp) {
                // Wycisz telefon na czas lekcji
                var applied = false
                val editor = prefs.edit()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && nm != null && nm.isNotificationPolicyAccessGranted) {
                    val curFilter = nm.currentInterruptionFilter
                    try {
                        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                        editor.putInt(KEY_PREV_FILTER, curFilter)
                        applied = true
                        Log.i(TAG, "Włączono tryb DND na lekcji (poprzedni filtr: $curFilter)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Nie udało się włączyć filtra DND", e)
                    }
                } else if (am != null) {
                    val curRinger = am.ringerMode
                    try {
                        am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                        editor.putInt(KEY_PREV_RINGER_MODE, curRinger)
                        applied = true
                        Log.i(TAG, "Wyciszono telefon (wibracje) na lekcji (poprzedni tryb: $curRinger)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Nie udało się ustawić trybu wibracji", e)
                    }
                }

                if (applied) {
                    editor.putBoolean(KEY_ACTIVE_BY_APP, true).apply()
                    return true
                } else {
                    return false
                }
            }
            return true
        } else {
            // Przerwa, okienko, koniec lekcji lub wyłączenie opcji w ustawieniach
            if (activeByApp) {
                val prevFilter = prefs.getInt(KEY_PREV_FILTER, -1)
                val prevRinger = prefs.getInt(KEY_PREV_RINGER_MODE, -1)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && nm != null && nm.isNotificationPolicyAccessGranted && prevFilter != -1) {
                    try {
                        nm.setInterruptionFilter(prevFilter)
                        Log.i(TAG, "Przywrócono profil DND po lekcji: $prevFilter")
                    } catch (e: Exception) {
                        Log.w(TAG, "Nie udało się przywrócić filtra DND", e)
                    }
                }
                if (am != null && prevRinger != -1) {
                    try {
                        am.ringerMode = prevRinger
                        Log.i(TAG, "Przywrócono tryb dzwonka po lekcji: $prevRinger")
                    } catch (e: Exception) {
                        Log.w(TAG, "Nie udało się przywrócić trybu dzwonka", e)
                    }
                }

                prefs.edit()
                    .putBoolean(KEY_ACTIVE_BY_APP, false)
                    .remove(KEY_PREV_FILTER)
                    .remove(KEY_PREV_RINGER_MODE)
                    .apply()
            }
            return false
        }
    }
}
