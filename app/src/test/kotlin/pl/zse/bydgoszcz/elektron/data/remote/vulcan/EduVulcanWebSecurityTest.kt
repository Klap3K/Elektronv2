package pl.zse.bydgoszcz.elektron.data.remote.vulcan

import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient
import pl.zse.bydgoszcz.elektron.domain.model.VulcanAccount

class EduVulcanWebSecurityTest {
    @Test fun onlyHttpsVulcanHostsWithoutUserInfoAreTrusted() {
        listOf("https://eduvulcan.pl/logowanie", "https://uczen.eduvulcan.pl/bydgoszcz/App",
            "https://uonetplus.vulcan.net.pl/").forEach { assertTrue(it, EduVulcanWebSecurity.isTrustedUrl(it)) }
        listOf(null, "http://eduvulcan.pl", "https://eduvulcan.pl.evil.test", "https://eviled uvulcan.pl",
            "https://eduvulcan.pl@evil.test", "https://evil@eduvulcan.pl", "https://eduvulcan.pl:8443",
            "javascript:alert(1)", "file:///test").forEach { assertFalse(it, EduVulcanWebSecurity.isTrustedUrl(it)) }
    }

    @Test fun invalidAccountIsRejectedBeforeNetworkOrCookieAccess() {
        val api = VulcanApiClient(OkHttpClient())
        val account = VulcanAccount(symbol = "bydgoszcz", pupilId = 1, pupilName = "", schoolName = "",
            restUrl = "", domain = "evil.test", cookiesHeader = "sensitive-cookie")
        assertTrue(api.fetchEduVulcanEvents(account).isFailure)
        assertTrue(api.fetchEduVulcanAttendance(account).isFailure)
        assertTrue(api.fetchEduVulcanGrades(account).isFailure)
        assertTrue(api.fetchEduVulcanEvents(account.copy(domain = "eduvulcan.pl", symbol = "../../evil")).isFailure)
        assertFalse(account.toString().contains("sensitive-cookie"))
    }
}
