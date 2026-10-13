package pl.zse.bydgoszcz.elektron.domain.model

/**
 * Zarejestrowane konto / sesja w systemie eduVULCAN (dziennik.vulcan.edu.pl / uczen.eduvulcan.pl)
 * lub w starszym API mobilnym VULCAN UONET+ (Hebe).
 */
data class VulcanAccount(
    val token: String = "",
    val symbol: String,
    val pin: String = "",
    val pupilId: Int,
    val pupilName: String,
    val schoolName: String,
    val restUrl: String,
    val certificatePem: String = "",
    val certificateThumbprint: String = "",
    val privateKeyPkcs8Base64: String = "",
    val isEduVulcan: Boolean = true,
    val domain: String = "eduvulcan.pl",
    val studentKey: String = "",
    val cookiesHeader: String = "",
    val loginUsername: String = ""
) {
    val shortDisplay: String
        get() = "$pupilName ($schoolName)"

    override fun toString(): String = "VulcanAccount(isEduVulcan=$isEduVulcan)"
}

/**
 * Dane sesji wyodrębnione po zalogowaniu hasłem do konta eduVULCAN (eduvulcan.pl / uczen.eduvulcan.pl).
 */
data class EduVulcanSession(
    val domain: String = "eduvulcan.pl",
    val symbol: String,
    val studentKey: String,
    val idDziennik: Int,
    val pupilName: String,
    val schoolName: String,
    val className: String = "",
    val cookiesHeader: String,
    val loginUsername: String = "",
    val loginPassword: String = ""
) {
    val baseRestUrl: String
        get() = "https://uczen.$domain/$symbol"

    fun toVulcanAccount(): VulcanAccount {
        val displaySchool = if (className.isNotBlank() && !schoolName.contains(className)) {
            "$schoolName ($className)".trim()
        } else {
            schoolName.ifBlank { symbol }
        }
        return VulcanAccount(
            symbol = symbol,
            pin = "",
            pupilId = idDziennik,
            pupilName = pupilName.ifBlank { "Uczeń" },
            schoolName = displaySchool,
            restUrl = baseRestUrl,
            isEduVulcan = true,
            domain = domain,
            studentKey = studentKey,
            cookiesHeader = cookiesHeader,
            loginUsername = loginUsername
        )
    }

    override fun toString(): String = "EduVulcanSession(redacted)"
}

/**
 * Dane wejściowe do rejestracji urządzenia mobilnego (starsze API Hebe).
 */
data class VulcanRegistrationInput(
    val token: String,
    val symbol: String,
    val pin: String
) {
    override fun toString(): String = "VulcanRegistrationInput(redacted)"
    companion object {
        fun parseOrNull(raw: String): VulcanRegistrationInput? {
            val text = raw.trim()
            if (text.isBlank()) return null

            if (text.startsWith("{") && text.endsWith("}")) {
                try {
                    val json = org.json.JSONObject(text)
                    val token = json.optString("Token").ifBlank { json.optString("token") }
                    val symbol = json.optString("Symbol").ifBlank { json.optString("symbol") }
                    val pin = json.optString("Pin").ifBlank { json.optString("pin") }
                    if (token.isNotBlank() && symbol.isNotBlank() && pin.isNotBlank()) {
                        return VulcanRegistrationInput(token.trim(), symbol.trim(), pin.trim())
                    }
                } catch (_: Exception) {
                }
            }

            val separators = listOf("#", ";", ":", ",")
            for (sep in separators) {
                if (text.contains(sep)) {
                    val parts = text.split(sep).map { it.trim() }
                    if (parts.size >= 3 && parts[0].isNotBlank() && parts[1].isNotBlank() && parts[2].isNotBlank()) {
                        return VulcanRegistrationInput(parts[0], parts[1], parts[2])
                    }
                }
            }

            return null
        }
    }
}
