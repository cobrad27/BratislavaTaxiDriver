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
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Ponuka Uberu je obrazok bez textov - bud ako CardView v plavajucom okne,
 * alebo nakreslena priamo v okne Uberu (napr. pri rozdelenej obrazovke).
 *
 * Cesta A (strom): CardView v okne Uberu -> snimka -> krizik v rohu karty -> tuk -> karta zmizla?
 * Cesta B (snimka): text "UberX" + "Prijat/Zhoda" na snimke -> karta podla okna Uberu
 *                   -> krizik v rohu -> tuk -> nova snimka: ponuka zmizla?
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
        private const val IDLE_OCR_MS = 1200L
        private const val SETTLE_MS = 300L
        private const val VERIFY_MS = 700L
        private const val MAX_NODES = 2000
        private const val MAX_DEPTH = 80
        private const val MAX_DUMP_LINES = 150
        private const val MAX_TRIES = 3
        private const val DARK = 90

        /** Geometria karty v pixeloch (namerane zo snimok). */
        private const val CARD_MARGIN = 42
        private const val BADGE_TO_CARD_TOP = 62
        private const val X_FROM_RIGHT = 104
        private const val X_FROM_TOP = 100

        private val ACCEPT = setOf("zhoda", "prijat", "accept", "match", "prijmout")
        private val BADGE = listOf("uberx", "uber x", "comfort", "uber green", "uber black", "uberxl")
    }

    private val main = Handler(Looper.getMainLooper())
    private var scheduled = false
    private var busy = false
    private var lastSig = ""
    private var lastEvent = ""
    private var lastOcrAt = 0L
    private var pauseUntil = 0L
    private var targetKey = ""
    private var tries = 0
    private var uberWins: List<Rect> = emptyList()

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private fun prefs() = getSharedPreferences("xtest", Context.MODE_PRIVATE)
    private fun on(key: String, def: Boolean) = prefs().getBoolean(key, def)

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        instance = this
        XLog.add("Služba pripojená (v1.3). Kartu hľadám v okne Uberu aj na snímke.")
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
        // Nove okno Uberu = mozno ponuka: dalsia snimka hned, nie az po intervale.
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) lastOcrAt = 0L
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
        val cards: List<Card>,
        val uberWins: List<Rect>
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
        val wins = ArrayList<Rect>()
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
                // Male okna Uberu (bublina hore) nie su miesto pre kartu.
                if (b.width() > sw * 0.3f && b.height() > sh * 0.2f) wins.add(Rect(b))
                val start = nodes.size
                walk(r, nodes, 0)
                for (i in start until nodes.size) {
                    val n = nodes[i]
                    if (isCard(n, w.type, b)) {
                        val rr = Rect()
                        n.getBoundsInScreen(rr)
                        cards.add(Card(rr, w.type))
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return Snap(nodes, info, cards, wins)
    }

    /** Karta ponuky: takmer cela sirka svojho okna, v dolnej casti obrazovky. */
    private fun isCard(n: AccessibilityNodeInfo, type: Int, win: Rect): Boolean {
        val r = Rect()
        n.getBoundsInScreen(r)
        val sh = resources.displayMetrics.heightPixels
        if (r.width() < win.width() * 0.8f) return false
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
        uberWins = snap.uberWins
        val sig = "${snap.nodes.size}|" + snap.cards.joinToString { it.rect.toShortString() }
        if (sig != lastSig) {
            lastSig = sig
            if (on("dump", true)) XLog.putDump(buildDump(snap), snap.cards.isNotEmpty())
        }
        if (SystemClock.uptimeMillis() < pauseUntil) return

        val card = snap.cards.firstOrNull()
        if (card != null) {
            onTreeCard(card)
            return
        }
        if (targetKey.startsWith("strom")) {
            XLog.add("karta zmizla")
            targetKey = ""
            tries = 0
        }
        idleOcr()
    }

    private fun finishOrRetry(still: Boolean, retry: () -> Unit) {
        val now = SystemClock.uptimeMillis()
        when {
            !still -> {
                XLog.add("HOTOVO – ponuka zavretá (pokus $tries)")
                targetKey = ""
                tries = 0
                pauseUntil = now + 800
                lastOcrAt = now
                busy = false
            }
            tries >= MAX_TRIES -> {
                XLog.add("NEPODARILO SA – ponuka svieti aj po $tries pokusoch")
                pauseUntil = now + 4000
                busy = false
            }
            else -> {
                XLog.add("ponuka stále svieti – skúšam znova")
                retry()
            }
        }
    }

    // ---- cesta A: karta v strome

    private fun onTreeCard(card: Card) {
        val key = "strom " + card.rect.toShortString()
        if (key != targetKey) {
            targetKey = key
            tries = 0
            XLog.add("KARTA v strome ${card.rect.toShortString()} (okno ${winType(card.winType)})")
        }
        if (tries >= MAX_TRIES) return
        busy = true
        // Karta vychadza s animaciou - pockaj, kym dosadne, az potom snimka.
        main.postDelayed({
            shoot { bmp -> tapX(card.rect, bmp) { verifyTree(key) } }
        }, SETTLE_MS)
    }

    private fun verifyTree(key: String) {
        val still = try {
            snapshot().cards.any { "strom " + it.rect.toShortString() == key }
        } catch (_: Throwable) {
            false
        }
        finishOrRetry(still) {
            busy = false
            scheduleScan()
        }
    }

    // ---- cesta B: ponuka len na snimke

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
            ocrOffer(bmp) { rect ->
                if (rect == null) {
                    if (targetKey.startsWith("snimka")) {
                        targetKey = ""
                        tries = 0
                    }
                    busy = false
                    return@ocrOffer
                }
                if (!targetKey.startsWith("snimka")) {
                    targetKey = "snimka"
                    tries = 0
                    XLog.add("PONUKA na snímke · karta ${rect.toShortString()}")
                }
                if (tries >= MAX_TRIES) {
                    busy = false
                    return@ocrOffer
                }
                tapX(rect, bmp) { verifyOcr(rect) }
            }
        }
    }

    private fun verifyOcr(rect: Rect) {
        shoot { bmp ->
            if (bmp == null) {
                busy = false
                return@shoot
            }
            ocrOffer(bmp) { r ->
                val still = r != null && abs(r.top - rect.top) < 150
                finishOrRetry(still) {
                    if (r != null) tapX(r, bmp) { verifyOcr(r) } else busy = false
                }
            }
        }
    }

    /** Najde ponuku na snimke a vrati obdlznik karty zarovnany na okno Uberu. */
    private fun ocrOffer(bmp: Bitmap, cb: (Rect?) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
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
                    cb(null)
                    return@addOnSuccessListener
                }
                val win = uberWins.firstOrNull { it.contains(bd.centerX(), bd.centerY()) }
                if (win == null && uberWins.isNotEmpty()) {
                    // Text je mimo okna Uberu (napr. v inej appke) - nie je to ponuka.
                    cb(null)
                    return@addOnSuccessListener
                }
                val w = win ?: Rect(0, 0, bmp.width, bmp.height)
                cb(
                    Rect(
                        w.left + CARD_MARGIN,
                        (bd.top - BADGE_TO_CARD_TOP).coerceAtLeast(w.top),
                        w.right - CARD_MARGIN,
                        (a.bottom + 60).coerceAtMost(w.bottom)
                    )
                )
            }
            .addOnFailureListener {
                XLog.add("čítanie textu zlyhalo: ${it.message}")
                cb(null)
            }
    }

    // ------------------------------------------------------------ X a dotyk

    private fun tapX(rect: Rect, bmp: Bitmap?, after: () -> Unit) {
        val found = bmp?.let { crossInCorner(it, rect) }
        val how = if (found != null) "krížik nájdený" else "odhad"
        val p = found ?: Pair(rect.right - X_FROM_RIGHT, rect.top + X_FROM_TOP)

        if (!on("click", true)) {
            XLog.add("X na ${p.first},${p.second} ($how) – klikanie vypnuté")
            tries = MAX_TRIES
            busy = false
            return
        }

        tries++
        XLog.add("ťukám na X ${p.first},${p.second} ($how) · karta ${rect.toShortString()} · pokus $tries/$MAX_TRIES")
        tapAt(p.first.toFloat(), p.second.toFloat()) { ok ->
            if (!ok) XLog.add("gesto zlyhalo")
            main.postDelayed({ after() }, VERIFY_MS)
        }
    }

    /**
     * Krizik X: tmave body v malom stvorci v pravom hornom rohu karty.
     * Stvorec je dost maly, aby don nezasahoval nadpis ani stitky.
     */
    private fun crossInCorner(bmp: Bitmap, c: Rect): Pair<Int, Int>? {
        val x0 = (c.right - 200).coerceIn(0, bmp.width - 1)
        val x1 = (c.right - 20).coerceIn(0, bmp.width - 1)
        val y0 = (c.top + 20).coerceIn(0, bmp.height - 1)
        val y1 = (c.top + 190).coerceIn(0, bmp.height - 1)
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
        if (w < 10 || h < 10 || w > 140 || h > 140) return null
        return Pair((minX + maxX) / 2, (minY + maxY) / 2)
    }

    private fun lum(p: Int) = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000

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
