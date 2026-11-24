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

/**
 * Uber ukazuje ponuku vo vlastnom plavajucom okne ako jeden CardView bez textov.
 * Postup: najst kartu v strome -> snimka obrazovky -> tmavy krizik v pravom hornom
 * rohu karty -> tuk na jeho stred -> overit, ze karta zmizla.
 * Na samotnu kartu sa nikdy neklika (otvorila by ponuku).
 */
class XService : AccessibilityService() {

    companion object {
        @Volatile
        var running = false
            private set

        @Volatile
        var instance: XService? = null
            private set

        private const val UBER = "com.ubercab.driver"
        private const val DEBOUNCE_MS = 120L
        private const val IDLE_OCR_MS = 2500L
        private const val MAX_NODES = 2000
        private const val MAX_DEPTH = 80
        private const val MAX_DUMP_LINES = 150
        private const val MAX_TRIES = 3
        private const val DARK = 90

        /** Odhad polohy X od praveho horneho rohu karty, ak sa krizik na snimke nenajde. */
        private const val X_FROM_RIGHT = 102
        private const val X_FROM_TOP = 103

        private val ACCEPT = setOf("zhoda", "prijat", "accept", "match", "prijmout")
        private val BADGE = listOf("uberx", "uber x", "comfort", "uber green", "uber black", "uberxl")
        private val KM_RE = Regex("""\d+[.,]?\d*\s*km""")
    }

