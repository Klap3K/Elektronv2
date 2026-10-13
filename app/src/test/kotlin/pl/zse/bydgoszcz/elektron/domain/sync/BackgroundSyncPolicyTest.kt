package pl.zse.bydgoszcz.elektron.domain.sync

import org.junit.Assert.*
import org.junit.Test
import pl.zse.bydgoszcz.elektron.domain.model.SyncOutcome

class BackgroundSyncPolicyTest {
    @Test fun firstRunAndClockRollbackAlwaysFetch() {
        for (source in SyncRequest.ALL_SOURCES) {
            assertTrue(BackgroundSyncPolicy.isDue(source, null, 100))
            assertTrue(BackgroundSyncPolicy.isDue(source, 200, 100))
        }
    }
    @Test fun onlySubstitutionsAreDueAfterFifteenMinutes() {
        val due = SyncRequest.ALL_SOURCES.filter { BackgroundSyncPolicy.isDue(it, 100, 100 + 900) }
        assertEquals(listOf(SyncOutcome.SUBSTITUTIONS, SyncOutcome.CALENDAR), due)
    }
    @Test fun deadlinesAreInclusive() {
        for ((source, interval) in mapOf(SyncOutcome.SIDEBAR to 86400L,
            SyncOutcome.TIMETABLE to 21600L, SyncOutcome.ANNOUNCEMENTS to 3600L)) {
            assertFalse(BackgroundSyncPolicy.isDue(source, 100, 100 + interval - 1))
            assertTrue(BackgroundSyncPolicy.isDue(source, 100, 100 + interval))
        }
    }
    @Test fun atNightSubstitutionsAndAnnouncementsAreCheckedAtMostEveryTwoAndAHalfHours() {
        val interval = BackgroundSyncPolicy.NIGHT_INTERVAL
        val late = java.time.LocalTime.of(23, 30)
        assertTrue(BackgroundSyncPolicy.isDue(SyncOutcome.SUBSTITUTIONS, 100, 100 + 900, java.time.LocalTime.NOON))
        assertTrue(BackgroundSyncPolicy.isDue(SyncOutcome.SUBSTITUTIONS, 100, 100 + 900))
        for (source in listOf(SyncOutcome.SUBSTITUTIONS, SyncOutcome.ANNOUNCEMENTS)) {
            assertFalse(BackgroundSyncPolicy.isDue(source, 100, 100 + 900, late))
            assertFalse(BackgroundSyncPolicy.isDue(source, 100, 100 + interval - 1, late))
            assertTrue(BackgroundSyncPolicy.isDue(source, 100, 100 + interval, late))
            assertTrue("pierwszy odczyt także w nocy", BackgroundSyncPolicy.isDue(source, null, 100, late))
        }
        // Następny przebieg (co 15 min) nadal przed progiem "Sprawdzono: …" widżetu.
        assertTrue(interval + 15 * 60 < pl.zse.bydgoszcz.elektron.widget.SubstitutionsFreshness.STALE_HOURS * 3600)
        // Plan i pasek boczny bez zmian.
        assertFalse(BackgroundSyncPolicy.isDue(SyncOutcome.TIMETABLE, 100, 100 + interval, late))
        for (h in listOf(23, 0, 3, 4)) assertTrue(BackgroundSyncPolicy.isNight(java.time.LocalTime.of(h, 30)))
        for (h in listOf(5, 7, 14, 22)) assertFalse(BackgroundSyncPolicy.isNight(java.time.LocalTime.of(h, 30)))
    }

    @Test fun firstRunAfterMidnightChecksSubstitutionsFromBeforeMidnight() {
        val now = 1_000_000L
        val halfPastMidnight = java.time.LocalTime.of(0, 30)
        // Odczyt o 23:45 (45 min temu) - sprzed północy: sprawdzamy raz.
        assertTrue(BackgroundSyncPolicy.isDue(SyncOutcome.SUBSTITUTIONS, now - 45 * 60, now, halfPastMidnight))
        // Odczyt o 00:10 - już po północy: czekamy na odstęp nocny.
        assertFalse(BackgroundSyncPolicy.isDue(SyncOutcome.SUBSTITUTIONS, now - 20 * 60, now, halfPastMidnight))
        // Ogłoszenia nie mają tej reguły.
        assertFalse(BackgroundSyncPolicy.isDue(SyncOutcome.ANNOUNCEMENTS, now - 45 * 60, now, halfPastMidnight))
        // Przed północą bez wyjątku.
        assertFalse(BackgroundSyncPolicy.isDue(SyncOutcome.SUBSTITUTIONS, now - 45 * 60, now, java.time.LocalTime.of(23, 50)))
    }
}
