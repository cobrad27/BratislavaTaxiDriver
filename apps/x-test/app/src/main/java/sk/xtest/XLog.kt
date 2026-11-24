package sk.xtest

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object XLog {
    private const val MAX = 1500
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>()
    private val dumps = ArrayDeque<String>()
    private val offerDumps = ArrayDeque<String>()
    private val main = Handler(Looper.getMainLooper())

    var listener: (() -> Unit)? = null

    @Synchronized
    fun add(msg: String) {
        lines.addLast("${fmt.format(Date())}  $msg")
        while (lines.size > MAX) lines.removeFirst()
        main.post { listener?.invoke() }
    }

    @Synchronized
    fun raw(msg: String) {
        lines.addLast(msg)
        while (lines.size > MAX) lines.removeFirst()
        main.post { listener?.invoke() }
    }

    /** Vypisy stromu: s ponukou sa drzia zvlast, aby ich nevytlacili ine. */
    @Synchronized
    fun putDump(text: String, offer: Boolean) {
        val q = if (offer) offerDumps else dumps
        q.addLast(text)
        while (q.size > (if (offer) 3 else 2)) q.removeFirst()
    }

    @Synchronized
    fun dump(): String = buildString {
        append(lines.joinToString("\n"))
        if (offerDumps.isNotEmpty()) {
            append("\n\n===== VYPISY S PONUKOU =====\n")
            append(offerDumps.joinToString("\n\n"))
        }
        if (dumps.isNotEmpty()) {
            append("\n\n===== POSLEDNE VYPISY BEZ PONUKY =====\n")
            append(dumps.joinToString("\n\n"))
        }
    }

    @Synchronized
    fun clear() {
        lines.clear()
        dumps.clear()
        offerDumps.clear()
    }
}
