package io.github.brickystudios.antwortradar

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap

data class Messenger(val pkg: String, val label: String, val onByDefault: Boolean)

/** Apps the radar may read. All use Android's MessagingStyle; Instagram, Discord and Snapchat are off by default (untested formats). */
val MESSENGERS = listOf(
    Messenger("com.whatsapp", "WhatsApp", true),
    Messenger("com.whatsapp.w4b", "WhatsApp Business", true),
    Messenger("org.thoughtcrime.securesms", "Signal", true),
    Messenger("im.molly.app", "Molly", true),
    Messenger("org.telegram.messenger", "Telegram", true),
    Messenger("org.telegram.messenger.web", "Telegram", true),
    Messenger("org.thunderdog.challegram", "Telegram X", true),
    Messenger("ch.threema.app", "Threema", true),
    Messenger("ch.threema.app.libre", "Threema Libre", true),
    Messenger("com.google.android.apps.messaging", "Google Messages", true),
    Messenger("org.fossify.messages", "Fossify SMS", true),
    Messenger("com.moez.QKSMS", "QKSMS", true),
    Messenger("com.instagram.android", "Instagram", false),
    Messenger("com.discord", "Discord", false),
    Messenger("com.snapchat.android", "Snapchat", false),
)

fun messengerLabel(pkg: String) = MESSENGERS.firstOrNull { it.pkg == pkg }?.label ?: pkg

/**
 * What a live notification lets us do: open the exact chat and answer through the messenger's own reply button.
 * PendingIntents cannot be saved to disk, so this lives in memory only. After the app process restarts, a tap
 * falls back to opening the messenger and a reply falls back to "copy and open".
 */
object Live {
    private class Acts(val open: PendingIntent?, val reply: Notification.Action?)
    private val acts = ConcurrentHashMap<String, Acts>()

    fun remember(key: String, n: Notification) {
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        acts[key] = Acts(n.contentIntent, reply)
    }

    fun canReply(key: String) = acts[key]?.reply != null

    /** Opens the chat itself when we still hold its intent, otherwise the messenger. */
    fun open(ctx: Context, key: String) {
        val pi = acts[key]?.open
        if (pi != null && runCatching { pi.send(ctx, 0, null, null, null, null, allowStart()) }.isSuccess) return
        ctx.packageManager.getLaunchIntentForPackage(key.substringBefore('|'))?.let {
            ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Sends [text] through the messenger's reply button. false = not possible (no button, or the messenger revoked it). */
    fun reply(ctx: Context, key: String, text: String): Boolean {
        val a = acts[key]?.reply ?: return false
        val inputs = a.remoteInputs ?: return false
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val intent = Intent().also { RemoteInput.addResultsToIntent(inputs, it, results) }
        return runCatching { a.actionIntent.send(ctx, 0, intent) }.isSuccess
    }

    /** Fallback when there is no reply button: the text goes to the clipboard and the chat opens. */
    fun copyAndOpen(ctx: Context, key: String, text: String) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Antwort", text))
        open(ctx, key)
    }

    // Android 14+: a PendingIntent created by the messenger may only start its chat screen if we (in the foreground) allow it
    @Suppress("DEPRECATION")
    private fun allowStart(): Bundle? = if (Build.VERSION.SDK_INT >= 34)
        ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
    else null
}
