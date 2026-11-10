package dev.firetvlite.iptv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dev.firetvlite.iptv.playback.PlaybackLifecycle
import dev.firetvlite.iptv.ui.viewmodel.LibraryViewModel
import dev.firetvlite.iptv.ui.viewmodel.PlayerViewModel
import dev.firetvlite.iptv.update.UpdateViewModel

/** Single activity of the app. All screens are Compose destinations hosted by [FireLiteApp]. */
class MainActivity : ComponentActivity() {

    private val updateViewModel: UpdateViewModel by viewModels()
    private val libraryViewModel: LibraryViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by viewModels()

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
