package dev.firetvlite.iptv.playback

object PlaybackSourcePolicy {
    fun shouldClose(playingSourceId: Long?, activeSourceId: Long?) =
        playingSourceId != null && playingSourceId != activeSourceId
}
