package sk.uberhelper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.max

class UberWatchService : AccessibilityService() {

    companion object {
        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var instance: UberWatchService? = null
            private set

        private const val SCAN_DEBOUNCE_MS = 120L
        private const val COOLDOWN_MS = 2500L
        private const val MAX_NODES = 1200
        private const val MAX_DEPTH = 70

        private val GREEN = Color.parseColor("#4ADE80")
        private val RED = Color.parseColor("#F87171")
        private val AMBER = Color.parseColor("#FBBF24")
        private val GREY = Color.parseColor("#94A3B8")
    }

    private val main = Handler(Looper.getMainLooper())
    private var overlay: OverlayManager? = null

    @Volatile
    private var cfg = Config()

    private var lastSig = ""
    private var sigCount = 0
    private var busy = false
    private var scheduled = false
    private var lastActionAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        instance = this
        cfg = Prefs.load(this)
        overlay = OverlayManager(this)
        applyOverlayVisibility()
        LogBus.add("Služba pripojená. Sledujem: ${cfg.packageList().joinToString()}")
        setIdle("Uber pomocník", if (cfg.enabled) "zapnuté – čakám na ponuku" else "vypnuté")
    }

    override fun onDestroy() {
        running = false
        instance = null
        overlay?.detach()
        overlay = null
        LogBus.add("Služba odpojená")
        super.onDestroy()
    }

    override fun onInterrupt() {}

    fun reloadConfig() {
        cfg = Prefs.load(this)
        applyOverlayVisibility()
    }

    private fun applyOverlayVisibility() {
        if (cfg.showOverlay && cfg.enabled) overlay?.attach() else overlay?.detach()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (busy) return
        val pkg = event?.packageName?.toString() ?: return
        cfg = Prefs.load(this)
        if (!cfg.enabled) {
            overlay?.detach()
            return
        }
        if (!cfg.packageList().contains(pkg)) return
        applyOverlayVisibility()
        scheduleScan()
    }

    private fun scheduleScan() {
        if (scheduled) return
        scheduled = true
        main.postDelayed({
            scheduled = false
            try {
                scan()
            } catch (t: Throwable) {
                LogBus.add("Chyba pri čítaní: ${t.message}")
                busy = false
            }
        }, SCAN_DEBOUNCE_MS)
    }

    private class Snapshot(
        val lines: List<String>,
        val nodes: List<AccessibilityNodeInfo>
    )

    private fun snapshot(): Snapshot {
        val lines = ArrayList<String>()
        val nodes = ArrayList<AccessibilityNodeInfo>()
        for (root in collectRoots()) walk(root, lines, nodes, 0)
        return Snapshot(lines, nodes)
    }

    private fun collectRoots(): List<AccessibilityNodeInfo> {
        val pkgs = cfg.packageList()
        val out = ArrayList<AccessibilityNodeInfo>()
        // Ponuka jazdy casto bezi vo vlastnom okne - rootInActiveWindow ju minie.
        try {
            for (w in windows) {
                val r = w.root ?: continue
                if (pkgs.contains(r.packageName?.toString())) out.add(r)
            }
        } catch (_: Throwable) {
        }
        if (out.isEmpty()) rootInActiveWindow?.let {
            if (pkgs.contains(it.packageName?.toString())) out.add(it)
        }
        return out
    }

    private fun walk(
        node: AccessibilityNodeInfo?,
        lines: MutableList<String>,
        nodes: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (node == null || depth > MAX_DEPTH || nodes.size > MAX_NODES) return
        nodes.add(node)
        node.text?.toString()?.let { if (it.isNotBlank()) lines.add(it) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) lines.add(it) }
        val n = node.childCount
        for (i in 0 until n) walk(node.getChild(i), lines, nodes, depth + 1)
    }

    private fun scan() {
        if (busy) return
        val snap = snapshot()
        if (snap.nodes.isEmpty()) return

        val offer = OfferParser.parse(snap.lines, cfg.acceptList())
        val close = findCloseNode(snap.nodes)

        val acceptOk = !cfg.requireAcceptButton || offer.hasAcceptButton
        val isOffer = acceptOk && close != null && offer.allKm.isNotEmpty()

        if (!isOffer) {
            lastSig = ""
            sigCount = 0
            val why = buildString {
                if (!acceptOk) append("bez tlačidla Prijať")
                if (close == null) {
                    if (isNotEmpty()) append(" · ")
                    append("bez X")
                }
                if (offer.allKm.isEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append("bez km")
                }
            }
            setIdle("Čakám na ponuku", why.ifEmpty { "nič na obrazovke" })
            return
        }

        val sig = offer.signature()
        if (sig == lastSig) sigCount++ else {
            lastSig = sig
            sigCount = 1
        }

        val dec = OfferParser.decide(offer, cfg)
        showOffer(offer, dec, sigCount)

        if (dec.action != OfferParser.Action.DECLINE) return
        if (sigCount < max(1, cfg.confirmReads)) return
        if (SystemClock.uptimeMillis() - lastActionAt < COOLDOWN_MS) return

        lastActionAt = SystemClock.uptimeMillis()

        if (cfg.dryRun) {
            LogBus.add("NASUCHO – klikol by som X (${dec.reason})")
            overlay?.update("NASUCHO: X", dec.reason, AMBER)
            return
        }

        busy = true
        LogBus.add("ODMIETAM: ${dec.reason}")
        tryStrategy(1, close!!)
    }

    private fun findCloseNode(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        // Popisy prvkov sa porovnavaju bez diakritiky, takze aj kluce musia prejst norm().
        val keys = cfg.closeList().map { OfferParser.norm(it) }.filter { it.isNotEmpty() }
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0

        for (node in nodes) {
            val id = node.viewIdResourceName?.lowercase() ?: ""
            val desc = OfferParser.norm(node.contentDescription?.toString() ?: "")
            val txt = OfferParser.norm(node.text?.toString() ?: "")

            var score = 0
            if (id.isNotEmpty() && keys.any { id.contains(it) }) score += 60
            if (desc.isNotEmpty()) {
                if (keys.any { desc == it }) score += 55
                else if (keys.any { desc.contains(it) && it.length > 2 }) score += 35
            }
            if (txt == "x" || txt == "×" || txt == "✕") score += 30
            if (score == 0) continue

            if (node.isClickable) score += 25
            if (node.isEnabled) score += 5

            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() <= 0 || r.height() <= 0) continue
            // X je male stvorcove tlacidlo, nie cely panel.
            val w = r.width()
            val h = r.height()
            if (w in 24..260 && h in 24..260) score += 20
            val ratio = w.toFloat() / h.toFloat()
            if (ratio in 0.6f..1.7f) score += 10

            if (score > bestScore) {
                bestScore = score
                best = node
            }
        }
        return if (bestScore >= 55) best else null
    }

    private fun tryStrategy(step: Int, node: AccessibilityNodeInfo) {
        when (step) {
            1 -> {
                val ok = clickNodeTree(node)
                LogBus.add("1) klik na prvok: ${if (ok) "odoslaný" else "neúspešný"}")
                if (ok) verifyLater(step, node, 400L) else tryStrategy(2, node)
            }
            2 -> {
                gestureOnNode(node) { ok ->
                    LogBus.add("2) gesto na súradnice: ${if (ok) "dokončené" else "zlyhalo"}")
                    if (ok) verifyLater(step, node, 450L) else tryStrategy(3, node)
                }
            }
            3 -> {
                if (cfg.allowBackFallback) {
                    val ok = performGlobalAction(GLOBAL_ACTION_BACK)
                    LogBus.add("3) systémové Späť: $ok")
                    verifyLater(step, node, 500L)
                } else {
                    finish(false, "X sa nepodarilo potvrdiť")
                }
            }
            else -> finish(false, "vyčerpané možnosti")
        }
    }

    private fun verifyLater(step: Int, node: AccessibilityNodeInfo, delay: Long) {
        main.postDelayed({
            if (!offerStillOnScreen()) {
                finish(true, "zatvorené krokom $step")
            } else {
                LogBus.add("krok $step neúčinný – ponuka stále na obrazovke")
                tryStrategy(step + 1, node)
            }
        }, delay)
    }

    private fun offerStillOnScreen(): Boolean {
        return try {
            val snap = snapshot()
            val o = OfferParser.parse(snap.lines, cfg.acceptList())
            if (cfg.requireAcceptButton) o.hasAcceptButton
            else findCloseNode(snap.nodes) != null && o.allKm.isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    private fun finish(success: Boolean, msg: String) {
        busy = false
        lastSig = ""
        sigCount = 0
        lastActionAt = SystemClock.uptimeMillis()
        LogBus.add(if (success) "HOTOVO – $msg" else "NEPODARILO SA – $msg")
        overlay?.update(
            if (success) "ODMIETNUTÉ" else "X ZLYHALO",
            msg,
            if (success) GREEN else RED
        )
    }

    private fun clickNodeTree(node: AccessibilityNodeInfo): Boolean {
        try {
            node.refresh()
        } catch (_: Throwable) {
        }
        var n: AccessibilityNodeInfo? = node
        var hop = 0
        while (n != null && hop < 6) {
            if (n.isClickable && n.isEnabled) {
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            n = try {
                n.parent
            } catch (_: Throwable) {
                null
            }
            hop++
        }
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Gesto na stred prvku. Prekrytie sa pred gestom cele odoberie - prave prekrytie
     * nad cielovym tlacidlom je najcastejsi dovod, preco "TAP prebehol", ale ponuka
     * sa nezavrela (Android zahadzuje dotyky cez cudzie okno).
     */
    private fun gestureOnNode(node: AccessibilityNodeInfo, cb: (Boolean) -> Unit) {
        val r = Rect()
        try {
            node.refresh()
        } catch (_: Throwable) {
        }
        node.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) {
            cb(false)
            return
        }
        val x = r.exactCenterX()
        val y = r.exactCenterY()
        val wasShown = overlay?.isAttached() == true
        overlay?.detach()
        LogBus.add("gesto na ${x.toInt()},${y.toInt()} (prvok ${r.width()}×${r.height()}), prekrytie odobrané")

        main.postDelayed({
            val path = Path().apply { moveTo(x, y) }
            val gd = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, cfg.tapDurationMs))
                .build()
            val restore = {
                if (wasShown) main.postDelayed({ applyOverlayVisibility() }, 250)
            }
            val dispatched = try {
                dispatchGesture(gd, object : GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) {
                        restore()
                        cb(true)
                    }

                    override fun onCancelled(d: GestureDescription?) {
                        restore()
                        cb(false)
                    }
                }, null)
            } catch (t: Throwable) {
                LogBus.add("dispatchGesture vyhodil ${t.message}")
                false
            }
            if (!dispatched) {
                restore()
                cb(false)
            }
        }, 100L)
    }

    private fun setIdle(title: String, detail: String) {
        overlay?.update(title, detail, GREY)
    }

    private fun showOffer(o: OfferParser.Offer, d: OfferParser.Decision, confirms: Int) {
        val p = o.pickupKm?.let { OfferParser.km(it) } ?: "?"
        val t = o.tripKm?.let { OfferParser.km(it) } ?: "?"
        val f = o.fareEur?.let { OfferParser.eur(it) } ?: "?"
        val head = when (d.action) {
            OfferParser.Action.DECLINE -> "NEVYHOVUJE"
            OfferParser.Action.KEEP -> "VYHOVUJE"
            OfferParser.Action.WAIT -> "ČAKÁM"
        }
        val color = when (d.action) {
            OfferParser.Action.DECLINE -> RED
            OfferParser.Action.KEEP -> GREEN
            OfferParser.Action.WAIT -> AMBER
        }
        val need = max(1, cfg.confirmReads)
        val suffix = if (cfg.dryRun) "\n[nasucho]" else ""
        val detail = "dojazd $p · jazda $t · $f\n${d.reason}\nčítanie $confirms/$need$suffix"
        overlay?.update(head, detail, color)
    }
}
