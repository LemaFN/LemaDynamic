package de.lema.hyperisland

import android.graphics.Bitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Button der Island. Gleichheit nur über Label + Ton, damit Lambdas keine Dauer-Recomposition auslösen. */
class Btn(val label: String, val tone: Int = 0, val f: () -> Unit) {
    override fun equals(other: Any?) = other is Btn && other.label == label && other.tone == tone
    override fun hashCode() = label.hashCode() * 31 + tone
}

data class Act(
    val id: String, val kind: String, val title: String, val sub: String = "",
    val prog: Float = 0f, val on: Boolean = true, val ttl: Long = 0,
    val ctl: List<Btn> = emptyList(), val art: Bitmap? = null, val pkg: String = "",
    val t0: Long = System.nanoTime()
)

fun pr(k: String) = when (k) { "call" -> 11; "msg" -> 10; "timer" -> 6; "music" -> 5; else -> 3 }
/** Wichtigste Anzeige; eine vom Nutzer gewählte (Hub.sel) hat Vorrang, bis eine Nachricht/ein Anruf eintrifft. */
fun topAct(l: List<Act>, sel: String? = Hub.sel.value): Act? =
    l.find { it.id == sel } ?: l.maxWithOrNull(compareBy<Act>({ pr(it.kind) }, { it.t0 }))

object Hub {
    val acts = MutableStateFlow<List<Act>>(emptyList())
    val exp = MutableStateFlow(false)
    /** Vom Nutzer gewählte Anzeige bei mehreren gleichzeitigen (Wischen / Symbole). null = automatisch nach Wichtigkeit. */
    val sel = MutableStateFlow<String?>(null)
    val pulls = MutableStateFlow(0)
    /** true = Island komplett ausblenden (z. B. Querformat). */
    val hidden = MutableStateFlow(false)
    /** Paketname einer App, die gerade minimiert wurde und "eingezogen" werden soll. */
    val fly = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Wird vom Notification-Listener gesetzt; die App nutzt es, um einen Rebind anzustoßen. */
    @Volatile var listenerUp = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tj: Job? = null
    private val muted = HashMap<String, String>()
    private val ttlJobs = HashMap<String, Job>()

    private fun key(a: Act) = a.title + "\u0000" + a.sub

    fun post(a: Act) {
        if (!Cfg.v.value.allows(a.kind, a.id)) return
        if (muted[a.id] == key(a)) return
        val old = acts.value.find { it.id == a.id }
        if (old == null && (a.kind == "msg" || a.kind == "call")) { exp.value = true; sel.value = null }
        acts.update { l -> l.filter { it.id != a.id } + a.copy(t0 = old?.t0 ?: a.t0) }
        if (a.ttl > 0) {
            ttlJobs.remove(a.id)?.cancel()
            ttlJobs[a.id] = scope.launch { delay(a.ttl); drop(a.id) }
        }
    }

    /** Zur nächsten (dir = 1) oder vorherigen Anzeige wechseln, wenn mehrere laufen. */
    fun cycle(dir: Int = 1) {
        val l = acts.value.sortedWith(compareByDescending<Act> { pr(it.kind) }.thenByDescending { it.t0 })
        if (l.size < 2) return
        val i = l.indexOfFirst { it.id == topAct(l)?.id }.coerceAtLeast(0)
        sel.value = l[((i + dir) % l.size + l.size) % l.size].id
    }

    fun drop(id: String) {
        if (sel.value == id) sel.value = null
        ttlJobs.remove(id)?.cancel()
        acts.update { l -> l.filter { it.id != id } }
    }

    /** Vom Nutzer weggewischt: dieselbe Aktivität taucht nicht sofort wieder auf (z. B. gleicher Song). */
    fun dismiss(a: Act) {
        muted[a.id] = key(a)
        drop(a.id)
    }

    fun unmute(id: String) { muted.remove(id) }

    /** Räumt die Test-Anzeigen aus den Einstellungen weg, damit sich Tests nicht gegenseitig blockieren. */
    fun clearTests() {
        tj?.cancel()
        listOf("msg", "call", "timer", "tdone", "chg", "bt", "demo", "demo-music").forEach { drop(it) }
        sel.value = null
    }

    fun pull(pkg: String) {
        if (Cfg.v.value.pull && pkg.isNotEmpty() && acts.value.any { it.pkg == pkg }) {
            pulls.update { it + 1 }
            fly.tryEmit(pkg)
        }
    }

    fun timer(s: Int) {
        tj?.cancel()
        tj = scope.launch {
            for (i in s downTo 1) {
                post(Act("timer", "timer", "Timer", "%d:%02d".format(i / 60, i % 60),
                    prog = i / s.toFloat(),
                    ctl = listOf(Btn("Stopp", 2) { tj?.cancel(); drop("timer") })))
                delay(1000)
            }
            drop("timer")
            post(Act("tdone", "msg", "Timer", "Abgelaufen!", ttl = 5000))
        }
    }
}
