package io.github.brickystudios.antwortradar

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LogLine(val time: Long, val name: String, val event: String)

/**
 * Everything the radar knows, in one JSON file in the app's private storage. Listener and UI share this object
 * (same process). Nothing goes online: the app has no internet permission.
 * shortcut: writes the whole file on the main thread; fine for a few hundred chats, move to a background writer if it grows.
 */
object Store {
    val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    val log = MutableStateFlow<List<LogLine>>(emptyList())
    private var file: File? = null
    private const val LOG_MAX = 50

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "radar.json").also { file = it }
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            val es = o.getJSONArray("entries")
            entries.value = (0 until es.length()).map { es.getJSONObject(it).toEntry() }.associateBy { it.key }
            val ls = o.getJSONArray("log")
            log.value = (0 until ls.length()).map { ls.getJSONObject(it) }
                .map { LogLine(it.getLong("time"), it.getString("name"), it.getString("event")) }
        } // a broken file starts empty instead of crashing the listener
    }

    /** Replace one chat's entry (null = forget it) and optionally add a line to the check log. */
    fun put(key: String, new: Entry?, event: String?) {
        val old = entries.value[key]
        if (new == old && event == null) return
        val now = System.currentTimeMillis()
        entries.value = Radar.prune((entries.value - key).values + listOfNotNull(new), now).associateBy { it.key }
        if (event != null) log.value = (listOf(LogLine(now, new?.name ?: old?.name ?: key, event)) + log.value).take(LOG_MAX)
        save()
    }

    fun clearLog() { log.value = emptyList(); save() }

    private fun save() {
        val o = JSONObject()
            .put("entries", JSONArray(entries.value.values.map { it.toJson() }))
            .put("log", JSONArray(log.value.map { JSONObject().put("time", it.time).put("name", it.name).put("event", it.event) }))
        val f = file ?: return
        val tmp = File(f.path + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f) // atomic swap: a crash mid-write never leaves half a file
    }

    private fun Entry.toJson() = JSONObject().put("key", key).put("name", name).put("preview", preview)
        .put("group", group).put("since", since).put("last", last).put("state", state.name)
        .put("readAt", readAt).put("doneAt", doneAt)

    private fun JSONObject.toEntry() = Entry(
        getString("key"), getString("name"), getString("preview"), getBoolean("group"),
        getLong("since"), getLong("last"), State.valueOf(getString("state")), optLong("readAt"), optLong("doneAt"),
    )
}

/** Small settings, kept apart from the chat data. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    var hour: Int
        get() = p.getInt("hour", 19)
        set(v) = p.edit().putInt("hour", v).apply()
    /** Names that count as an @-mention in groups, comma separated. Empty = ignore groups. */
    var names: String
        get() = p.getString("names", "") ?: ""
        set(v) = p.edit().putString("names", v).apply()
    val nameList get() = names.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
