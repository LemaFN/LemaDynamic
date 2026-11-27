package de.lema.hyperisland

import android.accessibilityservice.AccessibilityService
import android.bluetooth.BluetoothDevice
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
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
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

    private val btSeen = HashMap<String, Long>()

    private fun bt(i: Intent) {
        val connected = i.action == BluetoothDevice.ACTION_ACL_CONNECTED
        val dev = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        val addr = dev?.address ?: "?"
        val now = System.currentTimeMillis()
        val key = addr + connected
        // Manche Geräte (v. a. BLE) melden dasselbe Ereignis mehrfach: Wiederholungen innerhalb 4 s ignorieren
        if (now - (btSeen[key] ?: 0L) < 4000) return
        btSeen[key] = now
        val may = Build.VERSION.SDK_INT < 31 || checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val name = try { if (may) dev?.name else null } catch (_: SecurityException) { null }
        Hub.post(Act("bt", "bt", name ?: "Bluetooth-Gerät", if (connected) "Verbunden" else "Getrennt", ttl = 4000))
    }

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == BluetoothDevice.ACTION_ACL_CONNECTED || i.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) { bt(i); return }
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
        addTouchWindow()
        scope.launch {
            combine(Hub.acts, Hub.exp, Hub.hidden, Cfg.v) { l, e, hd, c ->
                val top = if (hd) null else topAct(l)
                val (w, h) = if (top != null) dims(top, e, c.compactW.toInt()) else 0 to 0
                WinState(top != null, e && top != null, w, h, c)
            }
                .distinctUntilChanged()
                .collectLatest { applyWindow(v, it) }
        }
        scope.launch { Cfg.v.collect { Hub.hidden.value = landscape() && it.hideLandscape } }
        scope.launch { Hub.fly.collect { pkg -> launch { playFly(pkg) } } }
        ContextCompat.registerReceiver(this, rx, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private class WinState(val live: Boolean, val exp: Boolean, val w: Int, val h: Int, val c: Config)

    private var shown = false
    private var tv: View? = null
    private val tlp = WindowManager.LayoutParams(
        1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    )

    /**
     * Das Inselfenster ändert seine Größe NICHT, solange die Island sichtbar ist (feste Größe für die ausgeklappte Island).
     * Grund: Bei jeder Größenänderung zeigt das System kurz den alten Inhalt am neuen Rahmen, die Island ruckte dadurch zur Seite.
     * Eingeklappt ist das große Fenster nicht berührbar, Tippen übernimmt ein kleines, unsichtbares Touch-Fenster über der Pille.
     */
    private suspend fun applyWindow(v: ComposeView, st: WinState) {
        val c = st.c
        val d = resources.displayMetrics.density
        val nt = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (!st.live) {
            // Aus: erst die Ausblend-Animation laufen lassen, dann Fenster auf 1x1
            if (shown) delay((450 + 650 * c.wobble).toLong())
            shown = false
            place()
            lp.width = 1
            lp.height = 1
            lp.flags = lp.flags or nt
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            lp.blurBehindRadius = 0
            try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
            touchWindow(false)
            return
        }
        shown = true
        place()
        val blur = c.blur > 0f && Build.VERSION.SDK_INT >= 31
        if (c.blur > 0f) {
            // Experimentell (Blur): Fenster eng um die Island, dafür mit dem alten Verhalten (Größe folgt der Island)
            lp.width = (st.w * c.scale * d).toInt()
            lp.height = (st.h * c.scale * d).toInt()
            lp.flags = lp.flags and nt.inv()
            touchWindow(false)
        } else {
            val bw = maxOf(344f, c.compactW)
            // Extra Platz rundum, damit das Überschwingen (Wobble) und der Schatten nicht abgeschnitten werden
            lp.width = ((bw * c.scale + 2 * 44) * d).toInt()
            lp.height = ((EXP_MAX_H * c.scale + 56) * d).toInt()
            lp.flags = if (st.exp) lp.flags and nt.inv() else lp.flags or nt
        }
        if (Build.VERSION.SDK_INT >= 31) {
            if (blur && st.h > 36) {
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                lp.blurBehindRadius = c.blur.toInt()
            } else {
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                lp.blurBehindRadius = 0
            }
        }
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
        if (c.blur <= 0f) touchWindow(!st.exp)
    }

    /** Unsichtbares Fenster genau über der eingeklappten Pille: Tippen, Halten, Wischen. */
    private fun touchWindow(on: Boolean) {
        val t = tv ?: return
        val c = Cfg.v.value
        val d = resources.displayMetrics.density
        val nt = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (on) {
            tlp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            tlp.x = lp.x
            tlp.y = lp.y
            tlp.width = (c.compactW * c.scale * d).toInt()
            tlp.height = (36 * c.scale * d).toInt()
            tlp.flags = tlp.flags and nt.inv()
        } else {
            tlp.width = 1
            tlp.height = 1
            tlp.flags = tlp.flags or nt
        }
        if (Build.VERSION.SDK_INT >= 28) tlp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        try { wm.updateViewLayout(t, tlp) } catch (_: Exception) {}
    }

    private fun addTouchWindow() {
        val t = View(this)
        val d = resources.displayMetrics.density
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { Hub.exp.value = !Hub.exp.value; return true }
            override fun onLongPress(e: MotionEvent) {
                if (Cfg.v.value.haptic) t.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                Hub.exp.value = true
            }
        })
        var downX = 0f
        var downY = 0f
        t.setOnTouchListener { _, e ->
            gd.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY }
                MotionEvent.ACTION_UP -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (kotlin.math.abs(dx) > 90 * d && kotlin.math.abs(dx) > kotlin.math.abs(dy)) topAct(Hub.acts.value)?.let { Hub.dismiss(it) }
                    else if (dy > 30 * d && kotlin.math.abs(dy) > kotlin.math.abs(dx)) Hub.exp.value = true
                }
            }
            true
        }
        tlp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        try { wm.addView(t, tlp); tv = t } catch (_: Exception) {}
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
        if (tlp.width > 1) touchWindow(true)
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
        tv?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        tv = null
        view = null
        reg.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        super.onDestroy()
    }
}
