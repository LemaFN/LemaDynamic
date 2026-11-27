package de.lema.hyperisland

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Kurzes Protokoll der zuletzt eingegangenen Benachrichtigungen, nur zur Fehlersuche (wird in den Einstellungen angezeigt). */
object NLog {
    private val q = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date())
        q.addFirst("$t $line")
        while (q.size > 14) q.removeLast()
    }

    @Synchronized
    fun text(): String = q.joinToString("\n")
}
