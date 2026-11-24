package sk.uberhelper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import sk.uberhelper.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnAccessibility.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Throwable) {
                toast("Nepodarilo sa otvoriť nastavenia")
            }
        }

        b.btnTest.setOnClickListener { runTest() }
        b.btnClearLog.setOnClickListener {
            LogBus.clear()
            b.log.text = ""
        }
        b.btnCopyLog.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", LogBus.dump()))
            toast("Skopírované")
        }

        bindAll()
        LogBus.listener = { refreshLog() }
    }

    override fun onResume() {
        super.onResume()
        load()
        refreshStatus()
        refreshLog()
    }

    override fun onDestroy() {
        LogBus.listener = null
        super.onDestroy()
    }

    private fun serviceEnabledInSettings(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = "$packageName/${UberWatchService::class.java.name}"
        val meShort = "$packageName/.${UberWatchService::class.java.simpleName}"
        return flat.split(':').any { it.equals(me, true) || it.equals(meShort, true) }
    }

    private fun refreshStatus() {
        val granted = serviceEnabledInSettings()
        val live = UberWatchService.running
        val c = Prefs.load(this)
        b.status.text = buildString {
            append(if (granted) "✓ Prístupnosť povolená" else "✗ Prístupnosť NEPOVOLENÁ – zapni nižšie")
            append("\n")
            append(if (live) "✓ Služba beží" else "✗ Služba nebeží")
            append("\n")
            append(if (c.enabled) "✓ Filter zapnutý" else "✗ Filter vypnutý")
            if (c.dryRun) append("  ·  režim nasucho")
        }
    }

    private fun refreshLog() {
        b.log.text = LogBus.dump()
    }

    private fun load() {
        loading = true
        val c = Prefs.load(this)

        b.swEnabled.isChecked = c.enabled
        b.swDryRun.isChecked = c.dryRun
        b.swOverlay.isChecked = c.showOverlay
        b.swCash.isChecked = c.declineCash
        b.swUnknown.isChecked = c.declineOnUnknown
        b.swRequireAccept.isChecked = c.requireAcceptButton
        b.swBack.isChecked = c.allowBackFallback

        setSlider(b.sPickupMin, c.pickupMinKm)
        setSlider(b.sPickupMax, c.pickupMaxKm)
        setSlider(b.sTripMin, c.tripMinKm)
        setSlider(b.sTripMax, c.tripMaxKm)
        setSlider(b.sFare, c.minFareEur)
        setSlider(b.sConfirm, c.confirmReads.toFloat())
        setSlider(b.sTapMs, c.tapDurationMs.toFloat())

        if (b.etPackages.text.isNullOrEmpty()) b.etPackages.setText(c.targetPackages)
        if (b.etAccept.text.isNullOrEmpty()) b.etAccept.setText(c.acceptKeywords)
        if (b.etClose.text.isNullOrEmpty()) b.etClose.setText(c.closeKeywords)

        loading = false
        refreshLabels()
    }

    private fun setSlider(s: Slider, v: Float) {
        val step = s.stepSize
        var x = v.coerceIn(s.valueFrom, s.valueTo)
        if (step > 0f) x = Math.round(x / step) * step
        s.value = x.coerceIn(s.valueFrom, s.valueTo)
    }

    private fun current(): Config = Config(
        enabled = b.swEnabled.isChecked,
        dryRun = b.swDryRun.isChecked,
        showOverlay = b.swOverlay.isChecked,
        pickupMinKm = b.sPickupMin.value,
        pickupMaxKm = b.sPickupMax.value,
        tripMinKm = b.sTripMin.value,
        tripMaxKm = b.sTripMax.value,
        minFareEur = b.sFare.value,
        declineCash = b.swCash.isChecked,
        declineOnUnknown = b.swUnknown.isChecked,
        confirmReads = b.sConfirm.value.toInt(),
        requireAcceptButton = b.swRequireAccept.isChecked,
        allowBackFallback = b.swBack.isChecked,
        tapDurationMs = b.sTapMs.value.toLong(),
        targetPackages = b.etPackages.text?.toString().orEmpty().ifBlank { Config().targetPackages },
        acceptKeywords = b.etAccept.text?.toString().orEmpty().ifBlank { Config().acceptKeywords },
        closeKeywords = b.etClose.text?.toString().orEmpty().ifBlank { Config().closeKeywords }
    )

    private fun save() {
        if (loading) return
        Prefs.save(this, current())
        UberWatchService.instance?.reloadConfig()
        refreshLabels()
        refreshStatus()
    }

    private fun bindAll() {
        val switches = listOf(
            b.swEnabled, b.swDryRun, b.swOverlay, b.swCash,
            b.swUnknown, b.swRequireAccept, b.swBack
        )
        switches.forEach { sw -> sw.setOnCheckedChangeListener { _, _ -> save() } }

        val sliders = listOf(
            b.sPickupMin, b.sPickupMax, b.sTripMin,
            b.sTripMax, b.sFare, b.sConfirm, b.sTapMs
        )
        sliders.forEach { s -> s.addOnChangeListener { _, _, fromUser -> if (fromUser) save() } }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun afterTextChanged(s: Editable?) = save()
        }
        b.etPackages.addTextChangedListener(watcher)
        b.etAccept.addTextChangedListener(watcher)
        b.etClose.addTextChangedListener(watcher)
    }

    private fun refreshLabels() {
        lbl(b.lblSPickupMin, "Minimum dojazdu", b.sPickupMin.value, "km")
        lbl(b.lblSPickupMax, "Maximum dojazdu", b.sPickupMax.value, "km")
        lbl(b.lblSTripMin, "Minimum dĺžky jazdy", b.sTripMin.value, "km")
        lbl(b.lblSTripMax, "Maximum dĺžky jazdy", b.sTripMax.value, "km")
        lbl(b.lblSFare, "Minimálna cena", b.sFare.value, "€")
        b.lblSConfirm.text = "Zhodné čítania pred klikom: ${b.sConfirm.value.toInt()}"
        b.lblSTapMs.text = "Dĺžka dotyku: ${b.sTapMs.value.toInt()} ms"
    }

    private fun lbl(tv: TextView, name: String, v: Float, unit: String) {
        tv.text = if (v <= 0f) "$name: vypnuté"
        else String.format("%s: %.1f %s", name, v, unit)
    }

    private fun runTest() {
        val c = current()
        val lines = b.etTest.text?.toString().orEmpty()
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            b.testResult.text = "Vlož aspoň jeden riadok."
            return
        }
        val o = OfferParser.parse(lines, c.acceptList())
        val d = OfferParser.decide(o, c)
        b.testResult.text = buildString {
            append("dojazd   : ").append(o.pickupKm?.let { OfferParser.km(it) } ?: "?").append('\n')
            append("jazda    : ").append(o.tripKm?.let { OfferParser.km(it) } ?: "?").append('\n')
            append("cena     : ").append(o.fareEur?.let { OfferParser.eur(it) } ?: "?").append('\n')
            append("hotovosť : ").append(if (o.cashOnly) "áno" else "nie").append('\n')
            append("Prijať   : ").append(if (o.hasAcceptButton) "nájdené" else "nenájdené").append('\n')
            append("všetky km: ").append(o.allKm.joinToString { OfferParser.km(it) }).append('\n')
            append("-----\n")
            append(d.action.name).append(" – ").append(d.reason)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
