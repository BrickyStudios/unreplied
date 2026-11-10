package io.github.brickystudios.antwortradar

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.launch

@Composable
fun ListScreen(now: Long, onSettings: () -> Unit, onSprint: (String?) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val still = remember { reducedMotion(ctx) }
    val entries by Store.entries.collectAsStateWithLifecycle()
    val contacts by Store.contacts.collectAsStateWithLifecycle()
    var allowed by remember { mutableStateOf(true) }
    var triedAccess by remember { mutableStateOf(false) }
    var onboarded by remember { mutableStateOf(prefs.onboarded) }
    var swiped by remember { mutableStateOf(prefs.swiped) }
    var selfTestDone by remember { mutableStateOf(prefs.selfTestDone) }
    val log by Store.log.collectAsStateWithLifecycle()
    val update by Updater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
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

    fun learned() { if (!swiped) { swiped = true; prefs.swiped = true } }
    fun done(e: Entry) {
        learned()
        if (Store.entries.value[e.key]?.state != State.DONE) Store.put(e.key, Radar.done(e, System.currentTimeMillis()), Ev.DONE)
    }
    fun later(e: Entry, until: Long) {
        learned()
        if (Store.entries.value[e.key]?.snoozeUntil != until) Store.put(e.key, Radar.snooze(e, until), Ev.SNOOZED)
    }
    // rows slide into their new place instead of jumping; under "remove animations" they just appear
    @Composable
    fun LazyItemScope.moving(): Modifier = if (still) Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null)
    else Modifier.animateItem(placementSpec = spring(dampingRatio = 1f, stiffness = SPRING_STIFFNESS, visibilityThreshold = androidx.compose.ui.unit.IntOffset(1, 1)))

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.waiting_title), color = Text1, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
                    val meta = pluralStringResource(R.plurals.waiting_meta, waiting.size, waiting.size)
                    Text(if (weekDone > 0) meta + "\n" + stringResource(R.string.week_done, weekDone) else meta, color = Text2, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
                }
                IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, stringResource(R.string.settings), tint = Text2) }
            }
        }
        update?.let { u ->
            item(key = "update") {
                Group {
                    Column(Modifier.padding(20.dp)) {
                        Text(stringResource(R.string.update_available, u.release.version), color = Text1, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        if (u.failed) Text(stringResource(R.string.update_failed), color = Coral, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { scope.launch { Updater.install(ctx) } }, enabled = u.progress == null,
                                colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Ink),
                            ) {
                                Text(if (u.progress == null) stringResource(R.string.update_install) else stringResource(R.string.update_downloading, (u.progress * 100).toInt()))
                            }
                            if (u.progress == null) TextButton(onClick = Updater::later) { Text(stringResource(R.string.update_later), color = Text2) }
                        }
                    }
                }
            }
        }
        if (!allowed) item {
            Group {
                Column(Modifier.padding(20.dp)) {
                    Text(stringResource(R.string.access_title), color = Text1, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.access_body), color = Text2, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
                    Button(
                        onClick = { triedAccess = true; ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Ink),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.access_button), fontWeight = FontWeight.SemiBold) }
                    if (triedAccess) {
                        Text(stringResource(R.string.restricted_help), color = Text2, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp))
                        TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) }) {
                            Text(stringResource(R.string.restricted_button), color = Coral)
                        }
                    }
                }
            }
        }
        if (!onboarded) item {
            Group {
                Column(Modifier.padding(20.dp)) {
                    Text(stringResource(R.string.onboard_title), color = Text1, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    listOf(R.string.onboard_1, R.string.onboard_2, R.string.onboard_3).forEach {
                        Text(stringResource(it), color = Text2, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                    TextButton(onClick = { prefs.onboarded = true; onboarded = true }, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.onboard_ok), color = Coral)
                    }
                }
            }
        }
        if (allowed && onboarded && !selfTestDone) item(key = "selftest") {
            SelfTest(Radar.detection(log)) { prefs.selfTestDone = true; selfTestDone = true }
        }
        if (waiting.size >= 2) item(key = "sprint") {
            Button(
                onClick = { onSprint(null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(bottom = 4.dp).then(moving()),
                colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Ink),
            ) { Text(stringResource(R.string.start_sprint), fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
        }
        if (waiting.isEmpty() && allowed) item(key = "empty") { Empty(Radar.doneSince(entries.values, startOfDay(now)), still) }
        items(waiting, key = { it.key }) { e ->
            Box(moving()) {
                Swipe(
                    left = stringResource(R.string.done) to { done(e) },
                    right = stringResource(R.string.later_lc) to { later(e, Radar.laterTimes(System.currentTimeMillis(), prefs.hour)[0]) },
                ) { EntryRow(e, now, showBadges, prefs, onSprint, ::done, ::later) }
            }
        }
        if (waiting.isNotEmpty() && !swiped) item(key = "hint") {
            Text(stringResource(R.string.hint_swipe), color = Text2, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).then(moving()))
        }
        if (snoozed.isNotEmpty()) {
            item(key = "h-later") { SectionLabel(stringResource(R.string.section_later), moving()) }
            items(snoozed, key = { "s" + it.key }) { e ->
                Box(moving()) {
                    Swipe(left = stringResource(R.string.done) to { done(e) }, right = null) {
                        EntryRow(e, now, showBadges, prefs, onSprint, ::done, ::later, snoozedRow = true)
                    }
                }
            }
        }
        if (quiet.isNotEmpty()) {
            item(key = "h-quiet") { SectionLabel(stringResource(R.string.section_quiet), moving()) }
            items(quiet, key = { "q" + it.key }) { c ->
                Box(moving()) {
                    Swipe(left = stringResource(R.string.checked_in) to { Store.touch(c.key, c.name, System.currentTimeMillis()) }, right = null) {
                        ChatRow(
                            c.name, stringResource(R.string.quiet_since, ageText(ctx, now - c.last)), "★", null,
                            group = false, badge = if (showBadges) messengerLabel(c.key.substringBefore('|')) else null,
                            urgent = false, dim = false, onClick = { Live.open(ctx, c.key) },
                        )
                    }
                }
            }
        }
        if (unread.isNotEmpty()) {
            item(key = "h-unread") { SectionLabel(stringResource(R.string.section_unread), moving()) }
            items(unread, key = { "u" + it.key }) { e ->
                Box(moving()) {
                    ChatRow(
                        e.name, e.preview, ageText(ctx, now - e.last), null, e.group,
                        if (showBadges) messengerLabel(e.app) else null, urgent = false, dim = true, onClick = { Live.open(ctx, e.key) },
                    )
                }
            }
        }
        item(key = "note") { Footnote(stringResource(R.string.stored_note), Modifier.padding(vertical = 16.dp)) }
    }
}

/** First run: proof on this very phone that opening a chat is noticed, per messenger. */
@Composable
private fun SelfTest(result: Map<String, Pair<Boolean, Int>>, onDone: () -> Unit) {
    Group {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.selftest_title), color = Text1, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.selftest_body), color = Text2, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            if (result.isEmpty()) Text(stringResource(R.string.selftest_waiting), color = Text2, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
            result.forEach { (app, r) ->
                val label = messengerLabel(app)
                Text(
                    if (r.first) stringResource(R.string.selftest_ok, label, r.second) else stringResource(R.string.selftest_bad, label, r.second),
                    color = if (r.first) Coral else Text1, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (result.values.any { !it.first }) Text(stringResource(R.string.selftest_bad_help), color = Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = onDone, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.selftest_done), color = Coral) }
        }
    }
}

/** The reward for an empty list: the check settles in once, then stays still. */
@Composable
private fun Empty(doneToday: Int, still: Boolean) {
    val scale = remember { Animatable(if (still) 1f else 0.8f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, spring(dampingRatio = 1f, stiffness = SPRING_STIFFNESS)) }
    Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("✓", color = Coral, fontSize = 52.sp, modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value })
        Text(stringResource(R.string.empty_title), color = Text1, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        if (doneToday > 0) Text(pluralStringResource(R.plurals.done_today, doneToday, doneToday), color = Text2, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

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
            urgent = !snoozedRow && now - e.since > 2 * Radar.DAY, dim = snoozedRow,
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
