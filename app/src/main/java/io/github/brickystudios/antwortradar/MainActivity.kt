package io.github.brickystudios.antwortradar

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private sealed interface Screen {
    data object List : Screen
    data object Settings : Screen
    data class Sprint(val start: String?) : Screen
}

class MainActivity : ComponentActivity() {
    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val sprintRequest = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        sprintRequest.value = intent.getBooleanExtra(Summary.EXTRA_SPRINT, false)
        // only Unreplied-Updates.apk has internet; release builds only, a debug build carries another key
        if (Updater.supported && !BuildConfig.DEBUG && Prefs(this).autoUpdate) lifecycleScope.launch { Updater.check(this@MainActivity, manual = false) }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = CardBg, primary = Coral, onPrimary = Ink)) {
                Box(Modifier.fillMaxSize().background(Ink).safeDrawingPadding()) { App(sprintRequest) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(Summary.EXTRA_SPRINT, false)) sprintRequest.value = true
    }

    override fun onResume() {
        super.onResume()
        Widgets.refresh(this) // "Später" may have run out since the last change
        // access granted but the listener not bound (after an update, or killed by a battery saver): ask again
        val granted = packageName in NotificationManagerCompat.getEnabledListenerPackages(this)
        if (granted && !Live.connected) NotificationListenerService.requestRebind(ComponentName(this, RadarListener::class.java))
    }
}

@Composable
private fun App(sprintRequest: MutableState<Boolean>) {
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleResumeEffect(Unit) { now = System.currentTimeMillis(); onPauseOrDispose {} }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } } // ages and "Später" tick on screen
    LaunchedEffect(sprintRequest.value) { if (sprintRequest.value) { screen = Screen.Sprint(null); sprintRequest.value = false } }
    BackHandler(enabled = screen != Screen.List) { screen = Screen.List }

    val ctx = LocalContext.current
    val still = remember { reducedMotion(ctx) }
    // push and pop on one axis: deeper screens come in from the right and leave the way they came
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            val forward = targetState != Screen.List
            val move = spring(dampingRatio = 1f, stiffness = SPRING_STIFFNESS, visibilityThreshold = IntOffset.VisibilityThreshold)
            when {
                still -> fadeIn(tween(150)) togetherWith fadeOut(tween(100))
                forward -> slideInHorizontally(move) { it } togetherWith (slideOutHorizontally(move) { -it / 4 } + fadeOut(tween(220)))
                else -> (slideInHorizontally(move) { -it / 4 } + fadeIn(tween(220))) togetherWith slideOutHorizontally(move) { it }
            }.apply { targetContentZIndex = if (forward) 1f else 0f }
        },
        label = "screen",
    ) { s ->
        when (s) {
            Screen.List -> ListScreen(now, onSettings = { screen = Screen.Settings }, onSprint = { screen = Screen.Sprint(it) })
            Screen.Settings -> SettingsScreen(now, onBack = { screen = Screen.List })
            is Screen.Sprint -> SprintScreen(s.start, now, onExit = { screen = Screen.List })
        }
    }
}
