package sk.david.retrohodiny

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.Calendar
import kotlin.math.min

/**
 * Celoobrazovkove retro hodiny v style 7-segmentoveho LED/VFD displeja.
 *  - tuknutie          -> dalsia farba displeja
 *  - dlhe podrzanie    -> prepnutie 24h / 12h
 *  - tah hore / dole   -> jas obrazovky
 *  - dvojite tuknutie  -> zobrazit / skryt sekundy
 */
@SuppressLint("ViewConstructor")
class RetroClockView(context: Context) : View(context) {

    private val colors = intArrayOf(
        Color.rgb(255, 40, 20),
        Color.rgb(255, 150, 0),
        Color.rgb(60, 255, 120),
        Color.rgb(40, 200, 255),
        Color.rgb(235, 235, 235)
    )

    private val prefs = context.getSharedPreferences("retro", Context.MODE_PRIVATE)
    private var colorIndex = prefs.getInt("color", 3).coerceIn(0, colors.size - 1)
    private var use24h = prefs.getBoolean("h24", DateFormat.is24HourFormat(context))
    private var showSeconds = prefs.getBoolean("sec", true)
    private var brightness = prefs.getFloat("bright", -1f)

    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textOn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val textOff = Paint(textOn)
    private val scanPaint = Paint().apply { color = Color.argb(70, 0, 0, 0) }

    private val path = Path()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private var shiftX = 0f
    private var shiftY = 0f
    private var lastMinute = -1

    private val days = arrayOf("NE", "PO", "UT", "ST", "ŠT", "PI", "SO")
    private val dayOrder = intArrayOf(2, 3, 4, 5, 6, 7, 1)

