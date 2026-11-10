package io.github.brickystudios.antwortradar

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SprintScreen(start: String?, now: Long, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val prefs = remember { Prefs(ctx) }
    val still = remember { reducedMotion(ctx) }
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
    val progress by animateFloatAsState(
        if (idx == null) 1f else idx.toFloat() / queue.size.coerceAtLeast(1),
        spring(dampingRatio = 1f, stiffness = SPRING_STIFFNESS), label = "progress",
    )

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Text1) }
            LinearProgressIndicator(
                progress = { progress }, color = Coral, trackColor = Line, drawStopIndicator = {},
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Text(if (idx == null) "${queue.size}/${queue.size}" else "${idx + 1}/${queue.size}", color = Text2, fontSize = 14.sp)
        }

        // the next person slides in from the right, the last one leaves to the left: the queue has a direction
        AnimatedContent(
            targetState = idx?.let { queue[it] },
            transitionSpec = {
                if (still) fadeIn(tween(150)) togetherWith fadeOut(tween(100))
                else (slideInHorizontally(spring(dampingRatio = 1f, stiffness = SPRING_STIFFNESS)) { it / 3 } + fadeIn(tween(180))) togetherWith
                    (slideOutHorizontally(spring(dampingRatio = 1f, stiffness = SPRING_STIFFNESS)) { -it / 3 } + fadeOut(tween(120)))
            },
            label = "sprint",
        ) { key ->
            val e = key?.let { entries[it] }
            if (e == null) {
                Done(handled, onExit)
                return@AnimatedContent
            }
            val i = queue.indexOf(key)
            fun next(counted: Boolean) { if (counted) handled++; pos = i + 1 }
            var text by remember(e.key) { mutableStateOf("") }
            val canReply = Live.canReply(e.key)

            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 24.dp, bottom = 24.dp)) {
                Text(e.name, color = Text1, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
                Text(
                    messengerLabel(e.app) + " · " + stringResource(R.string.waiting_since, ageText(ctx, now - e.since)),
                    color = Text2, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp),
                )
                Group(Modifier.padding(top = 20.dp)) {
                    Text(e.preview.ifEmpty { "…" }, color = Text1, fontSize = 17.sp, lineHeight = 24.sp, modifier = Modifier.padding(16.dp))
                }

                FlowRow(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    prefs.replies.forEach { r ->
                        FilterChip(
                            selected = text == r, onClick = { text = if (text == r) "" else r },
                            label = { Text(r, fontSize = 14.sp) },
                            shape = RoundedCornerShape(20.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = CardBg, labelColor = Text1,
                                selectedContainerColor = Coral.copy(alpha = 0.18f), selectedLabelColor = Text1,
                            ),
                            border = FilterChipDefaults.filterChipBorder(enabled = true, selected = text == r, borderColor = Line, selectedBorderColor = Coral),
                        )
                    }
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, label = { Text(stringResource(R.string.reply_hint)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp), minLines = 2, shape = RoundedCornerShape(16.dp),
                )
                if (!canReply) Footnote(stringResource(R.string.no_reply_button))

                Button(
                    enabled = text.isNotBlank(),
                    onClick = {
                        if (canReply && Live.reply(ctx, e.key, text.trim())) {
                            confirmHaptic(view)
                            Store.put(e.key, Radar.done(e, System.currentTimeMillis()), Ev.REPLIED)
                            Toast.makeText(ctx, R.string.sent_ok, Toast.LENGTH_SHORT).show()
                            next(true)
                        } else {
                            if (canReply) Toast.makeText(ctx, R.string.send_failed, Toast.LENGTH_LONG).show()
                            Live.copyAndOpen(ctx, e.key, text.trim()) // the chat stays here: tap "Erledigt" once it is sent
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Ink),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 52.dp),
                ) { Text(stringResource(if (canReply) R.string.send else R.string.copy_open), fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }

                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val tonal = ButtonDefaults.filledTonalButtonColors(containerColor = CardBg, contentColor = Text1)
                    var laterMenu by remember { mutableStateOf(false) }
                    Box(Modifier.weight(1f)) {
                        FilledTonalButton(onClick = { laterMenu = true }, colors = tonal, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.later))
                        }
                        DropdownMenu(expanded = laterMenu, onDismissRequest = { laterMenu = false }) {
                            val times = Radar.laterTimes(now, prefs.hour)
                            listOf(ctx.getString(R.string.in_3h), whenText(ctx, times[1], now), whenText(ctx, times[2], now)).forEachIndexed { k, label ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    laterMenu = false
                                    confirmHaptic(view)
                                    Store.put(e.key, Radar.snooze(e, times[k]), Ev.SNOOZED)
                                    next(true)
                                })
                            }
                        }
                    }
                    FilledTonalButton(
                        onClick = { confirmHaptic(view); Store.put(e.key, Radar.done(e, System.currentTimeMillis()), Ev.DONE); next(true) },
                        colors = tonal, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.mark_done)) }
                    FilledTonalButton(onClick = { next(false) }, colors = tonal, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.skip))
                    }
                }
            }
        }
    }
}

@Composable
private fun Done(handled: Int, onExit: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("✓", color = Coral, fontSize = 52.sp)
        Text(stringResource(R.string.sprint_done), color = Text1, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(pluralStringResource(R.plurals.sprint_done_count, handled, handled), color = Text2, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
        Button(
            onClick = onExit, modifier = Modifier.padding(top = 28.dp).height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Ink),
        ) { Text(stringResource(R.string.back), fontWeight = FontWeight.SemiBold) }
    }
}
