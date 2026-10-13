package pl.zse.bydgoszcz.elektron.domain.model

/**
 * Identyfikatory kart widocznych na stronie głównej (Dashboard).
 */
enum class DashboardCardId(val defaultTitle: String) {
    COUNTDOWN("Odliczanie do wydarzenia"),
    DUTY("Dyżurny klasowy"),
    ATTENDANCE("Strażnik frekwencji"),
    GRADES("Oceny i średnia"),
    ACTIVITIES("Własne plany i zadania"),
    DEPARTURE("Odjazdy autobusów i tramwajów"),
    SUBS("Zastępstwa"),
    EVENTS("Sprawdziany i wydarzenia"),
    ANNOUNCEMENTS("Ogłoszenia szkolne");

    companion object {
        fun fromName(name: String): DashboardCardId? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * Pojedynczy element układu dashboardu — karta + informacja o widoczności.
 */
data class DashboardCardItem(
    val id: DashboardCardId,
    val isVisible: Boolean = true
)

/**
 * Narzędzia do serializacji / deserializacji układu dashboardu
 * oraz dostarczania domyślnej kolejności kart.
 */
object DashboardLayoutUtils {

    private const val SEPARATOR = ";"
    private const val VISIBLE_FLAG = "1"
    private const val HIDDEN_FLAG = "0"

    /**
     * Domyślna kolejność kart widoczna dla użytkowników.
     */
    fun defaultOrder(): List<DashboardCardItem> = listOf(
        DashboardCardItem(DashboardCardId.COUNTDOWN),
        DashboardCardItem(DashboardCardId.DUTY),
        DashboardCardItem(DashboardCardId.ATTENDANCE),
        DashboardCardItem(DashboardCardId.GRADES),
        DashboardCardItem(DashboardCardId.ACTIVITIES),
        DashboardCardItem(DashboardCardId.DEPARTURE),
        DashboardCardItem(DashboardCardId.SUBS),
        DashboardCardItem(DashboardCardId.EVENTS),
        DashboardCardItem(DashboardCardId.ANNOUNCEMENTS)
    )

    /**
     * Serializuje listę kart do formatu:
     *   "COUNTDOWN:1;DUTY:0;GRADES:1;..."
     */
    fun encode(items: List<DashboardCardItem>): String =
        items.joinToString(SEPARATOR) { item ->
            val flag = if (item.isVisible) VISIBLE_FLAG else HIDDEN_FLAG
            "${item.id.name}:$flag"
        }

    /**
     * Deserializuje listę kart z [raw].
     * - Zwraca [defaultOrder] gdy [raw] jest null/pusty.
     * - Pomija nieznane identyfikatory.
     * - Uzupełnia brakujące karty na końcu.
     * - Usuwa duplikaty.
     */
    fun decode(raw: String?): List<DashboardCardItem> {
        if (raw.isNullOrBlank()) return defaultOrder()

        val seen = mutableSetOf<DashboardCardId>()
        val parsed = mutableListOf<DashboardCardItem>()

        raw.split(SEPARATOR).forEach { token ->
            val trimmed = token.trim()
            if (trimmed.isEmpty()) return@forEach

            val parts = trimmed.split(":")
            val idName = parts.getOrNull(0)?.trim().orEmpty()
            val flag = parts.getOrNull(1)?.trim()

            val id = DashboardCardId.fromName(idName) ?: return@forEach
            if (!seen.add(id)) return@forEach

            val isVisible = flag != HIDDEN_FLAG
            parsed += DashboardCardItem(id = id, isVisible = isVisible)
        }

        val missing = DashboardCardId.entries.filterNot { it in seen }
            .map { DashboardCardItem(it) }

        return parsed + missing
    }
}
