package sk.uberhelper

/**
 * Cista logika bez Android zavislosti - da sa testovat na JVM.
 * Vstup: riadky textu vycitane zo stromu pristupnosti Uber Driver.
 */
object OfferParser {

    private const val EPS = 0.001

    private val DIST_RE = Regex(
        """(\d{1,4}(?:[.,]\d{1,2})?)\s*(km|m|mi)(?![a-zA-Zà-ſ])""",
        RegexOption.IGNORE_CASE
    )

    private val FARE_RE = Regex(
        """(?:€\s*(\d{1,4}(?:[.,]\d{1,2})?))|(?:(\d{1,4}(?:[.,]\d{1,2})?)\s*(?:€|eur\b))""",
        RegexOption.IGNORE_CASE
    )

    private val PICKUP_LABELS = listOf(
        "od vas", "od teba", "daleko", "away", "vyzdvihnut", "nastup",
        "pickup", "dojazd", "prichod", "k zakaznik", "odtialto"
    )
    private val TRIP_LABELS = listOf(
        "jazda", "jazdy", "cesta", "cesty", "trip", "vystup", "dropoff",
        "cielov", "celkom", "dlzka"
    )
    private val CASH_LABELS = listOf("hotovos", "cash", "bar")

    data class Offer(
        val lines: List<String>,
        val allKm: List<Double>,
        val pickupKm: Double?,
        val tripKm: Double?,
        val fareEur: Double?,
        val cashOnly: Boolean,
        val hasAcceptButton: Boolean
    ) {
        fun signature(): String =
            "${fmt(pickupKm)}|${fmt(tripKm)}|${fmt(fareEur)}|$cashOnly|${allKm.size}"

        private fun fmt(d: Double?) = d?.let { String.format("%.2f", it) } ?: "?"
    }

    enum class Action { WAIT, KEEP, DECLINE }

    data class Decision(val action: Action, val reason: String)

    /** Odstrani diakritiku a zmensi - aby "daleko" sedelo aj s makcenom. */
    fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.lowercase()) {
            sb.append(
                when (c) {
                    'á', 'ä', 'à', 'â' -> 'a'
                    'č', 'ć' -> 'c'
                    'ď' -> 'd'
                    'é', 'ě', 'è', 'ê' -> 'e'
                    'í', 'ì', 'î' -> 'i'
                    'ĺ', 'ľ' -> 'l'
                    'ň', 'ń' -> 'n'
                    'ó', 'ô', 'ö', 'ò' -> 'o'
                    'ŕ', 'ř' -> 'r'
                    'š', 'ś' -> 's'
                    'ť' -> 't'
                    'ú', 'ů', 'ü', 'ù', 'û' -> 'u'
                    'ý', 'ÿ' -> 'y'
                    'ž', 'ź', 'ż' -> 'z'
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    private fun toKm(value: String, unit: String): Double? {
        val v = value.replace(',', '.').toDoubleOrNull() ?: return null
        return when (unit.lowercase()) {
            "km" -> v
            "m" -> v / 1000.0
            "mi" -> v * 1.609344
            else -> null
        }
    }

    private data class Hit(val km: Double, val pickupLabel: Boolean, val tripLabel: Boolean)

    fun parse(lines: List<String>, acceptKeywords: List<String>): Offer {
        val hits = mutableListOf<Hit>()
        var fare: Double? = null
        var cash = false
        var accept = false

        val accepts = acceptKeywords.map { norm(it).trim() }.filter { it.isNotEmpty() }

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val n = norm(line)

            // Tlacidlo Prijat musi sediet presne - "Prijat platbu" na obrazovke
            // po prijati jazdy sa tak nezamiena za ponuku.
            if (accepts.any { it == n }) accept = true

            if (CASH_LABELS.any { n.contains(it) }) cash = true

            if (fare == null) {
                FARE_RE.find(line)?.let { m ->
                    val g = m.groupValues[1].ifEmpty { m.groupValues[2] }
                    fare = g.replace(',', '.').toDoubleOrNull()
                }
            }

            val found = DIST_RE.findAll(line)
                .mapNotNull { toKm(it.groupValues[1], it.groupValues[2]) }
                .toList()
            if (found.isEmpty()) continue

            val isPickup = PICKUP_LABELS.any { n.contains(it) }
            val isTrip = TRIP_LABELS.any { n.contains(it) }
            found.forEach { hits.add(Hit(it, isPickup && !isTrip, isTrip && !isPickup)) }
        }

        var pickupIdx = hits.indexOfFirst { it.pickupLabel }
        var tripIdx = hits.indexOfFirst { it.tripLabel }

        // Bez popisiek plati poradie na karte: najprv dojazd, potom dlzka jazdy.
        if (pickupIdx < 0 && hits.isNotEmpty()) pickupIdx = if (tripIdx == 0) -1 else 0
        if (tripIdx < 0 || tripIdx == pickupIdx) {
            tripIdx = hits.indices.firstOrNull { it != pickupIdx && it > pickupIdx } ?: -1
        }

        val pickup = hits.getOrNull(pickupIdx)?.km
        val trip = hits.getOrNull(tripIdx)?.km

        return Offer(lines, hits.map { it.km }, pickup, trip, fare, cash, accept)
    }

    fun decide(o: Offer, c: Config): Decision {
        if (o.allKm.isEmpty()) return Decision(Action.WAIT, "ziadna vzdialenost v texte")

        val p = o.pickupKm
        val t = o.tripKm

        if (p == null) {
            return if (c.declineOnUnknown) Decision(Action.DECLINE, "dojazd neprecitany")
            else Decision(Action.WAIT, "dojazd neprecitany")
        }

        if (c.pickupMinKm > 0f && p < c.pickupMinKm - EPS)
            return Decision(Action.DECLINE, "dojazd ${km(p)} < min ${km(c.pickupMinKm.toDouble())}")
        if (c.pickupMaxKm > 0f && p > c.pickupMaxKm + EPS)
            return Decision(Action.DECLINE, "dojazd ${km(p)} > max ${km(c.pickupMaxKm.toDouble())}")

        if (c.tripMinKm > 0f || c.tripMaxKm > 0f) {
            if (t == null) {
                if (c.declineOnUnknown) return Decision(Action.DECLINE, "dlzka jazdy neprecitana")
                return Decision(Action.WAIT, "dlzka jazdy neprecitana")
            }
            if (c.tripMinKm > 0f && t < c.tripMinKm - EPS)
                return Decision(Action.DECLINE, "jazda ${km(t)} < min ${km(c.tripMinKm.toDouble())}")
            if (c.tripMaxKm > 0f && t > c.tripMaxKm + EPS)
                return Decision(Action.DECLINE, "jazda ${km(t)} > max ${km(c.tripMaxKm.toDouble())}")
        }

        if (c.minFareEur > 0f) {
            val f = o.fareEur
            if (f == null) {
                if (c.declineOnUnknown) return Decision(Action.DECLINE, "cena neprecitana")
                return Decision(Action.WAIT, "cena neprecitana")
            }
            if (f < c.minFareEur - EPS)
                return Decision(Action.DECLINE, "cena ${eur(f)} < min ${eur(c.minFareEur.toDouble())}")
        }

        if (c.declineCash && o.cashOnly) return Decision(Action.DECLINE, "iba hotovost")

        return Decision(Action.KEEP, "vyhovuje")
    }

    fun km(d: Double) = String.format("%.1f km", d)
    fun eur(d: Double) = String.format("%.2f €", d)
}
