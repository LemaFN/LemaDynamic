package de.lema.hyperisland

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
    private val tick = object : Runnable { override fun run() { refresh(); h.postDelayed(this, 1000) } }

    private fun go(p: PendingIntent?) { try { p?.send() } catch (_: Exception) {} }

    override fun onListenerConnected() { h.removeCallbacks(tick); h.post(tick) }
    override fun onListenerDisconnected() { h.removeCallbacks(tick); Hub.drop("music") }

    private fun refresh() {
        val l = try {
            getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(this, Notifs::class.java))
        } catch (_: Exception) { emptyList<MediaController>() }
        val c = l.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: l.firstOrNull { it.metadata != null && it.playbackState?.state == PlaybackState.STATE_PAUSED }
        val md = c?.metadata
        val ps = c?.playbackState
        if (c == null || md == null || ps == null) return Hub.drop("music")
        val pl = ps.state == PlaybackState.STATE_PLAYING || ps.state == PlaybackState.STATE_BUFFERING
        val now = SystemClock.elapsedRealtime()
        if (pl) lastPlay = now else if (now - lastPlay > 15000) return Hub.drop("music")
        val t = c.transportControls
        val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val pos = ps.position + if (pl) ((now - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong() else 0L
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        Hub.post(Act("music", "music", md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Musik",
            md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "",
            if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f, on = pl, art = art, pkg = c.packageName,
            ctl = listOf(Btn("prev") { t.skipToPrevious() }, Btn(if (pl) "pause" else "play") { if (pl) t.pause() else t.play() }, Btn("next") { t.skipToNext() })))
    }

    override fun onNotificationPosted(s: StatusBarNotification) {
        val n = s.notification
        if (s.packageName == packageName) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val icon = try { packageManager.getApplicationIcon(s.packageName).toBitmap(64, 64) } catch (_: Exception) { null }
        if (n.category == Notification.CATEGORY_CALL) {
            val a = n.actions.orEmpty()
            Hub.post(Act("call", "call", title, text.ifEmpty { "Anruf" }, art = icon, pkg = s.packageName,
                ctl = a.mapIndexed { i, x -> Btn(x.title.toString(), if (a.size == 1 || i == 0) 2 else 1) { go(x.actionIntent) } }))
            return
        }
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0 || n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || n.category == Notification.CATEGORY_TRANSPORT) return
        val rk = Ranking()
        if (currentRanking.getRanking(s.key, rk) && rk.importance < NotificationManager.IMPORTANCE_DEFAULT) return
        Hub.post(Act("msg", "msg", title, text, ttl = 6000, art = icon, pkg = s.packageName, ctl = listOf(Btn("Öffnen") { go(n.contentIntent) })))
    }

    override fun onNotificationRemoved(s: StatusBarNotification) {
        if (s.notification.category == Notification.CATEGORY_CALL) Hub.drop("call")
    }
}
