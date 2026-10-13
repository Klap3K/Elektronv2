package pl.zse.bydgoszcz.elektron.data.update

import org.junit.Assert.*
import org.junit.Test
import pl.zse.bydgoszcz.elektron.domain.model.UpdateChannel

class UpdateChannelSelectionTest {
    private fun release(tag: String, prerelease: Boolean = false, draft: Boolean = false) =
        GitHubRelease(tag, "https://github.com/Klap3K/Elektronv2/releases/tag/$tag", draft, prerelease)

    private val releases = listOf(
        release("v1.0.0"),
        release("v1.0.1-beta1", prerelease = true),
        release("v1.0.2", draft = true)
    )

    @Test fun studentsOnStableChannelDoNotGetBetas() {
        assertEquals("1.0.0", UpdateRepositoryImpl.newestFor(UpdateChannel.STABLE, releases)?.versionName)
    }

    @Test fun betaChannelGetsNewestBetaButNeverDrafts() {
        assertEquals("1.0.1-beta1", UpdateRepositoryImpl.newestFor(UpdateChannel.BETA, releases)?.versionName)
        val stableAfterBeta = releases + release("v1.0.1")
        assertEquals("1.0.1", UpdateRepositoryImpl.newestFor(UpdateChannel.BETA, stableAfterBeta)?.versionName)
    }

    @Test fun channelWithoutReleasesGivesNothing() {
        assertNull(UpdateRepositoryImpl.newestFor(UpdateChannel.STABLE, listOf(release("v2.0.0-beta1", prerelease = true))))
    }

    @Test fun savedPrereleaseFlagSurvivesSwitchingToStableWithoutNetwork() {
        // Numeryczny tag oznaczony na GitHubie jako Pre-release, zapisany na kanale Beta.
        val beta = UpdateRepositoryImpl.newestFor(UpdateChannel.BETA, listOf(release("v1.0.1", prerelease = true)))!!
        val restored = UpdateRepositoryImpl.decode(UpdateRepositoryImpl.encode(beta))!!
        assertTrue(restored.prerelease)
        assertFalse(UpdateChannel.STABLE.accepts(restored.versionName, restored.prerelease))
        assertTrue(UpdateChannel.BETA.accepts(restored.versionName, restored.prerelease))
        val stable = UpdateRepositoryImpl.decode(UpdateRepositoryImpl.encode(UpdateRepositoryImpl.newestFor(UpdateChannel.STABLE, releases)!!))!!
        assertFalse(stable.prerelease)
        assertTrue(UpdateChannel.STABLE.accepts(stable.versionName, stable.prerelease))
    }

    @Test fun recordSavedBeforeChannelsIsTreatedAsPrerelease() {
        val old = UpdateRepositoryImpl.decode("1.0.1\nhttps://github.com/Klap3K/Elektronv2/releases/tag/v1.0.1\n\n\n0")!!
        assertTrue(old.prerelease)
        assertFalse(UpdateChannel.STABLE.accepts(old.versionName, old.prerelease))
    }
}
