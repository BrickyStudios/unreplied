package io.github.brickystudios.antwortradar

import org.json.JSONObject

data class Release(val version: String, val apkUrl: String, val bytes: Long)

/** progress: null = waiting for a tap, 0..1 = downloading. */
data class UpdateState(val release: Release, val progress: Float? = null, val failed: Boolean = false)

/** What a GitHub release means for this install. Pure, so both APK flavors share and test it. */
object Releases {
    const val LATEST = "https://api.github.com/repos/BrickyStudios/unreplied/releases/latest"
    const val PAGE = "https://github.com/BrickyStudios/unreplied/releases/latest"
    private const val DOWNLOADS = "https://github.com/BrickyStudios/unreplied/releases/download/"

    /** "v0.10.0" is newer than "0.9.2". */
    fun isNewer(tag: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(tag)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** Reads GitHub's answer. Only the asset with this flavor's file name, from this repo's own downloads, counts. */
    fun parse(json: String, apkName: String): Release? = runCatching {
        val o = JSONObject(json)
        val assets = o.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == apkName } ?: return null
        val url = apk.optString("browser_download_url").takeIf { it.startsWith(DOWNLOADS) } ?: return null
        Release(o.getString("tag_name").removePrefix("v"), url, apk.optLong("size"))
    }.getOrNull()
}
