package sk.uberhelper

/** Nastavenia. Hodnoty v km su vzdy nasobky 0,5. 0 = filter vypnuty. */
data class Config(
    val enabled: Boolean = false,
    val dryRun: Boolean = true,
    val showOverlay: Boolean = true,

    val pickupMinKm: Float = 0f,
    val pickupMaxKm: Float = 5f,
    val tripMinKm: Float = 0f,
    val tripMaxKm: Float = 0f,
    val minFareEur: Float = 0f,
    val declineCash: Boolean = false,

    /** Ak sa udaj neda precitat: false = nerob nic (bezpecne), true = odmietni. */
    val declineOnUnknown: Boolean = false,

    /** Kolko zhodnych citani za sebou treba, nez appka klikne. */
    val confirmReads: Int = 2,

    /** Klikne len ked na karte vidi tlacidlo Prijat. */
    val requireAcceptButton: Boolean = true,

    /** Posledna zachrana: ak X nezabralo, skusi systemove Spat. */
    val allowBackFallback: Boolean = false,

    val tapDurationMs: Long = 120L,

    val targetPackages: String = "com.ubercab.driver",
    val acceptKeywords: String = "Prijať,Prijat,Accept,Přijmout,Prijmout",
    val closeKeywords: String = "zavrieť,zavriet,odmietnuť,odmietnut,zrušiť,zrusit,close,dismiss,decline,reject,cancel,×,✕,x"
) {
    fun packageList(): Set<String> =
        targetPackages.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun acceptList(): List<String> =
        acceptKeywords.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun closeList(): List<String> =
        closeKeywords.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
}
