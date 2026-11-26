package de.lema.hyperisland

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** Alle Einstellungen der Island. Werte werden live übernommen (Service und App laufen im selben Prozess). */
data class Config(
    val wobble: Float = 0.45f,          // 0 = straff, 1 = sehr wobbelig
    val opacity: Float = 0.80f,         // Deckkraft ausgeklappt (0.35 = sehr durchsichtig)
    val compactOpacity: Float = 0.97f,  // Deckkraft eingeklappt (hoch = verschmilzt mit der Kamera)
    val frost: Float = 0.25f,           // 0 = klares Glas, 1 = milchig/frosted
    val glow: Float = 0.35f,            // Farbschimmer + Schatten der Aktivität
    val corner: Float = 38f,            // max. Eckenradius in dp
    val scale: Float = 1f,              // Gesamtgröße
    val offX: Float = 0f,               // Verschiebung nach rechts (dp, negativ = links)
    val offY: Float = 0f,               // Verschiebung nach unten (dp)
    val compactW: Float = 236f,         // Breite eingeklappt (dp)
    val holdSec: Float = 6f,            // so lange bleibt die Island aufgeklappt
    val blur: Float = 0f,               // Hintergrund-Blur in px (0 = aus, nur Android 12+, experimentell)
    val showMsg: Boolean = true,
    val showMusic: Boolean = true,
    val showTimer: Boolean = true,
    val showCall: Boolean = true,
    val showCharge: Boolean = true,
    val pull: Boolean = true,           // minimierte App wird eingezogen
    val haptic: Boolean = true,
    val hideLandscape: Boolean = true,
    val privacy: Boolean = true         // auf dem Sperrbildschirm keinen Nachrichtentext zeigen
) {
    fun allows(kind: String, id: String) = when {
        id == "tdone" || kind == "timer" -> showTimer
        kind == "music" -> showMusic
        kind == "call" -> showCall
        kind == "chg" -> showCharge
        else -> showMsg
    }
}

object Cfg {
    val v = MutableStateFlow(Config())
    private var loaded = false
    private const val FILE = "cfg"

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val p = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val d = Config()
        v.value = Config(
            wobble = p.getFloat("wobble", d.wobble),
            opacity = p.getFloat("opacity", d.opacity),
            compactOpacity = p.getFloat("compactOpacity", d.compactOpacity),
            frost = p.getFloat("frost", d.frost),
            glow = p.getFloat("glow", d.glow),
            corner = p.getFloat("corner", d.corner),
            scale = p.getFloat("scale", d.scale),
            offX = p.getFloat("offX", d.offX),
            offY = p.getFloat("offY", d.offY),
            compactW = p.getFloat("compactW", d.compactW),
            holdSec = p.getFloat("holdSec", d.holdSec),
            blur = p.getFloat("blur", d.blur),
            showMsg = p.getBoolean("showMsg", d.showMsg),
            showMusic = p.getBoolean("showMusic", d.showMusic),
            showTimer = p.getBoolean("showTimer", d.showTimer),
            showCall = p.getBoolean("showCall", d.showCall),
            showCharge = p.getBoolean("showCharge", d.showCharge),
            pull = p.getBoolean("pull", d.pull),
            haptic = p.getBoolean("haptic", d.haptic),
            hideLandscape = p.getBoolean("hideLandscape", d.hideLandscape),
            privacy = p.getBoolean("privacy", d.privacy)
        )
    }

    fun update(c: Context, f: (Config) -> Config) {
        load(c)
        val n = f(v.value)
        v.value = n
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putFloat("wobble", n.wobble).putFloat("opacity", n.opacity).putFloat("compactOpacity", n.compactOpacity)
            .putFloat("frost", n.frost).putFloat("glow", n.glow).putFloat("corner", n.corner)
            .putFloat("scale", n.scale).putFloat("offX", n.offX).putFloat("offY", n.offY)
            .putFloat("compactW", n.compactW).putFloat("holdSec", n.holdSec).putFloat("blur", n.blur)
            .putBoolean("showMsg", n.showMsg).putBoolean("showMusic", n.showMusic).putBoolean("showTimer", n.showTimer)
            .putBoolean("showCall", n.showCall).putBoolean("showCharge", n.showCharge).putBoolean("pull", n.pull)
            .putBoolean("haptic", n.haptic).putBoolean("hideLandscape", n.hideLandscape).putBoolean("privacy", n.privacy)
            .apply()
    }

    fun reset(c: Context) = update(c) { Config() }
}
