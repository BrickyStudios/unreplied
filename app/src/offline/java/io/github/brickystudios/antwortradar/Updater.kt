package io.github.brickystudios.antwortradar

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** Unreplied.apk has no internet permission: nothing to check, nothing to download. Updates come through Obtainium. */
object Updater {
    const val supported = false
    val state = MutableStateFlow<UpdateState?>(null)
    val note = MutableStateFlow<Int?>(null)
    suspend fun check(ctx: Context, manual: Boolean) = Unit
    suspend fun install(ctx: Context) = Unit
    fun later() = Unit
}
