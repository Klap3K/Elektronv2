package pl.zse.bydgoszcz.elektron.domain.model

/**
 * Zarejestrowane konto w systemie VULCAN UONET+ / eduVULCAN (Hebe API).
 */
data class VulcanAccount(
    val token: String,
    val symbol: String,
    val pin: String = "",
    val pupilId: Int,
    val pupilName: String,
    val schoolName: String,
    val restUrl: String,
    val certificatePem: String,
    val certificateThumbprint: String,
    val privateKeyPkcs8Base64: String
) {
    val shortDisplay: String
        get() = "$pupilName ($schoolName)"
}

/**
 * Dane wejściowe do rejestracji nowego urządzenia mobilnego w VULCAN.
 */
data class VulcanRegistrationInput(
    val token: String,
    val symbol: String,
    val pin: String
) {
    companion object {
        /**
         * Parsuje dane rejestracyjne z tekstu, JSON lub formatu kodu QR.
         * Obsługuje m.in.:
         * - Czysty JSON z eduVULCAN / VULCAN (np. {"Token":"...","Symbol":"...","Pin":"..."})
         * - Format rozdzielany dwukropkiem lub średnikiem (TOKEN:SYMBOL:PIN)
         */
        fun parseOrNull(raw: String): VulcanRegistrationInput? {
            val text = raw.trim()
            if (text.isBlank()) return null

            // Próba parsowania jako JSON
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
                    // ignorujemy błąd JSON, próbujemy innych formatów
                }
            }

            // Próba parsowania formatów rozdzielanych: TOKEN#SYMBOL#PIN lub TOKEN:SYMBOL:PIN
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
