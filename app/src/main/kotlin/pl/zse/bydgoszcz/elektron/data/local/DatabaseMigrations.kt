package pl.zse.bydgoszcz.elektron.data.local

import androidx.room.migration.Migration

/** Aktualna wersja schematu bazy. Podnosisz ją => MUSISZ dodać migrację poniżej. */
const val DATABASE_VERSION = 4

/**
 * Migracje schematu Room.
 *
 * Od 0.4.0 baza NIE jest już kasowana przy aktualizacji aplikacji (dawniej
 * fallbackToDestructiveMigration() czyściło cache, stan synchronizacji i zbiory "widzianych"
 * zastępstw/ogłoszeń przy każdej zmianie schematu). Zmiana schematu wymaga teraz migracji:
 *
 *  1. Zmień encję w Entities.kt.
 *  2. Podnieś [DATABASE_VERSION] o 1 (np. 2 -> 3).
 *  3. Zbuduj projekt — Room zapisze nowy schemat w app/schemas/.../3.json (commituj go).
 *  4. Dodaj migrację do [ALL], np.:
 *
 *       val MIGRATION_2_3 = object : Migration(2, 3) {
 *           override fun migrate(db: SupportSQLiteDatabase) {
 *               db.execSQL("ALTER TABLE substitutions ADD COLUMN extra TEXT")
 *           }
 *       }
 *
 *     Treść SQL podpowie wygenerowany plik schematu (różnica między 2.json a 3.json).
 *
 * Test DatabaseMigrationsTest pilnuje, żeby każdy krok wersji miał migrację — podniesienie
 * wersji bez migracji wysypie testy zamiast skasować użytkownikom dane.
 */
object DatabaseMigrations {

    /** Najstarsza wersja, z której migrujemy (plik elektron.db powstał od razu w wersji 2). */
    const val FIRST_MIGRATABLE_VERSION = 2

    val ALL: Array<Migration> = arrayOf(
        object : Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE announcements ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
            }
        },
        object : Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `school_events` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `description` TEXT,
                        `eventType` TEXT NOT NULL,
                        `subject` TEXT,
                        `dateEpochDay` INTEGER NOT NULL,
                        `timeFrom` TEXT,
                        `timeTo` TEXT,
                        `location` TEXT,
                        `rawSummary` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_school_events_dateEpochDay` ON `school_events` (`dateEpochDay`)")
            }
        },
    )
}
