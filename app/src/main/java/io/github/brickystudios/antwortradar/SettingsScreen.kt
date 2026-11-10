package io.github.brickystudios.antwortradar

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(now: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var hour by remember { mutableIntStateOf(prefs.hour) }
    var names by remember { mutableStateOf(prefs.names) }
    var replies by remember { mutableStateOf(prefs.replies.joinToString("\n")) }
    var weeks by remember { mutableIntStateOf(prefs.quietWeeks) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var ignored by remember { mutableStateOf(prefs.ignored) }
    var showAll by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val log by Store.log.collectAsStateWithLifecycle()
    val installed = remember { MESSENGERS.filter { ctx.packageManager.getLaunchIntentForPackage(it.pkg) != null } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← " + stringResource(R.string.back), color = Coral) }
            Text(stringResource(R.string.settings), color = Text1, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Panel {
            Heading(stringResource(R.string.settings_reminder))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { hour = (hour + 23) % 24; prefs.hour = hour; Summary.schedule(ctx) }) { Text("−", color = Coral, fontSize = 22.sp) }
                Text(stringResource(R.string.hour_fmt, hour), color = Text1, fontSize = 18.sp)
                TextButton(onClick = { hour = (hour + 1) % 24; prefs.hour = hour; Summary.schedule(ctx) }) { Text("+", color = Coral, fontSize = 22.sp) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { Summary.show(ctx) }) { Text(stringResource(R.string.test_now), color = Coral) }
            }
        }

        Panel {
            Heading(stringResource(R.string.settings_messengers))
            val shown = if (showAll || installed.isEmpty()) MESSENGERS else installed
            if (installed.isEmpty()) Body(stringResource(R.string.none_installed), Modifier.padding(top = 4.dp))
            shown.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.label, color = Text1)
                        if (!m.onByDefault) Body(stringResource(R.string.untested))
                    }
                    Switch(
                        checked = m.pkg in enabled,
                        onCheckedChange = { on -> enabled = if (on) enabled + m.pkg else enabled - m.pkg; prefs.enabled = enabled },
                        colors = SwitchDefaults.colors(checkedTrackColor = Coral, checkedThumbColor = Ink),
                    )
                }
            }
            if (!showAll && installed.isNotEmpty() && installed.size < MESSENGERS.size)
                TextButton(onClick = { showAll = true }) { Text(stringResource(R.string.show_all), color = Coral) }
        }

        Panel {
            Heading(stringResource(R.string.settings_groups))
            Body(stringResource(R.string.groups_body))
            OutlinedTextField(
                value = names, onValueChange = { names = it; prefs.names = it },
                placeholder = { Text(stringResource(R.string.names_hint)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }

        Panel {
            Heading(stringResource(R.string.settings_replies))
            Body(stringResource(R.string.replies_body))
            OutlinedTextField(
                value = replies, onValueChange = { replies = it; prefs.replies = it.lines() },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp), minLines = 3,
            )
        }

        Panel {
            Heading(stringResource(R.string.settings_quiet))
            Body(stringResource(R.string.quiet_body))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { weeks = (weeks - 1).coerceAtLeast(1); prefs.quietWeeks = weeks }) { Text("−", color = Coral, fontSize = 22.sp) }
                Text(pluralStringResource(R.plurals.weeks, weeks, weeks), color = Text1, fontSize = 18.sp)
                TextButton(onClick = { weeks = (weeks + 1).coerceAtMost(26); prefs.quietWeeks = weeks }) { Text("+", color = Coral, fontSize = 22.sp) }
            }
        }

        Panel {
            Heading(stringResource(R.string.settings_ignored))
            if (ignored.isEmpty()) Body(stringResource(R.string.none))
            ignored.forEach { (key, name) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$name · ${messengerLabel(key.substringBefore('|'))}", color = Text1, modifier = Modifier.weight(1f))
                    TextButton(onClick = { ignored = ignored - key; prefs.ignored = ignored }) { Text(stringResource(R.string.unignore), color = Coral) }
                }
            }
        }

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Heading(stringResource(R.string.settings_log), Modifier.weight(1f))
                if (log.isNotEmpty()) TextButton(onClick = Store::clearLog) { Text(stringResource(R.string.clear), color = Coral) }
            }
            Body(stringResource(R.string.log_body))
            if (log.isEmpty()) Body(stringResource(R.string.log_empty), Modifier.padding(top = 8.dp))
            log.forEach {
                Text("${shortTime(ctx, it.time, now)}  ${it.name}: ${evText(ctx, it)}", color = Text1, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        OutlinedButton(
            onClick = {
                if (!confirmDelete) { confirmDelete = true; return@OutlinedButton }
                Store.clearAll(); confirmDelete = false
                Toast.makeText(ctx, R.string.deleted, Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(if (confirmDelete) R.string.delete_confirm else R.string.delete_all), color = if (confirmDelete) Coral else Text1) }

        TextButton(onClick = {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/BrickyStudios/antwort-radar"))) }
        }) { Text(stringResource(R.string.about, BuildConfig.VERSION_NAME), color = Text2, fontSize = 12.sp) }
    }
}
