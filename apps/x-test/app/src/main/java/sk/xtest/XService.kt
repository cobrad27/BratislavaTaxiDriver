package sk.xtest

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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
        private const val OCR_INTERVAL_MS = 1000L
        private const val MAX_NODES = 2000
        private const val MAX_DEPTH = 80
        private const val MAX_DUMP_LINES = 150
        private const val DARK = 90

        /** Tlacidlo prijatia - musi sediet presne na cely text. */
        private val ACCEPT = setOf("zhoda", "prijat", "accept", "match", "prijmout")

        /** Dalsie znaky karty ponuky v strome. */
        private val INFO = listOf(
            "uberx", "uber x", "comfort", "vzdialenost", "platba v", "platba kartou", "hotovost"
        )

        /** Stitok typu jazdy - v jeho riadku je vpravo X. */
        private val BADGE = listOf("uberx", "uber x", "comfort", "uber green", "uber black", "uberxl")

        private val CLOSE_WORDS = listOf(
            "close", "dismiss", "decline", "reject", "zavriet", "odmietnut", "zavri", "ic_close", "btn_close"
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private var scheduled = false
    private var busy = false
    private var lastActionAt = 0L
    private var lastOcrAt = 0L
    private var lastSig = ""
    private var lastEvent = ""
    private var lastOcrNote = ""
    private val ocrTaps = ArrayDeque<Long>()

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private fun prefs() = getSharedPreferences("xtest", Context.MODE_PRIVATE)
    private fun on(key: String, def: Boolean) = prefs().getBoolean(key, def)

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        instance = this
        XLog.add("Služba pripojená (v1.1). X hľadám v strome aj na snímke obrazovky.")
        if (Build.VERSION.SDK_INT < 30) XLog.add("Android pod 11 – snímka obrazovky nie je k dispozícii.")
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
        if (!on("enabled", true)) return
        val pkg = event.packageName?.toString()
        if (!watched(pkg)) return
        logEvent(event, pkg ?: "")
        if (busy) return
        scheduleScan()
    }

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

    // ------------------------------------------------------------ ponuka v strome

    private class Offer(val accept: AccessibilityNodeInfo?, val card: Rect, val words: Set<String>)

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
        val changed = sig != lastSig
        if (changed) {
            lastSig = sig
            if (on("dump", true)) XLog.putDump(buildDump(snap, offer), offer != null)
        }

        if (offer == null || cands.isEmpty()) {
            if (changed) {
                XLog.add(
                    if (offer == null) "v strome ponuka nie je · uzlov ${snap.nodes.size} · skúšam snímku"
                    else "PONUKA v strome (${offer.words.joinToString()}), ale X nie · skúšam snímku"
                )
            }
            ocrFallback()
            return
        }
        if (!changed) return

        XLog.add(
            "PONUKA v strome: ${offer.words.joinToString()} · karta ${offer.card.toShortString()} · " +
                "kandidátov na X: ${cands.size}"
        )
        cands.take(3).forEachIndexed { i, c ->
            XLog.add("  X kandidát ${i + 1}: skóre=${c.score} (${c.why}) ${describe(c.node)}")
        }
        if (!on("click", true)) {
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
            2 -> {
                val r = Rect()
                try {
                    node.refresh()
                } catch (_: Throwable) {
                }
                node.getBoundsInScreen(r)
                if (r.isEmpty) {
                    done(false, "prvok nemá rozmery")
                    return
                }
                XLog.add("gesto na ${r.centerX()},${r.centerY()}")
                tapAt(r.exactCenterX(), r.exactCenterY()) { ok ->
                    XLog.add("2) gesto: ${if (ok) "dokončené" else "zlyhalo"}")
                    if (ok) verify(node, 2, 600L) else done(false, "gesto zlyhalo")
                }
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

    private fun tapAt(x: Float, y: Float, cb: (Boolean) -> Unit) {
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

    // ------------------------------------------------------------ snimka obrazovky + OCR

    private fun ocrNote(s: String) {
        if (s == lastOcrNote) return
        lastOcrNote = s
        XLog.add(s)
    }

    private fun ocrFallback() {
        if (busy) return
        if (Build.VERSION.SDK_INT < 30) return
        val now = SystemClock.uptimeMillis()
        if (now - lastOcrAt < OCR_INTERVAL_MS) return
        lastOcrAt = now
        busy = true
        shoot()
    }

    @RequiresApi(30)
    private fun shoot() {
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(res: ScreenshotResult) {
                    val bmp = try {
                        Bitmap.wrapHardwareBuffer(res.hardwareBuffer, res.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (_: Throwable) {
                        null
                    } finally {
                        res.hardwareBuffer.close()
                    }
                    if (bmp == null) {
                        ocrNote("snímku sa nepodarilo prečítať")
                        busy = false
                        return
                    }
                    recognize(bmp)
                }

                override fun onFailure(errorCode: Int) {
                    ocrNote("snímka obrazovky zlyhala: kód $errorCode")
                    busy = false
                }
            })
        } catch (t: Throwable) {
            ocrNote("takeScreenshot: ${t.message}")
            busy = false
        }
    }

    private fun recognize(bmp: Bitmap) {
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                try {
                    analyze(text, bmp)
                } catch (t: Throwable) {
                    ocrNote("analýza snímky: ${t.message}")
                    busy = false
                }
            }
            .addOnFailureListener { e ->
                ocrNote("čítanie textu zlyhalo: ${e.message}")
                busy = false
            }
    }

    private fun analyze(text: Text, bmp: Bitmap) {
        var accept: Rect? = null
        var badge: Rect? = null
        val seen = ArrayList<String>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val b = line.boundingBox ?: continue
                val t = norm(line.text)
                if (seen.size < 12) seen.add(line.text)
                if (accept == null && t in ACCEPT) accept = b
                if (badge == null && BADGE.any { t.contains(it) }) badge = b
            }
        }
        val a = accept
        val bd = badge
        if (a == null || bd == null || a.top <= bd.bottom) {
            ocrNote("snímka: ponuka nie je · " + seen.joinToString(" | ").take(220))
            busy = false
            return
        }

        val x = locateX(bmp, bd, a)
        if (x == null) {
            XLog.add("snímka: PONUKA (štítok ${bd.toShortString()}), ale krížik X v obraze nenájdený")
            busy = false
            return
        }
        XLog.add(
            "snímka: PONUKA · štítok ${bd.toShortString()} · prijať ${a.toShortString()} · " +
                "X na ${x.first},${x.second}"
        )
        if (!on("click", true)) {
            XLog.add("klikanie vypnuté – neklikám")
            busy = false
            return
        }

        val now = SystemClock.uptimeMillis()
        while (ocrTaps.isNotEmpty() && now - ocrTaps.first() > 10_000) ocrTaps.removeFirst()
        if (ocrTaps.size >= 3) {
            XLog.add("3 ťuknutia za 10 s a ponuka stále svieti – chvíľu čakám")
            busy = false
            return
        }
        ocrTaps.addLast(now)

        tapAt(x.first.toFloat(), x.second.toFloat()) { ok ->
            XLog.add("ťuk na X zo snímky: ${if (ok) "dokončený" else "zlyhal"}")
            // Daj ponuke cas zmiznut, potom sa overi dalsou snimkou.
            lastOcrAt = SystemClock.uptimeMillis() + 300
            lastOcrNote = ""
            busy = false
        }
    }

    private fun lum(p: Int) = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000

    /**
     * X je v riadku stitku (UberX) vpravo. Od konca stitku ide doprava po bielej karte
     * az po prvy tmavy bod - to je ciara krizika. Stred sa dopocita z oboch ciar.
     */
    private fun locateX(bmp: Bitmap, badge: Rect, accept: Rect): Pair<Int, Int>? {
        val h = badge.height().coerceAtLeast(10)
        val y0 = badge.centerY().coerceIn(0, bmp.height - 1)
        val start = (badge.right + h * 2).coerceIn(0, bmp.width - 1)
        val limit = (2 * accept.centerX() - badge.left + h * 3).coerceIn(start, bmp.width - 1)

        var xs = -1
        for (x in start..limit) {
            if (lum(bmp.getPixel(x, y0)) < DARK) {
                xs = x
                break
            }
        }
        if (xs < 0) return null

        var maxX = xs
        val xEnd = (xs + 2 * h).coerceAtMost(bmp.width - 1)
        for (x in xs..xEnd) if (lum(bmp.getPixel(x, y0)) < DARK) maxX = x
        val xc = (xs + maxX) / 2

        var minY = -1
        var maxY = -1
        val yFrom = (badge.top - h).coerceAtLeast(0)
        val yTo = (badge.bottom + 2 * h).coerceAtMost(bmp.height - 1)
        for (y in yFrom..yTo) {
            if (lum(bmp.getPixel(xc, y)) < DARK) {
                if (minY < 0) minY = y
                maxY = y
            }
        }
        val yc = if (minY >= 0) (minY + maxY) / 2 else y0
        return Pair(xc, yc)
    }
}
