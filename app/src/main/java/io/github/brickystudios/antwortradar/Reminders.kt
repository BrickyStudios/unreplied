package io.github.brickystudios.antwortradar

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * One alarm for everything that has a time: a "Später" that runs out and a favorite who has waited too long.
 * It always points at the earliest pending moment and is re-armed after every change and after it fires.
 */
object Reminders {
    private const val CHANNEL = "reminders"

    private fun favorites() = Store.contacts.value.values.filter { it.favorite }.map { it.key }.toSet()

    fun schedule(ctx: Context) {
        val next = Radar.nextReminder(Store.entries.value.values, favorites(), System.currentTimeMillis(), Prefs(ctx).nudgeHours)
        val pi = PendingIntent.getBroadcast(ctx, 6, Intent(ctx, ReminderReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        val am = ctx.getSystemService(AlarmManager::class.java)
        // shortcut: a window instead of an exact alarm (no SCHEDULE_EXACT_ALARM): 10 minutes for "in 3 hours", 1 minute when due now
        val now = System.currentTimeMillis()
        if (next == null) am.cancel(pi)
        else am.setWindow(AlarmManager.RTC_WAKEUP, next, if (next - now < 15 * 60_000L) 60_000L else 10 * 60_000L, pi)
    }

    fun fire(ctx: Context) {
        Store.init(ctx)
        val now = System.currentTimeMillis()
        Radar.dueSnoozes(Store.entries.value.values, now).forEach {
            Store.put(it.key, it.copy(snoozeUntil = 0))
            notify(ctx, it, ctx.getString(R.string.reminder_back, it.name))
        }
        Radar.dueFavorites(Store.entries.value.values, favorites(), now, Prefs(ctx).nudgeHours).forEach {
            Store.put(it.key, it.copy(nudgedAt = now))
            notify(ctx, it, ctx.getString(R.string.reminder_favorite, it.name, ageText(ctx, now - it.since)))
        }
        schedule(ctx)
    }

    private fun notify(ctx: Context, e: Entry, title: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(e.preview)
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { nm.notify(e.key.hashCode(), n) }.onFailure { android.util.Log.w("Unreplied", "reminder not shown", it) }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Reminders.fire(ctx)
}
