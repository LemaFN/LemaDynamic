package de.lema.hyperisland

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { Home() } } }
    }
}

@Composable
fun Home() {
    val c = LocalContext.current
    var n by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); n++ } }
    val acc = remember(n) { Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains(c.packageName + "/") == true }
    val nl = remember(n) { NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName) }
    Column(Modifier.padding(24.dp).padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Hyper Island", fontSize = 32.sp)
        Text("Dynamic Island für Android")
        Step("Bedienungshilfe „Hyper Island“ aktivieren", acc) { c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        Step("Benachrichtigungszugriff (Musik, Nachrichten, Anrufe)", nl) { c.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        Text("Falls ein Schalter ausgegraut ist: Einstellungen → Apps → Hyper Island → ⋮ → Eingeschränkte Einstellungen zulassen.", fontSize = 12.sp)
        Text("Tests")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { Hub.post(Act("msg", "msg", "Lena", "Kommst du heute zum Server-Treff? 🎮", ttl = 6000, ctl = listOf(Btn("Öffnen") {}))) }) { Text("Nachricht") }
            Button(onClick = { Hub.timer(60) }) { Text("Timer") }
        }
    }
}

@Composable
fun Step(t: String, ok: Boolean, f: () -> Unit) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(if (ok) "✅ " else "⬜ ")
    Text(t, Modifier.weight(1f))
    if (!ok) Button(onClick = f) { Text("Erlauben") }
}
