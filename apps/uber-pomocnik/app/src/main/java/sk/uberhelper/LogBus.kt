package sk.uberhelper

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Jednoduchy kruhovy log zdielany medzi sluzbou a UI (rovnaky proces). */
object LogBus {
    private const val MAX = 300
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>()
    private val main = Handler(Looper.getMainLooper())

    var listener: ((String) -> Unit)? = null

    @Synchronized
    fun add(msg: String) {
        val line = "${fmt.format(Date())}  $msg"
        lines.addLast(line)
        while (lines.size > MAX) lines.removeFirst()
        main.post { listener?.invoke(line) }
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
