package io.github.brickystudios.antwortradar

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** Grouped list: a muted label over each group, rows parted by hairlines, the explanation as a footnote below. */
@Composable
fun SettingsScreen(now: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var hour by remember { mutableIntStateOf(prefs.hour) }
    var names by remember { mutableStateOf(prefs.names) }
    var replies by remember { mutableStateOf(prefs.replies.joinToString("\n")) }
    var weeks by remember { mutableIntStateOf(prefs.quietWeeks) }
    var nudge by remember { mutableIntStateOf(prefs.nudgeHours) }
    var autoUpdate by remember { mutableStateOf(prefs.autoUpdate) }
    val update by Updater.state.collectAsStateWithLifecycle()
    val note by Updater.note.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var ignored by remember { mutableStateOf(prefs.ignored) }
    var showAll by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val log by Store.log.collectAsStateWithLifecycle()
    val installed = remember { MESSENGERS.filter { ctx.packageManager.getLaunchIntentForPackage(it.pkg) != null } }
    val sleeping = !ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    val field = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent, focusedBorderColor = Coral)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Text1) }
            Text(stringResource(R.string.settings), color = Text1, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
        }

        // why messages may arrive late: an unbound listener or a battery saver that stops it
        SectionLabel(stringResource(R.string.settings_connection))
        Group {
            Text(stringResource(if (Live.connected) R.string.connection_ok else R.string.connection_off), color = Text1, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
            Radar.detection(log).forEach { (app, r) ->
                GroupDivider()
                Text(
                    if (r.first) stringResource(R.string.selftest_ok, messengerLabel(app), r.second) else stringResource(R.string.selftest_bad, messengerLabel(app), r.second),
                    color = if (r.first) Text1 else Coral, fontSize = 15.sp, modifier = Modifier.padding(16.dp),
                )
            }
            GroupDivider()
            TapRow(stringResource(R.string.diagnostics), Coral) {
                Diagnostics.share(ctx)
                Toast.makeText(ctx, R.string.diagnostics_copied, Toast.LENGTH_LONG).show()
            }
            if (sleeping) {
                GroupDivider()
                TapRow(stringResource(R.string.battery_button), Coral) {
                    @SuppressLint("BatteryLife")
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))
                    runCatching { ctx.startActivity(i) }
                }
            }
        }
        if (sleeping) Footnote(stringResource(R.string.battery_body))

        SectionLabel(stringResource(R.string.settings_reminder))
        Group {
            Row(Modifier.padding(horizontal = 8.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { hour = (hour + 23) % 24; prefs.hour = hour; Summary.schedule(ctx) }) { Text("−", color = Coral, fontSize = 22.sp) }
                Text(stringResource(R.string.hour_fmt, hour), color = Text1, fontSize = 17.sp)
                TextButton(onClick = { hour = (hour + 1) % 24; prefs.hour = hour; Summary.schedule(ctx) }) { Text("+", color = Coral, fontSize = 22.sp) }
            }
            GroupDivider()
            TapRow(stringResource(R.string.test_now), Coral) { Summary.show(ctx) }
        }

        SectionLabel(stringResource(R.string.settings_messengers))
        Group {
            val shown = if (showAll || installed.isEmpty()) MESSENGERS else installed
            shown.forEachIndexed { i, m ->
                if (i > 0) GroupDivider()
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.label, color = Text1, fontSize = 16.sp)
                        if (!m.onByDefault) Text(stringResource(R.string.untested), color = Text2, fontSize = 13.sp)
                    }
                    Switch(
                        checked = m.pkg in enabled,
                        onCheckedChange = { on -> enabled = if (on) enabled + m.pkg else enabled - m.pkg; prefs.enabled = enabled },
                        colors = SwitchDefaults.colors(checkedTrackColor = Coral, checkedThumbColor = Ink),
                    )
                }
            }
            if (!showAll && installed.isNotEmpty() && installed.size < MESSENGERS.size) {
                GroupDivider()
                TapRow(stringResource(R.string.show_all), Coral) { showAll = true }
            }
        }
        if (installed.isEmpty()) Footnote(stringResource(R.string.none_installed))

        SectionLabel(stringResource(R.string.settings_groups))
        Group {
            OutlinedTextField(
                value = names, onValueChange = { names = it; prefs.names = it }, colors = field,
                placeholder = { Text(stringResource(R.string.names_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        Footnote(stringResource(R.string.groups_body))

        SectionLabel(stringResource(R.string.settings_replies))
        Group {
            OutlinedTextField(value = replies, onValueChange = { replies = it; prefs.replies = it.lines() }, colors = field, modifier = Modifier.fillMaxWidth(), minLines = 3)
        }
        Footnote(stringResource(R.string.replies_body))

        SectionLabel(stringResource(R.string.settings_favorites))
        Group {
            Stepper(stringResource(R.string.nudge_label), stringResource(R.string.age_hour, nudge),
                { nudge = (nudge - 1).coerceAtLeast(1); prefs.nudgeHours = nudge; Reminders.schedule(ctx) },
                { nudge = (nudge + 1).coerceAtMost(48); prefs.nudgeHours = nudge; Reminders.schedule(ctx) })
            GroupDivider()
            Stepper(stringResource(R.string.quiet_label), pluralStringResource(R.plurals.weeks, weeks, weeks),
                { weeks = (weeks - 1).coerceAtLeast(1); prefs.quietWeeks = weeks },
                { weeks = (weeks + 1).coerceAtMost(26); prefs.quietWeeks = weeks })
        }
        Footnote(stringResource(R.string.favorites_body))

        SectionLabel(stringResource(R.string.settings_ignored))
        Group {
            if (ignored.isEmpty()) Text(stringResource(R.string.none), color = Text2, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
            ignored.entries.forEachIndexed { i, (key, name) ->
                if (i > 0) GroupDivider()
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$name  ${messengerLabel(key.substringBefore('|'))}", color = Text1, modifier = Modifier.weight(1f))
                    TextButton(onClick = { ignored = ignored - key; prefs.ignored = ignored }) { Text(stringResource(R.string.unignore), color = Coral) }
                }
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            SectionLabel(stringResource(R.string.settings_log), Modifier.weight(1f))
            if (log.isNotEmpty()) TextButton(onClick = Store::clearLog) { Text(stringResource(R.string.clear), color = Coral) }
        }
        Group {
            if (log.isEmpty()) Text(stringResource(R.string.log_empty), color = Text2, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
            log.forEachIndexed { i, l ->
                if (i > 0) GroupDivider()
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(shortTime(ctx, l.time, now), color = Text2, fontSize = 13.sp, modifier = Modifier.padding(end = 12.dp))
                    Text("${l.name}: ${evText(ctx, l)}", color = Text1, fontSize = 13.sp)
                }
            }
        }
        Footnote(stringResource(R.string.log_body))

        SectionLabel(stringResource(R.string.updates_title))
        Group {
            if (Updater.supported) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.auto_update), color = Text1, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = autoUpdate, onCheckedChange = { autoUpdate = it; prefs.autoUpdate = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Coral, checkedThumbColor = Ink),
                    )
                }
                GroupDivider()
                val u = update
                if (u != null) TapRow(stringResource(R.string.update_available, u.release.version) + " · " + stringResource(R.string.update_install), Coral) {
                    scope.launch { Updater.install(ctx) }
                } else TapRow(stringResource(R.string.check_now), Coral) { scope.launch { Updater.check(ctx, manual = true) } }
                note?.let { Text(stringResource(it), color = Text2, fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) }
            } else {
                TapRow(stringResource(R.string.open_releases), Coral) {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Releases.PAGE))) }
                }
            }
        }
        Footnote(stringResource(if (Updater.supported) R.string.updates_online_body else R.string.updates_offline_body))

        Group(Modifier.padding(top = 32.dp)) {
            TapRow(stringResource(if (confirmDelete) R.string.delete_confirm else R.string.delete_all), Coral) {
                if (!confirmDelete) { confirmDelete = true; return@TapRow }
                Store.clearAll(); confirmDelete = false
                Toast.makeText(ctx, R.string.deleted, Toast.LENGTH_SHORT).show()
            }
        }

        TextButton(
            onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/BrickyStudios/unreplied"))) } },
            modifier = Modifier.padding(top = 16.dp),
        ) { Text(stringResource(R.string.about, BuildConfig.VERSION_NAME), color = Text2, fontSize = 12.sp) }
    }
}

/** Label left, value with − and + right. */
@Composable
private fun Stepper(label: String, value: String, minus: () -> Unit, plus: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Text1, fontSize = 16.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = minus) { Text("−", color = Coral, fontSize = 22.sp) }
        Text(value, color = Text1, fontSize = 16.sp)
        TextButton(onClick = plus) { Text("+", color = Coral, fontSize = 22.sp) }
    }
}

/** A full-width tappable row inside a group: the whole row is the target, at least 52dp tall. */
@Composable
private fun TapRow(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label, color = color, fontSize = 16.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 15.dp),
    )
}
