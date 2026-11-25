package de.lema.hyperisland

import android.graphics.Bitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class Btn(val label: String, val tone: Int = 0, val f: () -> Unit)

data class Act(
    val id: String, val kind: String, val title: String, val sub: String = "",
    val prog: Float = 0f, val on: Boolean = true, val ttl: Long = 0,
    val ctl: List<Btn> = emptyList(), val art: Bitmap? = null, val pkg: String = "",
    val t0: Long = System.nanoTime()
)

fun pr(k: String) = when (k) { "call" -> 11; "msg" -> 10; "timer" -> 6; "music" -> 5; else -> 3 }
fun topAct(l: List<Act>) = l.maxWithOrNull(compareBy<Act>({ pr(it.kind) }, { it.t0 }))

object Hub {
    val acts = MutableStateFlow<List<Act>>(emptyList())
    val exp = MutableStateFlow(false)
    val pulls = MutableStateFlow(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tj: Job? = null

    fun post(a: Act) {
        val old = acts.value.find { it.id == a.id }
        if (old == null && (a.kind == "msg" || a.kind == "call")) exp.value = true
        acts.update { l -> l.filter { it.id != a.id } + a.copy(t0 = old?.t0 ?: a.t0) }
        if (a.ttl > 0) scope.launch { delay(a.ttl); drop(a.id) }
    }

    fun drop(id: String) = acts.update { l -> l.filter { it.id != id } }

    fun pull(pkg: String) { if (pkg.isNotEmpty() && acts.value.any { it.pkg == pkg }) pulls.update { it + 1 } }

    fun timer(s: Int) {
        tj?.cancel()
        tj = scope.launch {
            for (i in s downTo 1) {
                post(Act("timer", "timer", "Timer", "%d:%02d".format(i / 60, i % 60), ctl = listOf(Btn("Stopp", 2) { tj?.cancel(); drop("timer") })))
                delay(1000)
            }
            drop("timer")
            post(Act("tdone", "msg", "Timer", "Abgelaufen!", ttl = 5000))
        }
    }
}
