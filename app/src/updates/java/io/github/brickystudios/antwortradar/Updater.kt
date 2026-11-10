package io.github.brickystudios.antwortradar

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Unreplied-Updates.apk: asks GitHub for the latest release, downloads its own APK and hands it to Android's
 * installer, which only accepts it when it carries the same signing key. This is the app's only use of the internet.
 */
object Updater {
    const val supported = true
    val state = MutableStateFlow<UpdateState?>(null)
    /** after a manual check: R.string.update_none or R.string.update_failed */
    val note = MutableStateFlow<Int?>(null)

    private fun apk(ctx: Context) = File(ctx.cacheDir, "updates/" + BuildConfig.APK_NAME)

    private fun open(url: String, readTimeout: Int) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        this.readTimeout = readTimeout
    }

    suspend fun check(ctx: Context, manual: Boolean) {
        note.value = null
        val result = withContext(Dispatchers.IO) {
            apk(ctx).parentFile?.deleteRecursively() // the APK from the last update
            runCatching {
                val c = open(Releases.LATEST, 10_000).apply { setRequestProperty("Accept", "application/vnd.github+json") }
                c.inputStream.bufferedReader().use { Releases.parse(it.readText(), BuildConfig.APK_NAME) }
            }
        }
        val newer = result.getOrNull()?.takeIf { Releases.isNewer(it.version, BuildConfig.VERSION_NAME) }
        state.value = newer?.let(::UpdateState)
        if (manual && newer == null) note.value = if (result.isFailure) R.string.update_failed else R.string.update_none
    }

    fun later() { if (state.value?.progress == null) state.value = null }

    suspend fun install(ctx: Context) {
        val s = state.value?.takeIf { it.progress == null } ?: return
        state.value = s.copy(progress = 0f, failed = false)
        val target = apk(ctx)
        // a cancelled installer leaves the APK here: no second download
        val ok = target.exists() || runCatching { download(s.release.apkUrl, target) { p -> state.update { it?.copy(progress = p) } } }.isSuccess
        state.value = s.copy(progress = null, failed = !ok)
        if (ok) {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", target)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Downloads into a .part file first, so a broken download never looks like a finished APK. */
    private suspend fun download(url: String, target: File, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val c = open(url, 30_000)
        val total = c.contentLengthLong
        val part = File(target.path + ".part")
        target.parentFile?.mkdirs()
        var done = 0L
        c.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) progress(done.toFloat() / total)
                }
            }
        }
        if (total > 0 && done != total) throw IOException("download cut off at $done of $total bytes")
        if (!part.renameTo(target)) throw IOException("could not move ${part.name}")
    }
}
