package de.lema.hyperisland

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import androidx.core.graphics.drawable.toBitmap

class Notifs : NotificationListenerService() {
    private var lastPlay = 0L
    private val h = Handler(Looper.getMainLooper())
    private val chrono = HashMap<String, StatusBarNotification>()
    private var watched: List<MediaController> = emptyList()
    /** Player-Benachrichtigung (für Apps ohne MediaSession, z. B. manche Dateimanager). */
    private var mediaN: StatusBarNotification? = null
    private var artSrc: Bitmap? = null
    private var artCopy: Bitmap? = null

    /** Cover kopieren: der Player kann das Original recyceln, Zeichnen wäre dann ein Absturz. */
    private fun safeArt(b: Bitmap?): Bitmap? {
        if (b == null || b.isRecycled || b === artSrc) return artCopy?.takeIf { !it.isRecycled }
        return try {
            val c = Bitmap.createScaledBitmap(b, 192, 192, true)
            artSrc = b
            artCopy = c
            c
        } catch (_: Throwable) { null }
    }

    private val tick = object : Runnable {
        override fun run() { refresh(); chronos(); customTick(); h.postDelayed(this, 1000) }
    }

    // Sofortige Reaktion auf Play/Pause/Songwechsel, das Sekunden-Polling bleibt nur für den Fortschrittsbalken.
    private val cb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { refresh() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { refresh() }
    }
    private val sl = MediaSessionManager.OnActiveSessionsChangedListener { l -> watch(l.orEmpty()); refresh() }

    private fun go(p: PendingIntent?) { try { p?.send() } catch (_: Exception) {} }

    private fun watch(l: List<MediaController>) {
        watched.forEach { try { it.unregisterCallback(cb) } catch (_: Exception) {} }
        watched = l.toList()
        watched.forEach { try { it.registerCallback(cb, h) } catch (_: Exception) {} }
    }

    override fun onListenerConnected() {
        Hub.listenerUp = true
        Cfg.load(applicationContext)
        val cn = ComponentName(this, Notifs::class.java)
        try {
            val msm = getSystemService(MediaSessionManager::class.java)
            msm.addOnActiveSessionsChangedListener(sl, cn, h)
            watch(msm.getActiveSessions(cn))
        } catch (_: Exception) {}
        // Schon laufende Timer/Stoppuhren beim Start erkennen
        try {
            activeNotifications?.forEach {
                if (isChrono(it.notification, it.packageName)) chrono[it.key] = it
                else if (it.packageName != packageName && looksLikeMedia(it.notification)) mediaN = it
            }
        } catch (_: Exception) {}
        h.removeCallbacks(tick)
        h.post(tick)
    }

