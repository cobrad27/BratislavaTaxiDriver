package sk.xtest

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object XLog {
    private const val MAX = 600
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>()
    private val main = Handler(Looper.getMainLooper())

    var listener: (() -> Unit)? = null

    @Synchronized
    fun add(msg: String) {
        lines.addLast("${fmt.format(Date())}  $msg")
        while (lines.size > MAX) lines.removeFirst()
        main.post { listener?.invoke() }
    }

    /** Riadok bez casovej peciatky - na vypis stromu. */
    @Synchronized
    fun raw(msg: String) {
        lines.addLast(msg)
        while (lines.size > MAX) lines.removeFirst()
        main.post { listener?.invoke() }
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
