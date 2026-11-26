package de.lema.hyperisland

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Cfg.load(this)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize(), color = Color(0xFF05050A), contentColor = Color.White) {
            // Schriftfarbe an der Wurzel erzwingen, unabhängig von Surface/Theme
            CompositionLocalProvider(LocalContentColor provides Color.White, LocalTextStyle provides LocalTextStyle.current.copy(color = Color.White)) { Home() }
        } } }
    }
}

@Composable
fun Home() {
    val c = LocalContext.current
    val cfg by Cfg.v.collectAsState()
    var n by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); n++ } }
    val acc = remember(n) { Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains(c.packageName + "/") == true }
    val nl = remember(n) { NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName) }
    // Erlaubnis ist da, der Listener aber nicht verbunden (z. B. nach App-Update): Rebind anstoßen
    LaunchedEffect(nl) {
        if (nl && !Hub.listenerUp) try { NotificationListenerService.requestRebind(ComponentName(c, Notifs::class.java)) } catch (_: Exception) {}
    }
    fun set(f: (Config) -> Config) = Cfg.update(c, f)

    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF16161F), Color(0xFF05050A))))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp).padding(top = 40.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Hyper Island", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Dynamic Island für Android", color = Color(0xFFB4B4BC))
        val ver = remember { try { c.packageManager.getPackageInfo(c.packageName, 0).versionName } catch (_: Exception) { "?" } }
        Text("Version $ver", fontSize = 12.sp, color = Color(0xFF8A8A96))

        Sec("Einrichtung") {
            Step("Bedienungshilfe „Hyper Island“ aktivieren", acc) { c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            Step("Benachrichtigungszugriff (Musik, Nachrichten, Timer, Anrufe)", nl) { c.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            Text("Schalter ausgegraut? Einstellungen → Apps → Hyper Island → ⋮ → Eingeschränkte Einstellungen zulassen.", fontSize = 12.sp, color = Color(0xFFB4B4BC))
        }

        Sec("Testen") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tb("Nachricht", Modifier.weight(1f)) { Hub.post(Act("msg", "msg", "Lena", "Kommst du heute zum Server-Treff? 🎮", ttl = 6000, ctl = listOf(Btn("Öffnen") {}))) }
                Tb("Timer", Modifier.weight(1f)) { Hub.timer(60) }
                Tb("Laden", Modifier.weight(1f)) { Hub.post(Act("chg", "chg", "Lädt", "73 %", prog = 0.73f, ttl = 5000)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tb("Anruf", Modifier.weight(1f)) {
                    Hub.post(Act("call", "call", "Lena", "Eingehender Anruf",
                        ctl = listOf(Btn("Ablehnen", 2) { Hub.drop("call") }, Btn("Annehmen", 1) { Hub.drop("call") })))
                }
                Tb("Musik", Modifier.weight(1f)) {
                    Hub.post(Act("demo-music", "music", "Beispiel-Song", "Beispiel-Künstler", prog = 0.4f,
                        ctl = listOf(Btn("prev") {}, Btn("pause") { Hub.drop("demo-music") }, Btn("next") {})))
                }
                Tb("Vorschau", Modifier.weight(1f)) {
                    Hub.post(Act("demo", "call", "Vorschau", "Verstelle die Regler, die Island ändert sich live",
                        ctl = listOf(Btn("Schließen", 2) { Hub.drop("demo") })))
                }
            }
            Text("„Vorschau“ bleibt offen, solange du an den Reglern drehst.", fontSize = 12.sp, color = Color(0xFFB4B4BC))
        }

        Sec("Bewegung") {
            Sl("Wobbeligkeit", cfg.wobble, 0f, 1f, { "${(it * 100).toInt()} %" }) { v -> set { it.copy(wobble = v) } }
            Sl("Haltezeit aufgeklappt", cfg.holdSec, 2f, 15f, { "${it.toInt()} s" }) { v -> set { it.copy(holdSec = v) } }
        }

        Sec("Glas") {
            Sw("Lichtbrechung am Rand (Glas-Shader, Android 13+)", cfg.refract) { v -> set { it.copy(refract = v) } }
            Sl("Durchsichtigkeit (aufgeklappt)", 1f - cfg.opacity, 0f, 0.65f, { "${(it * 100).toInt()} %" }) { v -> set { it.copy(opacity = 1f - v) } }
            Sl("Durchsichtigkeit (eingeklappt)", 1f - cfg.compactOpacity, 0f, 0.5f, { "${(it * 100).toInt()} %" }) { v -> set { it.copy(compactOpacity = 1f - v) } }
            Sl("Frosted (milchig)", cfg.frost, 0f, 1f, { "${(it * 100).toInt()} %" }) { v -> set { it.copy(frost = v) } }
            Sl("Farbschimmer / Glow", cfg.glow, 0f, 1f, { "${(it * 100).toInt()} %" }) { v -> set { it.copy(glow = v) } }
            Sl("Hintergrund-Blur (experimentell, Android 12+)", cfg.blur, 0f, 60f, { if (it < 1f) "aus" else "${it.toInt()} px" }) { v -> set { it.copy(blur = v) } }
        }

        Sec("Größe und Position") {
            Sl("Größe", cfg.scale, 0.8f, 1.1f, { "${(it * 100).toInt()} %" }) { v -> set { it.copy(scale = v) } }
            Sl("Breite eingeklappt", cfg.compactW, 140f, 300f, { "${it.toInt()} dp" }) { v -> set { it.copy(compactW = v) } }
            Sl("Ecken-Rundung", cfg.corner, 12f, 44f, { "${it.toInt()} dp" }) { v -> set { it.copy(corner = v) } }
            Sl("Position horizontal", cfg.offX, -80f, 80f, { "${it.toInt()} dp" }) { v -> set { it.copy(offX = v) } }
            Sl("Position vertikal", cfg.offY, 0f, 100f, { "${it.toInt()} dp" }) { v -> set { it.copy(offY = v) } }
        }

        Sec("Anzeigen") {
            Sw("Nachrichten", cfg.showMsg) { v -> set { it.copy(showMsg = v) } }
            Sw("Musik", cfg.showMusic) { v -> set { it.copy(showMusic = v) } }
            Sw("Timer, Stoppuhr, Wecker", cfg.showTimer) { v -> set { it.copy(showTimer = v) } }
            Sw("Anrufe", cfg.showCall) { v -> set { it.copy(showCall = v) } }
            Sw("Laden", cfg.showCharge) { v -> set { it.copy(showCharge = v) } }
        }

        Sec("Verhalten") {
            Sw("Minimierte App einziehen", cfg.pull) { v -> set { it.copy(pull = v) } }
            Sw("Vibration bei langem Drücken", cfg.haptic) { v -> set { it.copy(haptic = v) } }
            Sw("Im Querformat ausblenden", cfg.hideLandscape) { v -> set { it.copy(hideLandscape = v) } }
            Sw("Sperrbildschirm: Nachrichtentext verbergen", cfg.privacy) { v -> set { it.copy(privacy = v) } }
        }

        Sec("Diagnose") {
            val crash = remember(n) { c.getSharedPreferences("crash", Context.MODE_PRIVATE).getString("last", null) }
            Text("Bedienungshilfe: " + if (acc) "an" else "AUS", fontSize = 13.sp)
            Text("Benachrichtigungszugriff: " + if (!nl) "AUS" else if (Hub.listenerUp) "an, verbunden" else "an, NICHT verbunden", fontSize = 13.sp)
            Text("Glas-Shader: ${Glass.status}", fontSize = 13.sp)
            Text("Aktive Anzeigen: " + Hub.acts.value.joinToString { it.kind + ":" + it.id }.ifEmpty { "keine" }, fontSize = 13.sp)
            if (crash != null) {
                Text("Letzter Absturz:", fontSize = 13.sp, color = Color(0xFFFF8A80))
                Text(crash.take(1500), fontSize = 10.sp, color = Color(0xFFB4B4BC))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tb("Kopieren", Modifier.weight(1f)) {
                        (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Absturz", crash))
                    }
                    Tb("Löschen", Modifier.weight(1f)) { c.getSharedPreferences("crash", Context.MODE_PRIVATE).edit().clear().apply() }
                }
            }
        }

        OutlinedButton(onClick = { Cfg.reset(c) }, Modifier.fillMaxWidth()) { Text("Alles auf Standard zurücksetzen") }
        Text("Xiaomi/HyperOS: Autostart erlauben und Akku auf „Keine Einschränkungen“ stellen.", fontSize = 12.sp, color = Color(0xFFB4B4BC))
    }
}