    override fun onListenerDisconnected() {
        Hub.listenerUp = false
        h.removeCallbacks(tick)
        try { getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sl) } catch (_: Exception) {}
        watch(emptyList())
        chrono.keys.forEach { Hub.drop("tm:$it") }
        chrono.clear()
        mediaN = null
        Hub.drop("music")
    }

    private fun refresh() {
        val l = try {
            getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(this, Notifs::class.java))
        } catch (_: Exception) { emptyList<MediaController>() }
        val c = l.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: l.firstOrNull { it.metadata != null && it.playbackState?.state == PlaybackState.STATE_PAUSED }
        val md = c?.metadata
        val ps = c?.playbackState
        if (c == null || md == null || ps == null) {
            val m = mediaN
            if (m != null) { fromNotif(m); return }
            Hub.unmute("music")
            Hub.drop("music")
            return
        }
        val pl = ps.state == PlaybackState.STATE_PLAYING || ps.state == PlaybackState.STATE_BUFFERING
        val now = SystemClock.elapsedRealtime()
        if (pl) lastPlay = now else if (now - lastPlay > 15000) { Hub.drop("music"); return }
        val t = c.transportControls
        val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val pos = ps.position.coerceAtLeast(0L) + if (pl) ((now - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong() else 0L
        val art = safeArt(md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))
        Hub.post(Act("music", "music", md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Musik",
            md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "",
            if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f, on = pl, art = art, pkg = c.packageName,
            ctl = listOf(Btn("prev") { t.skipToPrevious() }, Btn(if (pl) "pause" else "play") { if (pl) t.pause() else t.play() }, Btn("next") { t.skipToNext() })))
    }

    private val clockHint = Regex("(?i)(clock|timer|stopwatch|alarm|uhr)")
    private val mediaLabel = Regex("(?i)(prev|next|pause|play|skip|wiedergabe|abspielen|pausieren|zurück|weiter)")

    /** Strenge Medien-Erkennung: MediaStyle, Transport-Kategorie oder mindestens zwei Player-Buttons. */
    private fun looksLikeMedia(n: Notification): Boolean {
        if (n.category == Notification.CATEGORY_TRANSPORT) return true
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return true
        if (n.extras.getString(Notification.EXTRA_TEMPLATE)?.endsWith("MediaStyle") == true) return true
        return n.actions.orEmpty().count { mediaLabel.containsMatchIn(it.title?.toString() ?: "") } >= 2
    }

    /**
     * Timer/Stoppuhr: laufende Benachrichtigung mit Chronometer. Player zählen mit, wenn sie ihre Spielzeit
     * als Chronometer anzeigen, das ist KEIN Timer (lief sonst nach dem Pausieren weiter).
     * Gezählt wird nur ein Countdown oder eine Stoppuhr einer Uhr-App.
     */
    private fun isChrono(n: Notification, pkg: String): Boolean {
        if (!n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) || n.`when` <= 0) return false
        if (n.category == Notification.CATEGORY_CALL || looksLikeMedia(n)) return false
        return n.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN) || clockHint.containsMatchIn(pkg)
    }

    private fun btnKey(l: String): String {
        val x = l.lowercase()
        return when {
            x.contains("prev") || x.contains("zurück") || x.contains("vorher") -> "prev"
            x.contains("next") || x.contains("weiter") || x.contains("skip") || x.contains("nächst") -> "next"
            x.contains("pause") -> "pause"
            x.contains("play") || x.contains("wiedergabe") || x.contains("abspielen") -> "play"
            else -> l
        }
    }

    /** Musik-Anzeige direkt aus der Player-Benachrichtigung, wenn es keine MediaSession gibt. */
    private fun fromNotif(s: StatusBarNotification) {
        val n = s.notification
        val acts = n.actions.orEmpty()
        val playing = acts.any { (it.title?.toString() ?: "").lowercase().contains("pause") }
        val now = SystemClock.elapsedRealtime()
        if (playing) lastPlay = now else if (now - lastPlay > 15000) { Hub.drop("music"); return }
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "Musik"
        val sub = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val big = try { n.getLargeIcon()?.loadDrawable(this)?.toBitmap(128, 128) } catch (_: Exception) { null }
        val art = big ?: try { packageManager.getApplicationIcon(s.packageName).toBitmap(64, 64) } catch (_: Exception) { null }
        Hub.post(Act("music", "music", title, sub, on = playing, art = art, pkg = s.packageName,
            ctl = acts.take(4).map { x -> Btn(btnKey(x.title?.toString() ?: "")) { go(x.actionIntent) } }))
    }

    private fun fmt(sec: Long) =
        if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)

    private fun chronos() {
        val now = System.currentTimeMillis()
        for ((k, s) in chrono) {
            val n = s.notification
            val down = n.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
            val ms = if (down) n.`when` - now else now - n.`when`
            if (down && ms < -2000) { Hub.drop("tm:$k"); continue }
            val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
                ?: if (down) "Timer" else "Stoppuhr"
            val icon = try { packageManager.getApplicationIcon(s.packageName).toBitmap(64, 64) } catch (_: Exception) { null }
            val a = n.actions.orEmpty()
            Hub.post(Act("tm:$k", "timer", title, fmt((ms / 1000).coerceAtLeast(0)), art = icon, pkg = s.packageName,
                ctl = a.take(2).mapIndexed { i, x -> Btn(x.title.toString(), if (i == 0) 2 else 0) { go(x.actionIntent) } }))
        }
    }

    /** Inhalt einer Benachrichtigung mit eigener Ansicht (RemoteViews), z. B. der Timer der Uhr-App ohne Titel/Text. */
    private class Cv(val texts: List<String>, val base: Long, val down: Boolean, val running: Boolean, val hasChrono: Boolean,
                     val btns: List<Triple<String, Int, android.view.View>> = emptyList())

    /** Anzeigename und Farbe einer Taste aus Beschriftung/Beschreibung/Ressourcenname der Uhr-Ansicht. */
    private fun niceBtn(raw: String, alt: String): Pair<String, Int>? {
        val x = raw.lowercase()
        return when {
            Regex("pausier|pause").containsMatchIn(x) -> "Pause" to 0
            Regex("resume|continue|weiter|fortsetz|play|start").containsMatchIn(x) -> "Weiter" to 1
            Regex("delete|lösch|loesch|cancel|abbrech|dismiss|close|remove|clear").containsMatchIn(x) -> "Löschen" to 2
            Regex("stop").containsMatchIn(x) -> "Stopp" to 2
            Regex("reset|neustart|restart|zurücksetz").containsMatchIn(x) -> "Neu" to 0
            Regex("lap|runde").containsMatchIn(x) -> "Runde" to 0
            alt.isNotBlank() && !Regex("root|container|layout|content|parent|wrapper|icon|image|text|title|time|chrono").containsMatchIn(x) -> alt.take(14) to 0
            else -> null
        }
    }

    /**
     * Ansicht der Benachrichtigung erzeugen. Der Interaktions-Handler ist keine öffentliche API, daher per Reflection:
     * Klicks auf Tasten lösen so direkt den PendingIntent aus. Klappt das nicht, gilt der Standard-Handler des Systems.
     */
    private fun applyViews(rv: android.widget.RemoteViews): android.view.View {
        try {
            val hc = Class.forName("android.widget.RemoteViews\$InteractionHandler")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(hc.classLoader, arrayOf(hc)) { _, m, a ->
                when (m.name) {
                    "onInteraction" -> { try { (a?.getOrNull(1) as? android.app.PendingIntent)?.send() } catch (_: Throwable) {}; true }
                    "toString" -> "ih"
                    "hashCode" -> 0
                    "equals" -> false
                    else -> null
                }
            }
            val m = android.widget.RemoteViews::class.java.getMethod("apply", android.content.Context::class.java, android.view.ViewGroup::class.java, hc)
            return m.invoke(rv, applicationContext, android.widget.FrameLayout(this), proxy) as android.view.View
        } catch (_: Throwable) {}
        return rv.apply(applicationContext, android.widget.FrameLayout(this))
    }

    @Suppress("DEPRECATION")
    private fun inspectViews(s: StatusBarNotification): Cv? {
        val n = s.notification
        val rvs = listOfNotNull(n.bigContentView, n.contentView, n.headsUpContentView)
        for (rv in rvs) {
            try {
                val v = applyViews(rv)
                val texts = ArrayList<String>()
                val btns = ArrayList<Triple<String, Int, android.view.View>>()
                var chr: android.widget.Chronometer? = null
                fun walk(x: android.view.View) {
                    if (x is android.widget.Chronometer && chr == null) chr = x
                    if (x is android.widget.TextView) { val t = x.text?.toString()?.trim().orEmpty(); if (t.isNotEmpty()) texts.add(t) }
                    if (x is android.view.ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
                }
                walk(v)
                fun walkBtn(x: android.view.View, root: Boolean) {
                    if (!root && x.hasOnClickListeners()) {
                        val cd = x.contentDescription?.toString()?.trim().orEmpty()
                        val tx = (x as? android.widget.TextView)?.text?.toString()?.trim().orEmpty()
                        val nm = try { if (x.id != android.view.View.NO_ID) x.resources.getResourceEntryName(x.id) else "" } catch (_: Throwable) { "" }
                        niceBtn("$cd $tx $nm", cd.ifEmpty { tx })?.let { (l, t) -> if (btns.none { it.first == l }) btns.add(Triple(l, t, x)) }
                    }
                    if (x is android.view.ViewGroup) for (i in 0 until x.childCount) walkBtn(x.getChildAt(i), false)
                }
                walkBtn(v, true)
                val c = chr
                if (c != null || texts.isNotEmpty()) {
                    var running = true
                    if (c != null) try {
                        val f = android.widget.Chronometer::class.java.getDeclaredField("mStarted"); f.isAccessible = true; running = f.getBoolean(c)
                    } catch (_: Throwable) {}
                    val down = c != null && try { c.isCountDown } catch (_: Throwable) { false }
                    return Cv(texts, c?.base ?: 0L, down, running, c != null, btns)
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    /**
     * Stoppuhr der Uhr-App ohne eigene Ansicht: In der Meldung steht keine Zeit, aber ihr Zeitstempel (when)
     * ist der Startzeitpunkt. Daraus wird die laufende Zeit berechnet.
     */
    private fun stopwatchFromWhen(s: StatusBarNotification): Cv? {
        val n = s.notification
        val t = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        if (!Regex("(?i)(stoppuhr|stopwatch)").containsMatchIn(t)) return null
        val age = System.currentTimeMillis() - n.`when`
        if (n.`when` <= 0 || age < 0 || age > 24L * 3600_000) return null
        return Cv(emptyList(), SystemClock.elapsedRealtime() - age, false, true, true)
    }

    private class CT(val s: StatusBarNotification, val cv: Cv)
    private val customs = HashMap<String, CT>()

    private fun customTick() {
        val now = SystemClock.elapsedRealtime()
        for ((k, ct) in customs) {
            val cv = ct.cv
            val text = if (cv.hasChrono && cv.running) {
                val ms = if (cv.down) cv.base - now else now - cv.base
                if (cv.down && ms < -2000) { Hub.drop("tm:$k"); continue }
                fmt((ms / 1000).coerceAtLeast(0))
            } else cv.texts.firstOrNull { timeAny.containsMatchIn(it) } ?: continue
            val icon = try { packageManager.getApplicationIcon(ct.s.packageName).toBitmap(64, 64) } catch (_: Exception) { null }
            val a = ct.s.notification.actions.orEmpty()
            val ctl = if (cv.btns.isNotEmpty()) cv.btns.take(3).map { (l, t, view) -> Btn(l, t) { try { view.performClick() } catch (_: Throwable) {} } }
            else if (a.isNotEmpty()) a.take(3).mapIndexed { i, x -> Btn(x.title?.toString() ?: "", if (i == 0) 2 else 0) { go(x.actionIntent) } }
            else listOf(Btn("Öffnen") { go(ct.s.notification.contentIntent) })
            val nt = ct.s.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            Hub.post(Act("tm:$k", "timer", nt ?: if (cv.hasChrono && cv.down) "Timer" else if (cv.hasChrono) "Stoppuhr" else "Timer", text, art = icon, pkg = ct.s.packageName, ctl = ctl))
        }
    }

    private fun appLabel(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private val lastShown = HashMap<String, String>()
    private val timeSec = Regex("\\d{1,2}:\\d{2}[:.,]\\d{1,2}")
    private val timeAny = Regex("\\d{1,2}:\\d{2}([:.,]\\d{1,2})?")
    private val timerWord = Regex("(?i)(timer|stoppuhr|stopwatch)")

    private fun mediaPkgs(): Set<String> = (watched.map { it.packageName } + listOfNotNull(mediaN?.packageName)).toSet()

    private fun log(s: StatusBarNotification) {
        val n = s.notification
        val ex = n.extras
        val f = StringBuilder()
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) f.append(" ongoing")
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) f.append(" summary")
        if (ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) f.append(if (ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)) " chrono-down" else " chrono-up")
        val tpl = ex.getString(Notification.EXTRA_TEMPLATE)?.substringAfterLast('$')?.substringAfterLast('.') ?: "-"
        val ti = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(24) ?: ""
        val tx = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(28) ?: ""
        var cvInfo = ""
        if (clockHint.containsMatchIn(s.packageName)) {
            val cv = inspectViews(s)
            cvInfo = if (cv == null) " | cv: keine" else " | cv: chrono=${cv.hasChrono} down=${cv.down} run=${cv.running} texts=${cv.texts.take(5)} tasten=${cv.btns.map { it.first }}"
        }
        if (clockHint.containsMatchIn(s.packageName)) cvInfo += " when=${(System.currentTimeMillis() - n.`when`) / 1000}s sub=${ex.getCharSequence(Notification.EXTRA_SUB_TEXT)} info=${ex.getCharSequence(Notification.EXTRA_INFO_TEXT)} keys=${ex.keySet().map { it.substringAfterLast('.') }.take(14)}"
        NLog.add("${s.packageName.substringAfterLast('.')} cat=${n.category ?: "-"} tpl=$tpl acts=${n.actions?.size ?: 0}$f | $ti | $tx$cvInfo")
    }

    override fun onNotificationPosted(s: StatusBarNotification) {
        val n = s.notification
        if (s.packageName == packageName) return
        log(s)
        if (isChrono(n, s.packageName)) { chrono[s.key] = s; chronos(); return }
        if (chrono.remove(s.key) != null) Hub.drop("tm:${s.key}")
        if (looksLikeMedia(n)) { mediaN = s; refresh(); return }
        val ex = n.extras
        if (clockHint.containsMatchIn(s.packageName) && n.flags and Notification.FLAG_ONGOING_EVENT != 0 && n.category != Notification.CATEGORY_ALARM &&
            !timeAny.containsMatchIn("${ex.getCharSequence(Notification.EXTRA_TITLE)} ${ex.getCharSequence(Notification.EXTRA_TEXT)}")) {
            // Timer/Stoppuhr der Uhr-App: Zeit steckt in einer eigenen Ansicht, nicht in Titel/Text
            val cv = inspectViews(s) ?: stopwatchFromWhen(s)
            if (cv != null) { customs[s.key] = CT(s, cv); customTick(); return }
        }
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val icon = try { packageManager.getApplicationIcon(s.packageName).toBitmap(64, 64) } catch (_: Exception) { null }

        // Uhr-App: laufender Timer/Stoppuhr, der seine Zeit als Text aktualisiert (kein Chronometer)
        if (clockHint.containsMatchIn(s.packageName) && n.flags and Notification.FLAG_ONGOING_EVENT != 0 && n.category != Notification.CATEGORY_ALARM) {
            val named = timerWord.containsMatchIn("$title $text")
            val tm = timeSec.find(text) ?: timeSec.find(title) ?: if (named) (timeAny.find(text) ?: timeAny.find(title)) else null
            if (tm != null) {
                val a = n.actions.orEmpty()
                Hub.post(Act("tm:${s.key}", "timer", if (named) title else "Timer", tm.value, art = icon, pkg = s.packageName,
                    ctl = a.take(2).mapIndexed { i, x -> Btn(x.title?.toString() ?: "", if (i == 0) 2 else 0) { go(x.actionIntent) } }))
                return
            }
        }

        if (n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_ALARM) {
            val isCall = n.category == Notification.CATEGORY_CALL
            val a = n.actions.orEmpty()
            Hub.post(Act(if (isCall) "call" else "alarm", "call", title, text.ifEmpty { if (isCall) "Anruf" else "Wecker" }, art = icon, pkg = s.packageName,
                ctl = a.mapIndexed { i, x -> Btn(x.title.toString(), if (a.size == 1 || i == 0) 2 else 1) { go(x.actionIntent) } }))
            return
        }
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0 || n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || n.category == Notification.CATEGORY_TRANSPORT) return
        val rk = Ranking()
        if (currentRanking.getRanking(s.key, rk) && rk.importance < NotificationManager.IMPORTANCE_DEFAULT) return
        if (n.visibility == Notification.VISIBILITY_SECRET) return

        // Nur frische Nachrichten: Apps wie Telegram posten ältere Benachrichtigungen erneut, das zeigte alte Nachrichten an
        val now = System.currentTimeMillis()
        if (n.`when` > 0 && now - n.`when` > 60_000) return
        var t2 = title
        var x2 = text
        // Chat-Benachrichtigungen: die NEUESTE Nachricht samt Absender nehmen
        @Suppress("DEPRECATION")
        val last = ex.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull() as? android.os.Bundle
        if (last != null) {
            val body = last.getCharSequence("text")?.toString()
            val sender = last.getCharSequence("sender")?.toString()
            val tm = last.getLong("time", 0L)
            if (tm > 0 && now - tm > 60_000) return
            if (!body.isNullOrBlank()) {
                x2 = body
                if (!sender.isNullOrBlank() && sender != title) t2 = "$sender ($title)"
            }
        }
        // Gleicher Inhalt wie zuletzt für diese Benachrichtigung (z. B. bloßes Aktualisieren): nicht erneut zeigen
        val sig = "${n.`when`}|$t2|$x2"
        if (lastShown[s.key] == sig) return
        lastShown[s.key] = sig

        // Privatsphäre: auf dem Sperrbildschirm keinen Inhalt zeigen, außer die App erlaubt es öffentlich
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val hide = Cfg.v.value.privacy && locked && n.visibility != Notification.VISIBILITY_PUBLIC
        val shownTitle = if (hide) appLabel(s.packageName) else t2
        val shownText = if (hide) "Neue Nachricht" else x2
        // Kurz warten: Player melden sich oft mit einer Start-Benachrichtigung ("Musik-Player"), kurz bevor ihre MediaSession da ist
        h.postDelayed({
            val chat = n.category == Notification.CATEGORY_MESSAGE || n.category == Notification.CATEGORY_EMAIL
            if (!chat && s.packageName in mediaPkgs()) return@postDelayed
            Hub.post(Act("msg", "msg", shownTitle, shownText, ttl = 6000, art = icon, pkg = s.packageName, ctl = listOf(Btn("Öffnen") { go(n.contentIntent) })))
        }, 500L)
    }

    override fun onNotificationRemoved(s: StatusBarNotification) {
        if (mediaN?.key == s.key) { mediaN = null; refresh() }
        lastShown.remove(s.key)
        customs.remove(s.key)
        Hub.drop("tm:${s.key}")
        if (chrono.remove(s.key) != null) Hub.drop("tm:${s.key}")
        when (s.notification.category) {
            Notification.CATEGORY_CALL -> Hub.drop("call")
            Notification.CATEGORY_ALARM -> Hub.drop("alarm")
        }
    }
}
