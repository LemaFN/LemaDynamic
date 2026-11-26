package de.lema.hyperisland

import android.app.Application
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

/** Speichert Abstürze, damit die App sie beim nächsten Start anzeigen und kopierbar machen kann. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Cfg.load(this)
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val txt = "Zeit: ${Date()}\nThread: ${t.name}\nAndroid ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}\n\n$sw"
                getSharedPreferences("crash", MODE_PRIVATE).edit().putString("last", txt.take(6000)).commit()
            } catch (_: Throwable) {}
            old?.uncaughtException(t, e)
        }
    }
}
