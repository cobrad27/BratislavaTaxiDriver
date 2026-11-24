package sk.uberhelper

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Stavove prekrytie nad Uberom.
 *
 * Zamerne NEDOTYKATELNE a poloprehladne:
 *  - TYPE_ACCESSIBILITY_OVERLAY je doveryhodne okno, takze Android 12+
 *    neblokuje dotyky prechadzajuce popod neho (untrusted touch blocking),
 *  - FLAG_NOT_TOUCHABLE znamena, ze prekrytie nikdy neukradne klik,
 *  - pred kazdym gestom sa aj tak cele odoberie z okna.
 */
class OverlayManager(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var tvVerdict: TextView? = null
    private var tvDetail: TextView? = null
    private var attached = false

    private var lastVerdict = "Uber pomocník"
    private var lastDetail = "čakám na ponuku"
    private var lastColor = Color.parseColor("#94A3B8")

    fun isAttached() = attached

    private fun params(): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(8)
        lp.y = dp(96)
        lp.alpha = 0.78f
        return lp
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun attach() {
        if (attached) return
        try {
            val v = LayoutInflater.from(ctx).inflate(R.layout.overlay, null)
            tvVerdict = v.findViewById(R.id.verdict)
            tvDetail = v.findViewById(R.id.detail)
            wm.addView(v, params())
            view = v
            attached = true
            render()
        } catch (t: Throwable) {
            LogBus.add("Prekrytie sa nepodarilo zobraziť: ${t.message}")
        }
    }

    fun detach() {
        if (!attached) return
        try {
            view?.let { wm.removeViewImmediate(it) }
        } catch (_: Throwable) {
        }
        view = null
        tvVerdict = null
        tvDetail = null
        attached = false
    }

    fun update(verdict: String, detail: String, color: Int) {
        lastVerdict = verdict
        lastDetail = detail
        lastColor = color
        render()
    }

    private fun render() {
        tvVerdict?.apply {
            text = lastVerdict
            setTextColor(lastColor)
        }
        tvDetail?.text = lastDetail
    }
}
