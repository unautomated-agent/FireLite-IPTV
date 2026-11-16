package dev.firetvlite.iptv.playback

object ScreenAwakePolicy {
    fun keepAwake(mode: PlayerMode, status: PlaybackStatus, playWhenReady: Boolean): Boolean =
        mode != PlayerMode.Closed && playWhenReady && status in setOf(
            PlaybackStatus.Connecting, PlaybackStatus.Reconnecting, PlaybackStatus.Buffering, PlaybackStatus.Playing)
}
