package io.github.brickystudios.antwortradar

import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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

/**
 * Antwort-Sprint: everyone who waits, one at a time, oldest first. Answer with a quick reply, push to later,
 * mark done or skip. [start] puts one chat first (from "Antworten" in the list menu).
 */
@Composable
fun SprintScreen(start: String?, now: Long, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val entries by Store.entries.collectAsStateWithLifecycle()
    val queue = remember(start) {
        val w = Radar.waiting(Store.entries.value.values, System.currentTimeMillis()).map { it.key }
        if (start != null) listOf(start) + (w - start) else w
    }
    var pos by rememberSaveable(start) { mutableIntStateOf(0) }
    var handled by rememberSaveable(start) { mutableIntStateOf(0) }
    // chats answered in the meantime (e.g. straight in the messenger) drop out on their own
    val idx = (pos until queue.size).firstOrNull { k ->
        entries[queue[k]]?.let { it.state != State.DONE && (queue[k] == start || (it.state == State.READ && it.snoozeUntil <= now)) } == true
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onExit) { Text("← " + stringResource(R.string.back), color = Coral) }
            Spacer(Modifier.weight(1f))
            if (idx != null) Text(stringResource(R.string.sprint_progress, idx + 1, queue.size), color = Text2)
        }
        if (idx == null) {
            Column(Modifier.fillMaxWidth().padding(top = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("✓", color = Coral, fontSize = 44.sp)
                Text(stringResource(R.string.sprint_done), color = Text1, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Body(pluralStringResource(R.plurals.sprint_done_count, handled, handled), Modifier.padding(top = 6.dp))
                Button(onClick = onExit, modifier = Modifier.padding(top = 24.dp), colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Ink)) {
                    Text(stringResource(R.string.back))
                }
            }
            return@Column
        }
        val e = entries.getValue(queue[idx])
        fun next(counted: Boolean) { if (counted) handled++; pos = idx + 1 }
        var text by remember(e.key) { mutableStateOf("") }
        val canReply = Live.canReply(e.key)

        Spacer(Modifier.height(16.dp))
        Text(e.name, color = Text1, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Body(messengerLabel(e.app) + " · " + stringResource(R.string.waiting_since, ageText(ctx, now - e.since)))
        Panel(Modifier.padding(top = 14.dp)) { Text(e.preview.ifEmpty { "…" }, color = Text1, fontSize = 16.sp) }

        Spacer(Modifier.height(16.dp))
        prefs.replies.forEach { r ->
            Box(
                Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    .border(1.dp, if (text == r) Coral else Line, RoundedCornerShape(10.dp))
                    .background(CardBg, RoundedCornerShape(10.dp))
                    .clickable { text = r }.padding(horizontal = 12.dp, vertical = 10.dp),
            ) { Text(r, color = Text1, fontSize = 14.sp) }
        }
        OutlinedTextField(
            value = text, onValueChange = { text = it }, label = { Text(stringResource(R.string.reply_hint)) },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp), minLines = 2,
        )
        if (!canReply) Body(stringResource(R.string.no_reply_button), Modifier.padding(top = 6.dp))

        Button(
            enabled = text.isNotBlank(),
            onClick = {
                if (canReply && Live.reply(ctx, e.key, text.trim())) {
                    Store.put(e.key, Radar.done(e, System.currentTimeMillis()), Ev.REPLIED)
                    Toast.makeText(ctx, R.string.sent_ok, Toast.LENGTH_SHORT).show()
                    next(true)
                } else {
                    if (canReply) Toast.makeText(ctx, R.string.send_failed, Toast.LENGTH_LONG).show()
                    Live.copyAndOpen(ctx, e.key, text.trim()) // the chat stays here: tap "Erledigt" once it is sent
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Ink),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) { Text(stringResource(if (canReply) R.string.send else R.string.copy_open), fontWeight = FontWeight.Bold) }

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var laterMenu by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { laterMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.later), color = Text1) }
                DropdownMenu(expanded = laterMenu, onDismissRequest = { laterMenu = false }) {
                    val times = Radar.laterTimes(now, prefs.hour)
                    listOf(ctx.getString(R.string.in_3h), whenText(ctx, times[1], now), whenText(ctx, times[2], now)).forEachIndexed { i, label ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            laterMenu = false
                            Store.put(e.key, Radar.snooze(e, times[i]), Ev.SNOOZED)
                            next(true)
                        })
                    }
                }
            }
            OutlinedButton(onClick = { Store.put(e.key, Radar.done(e, System.currentTimeMillis()), Ev.DONE); next(true) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.mark_done), color = Text1)
            }
            OutlinedButton(onClick = { next(false) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.skip), color = Text1) }
        }
    }
}
