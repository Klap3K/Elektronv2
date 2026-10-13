package pl.zse.bydgoszcz.elektron.data.local

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pilnuje, żeby podniesienie DATABASE_VERSION zawsze szło w parze z migracją.
 * Bez tego Room przy aktualizacji aplikacji rzuciłby wyjątek (brak ścieżki migracji).
 */
class DatabaseMigrationsTest {

    @Test
    fun everyVersionStepHasMigration() {
        val steps = DatabaseMigrations.ALL.map { it.startVersion to it.endVersion }.toSet()
        for (v in DatabaseMigrations.FIRST_MIGRATABLE_VERSION until DATABASE_VERSION) {
            assertTrue(
                "Brak migracji $v -> ${v + 1}. Dodaj ją w DatabaseMigrations.ALL.",
                (v to v + 1) in steps
            )
        }
    }

    @Test
    fun everySupportedVersionHasAnExportedSchema() {
        for (version in DatabaseMigrations.FIRST_MIGRATABLE_VERSION..DATABASE_VERSION) {
            assertTrue("Brak schematu Room $version.json — wygeneruj i dodaj plik do repozytorium.",
                javaClass.getResource("/pl.zse.bydgoszcz.elektron.data.local.AppDatabase/$version.json") != null)
        }
    }

    @Test
    fun migrationsDoNotGoBeyondCurrentVersion() {
        DatabaseMigrations.ALL.forEach {
            assertTrue("Migracja ${it.startVersion}->${it.endVersion} wykracza poza DATABASE_VERSION",
                it.endVersion <= DATABASE_VERSION)
        }
    }
}
