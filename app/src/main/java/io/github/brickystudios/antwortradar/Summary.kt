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

/** One collected reminder per day at the chosen hour, never one per message. */
object Summary {
    private const val CHANNEL = "summary"
    private const val ID = 1
    const val ACTION_LATER = "io.github.brickystudios.antwortradar.LATER"

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

    /** "Mama (3 T), Lisa (2 T), Tim (5 Std)" */
    fun text(waiting: List<Entry>, now: Long) =
        waiting.joinToString(", ") { "${it.name} (${Radar.age(now - it.since)})" }

    fun show(ctx: Context) {
        Store.init(ctx)
        val waiting = Radar.waiting(Store.entries.value.values)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (waiting.isEmpty()) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tagesmeldung", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val later = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, SummaryReceiver::class.java).setAction(ACTION_LATER), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (waiting.size == 1) "1 Person wartet auf dich" else "${waiting.size} Leute warten auf dich")
            .setContentText(text(waiting, System.currentTimeMillis()))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text(waiting, System.currentTimeMillis())))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Liste öffnen", open)
            .addAction(0, "Morgen", later)
            .build()
        // no notification permission: the list in the app still works
        runCatching { nm.notify(ID, n) }.onFailure { android.util.Log.w("AntwortRadar", "summary not shown", it) }
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
