package sk.xtest

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class XService : AccessibilityService() {

    companion object {
        @Volatile
        var running = false
            private set

        @Volatile
        var instance: XService? = null
            private set

        private const val DEBOUNCE_MS = 150L
        private const val COOLDOWN_MS = 2000L
        private const val MAX_NODES = 1500
        private const val MAX_DEPTH = 80
        private const val MAX_DUMP_LINES = 90

        private val CLOSE_WORDS = listOf(
            "close", "dismiss", "cancel", "decline", "reject", "exit",
            "zavriet", "odmietnut", "zrusit", "zavri", "x_button", "btn_close", "ic_close"
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private var scheduled = false
    private var busy = false
    private var lastActionAt = 0L
    private var lastDumpSig = ""

    private fun prefs() = getSharedPreferences("xtest", Context.MODE_PRIVATE)
    private fun on(key: String, def: Boolean) = prefs().getBoolean(key, def)

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        instance = this
        XLog.add("Služba pripojená.")
    }

    override fun onDestroy() {
        running = false
        instance = null
        XLog.add("Služba odpojená.")
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (busy) return
        if (!on("enabled", false)) return
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (!on("allApps", false) && pkg != "com.ubercab.driver") return
        scheduleScan(pkg)
    }

    private fun scheduleScan(pkg: String) {
        if (scheduled) return
        scheduled = true
        main.postDelayed({
            scheduled = false
            try {
                scan(pkg)
            } catch (t: Throwable) {
                XLog.add("CHYBA: ${t.message}")
                busy = false
            }
        }, DEBOUNCE_MS)
    }

    // ------------------------------------------------------------ zber uzlov

    private fun roots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) w.root?.let { out.add(it) }
        } catch (_: Throwable) {
        }
        if (out.isEmpty()) rootInActiveWindow?.let { out.add(it) }
        return out
    }

    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (n == null || depth > MAX_DEPTH || out.size > MAX_NODES) return
        out.add(n)
        for (i in 0 until n.childCount) walk(n.getChild(i), out, depth + 1)
    }

    private fun collect(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        for (r in roots()) walk(r, out, 0)
        return out
    }

    private fun describe(n: AccessibilityNodeInfo): String {
        val r = Rect()
        n.getBoundsInScreen(r)
        val id = n.viewIdResourceName?.substringAfterLast('/') ?: ""
        val txt = n.text?.toString()?.replace('\n', ' ') ?: ""
        val desc = n.contentDescription?.toString()?.replace('\n', ' ') ?: ""
        val cls = n.className?.toString()?.substringAfterLast('.') ?: ""
        return "$cls id=$id txt=\"$txt\" desc=\"$desc\" klik=${n.isClickable} " +
            "${r.left},${r.top}-${r.right},${r.bottom}"
    }

    // ------------------------------------------------------------ hladanie X

    private class Candidate(val node: AccessibilityNodeInfo, val score: Int, val why: String)

    private fun findCandidates(nodes: List<AccessibilityNodeInfo>): List<Candidate> {
        val screenW = resources.displayMetrics.widthPixels
        val byShape = on("shape", true)
        val out = ArrayList<Candidate>()

        for (n in nodes) {
            val r = Rect()
            n.getBoundsInScreen(r)
            val w = r.width()
            val h = r.height()
            if (w <= 0 || h <= 0) continue

            val id = (n.viewIdResourceName ?: "").lowercase()
            val desc = norm(n.contentDescription?.toString() ?: "")
            val txt = norm(n.text?.toString() ?: "")

            var score = 0
            val why = StringBuilder()

            if (CLOSE_WORDS.any { id.contains(it) }) {
                score += 60; why.append("id ")
            }
            if (desc.isNotEmpty() && CLOSE_WORDS.any { desc == it || desc.contains(it) }) {
                score += 55; why.append("popis ")
            }
            if (txt == "x" || txt == "×" || txt == "✕" || desc == "x") {
                score += 40; why.append("znak ")
            }

            if (byShape && score == 0 && n.isClickable) {
                val ratio = w.toFloat() / h.toFloat()
                val sq = ratio in 0.7f..1.45f
                val small = w in 50..260 && h in 50..260
                val right = (r.left + r.right) / 2 > screenW * 0.55
                val noText = txt.isEmpty() && desc.isEmpty()
                if (sq && small && right && noText) {
                    score += 30; why.append("tvar ")
                }
            }

            if (score == 0) continue
            if (n.isClickable) { score += 20; why.append("klikatelny ") }
            if (n.isEnabled) score += 5

            out.add(Candidate(n, score, why.toString().trim()))
        }
        return out.sortedByDescending { it.score }
    }

    private fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.lowercase()) {
            sb.append(
                when (c) {
                    'á', 'ä', 'à', 'â' -> 'a'
                    'č', 'ć' -> 'c'
                    'ď' -> 'd'
                    'é', 'ě', 'è' -> 'e'
                    'í', 'ì' -> 'i'
                    'ĺ', 'ľ' -> 'l'
                    'ň' -> 'n'
                    'ó', 'ô', 'ö' -> 'o'
                    'ŕ', 'ř' -> 'r'
                    'š', 'ś' -> 's'
                    'ť' -> 't'
                    'ú', 'ů', 'ü' -> 'u'
                    'ý' -> 'y'
                    'ž', 'ź' -> 'z'
                    else -> c
                }
            )
        }
        return sb.toString().trim()
    }

    // ------------------------------------------------------------ hlavny beh

    private fun scan(pkg: String) {
        if (busy) return
        val nodes = collect()
        if (nodes.isEmpty()) return

        val cands = findCandidates(nodes)
        val sig = pkg + "|" + nodes.size + "|" + cands.size + "|" +
            (cands.firstOrNull()?.let { describe(it.node) } ?: "")
        if (sig == lastDumpSig) return
        lastDumpSig = sig

        XLog.add("--- $pkg · uzlov: ${nodes.size} · kandidátov na X: ${cands.size} ---")

        if (on("dump", true)) {
            var printed = 0
            for (n in nodes) {
                val hasInfo = !n.text.isNullOrBlank() ||
                    !n.contentDescription.isNullOrBlank() ||
                    n.isClickable
                if (!hasInfo) continue
                XLog.raw("   " + describe(n))
                if (++printed >= MAX_DUMP_LINES) {
                    XLog.raw("   ... (skrátené)")
                    break
                }
            }
        }

        if (cands.isEmpty()) {
            XLog.add("X nenájdené")
            return
        }

        cands.take(3).forEachIndexed { i, c ->
            XLog.add("kandidát ${i + 1}: skóre=${c.score} (${c.why}) ${describe(c.node)}")
        }

        if (!on("click", false)) {
            XLog.add("klikanie vypnuté")
            return
        }
        if (SystemClock.uptimeMillis() - lastActionAt < COOLDOWN_MS) return

        lastActionAt = SystemClock.uptimeMillis()
        busy = true
        click(cands[0].node, 1)
    }

    private fun click(node: AccessibilityNodeInfo, step: Int) {
        when (step) {
            1 -> {
                val ok = clickTree(node)
                XLog.add("1) klik na prvok: ${if (ok) "odoslaný" else "neúspešný"}")
                if (ok) verify(node, step, 400L) else click(node, 2)
            }
            2 -> {
                gesture(node) { ok ->
                    XLog.add("2) gesto: ${if (ok) "dokončené" else "zlyhalo"}")
                    if (ok) verify(node, step, 450L) else done(false, "gesto zlyhalo")
                }
            }
            else -> done(false, "vyčerpané možnosti")
        }
    }

    private fun verify(node: AccessibilityNodeInfo, step: Int, delay: Long) {
        main.postDelayed({
            val gone = try {
                !node.refresh()
            } catch (_: Throwable) {
                true
            }
            if (gone) done(true, "zatvorené krokom $step")
            else {
                XLog.add("krok $step neúčinný – prvok je stále tam")
                click(node, step + 1)
            }
        }, delay)
    }

    private fun done(success: Boolean, msg: String) {
        busy = false
        lastDumpSig = ""
        XLog.add(if (success) "HOTOVO – $msg" else "NEPODARILO SA – $msg")
    }

    private fun clickTree(node: AccessibilityNodeInfo): Boolean {
        try {
            node.refresh()
        } catch (_: Throwable) {
        }
        var n: AccessibilityNodeInfo? = node
        var hop = 0
        while (n != null && hop < 6) {
            if (n.isClickable && n.isEnabled &&
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) return true
            n = try {
                n.parent
            } catch (_: Throwable) {
                null
            }
            hop++
        }
        return false
    }

    private fun gesture(node: AccessibilityNodeInfo, cb: (Boolean) -> Unit) {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) {
            cb(false)
            return
        }
        val x = r.exactCenterX()
        val y = r.exactCenterY()
        XLog.add("gesto na ${x.toInt()},${y.toInt()}")
        val path = Path().apply { moveTo(x, y) }
        val gd = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 120))
            .build()
        val sent = try {
            dispatchGesture(gd, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) = cb(true)
                override fun onCancelled(d: GestureDescription?) = cb(false)
            }, null)
        } catch (t: Throwable) {
            XLog.add("dispatchGesture: ${t.message}")
            false
        }
        if (!sent) cb(false)
    }
}
