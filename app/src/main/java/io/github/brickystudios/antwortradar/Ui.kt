package io.github.brickystudios.antwortradar

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Warm coral on dark ink
val Ink = Color(0xFF14121C)
val CardBg = Color(0xFF1F1C2B)
val Line = Color(0xFF3A3550)
val Text1 = Color(0xFFEDEAF5)
val Text2 = Color(0xFFA29CB5)
val Coral = Color(0xFFFF8A65)

fun startOfDay(t: Long) = Calendar.getInstance().apply {
    timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** "today 19:00", "tomorrow 9:00", "Fr 9:00" in the phone's 12/24 h style */
fun whenText(ctx: Context, t: Long, now: Long): String {
    val time = DateFormat.getTimeFormat(ctx).format(Date(t))
    val d0 = startOfDay(now)
    return when {
        t in d0 until d0 + Radar.DAY -> ctx.getString(R.string.when_today, time)
        t in d0 + Radar.DAY until d0 + 2 * Radar.DAY -> ctx.getString(R.string.when_tomorrow, time)
        else -> SimpleDateFormat("EE", Locale.getDefault()).format(Date(t)) + " " + time
    }
}

/** "13:20" for today, "Fr" for older */
fun shortTime(ctx: Context, t: Long, now: Long): String =
    if (t >= startOfDay(now)) DateFormat.getTimeFormat(ctx).format(Date(t)) else SimpleDateFormat("EE", Locale.getDefault()).format(Date(t))

fun evText(ctx: Context, l: LogLine): String = when (l.ev) {
    Ev.NEW -> ctx.getString(R.string.ev_new) + if (l.reason >= 5) " " + ctx.getString(R.string.ev_late, l.reason) else ""
    Ev.READ -> ctx.getString(R.string.ev_read, l.reason)
    Ev.REMOVED -> ctx.getString(R.string.ev_removed, l.reason)
    Ev.ANSWERED -> ctx.getString(R.string.ev_answered)
    Ev.DONE -> ctx.getString(R.string.ev_done)
    Ev.REPLIED -> ctx.getString(R.string.ev_replied)
    Ev.SNOOZED -> ctx.getString(R.string.ev_snoozed)
    Ev.IGNORED -> ctx.getString(R.string.ev_ignored)
    Ev.CONNECTED -> ctx.getString(R.string.ev_connected)
    Ev.DISCONNECTED -> ctx.getString(R.string.ev_disconnected)
}

@Composable
fun Panel(modifier: Modifier = Modifier, border: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(12.dp))
            .then(if (border != null) Modifier.border(1.dp, border, RoundedCornerShape(12.dp)) else Modifier)
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun Heading(text: String, modifier: Modifier = Modifier) =
    Text(text, color = Text1, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = modifier)

@Composable
fun Body(text: String, modifier: Modifier = Modifier) = Text(text, color = Text2, fontSize = 13.sp, modifier = modifier)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatRow(
    name: String, preview: String, right: String, rightSmall: String?,
    group: Boolean, badge: String?, strong: Boolean, dim: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().alpha(if (dim) 0.55f else 1f)
            .background(CardBg, RoundedCornerShape(12.dp))
            .border(BorderStroke(if (strong) 2.dp else 1.dp, if (strong) Text1 else Line), RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).border(1.dp, Line, CircleShape), Alignment.Center) {
            Text(if (group) "@" else name.take(1).uppercase(), color = if (group) Coral else Text2)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = Text1, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (badge != null) Text("  $badge", color = Text2, fontSize = 12.sp, maxLines = 1)
            }
            Text(preview, color = Text2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(right, color = if (right == "★") Coral else Text1, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (rightSmall != null) Text(rightSmall, color = Text2, fontSize = 11.sp)
        }
    }
}

/** Left swipe and right swipe with their own label. Callers make the actions idempotent: Material may confirm twice. */
@Composable
fun Swipe(left: Pair<String, () -> Unit>?, right: Pair<String, () -> Unit>?, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.EndToStart -> left?.second?.invoke().let { left != null }
            SwipeToDismissBoxValue.StartToEnd -> right?.second?.invoke().let { right != null }
            else -> false
        }
    })
    SwipeToDismissBox(
        state, enableDismissFromStartToEnd = right != null, enableDismissFromEndToStart = left != null,
        backgroundContent = {
            // only while swiping: dimmed rows are see-through and would show the label at rest
            if (state.dismissDirection == SwipeToDismissBoxValue.Settled) return@SwipeToDismissBox
            val toRight = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier.fillMaxSize().background(if (toRight) Text2 else Coral, RoundedCornerShape(12.dp)).padding(horizontal = 20.dp),
                if (toRight) Alignment.CenterStart else Alignment.CenterEnd,
            ) { Text((if (toRight) right?.first else left?.first) ?: "", color = Ink, fontWeight = FontWeight.Bold) }
        },
    ) { content() }
}
