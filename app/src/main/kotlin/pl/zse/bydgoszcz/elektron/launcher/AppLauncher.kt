package pl.zse.bydgoszcz.elektron.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.util.Log
import pl.zse.bydgoszcz.elektron.ElektronActivity
import pl.zse.bydgoszcz.elektron.R

/**
 * Ikona aplikacji do wyboru w Ustawieniach. Każda to osobny wpis launchera (activity-alias
 * w manifeście), włączony jest dokładnie jeden. Domyślny nosi dawną nazwę MainActivity, więc ikona
 * dodana na ekran główny przed aktualizacją zostaje.
 */
enum class AppIcon(val alias: String, val label: String, val iconRes: Int) {
    DEFAULT("pl.zse.bydgoszcz.elektron.MainActivity", "Domyślna", R.mipmap.ic_launcher),
    LIGHT("pl.zse.bydgoszcz.elektron.IconLight", "Ciemne tło", R.mipmap.ic_launcher_light)
}

object AppIcons {
    private const val TAG = "AppIcons"
    /**
     * Wycofana ikona "bez tła" (wersje testowe 1.0.0): launchery rysowały ją na czarnym tle.
     * Wpis zostaje w manifeście, żeby aplikacja wybrana z tą ikoną nie straciła ikony przy
     * aktualizacji - [migrate] przełącza ją na domyślną.
     */
    private const val RETIRED_ALIAS = "pl.zse.bydgoszcz.elektron.IconClear"

    /** Przy starcie: wycofana ikona -> domyślna. */
    fun migrate(context: Context) {
        val retired = ComponentName(context.packageName, RETIRED_ALIAS)
        if (context.packageManager.getComponentEnabledSetting(retired) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return
        Log.i(TAG, "Wycofana ikona bez tła - przywracam domyślną")
        set(context, AppIcon.DEFAULT, force = true)
    }

    fun current(context: Context): AppIcon =
        AppIcon.entries.firstOrNull { enabled(context, it) } ?: AppIcon.DEFAULT

    /**
     * Najpierw włącza nowy wpis, potem wyłącza pozostałe - aplikacja nie zostaje bez ikony.
     * Mimo DONT_KILL_APP większość launcherów zamyka przy tym aplikację (opis w oknie wyboru).
     */
    fun set(context: Context, icon: AppIcon, force: Boolean = false) {
        if (!force && current(context) == icon) return
        val pm = context.packageManager
        runCatching {
            pm.setComponentEnabledSetting(component(context, icon), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            (AppIcon.entries.filter { it != icon }.map { component(context, it) } + ComponentName(context.packageName, RETIRED_ALIAS)).forEach {
                pm.setComponentEnabledSetting(it, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }.onFailure { Log.w(TAG, "Nie udało się zmienić ikony", it) }
        // Skróty należą do wpisu launchera - po zmianie trzeba je przypiąć do nowego.
        AppShortcuts.refresh(context)
    }

    internal fun component(context: Context, icon: AppIcon) = ComponentName(context.packageName, icon.alias)

    private fun enabled(context: Context, icon: AppIcon): Boolean =
        when (context.packageManager.getComponentEnabledSetting(component(context, icon))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == AppIcon.DEFAULT   // stan z manifestu
            else -> false
        }
}

/**
 * Skróty po przytrzymaniu ikony: krótkie nazwy, ikona taka jak ikona aplikacji, Odjazdy tylko
 * z włączoną zakładką. Dynamiczne (dawniej stałe z XML), bo zależą od ustawień. Kolejność: przy
 * launcherach pokazujących 4 skróty odpada ostatni - Strona główna (otwiera ją sama ikona).
 */
object AppShortcuts {
    private const val TAG = "AppShortcuts"
    private const val TRANSIT_ID = "odjazdy"
    @Volatile private var transitTab = false

    fun update(context: Context, transitTabVisible: Boolean) {
        transitTab = transitTabVisible
        refresh(context)
    }

    fun refresh(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val icon = AppIcons.current(context)
        val items = buildList {
            add(Triple("plan", "timetable", "Plan lekcji"))
            add(Triple("zastepstwa", "substitutions", "Zastępstwa"))
            if (transitTab) add(Triple(TRANSIT_ID, "transit", "Odjazdy"))
            add(Triple("ogloszenia", "announcements", "Ogłoszenia"))
            add(Triple("start", "dashboard", "Strona główna"))
        }
        val shortcuts = items.mapIndexed { rank, (id, target, label) ->
            ShortcutInfo.Builder(context, id)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(Icon.createWithResource(context, icon.iconRes))
                .setActivity(AppIcons.component(context, icon))
                .setIntent(Intent(context, ElektronActivity::class.java).setAction(Intent.ACTION_VIEW)
                    .putExtra(EXTRA_SHORTCUT, target))
                .setRank(rank)
                .build()
        }
        runCatching {
            // Skrót Odjazdów przypięty na ekranie głównym: nieaktywny, gdy zakładka wyłączona.
            if (transitTab) manager.enableShortcuts(listOf(TRANSIT_ID))
            else manager.disableShortcuts(listOf(TRANSIT_ID), "Zakładka Odjazdy jest wyłączona")
            // Bez zmian - bez zapisu: Android ogranicza liczbę zapisów skrótów z tła (start procesu
            // przez synchronizację w tle zdarza się wiele razy dziennie).
            val signature = { list: List<ShortcutInfo> -> list.sortedBy { it.rank }.map { "${it.id}|${it.activity}|${it.shortLabel}" } }
            if (signature(manager.dynamicShortcuts) != signature(shortcuts)) manager.setDynamicShortcuts(shortcuts)
        }.onFailure { Log.w(TAG, "Nie udało się zapisać skrótów", it) }
    }

    /** Extra rozpoznawany przez ElektronActivity (ten sam co w widżetach). */
    const val EXTRA_SHORTCUT = "elektron_shortcut"
}
