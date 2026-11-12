package dev.firetvlite.iptv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dev.firetvlite.iptv.playback.PlaybackLifecycle
import dev.firetvlite.iptv.ui.navigation.GuideBackAction
import dev.firetvlite.iptv.ui.navigation.guideBackAction
import dev.firetvlite.iptv.ui.viewmodel.LibraryViewModel
import dev.firetvlite.iptv.ui.viewmodel.PlayerViewModel
import dev.firetvlite.iptv.update.UpdateViewModel

/** Single activity of the app. All screens are Compose destinations hosted by [FireLiteApp]. */
class MainActivity : ComponentActivity() {

    private val updateViewModel: UpdateViewModel by viewModels()
    private val libraryViewModel: LibraryViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by viewModels()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No stream may stay connected while the app is in the background.
        val container = (application as FireLiteApplication).container
        lifecycle.addObserver(PlaybackLifecycle(container.playback))

        setContent {
            FireLiteApp(
                updateViewModel = updateViewModel,
                libraryViewModel = libraryViewModel,
                playerViewModel = playerViewModel,
            )
        }
    }
}
