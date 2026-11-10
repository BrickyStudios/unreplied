package io.github.brickystudios.antwortradar

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Warm coral on dark ink, the same tokens as the website
val Ink = Color(0xFF14121C)
val CardBg = Color(0xFF1F1C2B)
val Line = Color(0xFF3A3550)
val Text1 = Color(0xFFEDEAF5)
val Text2 = Color(0xFFA29CB5)
val Coral = Color(0xFFFF8A65)

/** Critically damped, about 0.35 s to settle: no overshoot on anything that was not thrown. */
const val SPRING_STIFFNESS = 320f

/** "Remove animations" in Android's accessibility settings sets the animator scale to 0: cross-fade instead of slide. */
fun reducedMotion(ctx: Context) =
    Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

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

/** Feedback on press-down, not on release: the surface gives a little under the finger, then springs back. */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessHigh), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** A commit the hand can feel: fired on the frame the swipe or the button takes effect. */
fun confirmHaptic(view: android.view.View) {
    view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
}

/** Small muted label above a group, sentence case. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text, color = Text2, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp))

/** Footnote under a group: the explanation lives here, not inside the controls. */
@Composable
fun Footnote(text: String, modifier: Modifier = Modifier) =
    Text(text, color = Text2, fontSize = 13.sp, lineHeight = 18.sp, modifier = modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp))

/** One grouped surface; rows inside are parted by hairlines, never boxes in boxes. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg), content = content)
}

@Composable
fun GroupDivider() = HorizontalDivider(color = Line, thickness = 0.5.dp, modifier = Modifier.padding(start = 16.dp))

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatRow(
    name: String, preview: String, right: String, rightSmall: String?,
    group: Boolean, badge: String?, urgent: Boolean, dim: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().pressScale(interaction).alpha(if (dim) 0.5f else 1f)
            .clip(RoundedCornerShape(16.dp)).background(CardBg)
            .combinedClickable(interactionSource = interaction, indication = ripple(), onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (group) Coral.copy(alpha = 0.16f) else Line), Alignment.Center) {
            Text(if (group) "@" else name.take(1).uppercase(), color = if (group) Coral else Text1, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = Text1, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (badge != null) Text("  $badge", color = Text2, fontSize = 12.sp, maxLines = 1)
            }
            Text(preview, color = Text2, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            // waiting long is a colour signal, not a heavier box
            Text(right, color = if (urgent || right == "★") Coral else Text1, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (rightSmall != null) Text(rightSmall, color = Text2, fontSize = 12.sp)
        }
    }
}

/**
 * Swipe left and right with their own label. The commit fires one haptic on the frame it takes effect; Material may
 * confirm a value twice, so callers keep their actions idempotent.
 */
@Composable
fun Swipe(left: Pair<String, () -> Unit>?, right: Pair<String, () -> Unit>?, content: @Composable () -> Unit) {
    val view = LocalView.current
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        val action = when (v) {
            SwipeToDismissBoxValue.EndToStart -> left
            SwipeToDismissBoxValue.StartToEnd -> right
            else -> null
        }
        if (action != null) { confirmHaptic(view); action.second() }
        action != null
    })
    SwipeToDismissBox(
        state, enableDismissFromStartToEnd = right != null, enableDismissFromEndToStart = left != null,
        backgroundContent = {
            // only while swiping: dimmed rows are see-through and would show the label at rest
            if (state.dismissDirection == SwipeToDismissBoxValue.Settled) return@SwipeToDismissBox
            val toRight = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(if (toRight) Line else Coral).padding(horizontal = 24.dp),
                if (toRight) Alignment.CenterStart else Alignment.CenterEnd,
            ) { Text((if (toRight) right?.first else left?.first) ?: "", color = if (toRight) Text1 else Ink, fontWeight = FontWeight.SemiBold) }
        },
    ) { content() }
}