    private val main = Handler(Looper.getMainLooper())
    private var scheduled = false
    private var busy = false
    private var lastSig = ""
    private var lastEvent = ""
    private var lastOcrAt = 0L
    private var lastTapAt = 0L
    private var pauseUntil = 0L
    private var cardKey = ""
    private var tries = 0

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private fun prefs() = getSharedPreferences("xtest", Context.MODE_PRIVATE)
    private fun on(key: String, def: Boolean) = prefs().getBoolean(key, def)

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        instance = this
        XLog.add("Služba pripojená (v1.2). Kartu ponuky hľadám v plávajúcom okne Uberu.")
        if (Build.VERSION.SDK_INT < 30) XLog.add("Android pod 11 – bez snímky, ťukám na odhadovanú pozíciu X.")
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
            t != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
        ) return
        val txt = e.text.joinToString(" | ") { it.toString() }.replace('\n', ' ')
        val line = "UDALOSŤ ${AccessibilityEvent.eventTypeToString(t)} $pkg ${e.className ?: ""} \"$txt\""
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

    // ------------------------------------------------------------ strom a okna

    private class Card(val rect: Rect, val winType: Int)

    private class Snap(
        val nodes: List<AccessibilityNodeInfo>,
        val windowsInfo: List<String>,
        val cards: List<Card>
    )

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
        val cards = ArrayList<Card>()
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
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
                if (r == null || !watched(pkg)) continue
                val start = nodes.size
                walk(r, nodes, 0)
                for (i in start until nodes.size) {
                    val n = nodes[i]
                    if (isCard(n, w.type, sw, sh)) {
                        val rr = Rect()
                        n.getBoundsInScreen(rr)
                        cards.add(Card(rr, w.type))
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return Snap(nodes, info, cards)
    }

    /** Karta ponuky: siroka, v dolnej casti, CardView alebo klikatelny prvok plavajuceho okna. */
    private fun isCard(n: AccessibilityNodeInfo, type: Int, sw: Int, sh: Int): Boolean {
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.width() < sw * 0.7f) return false
        if (r.height() < sh * 0.12f || r.height() > sh * 0.6f) return false
        if (r.top < sh * 0.3f) return false
        val cls = n.className?.toString() ?: ""
        return cls.contains("Card") || (type == AccessibilityWindowInfo.TYPE_SYSTEM && n.isClickable)
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

    private fun buildDump(s: Snap): String = buildString {
        append(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()))
        append(if (s.cards.isNotEmpty()) " · KARTA" else " · bez karty")
        append(" · uzlov ").append(s.nodes.size).append('\n')
        s.windowsInfo.forEach { append("  ").append(it).append('\n') }
        s.cards.forEach { append("  karta ").append(it.rect.toShortString()).append('\n') }
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

    private fun scan() {
        if (busy) return
        val snap = snapshot()
        val sig = "${snap.nodes.size}|" + snap.cards.joinToString { it.rect.toShortString() }
        if (sig != lastSig) {
            lastSig = sig
            if (on("dump", true)) XLog.putDump(buildDump(snap), snap.cards.isNotEmpty())
        }
        if (SystemClock.uptimeMillis() < pauseUntil) return

        val card = snap.cards.firstOrNull()
        if (card != null) {
            onCard(card)
            return
        }
        if (cardKey.isNotEmpty()) {
            XLog.add("karta zmizla")
            cardKey = ""
            tries = 0
        }
        idleOcr()
    }

    private fun onCard(card: Card) {
        val key = card.rect.toShortString()
        if (key != cardKey) {
            cardKey = key
            tries = 0
            XLog.add("KARTA ponuky $key (okno ${winType(card.winType)})")
        }
        if (tries >= MAX_TRIES) return
        if (SystemClock.uptimeMillis() - lastTapAt < 900) return

        busy = true
        val trusted = card.winType == AccessibilityWindowInfo.TYPE_SYSTEM
        shoot { bmp ->
            if (bmp == null || trusted) {
                tapX(card.rect, bmp)
            } else {
                // Karta v beznom okne - over textom, ze je to naozaj ponuka.
                recognizer.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { text ->
                        if (confirms(text, card.rect)) {
                            tapX(card.rect, bmp)
                        } else {
                            XLog.add("karta $key nie je ponuka (text nesedí) – nechávam")
                            tries = MAX_TRIES
                            busy = false
                        }
                    }
                    .addOnFailureListener {
                        XLog.add("čítanie textu zlyhalo: ${it.message}")
                        busy = false
                    }
            }
        }
    }

    private fun confirms(text: Text, rect: Rect): Boolean {
        val area = Rect(rect.left - 40, rect.top - 40, rect.right + 40, rect.bottom + 40)
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val b = line.boundingBox ?: continue
                if (!area.contains(b.centerX(), b.centerY())) continue
                val t = norm(line.text)
                if (t in ACCEPT || BADGE.any { t.contains(it) } || t.contains("vzdialenost") ||
                    KM_RE.containsMatchIn(t)
                ) return true
            }
        }
        return false
    }

    private fun tapX(rect: Rect, bmp: Bitmap?) {
        val found = bmp?.let { crossInCorner(it, rect) }
        val how = if (found != null) "krížik nájdený na snímke" else "odhad podľa karty"
        val p = found ?: Pair(rect.right - X_FROM_RIGHT, rect.top + X_FROM_TOP)

        if (!on("click", true)) {
            XLog.add("X na ${p.first},${p.second} ($how) – klikanie vypnuté")
            tries = MAX_TRIES
            busy = false
            return
        }

        tries++
        lastTapAt = SystemClock.uptimeMillis()
        XLog.add("ťukám na X ${p.first},${p.second} ($how) · pokus $tries/$MAX_TRIES")
        tapAt(p.first.toFloat(), p.second.toFloat()) { ok ->
            if (!ok) XLog.add("gesto zlyhalo")
            main.postDelayed({ verifyCard() }, 700L)
        }
    }

    private fun verifyCard() {
        val still = try {
            snapshot().cards.any { it.rect.toShortString() == cardKey }
        } catch (_: Throwable) {
            false
        }
        val now = SystemClock.uptimeMillis()
        when {
            !still -> {
                XLog.add("HOTOVO – ponuka zavretá (pokus $tries)")
                cardKey = ""
                tries = 0
                pauseUntil = now + 800
            }
            tries >= MAX_TRIES -> {
                XLog.add("NEPODARILO SA – karta svieti aj po $tries pokusoch")
                pauseUntil = now + 4000
            }
            else -> XLog.add("karta stále svieti – skúšam znova")
        }
        busy = false
        if (still && tries < MAX_TRIES) scheduleScan()
    }

    /**
     * Krizik X: tmave body v pravom hornom rohu karty. Karta je biela, tlacidlo X
     * svetlosive, takze tmavy je len samotny krizik. Vrati stred jeho obrysu.
     */
    private fun crossInCorner(bmp: Bitmap, c: Rect): Pair<Int, Int>? {
        val x0 = (c.right - 280).coerceAtLeast(c.centerX()).coerceIn(0, bmp.width - 1)
        val x1 = (c.right - 8).coerceIn(0, bmp.width - 1)
        val y0 = (c.top + 8).coerceIn(0, bmp.height - 1)
        val y1 = (c.top + 280).coerceAtMost(c.bottom).coerceIn(0, bmp.height - 1)
        if (x1 <= x0 || y1 <= y0) return null

        var minX = Int.MAX_VALUE
        var maxX = -1
        var minY = Int.MAX_VALUE
        var maxY = -1
        var count = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                if (lum(bmp.getPixel(x, y)) < DARK) {
                    count++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                x += 2
            }
            y += 2
        }
        if (count < 8) return null
        val w = maxX - minX
        val h = maxY - minY
        if (w < 10 || h < 10 || w > 160 || h > 160) return null
        return Pair((minX + maxX) / 2, (minY + maxY) / 2)
    }

    private fun lum(p: Int) = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000

    // ------------------------------------------------------------ zaloha: ponuka bez karty v strome

    private fun idleOcr() {
        if (Build.VERSION.SDK_INT < 30) return
        val now = SystemClock.uptimeMillis()
        if (now - lastOcrAt < IDLE_OCR_MS) return
        lastOcrAt = now
        busy = true
        shoot { bmp ->
            if (bmp == null) {
                busy = false
                return@shoot
            }
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { text -> idleAnalyze(text, bmp) }
                .addOnFailureListener { busy = false }
        }
    }

    private fun idleAnalyze(text: Text, bmp: Bitmap) {
        var accept: Rect? = null
        var badge: Rect? = null
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val b = line.boundingBox ?: continue
                val t = norm(line.text)
                if (accept == null && t in ACCEPT) accept = b
                if (badge == null && BADGE.any { t.contains(it) }) badge = b
            }
        }
        val a = accept
        val bd = badge
        if (a == null || bd == null || a.top <= bd.bottom) {
            busy = false
            return
        }
        val sw = bmp.width
        val rect = Rect(42, (bd.top - 70).coerceAtLeast(0), sw - 42, (a.bottom + 120).coerceAtMost(bmp.height))
        XLog.add("snímka: PONUKA bez karty v strome · odvodená karta ${rect.toShortString()}")
        cardKey = ""
        tries = 0
        tapX(rect, bmp)
    }

    // ------------------------------------------------------------ snimka a dotyk

    private fun shoot(cb: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) {
            cb(null)
            return
        }
        shoot30(cb)
    }

    @RequiresApi(30)
    private fun shoot30(cb: (Bitmap?) -> Unit) {
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
                    cb(bmp)
                }

                override fun onFailure(errorCode: Int) {
                    XLog.add("snímka zlyhala: kód $errorCode")
                    cb(null)
                }
            })
        } catch (t: Throwable) {
            XLog.add("takeScreenshot: ${t.message}")
            cb(null)
        }
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
}
