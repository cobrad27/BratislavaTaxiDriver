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
import android.view.accessibility.AccessibilityWindowInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

class XService : AccessibilityService() {

    companion object {
        @Volatile
        var running = false
            private set

        @Volatile
        var instance: XService? = null
            private set

        private const val UBER = "com.ubercab.driver"
        private const val DEBOUNCE_MS = 150L
        private const val COOLDOWN_MS = 2000L
        private const val MAX_NODES = 2000
        private const val MAX_DEPTH = 80
        private const val MAX_DUMP_LINES = 150

        /** Tlacidlo prijatia - musi sediet presne na cely text. */
        private val ACCEPT = setOf("zhoda", "prijat", "accept", "match", "prijmout")

        /** Dalsie znaky karty ponuky. */
        private val INFO = listOf(
            "uberx", "uber x", "comfort", "vzdialenost", "platba v", "platba kartou", "hotovost"
        )

        private val CLOSE_WORDS = listOf(
            "close", "dismiss", "decline", "reject", "zavriet", "odmietnut", "zavri", "ic_close", "btn_close"
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private var scheduled = false
    private var busy = false
    private var lastActionAt = 0L
    private var lastSig = ""
    private var lastEvent = ""

    private fun prefs() = getSharedPreferences("xtest", Context.MODE_PRIVATE)
    private fun on(key: String, def: Boolean) = prefs().getBoolean(key, def)

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        instance = this
        XLog.add("Služba pripojená (v1.1). Klikám len keď vidím ponuku.")
    }

    override fun onDestroy() {
        running = false
        instance = null
        XLog.add("Služba odpojená.")
        super.onDestroy()
    }

    override fun onInterrupt() {}

    private fun watched(pkg: String?): Boolean =
        pkg != null && pkg != packageName && (pkg == UBER || on("allApps", false))

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!on("enabled", false)) return
        val pkg = event.packageName?.toString()
        if (!watched(pkg)) return
        logEvent(event, pkg ?: "")
        if (busy) return
        scheduleScan()
    }

    /** Udalosti okien loguje vzdy - ak Uber kartu neukaze v strome, aspon tu bude jej text. */
    private fun logEvent(e: AccessibilityEvent, pkg: String) {
        val t = e.eventType
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            t != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
            t != AccessibilityEvent.TYPE_ANNOUNCEMENT
        ) return
        val txt = e.text.joinToString(" | ") { it.toString() }.replace('\n', ' ')
        val line = "UDALOSŤ ${AccessibilityEvent.eventTypeToString(t)} $pkg " +
            "${e.className ?: ""} \"$txt\" ${e.contentDescription ?: ""}"
        if (line == lastEvent) return
        lastEvent = line
        XLog.add(line)
    }

    private fun scheduleScan() {
        if (scheduled) return
        scheduled = true
        main.postDelayed({
            scheduled = false
            try {
                scan()
            } catch (t: Throwable) {
                XLog.add("CHYBA: ${t.message}")
                busy = false
            }
        }, DEBOUNCE_MS)
    }

    // ------------------------------------------------------------ zber

    private class Snap(val nodes: List<AccessibilityNodeInfo>, val windowsInfo: List<String>)

    private fun winType(t: Int) = when (t) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "klavesnica"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "a11y"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "delic"
        else -> t.toString()
    }

    private fun snapshot(): Snap {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val info = ArrayList<String>()
        try {
            for (w in windows) {
                val r = w.root
                val pkg = r?.packageName?.toString()
                val b = Rect()
                w.getBoundsInScreen(b)
                info.add(
                    "okno typ=${winType(w.type)} vrstva=${w.layer} pkg=${pkg ?: "?"} " +
                        "titul=\"${w.title ?: ""}\" ${b.left},${b.top}-${b.right},${b.bottom}"
                )
                if (r != null && watched(pkg)) walk(r, nodes, 0)
            }
        } catch (_: Throwable) {
        }
        if (nodes.isEmpty()) {
            rootInActiveWindow?.let { if (watched(it.packageName?.toString())) walk(it, nodes, 0) }
        }
        return Snap(nodes, info)
    }

    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (n == null || depth > MAX_DEPTH || out.size > MAX_NODES) return
        out.add(n)
        for (i in 0 until n.childCount) walk(n.getChild(i), out, depth + 1)
    }

    private fun describe(n: AccessibilityNodeInfo): String {
        val r = Rect()
        n.getBoundsInScreen(r)
        val id = n.viewIdResourceName?.substringAfterLast('/') ?: ""
        val txt = (n.text?.toString() ?: "").replace('\n', ' ').take(80)
        val desc = (n.contentDescription?.toString() ?: "").replace('\n', ' ').take(80)
        val cls = n.className?.toString()?.substringAfterLast('.') ?: ""
        return "$cls id=$id txt=\"$txt\" desc=\"$desc\" klik=${n.isClickable} " +
            "${r.left},${r.top}-${r.right},${r.bottom}"
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

    // ------------------------------------------------------------ ponuka

    private class Offer(
        val accept: AccessibilityNodeInfo?,
        val card: Rect,
        val words: Set<String>
    )

    private fun findOffer(nodes: List<AccessibilityNodeInfo>): Offer? {
        var accept: AccessibilityNodeInfo? = null
        val anchors = ArrayList<AccessibilityNodeInfo>()
        val words = HashSet<String>()

        for (n in nodes) {
            val t = norm(n.text?.toString() ?: "")
            val d = norm(n.contentDescription?.toString() ?: "")
            if (accept == null && (t in ACCEPT || d in ACCEPT)) {
                accept = n
                anchors.add(n)
                words.add(if (t in ACCEPT) t else d)
                continue
            }
            val both = "$t $d"
            val hit = INFO.firstOrNull { both.contains(it) }
            if (hit != null) {
                anchors.add(n)
                words.add(hit)
            }
        }
        // Bez tlacidla prijatia treba aspon dva rozne znaky karty.
        if (accept == null && words.size < 2) return null

        val card = Rect()
        var first = true
        for (a in anchors) {
            val r = Rect()
            a.getBoundsInScreen(r)
            if (r.isEmpty) continue
            if (first) {
                card.set(r)
                first = false
            } else card.union(r)
        }

        // Rozsir na rodica, ktory obsahuje vsetky kotvy - to je karta.
        val minH = if (anchors.size == 1) card.height() * 3 else card.height()
        val screenH = resources.displayMetrics.heightPixels
        var p: AccessibilityNodeInfo? = try {
            (accept ?: anchors.first()).parent
        } catch (_: Throwable) {
            null
        }
        var hops = 0
        while (p != null && hops < 10) {
            val pr = Rect()
            p.getBoundsInScreen(pr)
            if (pr.contains(card) && pr.height() >= minH && pr.height() < screenH * 0.95) {
                card.set(pr)
                break
            }
            p = try {
                p.parent
            } catch (_: Throwable) {
                null
            }
            hops++
        }
        return Offer(accept, card, words)
    }

    // ------------------------------------------------------------ X v karte

    private class Cand(val node: AccessibilityNodeInfo, val score: Int, val why: String)

    private fun findX(nodes: List<AccessibilityNodeInfo>, o: Offer): List<Cand> {
        val out = ArrayList<Cand>()
        val c = o.card
        val shape = on("shape", true)

        for (n in nodes) {
            if (o.accept != null && n == o.accept) continue
            val r = Rect()
            n.getBoundsInScreen(r)
            val w = r.width()
            val h = r.height()
            if (w <= 0 || h <= 0) continue

            val id = (n.viewIdResourceName ?: "").lowercase()
            val t = norm(n.text?.toString() ?: "")
            val d = norm(n.contentDescription?.toString() ?: "")

            var score = 0
            val why = StringBuilder()
            if (CLOSE_WORDS.any { id.contains(it) }) {
                score += 60; why.append("id ")
            }
            if (d.isNotEmpty() && CLOSE_WORDS.any { d.contains(it) }) {
                score += 60; why.append("popis ")
            }
            if (t == "x" || t == "×" || t == "✕" || d == "x") {
                score += 50; why.append("znak ")
            }

            val cx = r.exactCenterX()
            val cy = r.exactCenterY()
            val inCard = cx >= c.left - 40 && cx <= c.right + 40 && cy >= c.top - 40 && cy <= c.bottom + 40
            val topRight = inCard && cx > c.exactCenterX() && cy < c.top + c.height() * 0.35f
            val ratio = w.toFloat() / h.toFloat()
            val squareSmall = w in 40..320 && h in 40..320 && ratio in 0.6f..1.6f

            if (shape && topRight && squareSmall && t.isEmpty()) {
                score += 40; why.append("tvar-v-rohu-karty ")
            }
            if (score == 0) continue
            // Mimo karty len ak ma jasny nazov.
            if (!inCard && score < 60) continue
            if (inCard) {
                score += 15; why.append("v-karte ")
            }
            val dist = hypot((c.right - cx).toDouble(), (cy - c.top).toDouble())
            score += (30 - (dist / 20).toInt()).coerceIn(0, 30)
            if (clickTarget(n) != null) {
                score += 20; why.append("klik ")
            }
            out.add(Cand(n, score, why.toString().trim()))
        }
        return out.sortedByDescending { it.score }
    }

    /** Klikatelny prvok alebo jeho maly klikatelny rodic - nikdy nie cela karta. */
    private fun clickTarget(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var p: AccessibilityNodeInfo? = n
        var hop = 0
        while (p != null && hop < 4) {
            if (p.isClickable && p.isEnabled) {
                val r = Rect()
                p.getBoundsInScreen(r)
                return if (r.width() <= 400 && r.height() <= 400) p else null
            }
            p = try {
                p.parent
            } catch (_: Throwable) {
                null
            }
            hop++
        }
        return null
    }

    // ------------------------------------------------------------ beh

    private fun buildDump(s: Snap, o: Offer?): String = buildString {
        append(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()))
        append(if (o != null) " · PONUKA" else " · bez ponuky")
        append(" · uzlov ").append(s.nodes.size).append('\n')
        s.windowsInfo.forEach { append("  ").append(it).append('\n') }
        var printed = 0
        for (node in s.nodes) {
            if (node.text.isNullOrBlank() && node.contentDescription.isNullOrBlank() && !node.isClickable) continue
            append("   ").append(describe(node)).append('\n')
            if (++printed >= MAX_DUMP_LINES) {
                append("   ... (skrátené)\n")
                break
            }
        }
    }

    private fun scan() {
        if (busy) return
        val snap = snapshot()
        if (snap.nodes.isEmpty()) return

        val offer = findOffer(snap.nodes)
        val cands = if (offer != null) findX(snap.nodes, offer) else emptyList()
        val sig = (offer?.words?.sorted()?.joinToString(",") ?: "-") + "|" + snap.nodes.size + "|" +
            (cands.firstOrNull()?.let { describe(it.node) } ?: "")
        if (sig == lastSig) return
        lastSig = sig

        if (on("dump", true)) XLog.putDump(buildDump(snap, offer), offer != null)

        if (offer == null) {
            XLog.add("bez ponuky · uzlov ${snap.nodes.size}")
            return
        }

        XLog.add(
            "PONUKA: ${offer.words.joinToString()} · karta ${offer.card.toShortString()} · " +
                "kandidátov na X: ${cands.size}"
        )
        cands.take(3).forEachIndexed { i, c ->
            XLog.add("  X kandidát ${i + 1}: skóre=${c.score} (${c.why}) ${describe(c.node)}")
        }
        if (cands.isEmpty()) {
            XLog.add("X v karte nenájdené")
            return
        }
        if (!on("click", false)) {
            XLog.add("klikanie vypnuté – neklikám")
            return
        }
        if (SystemClock.uptimeMillis() - lastActionAt < COOLDOWN_MS) return

        lastActionAt = SystemClock.uptimeMillis()
        busy = true
        click(cands[0].node, 1)
    }

    private fun offerGone(): Boolean = try {
        findOffer(snapshot().nodes) == null
    } catch (_: Throwable) {
        true
    }

    private fun click(node: AccessibilityNodeInfo, step: Int) {
        when (step) {
            1 -> {
                val target = clickTarget(node)
                val ok = try {
                    target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                } catch (_: Throwable) {
                    false
                }
                XLog.add("1) klik na prvok: ${if (ok) "odoslaný" else "nedá sa"}")
                if (ok) verify(node, 1, 500L) else click(node, 2)
            }
            2 -> gesture(node) { ok ->
                XLog.add("2) gesto: ${if (ok) "dokončené" else "zlyhalo"}")
                if (ok) verify(node, 2, 600L) else done(false, "gesto zlyhalo")
            }
            else -> done(false, "ponuka sa nezavrela")
        }
    }

    private fun verify(node: AccessibilityNodeInfo, step: Int, delay: Long) {
        main.postDelayed({
            if (offerGone()) {
                done(true, "ponuka zavretá krokom $step")
            } else {
                XLog.add("krok $step: ponuka je stále na obrazovke")
                click(node, step + 1)
            }
        }, delay)
    }

    private fun done(success: Boolean, msg: String) {
        busy = false
        lastSig = ""
        XLog.add(if (success) "HOTOVO – $msg" else "NEPODARILO SA – $msg")
    }

    private fun gesture(node: AccessibilityNodeInfo, cb: (Boolean) -> Unit) {
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
