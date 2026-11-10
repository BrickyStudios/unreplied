package io.github.brickystudios.antwortradar

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Warm coral on dark ink
private val Ink = Color(0xFF14121C)
private val Card = Color(0xFF1F1C2B)
private val Line = Color(0xFF3A3550)
private val Text1 = Color(0xFFEDEAF5)
private val Text2 = Color(0xFFA29CB5)
private val Coral = Color(0xFFFF8A65)

class MainActivity : ComponentActivity() {
    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Card, primary = Coral, onPrimary = Ink)) {
                Box(Modifier.fillMaxSize().background(Ink).safeDrawingPadding()) { RadarScreen() }
            }
        }
    }
}

@Composable
private fun RadarScreen() {
    val ctx = LocalContext.current
    val entries by Store.entries.collectAsStateWithLifecycle()
    var allowed by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleResumeEffect(Unit) {
        allowed = ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx)
        now = System.currentTimeMillis()
        onPauseOrDispose {}
    }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } } // ages tick on screen

    val waiting = Radar.waiting(entries.values)
    val unread = Radar.unread(entries.values)
    val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Wartet auf dich", color = Text1, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (waiting.size == 1) "1 Person · gelesen, unbeantwortet"
                        else "${waiting.size} Leute · gelesen, unbeantwortet",
                        color = Text2, fontSize = 14.sp,
                    )
                }
                TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "Fertig" else "Einstellungen", color = Coral) }
            }
        }
        if (!allowed) item { AccessCard() }
        if (showSettings) item { SettingsCard() }
        if (waiting.isEmpty() && allowed && !showSettings) item { Empty(Radar.doneSince(entries.values, today)) }
        items(waiting, key = { it.key }) { e ->
            Swipeable(onDone = { Store.put(e.key, Radar.done(e, System.currentTimeMillis()), "in der App erledigt") }) {
                ChatRow(e, now, dim = false)
            }
        }
        if (waiting.isNotEmpty()) item {
            Text("← wischen = erledigt · tippen = WhatsApp öffnen", color = Text2, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        if (unread.isNotEmpty()) {
            item { Text("Ungelesen (zeigt WhatsApp auch, nur zur Info)", color = Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            items(unread, key = { "u" + it.key }) { ChatRow(it, now, dim = true) }
        }
        item {
            Text("Gespeichert: Name, Uhrzeit, erste ${Radar.PREVIEW} Zeichen. Die App hat keinen Internetzugriff.",
                color = Text2, fontSize = 11.sp, modifier = Modifier.padding(vertical = 16.dp))
        }
    }
}

@Composable
private fun Swipeable(onDone: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it == SwipeToDismissBoxValue.EndToStart) onDone()
        it == SwipeToDismissBoxValue.EndToStart
    })
    SwipeToDismissBox(
        state, enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(Coral, RoundedCornerShape(12.dp)).padding(horizontal = 20.dp), Alignment.CenterEnd) {
                Text("erledigt", color = Ink, fontWeight = FontWeight.Bold)
            }
        },
    ) { content() }
}

@Composable
private fun ChatRow(e: Entry, now: Long, dim: Boolean) {
    val ctx = LocalContext.current
    val old = !dim && now - e.since > 2 * Radar.DAY
    val openWhatsApp = {
        Radar.PACKAGES.firstNotNullOfOrNull { ctx.packageManager.getLaunchIntentForPackage(it) }?.let(ctx::startActivity)
        Unit
    }
    Row(
        Modifier.fillMaxWidth().alpha(if (dim) 0.55f else 1f)
            .background(Card, RoundedCornerShape(12.dp))
            .border(BorderStroke(if (old) 2.dp else 1.dp, if (old) Text1 else Line), RoundedCornerShape(12.dp))
            .clickable(onClick = openWhatsApp)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).border(1.dp, Line, CircleShape), Alignment.Center) {
            Text(if (e.group) "#" else e.name.take(1).uppercase(), color = Text2)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name + if (e.group) "  @dich" else "", color = Text1, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(e.preview, color = Text2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(Radar.age(now - (if (dim) e.last else e.since)), color = Text1, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (!dim) Text("gelesen " + shortTime(e.readAt), color = Text2, fontSize = 11.sp)
        }
    }
}

private val hm = SimpleDateFormat("HH:mm", Locale.GERMANY)
private val weekday = SimpleDateFormat("EE", Locale.GERMANY)

/** "13:20" today, "Fr" for older */
private fun shortTime(t: Long): String = (if (System.currentTimeMillis() - t < Radar.DAY) hm else weekday).format(Date(t))

@Composable
private fun Empty(doneToday: Int) {
    Column(Modifier.fillMaxWidth().padding(top = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("✓", color = Coral, fontSize = 44.sp)
        Text("Niemand wartet auf dich.", color = Text1, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (doneToday > 0) Text("Heute $doneToday erledigt.", color = Text2, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun AccessCard() {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).border(1.dp, Coral, RoundedCornerShape(12.dp)).padding(16.dp)) {
        Text("Benachrichtigungen mitlesen erlauben", color = Text1, fontWeight = FontWeight.SemiBold)
        Text(
            "Nur so sieht der Radar, wann du einen WhatsApp-Chat geöffnet hast. Er liest nur WhatsApp, und nichts verlässt das Handy.",
            color = Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        Button(
            onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Ink),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Zugriff erlauben") }
    }
}

@Composable
private fun SettingsCard() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var hour by remember { mutableIntStateOf(prefs.hour) }
    var names by remember { mutableStateOf(prefs.names) }
    val log by Store.log.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(12.dp)).padding(16.dp)) {
        Text("Tagesmeldung", color = Text1, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { hour = (hour + 23) % 24; prefs.hour = hour; Summary.schedule(ctx) }) { Text("−", color = Coral, fontSize = 22.sp) }
            Text("%02d:00 Uhr".format(hour), color = Text1, fontSize = 18.sp)
            TextButton(onClick = { hour = (hour + 1) % 24; prefs.hour = hour; Summary.schedule(ctx) }) { Text("+", color = Coral, fontSize = 22.sp) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { Summary.show(ctx) }) { Text("Jetzt testen", color = Coral) }
        }
        Spacer(Modifier.height(8.dp))
        Text("Gruppen", color = Text1, fontWeight = FontWeight.SemiBold)
        Text("Gruppenchats erscheinen nur, wenn du darin @erwähnt wirst. Trag ein, wie du in Gruppen heißt (mehrere mit Komma).",
            color = Text2, fontSize = 13.sp)
        OutlinedTextField(
            value = names, onValueChange = { names = it; prefs.names = it },
            placeholder = { Text("z. B. Alex, Al") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Prüfprotokoll", color = Text1, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (log.isNotEmpty()) TextButton(onClick = Store::clearLog) { Text("leeren", color = Coral) }
        }
        Text("Zeigt, mit welchem Grund-Code WhatsApp eine Benachrichtigung entfernt. 8 oder 1 beim Öffnen eines Chats heißt: Die Lese-Erkennung funktioniert auf deinem Handy.",
            color = Text2, fontSize = 13.sp)
        if (log.isEmpty()) Text("Noch nichts. Lass dir eine WhatsApp schicken und öffne den Chat.", color = Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        log.forEach {
            Text("${hm.format(Date(it.time))}  ${it.name}: ${it.event}", color = Text1, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
