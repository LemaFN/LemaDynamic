package de.lema.hyperisland

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
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

    private val tick = object : Runnable {
        override fun run() { refresh(); chronos(); h.postDelayed(this, 1000) }
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
        try { activeNotifications?.forEach { if (isChrono(it.notification)) chrono[it.key] = it } } catch (_: Exception) {}
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
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        Hub.post(Act("music", "music", md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Musik",
            md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "",
            if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f, on = pl, art = art, pkg = c.packageName,
            ctl = listOf(Btn("prev") { t.skipToPrevious() }, Btn(if (pl) "pause" else "play") { if (pl) t.pause() else t.play() }, Btn("next") { t.skipToNext() })))
    }

    /** Timer/Stoppuhr der Uhr-App: laufende Benachrichtigung mit Chronometer. */
    private fun isChrono(n: Notification) =
        n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) && n.category != Notification.CATEGORY_CALL && n.`when` > 0

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

    private fun appLabel(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    override fun onNotificationPosted(s: StatusBarNotification) {
        val n = s.notification
        if (s.packageName == packageName) return
        if (isChrono(n)) { chrono[s.key] = s; chronos(); return }
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val icon = try { packageManager.getApplicationIcon(s.packageName).toBitmap(64, 64) } catch (_: Exception) { null }
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
        // Privatsphäre: auf dem Sperrbildschirm keinen Inhalt zeigen, außer die App erlaubt es öffentlich
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val hide = Cfg.v.value.privacy && locked && n.visibility != Notification.VISIBILITY_PUBLIC
        Hub.post(Act("msg", "msg", if (hide) appLabel(s.packageName) else title, if (hide) "Neue Nachricht" else text,
            ttl = 6000, art = icon, pkg = s.packageName, ctl = listOf(Btn("Öffnen") { go(n.contentIntent) })))
    }

    override fun onNotificationRemoved(s: StatusBarNotification) {
        if (chrono.remove(s.key) != null) Hub.drop("tm:${s.key}")
        when (s.notification.category) {
            Notification.CATEGORY_CALL -> Hub.drop("call")
            Notification.CATEGORY_ALARM -> Hub.drop("alarm")
        }
    }
}
