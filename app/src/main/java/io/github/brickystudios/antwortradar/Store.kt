package io.github.brickystudios.antwortradar

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * What happened, for the check log. Stored as a code so the log reads in the app's language.
 * LogLine.reason holds Android's removal reason for READ/REMOVED and the delivery delay in seconds for NEW.
 */
enum class Ev { NEW, READ, REMOVED, ANSWERED, DONE, REPLIED, SNOOZED, IGNORED, CONNECTED, DISCONNECTED }

data class LogLine(val time: Long, val name: String, val ev: Ev, val reason: Int = 0, val app: String = "")

/**
 * Everything the radar knows, in one JSON file in the app's private storage. Listener, UI, tile and widget share
 * this object (same process). Nothing goes online: the app has no internet permission.
 */
object Store {
    val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    val contacts = MutableStateFlow<Map<String, Contact>>(emptyMap())
    val log = MutableStateFlow<List<LogLine>>(emptyList())
    private var file: File? = null
    private lateinit var app: Context
    private val writer = Executors.newSingleThreadExecutor()
    private const val LOG_MAX = 50

    fun init(ctx: Context) {
        if (file != null) return
        app = ctx.applicationContext
        val f = File(ctx.filesDir, "radar.json").also { file = it }
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            entries.value = o.getJSONArray("entries").objects().map { it.toEntry() }.associateBy { it.key }
            contacts.value = (o.optJSONArray("contacts") ?: JSONArray()).objects().map { it.toContact() }.associateBy { it.key }
            log.value = o.getJSONArray("log").objects().filter { it.has("ev") }
                .map { LogLine(it.getLong("time"), it.getString("name"), Ev.valueOf(it.getString("ev")), it.optInt("reason"), it.optString("app")) }
        } // a broken file starts empty instead of crashing the listener
    }

    /** Replace one chat's entry (null = forget it) and optionally add a line to the check log. */
    fun put(key: String, new: Entry?, ev: Ev? = null, reason: Int = 0) {
        val old = entries.value[key]
        if (new == old && ev == null) return
        val now = System.currentTimeMillis()
        entries.value = Radar.prune((entries.value - key).values + listOfNotNull(new), now).associateBy { it.key }
        if (ev != null) log.value = (listOf(LogLine(now, new?.name ?: old?.name ?: key, ev, reason, key.substringBefore('|'))) + log.value).take(LOG_MAX)
        save()
    }

    /** A log line that is not about one chat (listener connected / disconnected). */
    fun note(name: String, ev: Ev) {
        log.value = (listOf(LogLine(System.currentTimeMillis(), name, ev)) + log.value).take(LOG_MAX); save()
    }

    fun touch(key: String, name: String, time: Long) {
        val old = contacts.value[key]
        val new = Radar.touch(old, key, name, time)
        if (new != old) { contacts.value = contacts.value + (key to new); save() }
    }

    fun setFavorite(key: String, name: String, favorite: Boolean) {
        val old = contacts.value[key] ?: Contact(key, name, System.currentTimeMillis())
        contacts.value = contacts.value + (key to old.copy(favorite = favorite)); save()
    }

    fun clearLog() { log.value = emptyList(); save() }

    /** "Alle Daten löschen": chats, contacts and log. Settings stay. */
    fun clearAll() { entries.value = emptyMap(); contacts.value = emptyMap(); log.value = emptyList(); save() }

    private fun save() {
        val f = file ?: return
        // snapshot on the caller's thread, write on one background thread in order
        val text = JSONObject()
            .put("version", 2)
            .put("entries", JSONArray(entries.value.values.map { it.toJson() }))
            .put("contacts", JSONArray(contacts.value.values.map { it.toJson() }))
            .put("log", JSONArray(log.value.map {
                JSONObject().put("time", it.time).put("name", it.name).put("ev", it.ev.name).put("reason", it.reason).put("app", it.app)
            }))
            .toString()
        writer.execute {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(f) // atomic swap: a crash mid-write never leaves half a file
        }
        Widgets.refresh(app)
        Reminders.schedule(app)
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    private fun Entry.toJson() = JSONObject().put("key", key).put("name", name).put("preview", preview)
        .put("group", group).put("since", since).put("last", last).put("state", state.name)
        .put("readAt", readAt).put("doneAt", doneAt).put("snoozeUntil", snoozeUntil).put("nudgedAt", nudgedAt)

    private fun JSONObject.toEntry() = Entry(
        // v0.1 stored WhatsApp chats without the "<package>|" prefix
        getString("key").let { if ('|' in it) it else Radar.key("com.whatsapp", it) },
        getString("name"), getString("preview"), getBoolean("group"),
        getLong("since"), getLong("last"), State.valueOf(getString("state")), optLong("readAt"), optLong("doneAt"),
        optLong("snoozeUntil"), optLong("nudgedAt"),
    )

    private fun Contact.toJson() = JSONObject().put("key", key).put("name", name).put("last", last).put("favorite", favorite)
    private fun JSONObject.toContact() = Contact(getString("key"), getString("name"), getLong("last"), optBoolean("favorite"))
}

/** Small settings, kept apart from the chat data. */
class Prefs(private val ctx: Context) {
    private val p = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** Hour of the daily reminder; "heute Abend" in Später uses it too. */
    var hour: Int
        get() = p.getInt("hour", 19)
        set(v) = p.edit().putInt("hour", v).apply()

    /** Names that count as an @-mention in groups, comma separated. Empty = ignore groups. */
    var names: String
        get() = p.getString("names", "") ?: ""
        set(v) = p.edit().putString("names", v).apply()
    val nameList get() = names.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    var enabled: Set<String>
        get() = p.getStringSet("enabled", null) ?: MESSENGERS.filter { it.onByDefault }.map { it.pkg }.toSet()
        set(v) = p.edit().putStringSet("enabled", v).apply()

    /** Ignored chats: key -> name, so the settings can list them by name. */
    var ignored: Map<String, String>
        get() = runCatching { JSONObject(p.getString("ignored", "{}")!!).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } }
            .getOrDefault(emptyMap())
        set(v) = p.edit().putString("ignored", JSONObject(v).toString()).apply()

    /** Quick replies, one per line. Default comes from strings.xml so it follows the app language. */
    var replies: List<String>
        get() = (p.getString("replies", null) ?: ctx.getString(R.string.default_replies)).lines().map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = p.edit().putString("replies", v.joinToString("\n")).apply()

    var quietWeeks: Int
        get() = p.getInt("quietWeeks", 3)
        set(v) = p.edit().putInt("quietWeeks", v).apply()

    /** The swipe hint goes away after the first swipe. */
    var swiped: Boolean
        get() = p.getBoolean("swiped", false)
        set(v) = p.edit().putBoolean("swiped", v).apply()

    /** Favorites get their own reminder once they have waited this long. */
    var nudgeHours: Int
        get() = p.getInt("nudgeHours", 4)
        set(v) = p.edit().putInt("nudgeHours", v).apply()

    /** The read-detection check card was closed. */
    var selfTestDone: Boolean
        get() = p.getBoolean("selfTestDone", false)
        set(v) = p.edit().putBoolean("selfTestDone", v).apply()

    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) = p.edit().putBoolean("onboarded", v).apply()
}
