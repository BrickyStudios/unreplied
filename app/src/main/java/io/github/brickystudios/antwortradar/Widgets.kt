package io.github.brickystudios.antwortradar

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews

/**
 * Home-screen widget and quick-settings tile: the count of people waiting, where you look anyway.
 * shortcut: they refresh when the data changes, on app start and with the daily reminder, so a chat whose "Später"
 * runs out shows up there at the next refresh, not to the minute.
 */
object Widgets {
    fun refresh(ctx: Context) {
        val now = System.currentTimeMillis()
        val waiting = Radar.waiting(Store.entries.value.values, now)
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, RadarWidget::class.java))
        if (ids.isNotEmpty()) mgr.updateAppWidget(ids, views(ctx, waiting))
        runCatching { TileService.requestListeningState(ctx, ComponentName(ctx, RadarTile::class.java)) }
    }

    fun title(ctx: Context, n: Int) =
        if (n == 0) ctx.getString(R.string.nobody_waiting) else ctx.resources.getQuantityString(R.plurals.summary_title, n, n)

    private fun views(ctx: Context, waiting: List<Entry>) = RemoteViews(ctx.packageName, R.layout.widget).apply {
        setTextViewText(R.id.widget_title, title(ctx, waiting.size))
        setTextViewText(R.id.widget_names, waiting.joinToString(", ") { it.name }.ifEmpty { ctx.getString(R.string.widget_empty) })
        val open = PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        setOnClickPendingIntent(R.id.widget_root, open)
    }
}

class RadarWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Store.init(ctx)
        Widgets.refresh(ctx)
    }
}

class RadarTile : TileService() {
    override fun onStartListening() {
        Store.init(this)
        val n = Radar.waiting(Store.entries.value.values, System.currentTimeMillis()).size
        qsTile?.apply {
            state = if (n > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.app_name)
            if (Build.VERSION.SDK_INT >= 29) subtitle = if (n > 0) getString(R.string.tile_waiting, n) else getString(R.string.tile_none)
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 4, intent, PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
    }
}
