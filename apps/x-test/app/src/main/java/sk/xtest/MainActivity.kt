package sk.xtest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import sk.xtest.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var loading = false

    private fun sp() = getSharedPreferences("xtest", Context.MODE_PRIVATE)

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

        b.btnCopy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("xtest", XLog.dump()))
            toast("Skopírované")
        }

        b.btnClear.setOnClickListener {
            XLog.clear()
            b.log.text = ""
        }

        val save = { _: android.widget.CompoundButton, _: Boolean ->
            if (!loading) {
                sp().edit()
                    .putBoolean("enabled", b.swEnabled.isChecked)
                    .putBoolean("click", b.swClick.isChecked)
                    .putBoolean("allApps", b.swAllApps.isChecked)
                    .putBoolean("shape", b.swShape.isChecked)
                    .putBoolean("dump", b.swDump.isChecked)
                    .apply()
                refreshStatus()
            }
        }
        b.swEnabled.setOnCheckedChangeListener(save)
        b.swClick.setOnCheckedChangeListener(save)
        b.swAllApps.setOnCheckedChangeListener(save)
        b.swShape.setOnCheckedChangeListener(save)
        b.swDump.setOnCheckedChangeListener(save)

        XLog.listener = { refreshLog() }
    }

    override fun onResume() {
        super.onResume()
        loading = true
        val p = sp()
        b.swEnabled.isChecked = p.getBoolean("enabled", false)
        b.swClick.isChecked = p.getBoolean("click", false)
        b.swAllApps.isChecked = p.getBoolean("allApps", false)
        b.swShape.isChecked = p.getBoolean("shape", true)
        b.swDump.isChecked = p.getBoolean("dump", true)
        loading = false
        refreshStatus()
        refreshLog()
    }

    override fun onDestroy() {
        XLog.listener = null
        super.onDestroy()
    }

    private fun granted(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = "$packageName/${XService::class.java.name}"
        val short = "$packageName/.XService"
        return flat.split(':').any { it.equals(me, true) || it.equals(short, true) }
    }

    private fun refreshStatus() {
        b.status.text = buildString {
            append(if (granted()) "✓ Prístupnosť povolená" else "✗ Prístupnosť NEPOVOLENÁ")
            append("\n")
            append(if (XService.running) "✓ Služba beží" else "✗ Služba nebeží")
            append("\n")
            val p = sp()
            append(if (p.getBoolean("enabled", false)) "✓ Zapnuté" else "✗ Vypnuté")
            append("  ·  ")
            append(if (p.getBoolean("click", false)) "klikanie ZAP" else "klikanie VYP")
            append("\n")
            append(
                if (p.getBoolean("allApps", false)) "sleduje všetky aplikácie"
                else "sleduje len com.ubercab.driver"
            )
        }
    }

    private fun refreshLog() {
        b.log.text = XLog.dump()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
