package pl.zse.bydgoszcz.elektron.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GitHubReleasesTest {

    private val json = """
        [
          {"tag_name": "v0.6.0-alpha", "html_url": "https://github.com/Klap3K/Elektronv2/releases/tag/v0.6.0-alpha", "draft": true,  "prerelease": true},
          {"tag_name": "v0.5.0-alpha", "html_url": "https://github.com/Klap3K/Elektronv2/releases/tag/v0.5.0-alpha", "draft": false, "prerelease": true},
          {"tag_name": "v0.4.0-alpha", "html_url": "https://evil.example/phishing", "draft": false, "prerelease": true}
        ]
    """.trimIndent()

    @Test
    fun parsesReleasesAndFlags() {
        val releases = GitHubReleases.parse(json)
        assertEquals(listOf("v0.6.0-alpha", "v0.5.0-alpha", "v0.4.0-alpha"), releases.map { it.tag })
        assertTrue(releases[0].draft)
        assertTrue(releases[1].prerelease)
    }

    @Test
    fun foreignLinksFallBackToReleasesPage() {
        // Otwieramy tylko strony wydań repozytorium eLektrona.
        assertEquals(GitHubReleases.REPO_RELEASES_PAGE, GitHubReleases.parse(json)[2].pageUrl)
    }

    @Test
    fun apkAssetWithDigestIsFound() {
        val sha = "a".repeat(64)
        val release = """
            [{"tag_name": "v0.6.1-beta", "html_url": "https://github.com/Klap3K/Elektronv2/releases/tag/v0.6.1-beta",
              "draft": false, "prerelease": true, "assets": [
                {"name": "notatki.txt", "browser_download_url": "https://github.com/Klap3K/Elektronv2/releases/download/v0.6.1-beta/notatki.txt"},
                {"name": "eLektron-0.6.1-beta.apk", "size": 20123456, "digest": "sha256:$sha",
                 "browser_download_url": "https://github.com/Klap3K/Elektronv2/releases/download/v0.6.1-beta/eLektron-0.6.1-beta.apk"}
              ]}]
        """.trimIndent()
        val r = GitHubReleases.parse(release).single()
        assertEquals("https://github.com/Klap3K/Elektronv2/releases/download/v0.6.1-beta/eLektron-0.6.1-beta.apk", r.apkUrl)
        assertEquals(sha, r.apkSha256)
        assertEquals(20123456L, r.apkSize)
    }

    @Test
    fun foreignApkIsIgnored() {
        // APK spoza wydań eLektrona nie jest pobierany - zostaje otwarcie strony wydania.
        val release = """
            [{"tag_name": "v9.9.9", "html_url": "https://github.com/Klap3K/Elektronv2/releases/tag/v9.9.9",
              "assets": [{"name": "eLektron.apk", "browser_download_url": "https://evil.example/eLektron.apk"}]}]
        """.trimIndent()
        assertEquals(null, GitHubReleases.parse(release).single().apkUrl)
        assertEquals(null, GitHubReleases.parse(release).single().apkSha256)
    }
}