@Composable
fun Sec(title: String, body: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color(0x12FFFFFF), RoundedCornerShape(22.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF8FB4FF))
        body()
    }
}

@Composable
fun Sl(label: String, v: Float, lo: Float, hi: Float, fmt: (Float) -> String, set: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 14.sp)
            Text(fmt(v), fontSize = 14.sp, color = Color(0xFFB4B4BC))
        }
        Slider(v.coerceIn(lo, hi), set, valueRange = lo..hi)
    }
}

@Composable
fun Sw(label: String, v: Boolean, set: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(label, Modifier.weight(1f), fontSize = 14.sp)
    Switch(v, set)
}

@Composable
fun Tb(label: String, m: Modifier, f: () -> Unit) =
    Button(onClick = f, modifier = m, contentPadding = PaddingValues(horizontal = 4.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C3A), contentColor = Color.White)) {
        Text(label, fontSize = 13.sp, maxLines = 1)
    }

@Composable
fun Step(t: String, ok: Boolean, f: () -> Unit) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(if (ok) "✅ " else "⬜ ")
    Text(t, Modifier.weight(1f), fontSize = 14.sp)
    if (!ok) Button(onClick = f, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8FB4FF), contentColor = Color(0xFF0A1530))) { Text("Erlauben", color = Color(0xFF0A1530)) }
}
