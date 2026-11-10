package io.github.brickystudios.antwortradar

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.util.Calendar

/** "3 T" / "3 d": short ages for lists, the reminder and the widget. */
fun ageText(ctx: Context, ms: Long): String {
    val (n, span) = Radar.age(ms)
    val res = when (span) { Span.MIN -> R.string.age_min; Span.HOUR -> R.string.age_hour; Span.DAY -> R.string.age_day; Span.WEEK -> R.string.age_week }
    return ctx.getString(res, n)
}

/** "Mama (3 T), Lisa (2 T), Tim (5 Std)" */
fun waitingText(ctx: Context, waiting: List<Entry>, now: Long) =
    waiting.joinToString(", ") { "${it.name} (${ageText(ctx, now - it.since)})" }

/** One collected reminder per day at the chosen hour, never one per message. */
object Summary {
    private const val CHANNEL = "summary"
    private const val ID = 1
    const val ACTION_LATER = "io.github.brickystudios.antwortradar.LATER"
    const val EXTRA_SPRINT = "sprint"

    /**
     * One alarm at a time, re-armed after it fires. A repeating inexact alarm may drift by up to 18 hours (seen on
     * Android 14), a one-shot window keeps it within the hour without the exact-alarm permission.
     */
    fun schedule(ctx: Context) {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, Prefs(ctx).hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, SummaryReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        ctx.getSystemService(AlarmManager::class.java)
            .setWindow(AlarmManager.RTC_WAKEUP, c.timeInMillis, Radar.HOUR, pi)
    }

    fun show(ctx: Context) {
        Store.init(ctx)
        val now = System.currentTimeMillis()
        val waiting = Radar.waiting(Store.entries.value.values, now)
        val quiet = Radar.quiet(Store.contacts.value.values, now, Prefs(ctx).quietWeeks)
        if (waiting.isEmpty() && quiet.isEmpty()) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.channel_summary), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val sprint = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, MainActivity::class.java).putExtra(EXTRA_SPRINT, true), PendingIntent.FLAG_IMMUTABLE,
        )
        val later = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, SummaryReceiver::class.java).setAction(ACTION_LATER), PendingIntent.FLAG_IMMUTABLE,
        )
        val lines = listOfNotNull(
            waitingText(ctx, waiting, now).takeIf { waiting.isNotEmpty() },
            ctx.getString(R.string.summary_quiet, quiet.joinToString(", ") { it.name }).takeIf { quiet.isNotEmpty() },
        ).joinToString("\n")
        val title = if (waiting.isEmpty()) ctx.getString(R.string.summary_quiet_title)
        else ctx.resources.getQuantityString(R.plurals.summary_title, waiting.size, waiting.size)
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(lines)
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
            .setContentIntent(open)
            .setAutoCancel(true)
        if (waiting.size >= 2) b.addAction(0, ctx.getString(R.string.start_sprint), sprint)
        else b.addAction(0, ctx.getString(R.string.open_list), open)
        b.addAction(0, ctx.getString(R.string.tomorrow), later)
        // no notification permission: the list in the app still works
        runCatching { nm.notify(ID, b.build()) }.onFailure { android.util.Log.w("AntwortRadar", "summary not shown", it) }
    }

    fun hide(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(ID)
}

class SummaryReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // "Morgen" only hides today's reminder; the next alarm brings it back tomorrow if someone still waits
        if (intent.action == Summary.ACTION_LATER) return Summary.hide(ctx)
        Summary.show(ctx)
        Summary.schedule(ctx) // next day
    }
}
