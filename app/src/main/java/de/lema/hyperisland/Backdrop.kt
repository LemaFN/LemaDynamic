package de.lema.hyperisland

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Bildschirmfoto des Hintergrunds hinter der Island (über den Bedienungshilfen-Dienst, API 30+, keine Aufnahme-Erlaubnis nötig).
 * Der Glas-Shader nutzt es zum Weichzeichnen und Verbiegen (Lichtbrechung). Das Foto wird nur beim Erscheinen der Island
 * und beim App-Wechsel gemacht, nicht dauernd (Akku).
 */
object Backdrop {
    /** Ausschnitt = das Island-Fenster, verkleinert um SCALE. */
    @Volatile var bmp: Bitmap? = null
    const val SCALE = 0.4f
    /** Versatz des Ausschnitts im Fenster (Pixel), falls das Fenster über den Bildschirmrand ragt. */
    @Volatile var cropX = 0
    @Volatile var cropY = 0
    /** Zählt hoch, sobald ein neues Foto da ist (Compose zeichnet dann neu). */
    var ver by mutableIntStateOf(0)
    /** true = Island wartet kurz auf das erste Foto, bevor sie erscheint. */
    var pending by mutableStateOf(false)
    @Volatile var lastAt = 0L
    @Volatile var serviceUp = false
    @Volatile var status = "noch kein Foto"

    fun enabled() = Build.VERSION.SDK_INT >= 30 && Cfg.v.value.backdrop && serviceUp
}
