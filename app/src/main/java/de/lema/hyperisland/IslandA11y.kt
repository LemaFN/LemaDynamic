package de.lema.hyperisland

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
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
    private var home = ""
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

    override fun onServiceConnected() {
        if (view != null) return
        ssc.performAttach()
        ssc.performRestore(null)
        reg.currentState = Lifecycle.State.RESUMED
        wm = getSystemService(WindowManager::class.java)
        val d = resources.displayMetrics.density
        home = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName ?: ""
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        lp.y = (8 * d).toInt()
        if (Build.VERSION.SDK_INT >= 30) {
            val r = wm.currentWindowMetrics.windowInsets.displayCutout?.boundingRects?.firstOrNull { it.top <= 0 }
            if (r != null) {
                lp.x = r.centerX() - resources.displayMetrics.widthPixels / 2
                lp.y = (r.centerY() - (18 * d).toInt()).coerceAtLeast(0)
            }
        }
        val v = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@IslandA11y)
            setViewTreeSavedStateRegistryOwner(this@IslandA11y)
            setViewTreeViewModelStoreOwner(this@IslandA11y)
            setContent { Island() }
        }
        view = v
        wm.addView(v, lp)
        scope.launch {
            combine(Hub.acts, Hub.exp) { l, e -> dims(topAct(l), e) }.distinctUntilChanged().collectLatest { (w, h) ->
                if (w * h < curW * curH) delay(750)
                curW = w
                curH = h
                if (w == 0) {
                    lp.width = 1
                    lp.height = 1
                    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                } else {
                    lp.width = ((w + 2 * M) * d).toInt()
                    lp.height = ((h + M) * d).toInt()
                    lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                }
                wm.updateViewLayout(v, lp)
            }
        }
        ContextCompat.registerReceiver(this, rx, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (e.className?.startsWith("android.inputmethodservice") == true) return
        val p = e.packageName?.toString() ?: return
        if (p == packageName || p == "android" || p.contains("systemui")) return
        if (p == home) { fg?.let { Hub.pull(it) }; fg = null } else fg = p
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        try { unregisterReceiver(rx) } catch (_: Exception) {}
        scope.cancel()
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        reg.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        super.onDestroy()
    }
}