    private val digitMap = intArrayOf(0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F)

    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            val now = System.currentTimeMillis()
            handler.postAtTime(this, SystemClock.uptimeMillis() + (500 - now % 500))
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setBackgroundColor(Color.BLACK)
        isHapticFeedbackEnabled = true
        applyColor()
    }

    fun start() {
        if (running) return
        running = true
        applyBrightness()
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    private fun applyColor() {
        val c = colors[colorIndex]
        onPaint.color = c
        textOn.color = c
        val ghost = Color.argb(28, Color.red(c), Color.green(c), Color.blue(c))
        offPaint.color = ghost
        textOff.color = ghost
    }

    private fun applyBrightness() {
        val act = context as? Activity ?: return
        val lp = act.window.attributes
        lp.screenBrightness = brightness
        act.window.attributes = lp
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            colorIndex = (colorIndex + 1) % colors.size
            prefs.edit().putInt("color", colorIndex).apply()
            applyColor(); invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            showSeconds = !showSeconds
            prefs.edit().putBoolean("sec", showSeconds).apply()
            invalidate()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            use24h = !use24h
            prefs.edit().putBoolean("h24", use24h).apply()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val cur = if (brightness < 0) 0.5f else brightness
            brightness = (cur + dy / height.toFloat()).coerceIn(0.01f, 1f)
            applyBrightness()
            prefs.edit().putFloat("bright", brightness).apply()
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cal = Calendar.getInstance()
        var hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND)
        val colonOn = cal.get(Calendar.MILLISECOND) < 500
        val pm = hour >= 12
        if (!use24h) {
            hour %= 12
            if (hour == 0) hour = 12
        }

        if (minute != lastMinute) {
            lastMinute = minute
            val r = min(width, height) * 0.015f
            shiftX = ((Math.random() * 2 - 1) * r).toFloat()
            shiftY = ((Math.random() * 2 - 1) * r).toFloat()
        }

        val vw = width.toFloat()
        val vh = height.toFloat()

        val digitH = 1.75f
        val pairGap = 0.28f
        val colonW = 0.62f
        val secGap = 0.32f
        val secW = 0.46f
        val secSp = 0.12f
        val totalWUnits = 4f + 2 * pairGap + colonW +
                (if (showSeconds) secGap + 2 * secW + secSp else 0f)

        val dateCharW = 0.30f
        val dateSp = 0.10f
        val dateH = dateCharW * digitH
        val rowGap = 0.40f
        val dayRowH = 0.30f
        val totalHUnits = dayRowH + rowGap * 0.7f + digitH + rowGap + dateH

        val W = min(vw * 0.90f / totalWUnits, vh * 0.86f / totalHUnits)
        val glow = W * 0.12f
        onPaint.setShadowLayer(glow, 0f, 0f, onPaint.color)
        textOn.setShadowLayer(glow * 0.6f, 0f, 0f, textOn.color)

        val H = W * digitH
        val left = (vw - totalWUnits * W) / 2f + shiftX
        val top = (vh - totalHUnits * W) / 2f + shiftY

        canvas.save()
        canvas.skew(-0.08f, 0f)
        canvas.translate(vh * 0.04f, 0f)

        val today = cal.get(Calendar.DAY_OF_WEEK)
        textOn.textSize = dayRowH * W
        textOff.textSize = dayRowH * W
        val dayRowY = top + dayRowH * W * 0.85f
        val dayCellW = totalWUnits * W / 7f
        for (i in 0 until 7) {
            val dow = dayOrder[i]
            val p = if (dow == today) textOn else textOff
            canvas.drawText(days[dow - 1], left + dayCellW * (i + 0.5f), dayRowY, p)
        }

        var x = left
        val y = top + dayRowH * W + rowGap * 0.7f * W
        val h1 = hour / 10
        drawDigit(canvas, if (!use24h && h1 == 0) -1 else h1, x, y, W, H); x += W + pairGap * W
        drawDigit(canvas, hour % 10, x, y, W, H); x += W
        drawColon(canvas, x, y, colonW * W, H, colonOn); x += colonW * W
        drawDigit(canvas, minute / 10, x, y, W, H); x += W + pairGap * W
        drawDigit(canvas, minute % 10, x, y, W, H); x += W

        if (showSeconds) {
            x += secGap * W
            val sw = secW * W
            val sh = sw * digitH
            val sy = y + H - sh
            drawDigit(canvas, second / 10, x, sy, sw, sh); x += sw + secSp * W
            drawDigit(canvas, second % 10, x, sy, sw, sh)
            if (!use24h) {
                textOn.textSize = W * 0.30f; textOff.textSize = W * 0.30f
                val cx = x - secSp * W / 2f
                canvas.drawText("AM", cx, y + W * 0.30f, if (pm) textOff else textOn)
                canvas.drawText("PM", cx, y + W * 0.68f, if (pm) textOn else textOff)
            }
        } else if (!use24h) {
            textOn.textSize = W * 0.30f
            canvas.drawText(if (pm) "PM" else "AM", x + W * 0.35f, y + W * 0.30f, textOn)
        }

        val dateStr = String.format(
            "%02d-%02d-%04d",
            cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR)
        )
        val dcw = dateCharW * W
        val dsp = dateSp * W
        val dateTotal = dateStr.length * dcw + (dateStr.length - 1) * dsp
        var dx = left + (totalWUnits * W - dateTotal) / 2f
        val dy = y + H + rowGap * W
        for (ch in dateStr) {
            val d = if (ch == '-') -2 else ch - '0'
            drawDigit(canvas, d, dx, dy, dcw, dateH * W)
            dx += dcw + dsp
        }
        canvas.restore()

        val step = (vh / 240f).coerceAtLeast(3f)
        var sy = 0f
        while (sy < vh) {
            canvas.drawRect(0f, sy, vw, sy + step * 0.4f, scanPaint)
            sy += step
        }
    }

    /** d = 0..9, -1 = prazdna, -2 = pomlcka */
    private fun drawDigit(c: Canvas, d: Int, x: Float, y: Float, w: Float, h: Float) {
        val mask = when {
            d in 0..9 -> digitMap[d]
            d == -2 -> 0x40
            else -> 0
        }
        val t = w * 0.19f
        val g = t * 0.18f
        val hl = w - t - 2 * g
        val hx = x + t / 2 + g
        val vUp = (h / 2 - t / 2) - 2 * g
        val yA = y + t / 2
        val yG = y + h / 2
        val yD = y + h - t / 2
        val xL = x + t / 2
        val xR = x + w - t / 2

        seg(c, mask, 0, true, hx, yA, hl, t)
        seg(c, mask, 1, false, xR, yA + g, vUp, t)
        seg(c, mask, 2, false, xR, yG + g, vUp, t)
        seg(c, mask, 3, true, hx, yD, hl, t)
        seg(c, mask, 4, false, xL, yG + g, vUp, t)
        seg(c, mask, 5, false, xL, yA + g, vUp, t)
        seg(c, mask, 6, true, hx, yG, hl, t)
    }

    private fun seg(c: Canvas, mask: Int, bit: Int, horiz: Boolean,
                    sx: Float, sy: Float, len: Float, t: Float) {
        val ht = t / 2
        path.reset()
        if (horiz) {
            path.moveTo(sx, sy)
            path.lineTo(sx + ht, sy - ht)
            path.lineTo(sx + len - ht, sy - ht)
            path.lineTo(sx + len, sy)
            path.lineTo(sx + len - ht, sy + ht)
            path.lineTo(sx + ht, sy + ht)
        } else {
            path.moveTo(sx, sy)
            path.lineTo(sx + ht, sy + ht)
            path.lineTo(sx + ht, sy + len - ht)
            path.lineTo(sx, sy + len)
            path.lineTo(sx - ht, sy + len - ht)
            path.lineTo(sx - ht, sy + ht)
        }
        path.close()
        c.drawPath(path, if (mask and (1 shl bit) != 0) onPaint else offPaint)
    }

    private fun drawColon(c: Canvas, x: Float, y: Float, w: Float, h: Float, on: Boolean) {
        val s = w * 0.30f
        val cx = x + w / 2
        val p = if (on) onPaint else offPaint
        c.drawRect(cx - s / 2, y + h * 0.30f - s / 2, cx + s / 2, y + h * 0.30f + s / 2, p)
        c.drawRect(cx - s / 2, y + h * 0.70f - s / 2, cx + s / 2, y + h * 0.70f + s / 2, p)
    }
}
