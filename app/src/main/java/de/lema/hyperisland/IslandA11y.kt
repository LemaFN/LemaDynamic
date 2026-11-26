package de.lema.hyperisland

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class IslandA11y : AccessibilityService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val reg = LifecycleRegistry(this)
    private val ssc = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = reg
    override val savedStateRegistry get() = ssc.savedStateRegistry
    override val viewModelStore = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private val lp = WindowManager.LayoutParams(
        1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    )
    private var view: ComposeView? = null
    private var fg: String? = null
    private var curW = 0
    private var curH = 0

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_POWER_CONNECTED) {
                val p = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                Hub.post(Act("chg", "chg", "Lädt", "$p %", prog = p / 100f, ttl = 5000))
            } else Hub.drop("chg")
        }
    }

    private fun owners(v: ComposeView) {
        v.setViewTreeLifecycleOwner(this)
        v.setViewTreeSavedStateRegistryOwner(this)
        v.setViewTreeViewModelStoreOwner(this)
    }

    private fun cutoutRect(): Rect? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                ?.cutout?.boundingRects?.firstOrNull { it.top <= 0 }
        } catch (_: Exception) { null }
    }

    /** Position der Island: genau über der Kamera-Aussparung, sonst oben mittig. */
    private fun place() {
        val c = Cfg.v.value
        val m = resources.displayMetrics
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        lp.x = 0
        lp.y = (8 * m.density).toInt()
        val r = cutoutRect()
        if (r != null) {
            lp.x = r.centerX() - m.widthPixels / 2
            lp.y = (r.centerY() - (18 * m.density * c.scale).toInt()).coerceAtLeast(0)
        }
        lp.x += (c.offX * m.density).toInt()
        lp.y = (lp.y + (c.offY * m.density).toInt()).coerceAtLeast(0)
    }

    private fun landscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun homePkg() = packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
    )?.activityInfo?.packageName ?: ""

    override fun onServiceConnected() {
        if (view != null) return
        ssc.performAttach()
        ssc.performRestore(null)
        reg.currentState = Lifecycle.State.RESUMED
        Cfg.load(applicationContext)
        wm = getSystemService(WindowManager::class.java)
        val d = resources.displayMetrics.density
        Hub.hidden.value = landscape() && Cfg.v.value.hideLandscape
        place()
        val v = ComposeView(this).apply {
            owners(this)
            setContent { Island() }
        }
        view = v
        wm.addView(v, lp)
        scope.launch {
            combine(Hub.acts, Hub.exp, Hub.hidden, Cfg.v) { l, e, hd, c ->
                val (w, h) = if (hd) 0 to 0 else dims(topAct(l), e, c.compactW.toInt())
                Triple(w, h, c)
            }
                .distinctUntilChanged()
                .collectLatest { (w, h, c) ->
                    // Wachsen: Fenster sofort größer, damit die Animation nicht abgeschnitten wird.
                    // Schrumpfen: erst warten, bis die Feder-Animation durch ist.
                    if (w * h < curW * curH) delay((450 + 650 * c.wobble).toLong())
                    curW = w
                    curH = h
                    place()
                    if (w == 0) {
                        lp.width = 1
                        lp.height = 1
                        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    } else {
                        lp.width = ((w * c.scale + 2 * M) * d).toInt()
                        lp.height = ((h * c.scale + M) * d).toInt()
                        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    }
                    if (Build.VERSION.SDK_INT >= 31) {
                        // Experimentell: echter Hintergrund-Blur hinter dem (rechteckigen) Fenster, nur wenn aufgeklappt
                        if (c.blur > 0f && w != 0 && h > 36) {
                            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                            lp.blurBehindRadius = c.blur.toInt()
                        } else {
                            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                            lp.blurBehindRadius = 0
                        }
                    }
                    try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                }
        }
        scope.launch { Cfg.v.collect { Hub.hidden.value = landscape() && it.hideLandscape } }
        scope.launch { Hub.fly.collect { pkg -> launch { playFly(pkg) } } }
        ContextCompat.registerReceiver(this, rx, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Icon der minimierten App fliegt in die Island (eigenes, nicht berührbares Vollbild-Fenster, nur für die Dauer der Animation). */
    private suspend fun playFly(pkg: String) {
        if (Hub.hidden.value) return
        val bmp = try { packageManager.getApplicationIcon(pkg).toBitmap(128, 128) } catch (_: Exception) { return }
        val m = resources.displayMetrics
        val tx = m.widthPixels / 2f + lp.x
        val ty = lp.y + 18 * m.density * Cfg.v.value.scale
        val fl = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) fl.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        val fv = ComposeView(this).apply {
            owners(this)
            setContent { FlyIcon(bmp, tx, ty, m.widthPixels.toFloat(), m.heightPixels.toFloat()) }
        }
        try {
            wm.addView(fv, fl)
            delay(640)
        } catch (_: Exception) {
        } finally {
            try { wm.removeView(fv) } catch (_: Exception) {}
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val v = view ?: return
        Hub.hidden.value = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE && Cfg.v.value.hideLandscape
        place()
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (e.className?.startsWith("android.inputmethodservice") == true) return
        val p = e.packageName?.toString() ?: return
        if (p == packageName || p == "android" || p.contains("systemui")) return
        if (p == homePkg()) { fg?.let { Hub.pull(it) }; fg = null } else fg = p
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        try { unregisterReceiver(rx) } catch (_: Exception) {}
        scope.cancel()
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
        reg.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        super.onDestroy()
    }
}
