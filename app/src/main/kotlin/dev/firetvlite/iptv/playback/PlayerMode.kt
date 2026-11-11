package dev.firetvlite.iptv.playback

enum class PlayerMode {
    Closed, Fullscreen, Overlay, Preview;

    val acceptsPreview: Boolean get() = this == Closed || this == Preview
    val fullFrame: Boolean get() = this == Fullscreen || this == Overlay

    fun afterBack(): PlayerMode = when (this) {
        Fullscreen -> Preview
        Overlay -> Fullscreen
        else -> this
    }
}
