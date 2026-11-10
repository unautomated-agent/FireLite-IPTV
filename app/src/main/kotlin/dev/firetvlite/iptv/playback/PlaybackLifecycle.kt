package dev.firetvlite.iptv.playback

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Ties the stream to the activity: nothing may stay connected in the background.
 * Pausing (Home, screensaver, a system dialog) closes the stream, stopping frees the decoder,
 * and coming back reconnects the channel that was running.
 */
class PlaybackLifecycle(private val playback: PlaybackController) : DefaultLifecycleObserver {

    override fun onPause(owner: LifecycleOwner) {
        playback.suspendStream()
    }

    override fun onStop(owner: LifecycleOwner) {
        playback.releasePlayer()
    }

    override fun onResume(owner: LifecycleOwner) {
        playback.resumeStream()
    }
}
