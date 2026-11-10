package io.github.brickystudios.antwortradar

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ListScreen(now: Long, onSettings: () -> Unit, onSprint: (String?) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val entries by Store.entries.collectAsStateWithLifecycle()
    val contacts by Store.contacts.collectAsStateWithLifecycle()
    var allowed by remember { mutableStateOf(true) }
    var triedAccess by remember { mutableStateOf(false) }
    var onboarded by remember { mutableStateOf(prefs.onboarded) }
    LifecycleResumeEffect(Unit) {
        allowed = ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx)
        onPauseOrDispose {}
    }

    val waiting = Radar.waiting(entries.values, now)
    val snoozed = Radar.snoozed(entries.values, now)
    val quiet = Radar.quiet(contacts.values, now, prefs.quietWeeks)
    val unread = Radar.unread(entries.values)
    val weekDone = Radar.doneSince(entries.values, now - Radar.WEEK)
    val showBadges = entries.values.map { it.app }.distinct().size > 1

    fun done(e: Entry) { if (Store.entries.value[e.key]?.state != State.DONE) Store.put(e.key, Radar.done(e, System.currentTimeMillis()), Ev.DONE) }
    fun later(e: Entry, until: Long) { if (Store.entries.value[e.key]?.snoozeUntil != until) Store.put(e.key, Radar.snooze(e, until), Ev.SNOOZED) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.waiting_title), color = Text1, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    val meta = pluralStringResource(R.plurals.waiting_meta, waiting.size, waiting.size)
                    Text(if (weekDone > 0) meta + " · " + stringResource(R.string.week_done, weekDone) else meta, color = Text2, fontSize = 14.sp)
                }
                TextButton(onClick = onSettings) { Text(stringResource(R.string.settings), color = Coral) }
            }
        }
        if (!allowed) item {
            Panel(border = Coral) {
                Heading(stringResource(R.string.access_title))
                Body(stringResource(R.string.access_body), Modifier.padding(top = 4.dp, bottom = 12.dp))
                Button(
                    onClick = { triedAccess = true; ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                    colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Ink), modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.access_button)) }
                if (triedAccess) {
                    Body(stringResource(R.string.restricted_help), Modifier.padding(top = 12.dp))
                    OutlinedButton(
                        onClick = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text(stringResource(R.string.restricted_button), color = Text1) }
                }
            }
        }
        if (!onboarded) item {
            Panel {
                Heading(stringResource(R.string.onboard_title))
                listOf(R.string.onboard_1, R.string.onboard_2, R.string.onboard_3).forEach { Body(stringResource(it), Modifier.padding(top = 6.dp)) }
                TextButton(onClick = { prefs.onboarded = true; onboarded = true }, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.onboard_ok), color = Coral)
                }
            }
        }
        if (waiting.size >= 2) item {
            Button(
                onClick = { onSprint(null) }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Ink),
            ) { Text(stringResource(R.string.start_sprint), fontWeight = FontWeight.Bold) }
        }
        if (waiting.isEmpty() && allowed) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("✓", color = Coral, fontSize = 44.sp)
                Text(stringResource(R.string.empty_title), color = Text1, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                val today = Radar.doneSince(entries.values, startOfDay(now))
                if (today > 0) Body(pluralStringResource(R.plurals.done_today, today, today), Modifier.padding(top = 6.dp))
            }
        }
        items(waiting, key = { it.key }) { e ->
            Swipe(
                left = stringResource(R.string.done) to { done(e) },
                right = stringResource(R.string.later_lc) to { later(e, Radar.laterTimes(System.currentTimeMillis(), prefs.hour)[0]) },
            ) {
                EntryRow(e, now, showBadges, prefs, onSprint, ::done, ::later)
            }
        }
        if (waiting.isNotEmpty()) item {
            Text(stringResource(R.string.hint_swipe), color = Text2, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        if (snoozed.isNotEmpty()) {
            item { Section(stringResource(R.string.section_later)) }
            items(snoozed, key = { "s" + it.key }) { e ->
                Swipe(left = stringResource(R.string.done) to { done(e) }, right = null) {
                    EntryRow(e, now, showBadges, prefs, onSprint, ::done, ::later, snoozedRow = true)
                }
            }
        }
        if (quiet.isNotEmpty()) {
            item { Section(stringResource(R.string.section_quiet)) }
            items(quiet, key = { "q" + it.key }) { c ->
                Swipe(left = stringResource(R.string.checked_in) to { Store.touch(c.key, c.name, System.currentTimeMillis()) }, right = null) {
                    ChatRow(
                        c.name, stringResource(R.string.quiet_since, ageText(ctx, now - c.last)), "★", null,
                        group = false, badge = if (showBadges) messengerLabel(c.key.substringBefore('|')) else null,
                        strong = false, dim = false, onClick = { Live.open(ctx, c.key) },
                    )
                }
            }
        }
        if (unread.isNotEmpty()) {
            item { Section(stringResource(R.string.section_unread)) }
            items(unread, key = { "u" + it.key }) { e ->
                ChatRow(
                    e.name, e.preview, ageText(ctx, now - e.last), null, e.group,
                    if (showBadges) messengerLabel(e.app) else null, strong = false, dim = true, onClick = { Live.open(ctx, e.key) },
                )
            }
        }
        item { Body(stringResource(R.string.stored_note), Modifier.padding(vertical = 16.dp)) }
    }
}

@Composable
private fun Section(title: String) = Text(title, color = Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))

/** One waiting (or snoozed) chat with its long-press menu. */
@Composable
private fun EntryRow(
    e: Entry, now: Long, showBadges: Boolean, prefs: Prefs, onSprint: (String?) -> Unit,
    done: (Entry) -> Unit, later: (Entry, Long) -> Unit, snoozedRow: Boolean = false,
) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val fav = Store.contacts.collectAsStateWithLifecycle().value[e.key]?.favorite == true
    Box {
        ChatRow(
            e.name, e.preview, ageText(ctx, now - e.since),
            if (snoozedRow) stringResource(R.string.back_at, whenText(ctx, e.snoozeUntil, now))
            else stringResource(R.string.read_at, shortTime(ctx, e.readAt, now)),
            e.group, if (showBadges) messengerLabel(e.app) else null,
            strong = !snoozedRow && now - e.since > 2 * Radar.DAY, dim = snoozedRow,
            onClick = { Live.open(ctx, e.key) }, onLongClick = { menu = true },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            @Composable fun item(text: String, action: () -> Unit) = DropdownMenuItem(text = { Text(text) }, onClick = { menu = false; action() })
            item(stringResource(R.string.menu_reply)) { onSprint(e.key) }
            if (snoozedRow) item(stringResource(R.string.menu_back_now)) { Store.put(e.key, e.copy(snoozeUntil = 0)) }
            val times = Radar.laterTimes(now, prefs.hour)
            listOf(ctx.getString(R.string.in_3h), whenText(ctx, times[1], now), whenText(ctx, times[2], now)).forEachIndexed { i, label ->
                item(ctx.getString(R.string.menu_later, label)) { later(e, times[i]) }
            }
            item(stringResource(if (fav) R.string.menu_unfavorite else R.string.menu_favorite)) { Store.setFavorite(e.key, e.name, !fav) }
            item(stringResource(R.string.menu_ignore)) {
                prefs.ignored = prefs.ignored + (e.key to e.name)
                Store.put(e.key, null, Ev.IGNORED)
            }
            item(stringResource(R.string.menu_done)) { done(e) }
        }
    }
}
