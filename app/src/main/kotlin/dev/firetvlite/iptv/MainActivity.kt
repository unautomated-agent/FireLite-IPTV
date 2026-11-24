package dev.firetvlite.iptv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dev.firetvlite.iptv.playback.PlaybackLifecycle
import dev.firetvlite.iptv.playback.RemoteAlias
import dev.firetvlite.iptv.ui.navigation.GuideBackAction
import dev.firetvlite.iptv.ui.navigation.guideBackAction
import dev.firetvlite.iptv.ui.navigation.OverlayBackAction
import dev.firetvlite.iptv.ui.navigation.overlayBackAction
import dev.firetvlite.iptv.ui.viewmodel.LibraryViewModel
import dev.firetvlite.iptv.ui.viewmodel.PlayerViewModel
import dev.firetvlite.iptv.update.UpdateViewModel

/** Single activity of the app. All screens are Compose destinations hosted by [FireLiteApp]. */
class MainActivity : ComponentActivity() {

    private val updateViewModel: UpdateViewModel by viewModels()
    private val libraryViewModel: LibraryViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by viewModels()

    /** A key of another remote learned in Settings, e.g. Tools as Menu and Exit as Home. */
    private fun remoteAlias(event: KeyEvent): Boolean? {
        val container = (application as FireLiteApplication).container
        if (!container.startup.ready.value) return null
        val remote = container.remoteKeys
        if (remote.learning.value != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                remote.pressed(event.keyCode)
                learnedKeyDown = event.keyCode
            }
            return true
        }
        if (event.keyCode == learnedKeyDown) {
            // The rest of the press that was just learned.
            if (event.action == KeyEvent.ACTION_UP) learnedKeyDown = KeyEvent.KEYCODE_UNKNOWN
            return true
        }
        return when (remote.keys.value.alias(event.keyCode)) {
            RemoteAlias.Menu -> dispatchKeyEvent(KeyEvent(event.downTime, event.eventTime, event.action,
                KeyEvent.KEYCODE_MENU, event.repeatCount, event.metaState, event.deviceId, event.scanCode,
                event.flags, event.source))
            RemoteAlias.Home -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) runCatching {
                    startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                true
            }
            null -> null
        }
    }

    private var learnedKeyDown = KeyEvent.KEYCODE_UNKNOWN

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) playerViewModel.cancelAutoReconnect()
        remoteAlias(event)?.let { return it }
        if (event.keyCode == KeyEvent.KEYCODE_SEARCH) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) playerViewModel.openSearch()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && !playerViewModel.searchVisible.value) {
            when (overlayBackAction(playerViewModel.mode.value, event.action == KeyEvent.ACTION_UP, event.isCanceled)) {
                OverlayBackAction.Pass -> Unit
                OverlayBackAction.Consume -> return true
                OverlayBackAction.Close -> { playerViewModel.closeOverlay(); return true }
            }
            when (guideBackAction(playerViewModel.mode.value, playerViewModel.channel.value != null,
                event.action == KeyEvent.ACTION_UP, event.isCanceled)) {
                GuideBackAction.Pass -> Unit
                GuideBackAction.Consume -> return true
                GuideBackAction.ReturnToPlayback -> { playerViewModel.returnToFullscreen(); return true }
                GuideBackAction.Exit -> { finish(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) playerViewModel.cancelAutoReconnect()
        return super.dispatchTouchEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No stream may stay connected while the app is in the background.
        val container = (application as FireLiteApplication).container
        container.startup.mark(LaunchPhase.Activity)
        lifecycle.addObserver(PlaybackLifecycle(container.playback,
            playerViewModel::pausePlaybackWork, playerViewModel::resumePlaybackWork))

        val player = playerViewModel

        setContent {
            FireLiteApp(
                updateViewModel = updateViewModel,
                libraryViewModel = libraryViewModel,
                playerViewModel = player,
            )
        }
    }
}
