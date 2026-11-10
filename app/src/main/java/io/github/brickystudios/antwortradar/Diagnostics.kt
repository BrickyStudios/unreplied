package io.github.brickystudios.antwortradar

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Diagnose kopieren": what a bug report needs, without the people in it. The app has no internet permission,
 * so this text in the clipboard plus the issue form is the only way back to the developer.
 */
object Diagnostics {
    private val time = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT)

    /** Log lines with chat names replaced by "Chat 1", "Chat 2" in order of first appearance; app and codes stay. */
    fun anonymize(log: List<LogLine>): List<String> {
        val ids = LinkedHashMap<String, Int>()
        return log.map { l ->
            val who = if (l.ev == Ev.CONNECTED || l.ev == Ev.DISCONNECTED) "listener" else "Chat " + ids.getOrPut(l.app + "|" + l.name) { ids.size + 1 }
            "${time.format(Date(l.time))}  ${l.app.ifEmpty { "-" }}  $who  ${l.ev}${if (l.reason != 0) " " + l.reason else ""}"
        }
    }

    fun text(ctx: Context): String {
        val prefs = Prefs(ctx)
        val pm = ctx.packageManager
        val messengers = MESSENGERS.mapNotNull { m ->
            runCatching { pm.getPackageInfo(m.pkg, 0).versionName }.getOrNull()?.let { "${m.pkg} $it (${if (m.pkg in prefs.enabled) "on" else "off"})" }
        }
        val asleep = !ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
        return buildString {
            appendLine("Unreplied ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Listener connected: ${Live.connected} · battery optimization: ${if (asleep) "on" else "off"}")
            appendLine("Messengers: ${messengers.ifEmpty { listOf("none installed") }.joinToString(", ")}")
            appendLine("Read detection: " + Radar.detection(Store.log.value).entries.joinToString(", ") { (app, r) -> "$app ${if (r.first) "ok" else "not counted"} (${r.second})" }.ifEmpty { "no removals logged yet" })
            appendLine()
            appendLine("Check log (newest first, names replaced):")
            anonymize(Store.log.value).forEach { appendLine(it) }
        }
    }

    /** Copies the report and opens the issue form; the person pastes it there. */
    fun share(ctx: Context) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Unreplied diagnostics", text(ctx)))
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/BrickyStudios/unreplied/issues/new")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
