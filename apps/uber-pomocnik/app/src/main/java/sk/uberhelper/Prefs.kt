package sk.uberhelper

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val FILE = "uberpomocnik"

    fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(ctx: Context): Config {
        val p = sp(ctx)
        val d = Config()
        return Config(
            enabled = p.getBoolean("enabled", d.enabled),
            dryRun = p.getBoolean("dryRun", d.dryRun),
            showOverlay = p.getBoolean("showOverlay", d.showOverlay),
            pickupMinKm = p.getFloat("pickupMinKm", d.pickupMinKm),
            pickupMaxKm = p.getFloat("pickupMaxKm", d.pickupMaxKm),
            tripMinKm = p.getFloat("tripMinKm", d.tripMinKm),
            tripMaxKm = p.getFloat("tripMaxKm", d.tripMaxKm),
            minFareEur = p.getFloat("minFareEur", d.minFareEur),
            declineCash = p.getBoolean("declineCash", d.declineCash),
            declineOnUnknown = p.getBoolean("declineOnUnknown", d.declineOnUnknown),
            confirmReads = p.getInt("confirmReads", d.confirmReads),
            requireAcceptButton = p.getBoolean("requireAcceptButton", d.requireAcceptButton),
            allowBackFallback = p.getBoolean("allowBackFallback", d.allowBackFallback),
            tapDurationMs = p.getLong("tapDurationMs", d.tapDurationMs),
            targetPackages = p.getString("targetPackages", d.targetPackages) ?: d.targetPackages,
            acceptKeywords = p.getString("acceptKeywords", d.acceptKeywords) ?: d.acceptKeywords,
            closeKeywords = p.getString("closeKeywords", d.closeKeywords) ?: d.closeKeywords
        )
    }

    fun save(ctx: Context, c: Config) {
        sp(ctx).edit().apply {
            putBoolean("enabled", c.enabled)
            putBoolean("dryRun", c.dryRun)
            putBoolean("showOverlay", c.showOverlay)
            putFloat("pickupMinKm", c.pickupMinKm)
            putFloat("pickupMaxKm", c.pickupMaxKm)
            putFloat("tripMinKm", c.tripMinKm)
            putFloat("tripMaxKm", c.tripMaxKm)
            putFloat("minFareEur", c.minFareEur)
            putBoolean("declineCash", c.declineCash)
            putBoolean("declineOnUnknown", c.declineOnUnknown)
            putInt("confirmReads", c.confirmReads)
            putBoolean("requireAcceptButton", c.requireAcceptButton)
            putBoolean("allowBackFallback", c.allowBackFallback)
            putLong("tapDurationMs", c.tapDurationMs)
            putString("targetPackages", c.targetPackages)
            putString("acceptKeywords", c.acceptKeywords)
            putString("closeKeywords", c.closeKeywords)
        }.apply()
    }
}
