package dev.firetvlite.iptv.playback

enum class PlayerMode {
    Closed, Fullscreen, Overlay, Preview;

    val acceptsPreview: Boolean get() = this == Closed || this == Preview
    val fullFrame: Boolean get() = this == Fullscreen || this == Overlay
    val recordsWatch: Boolean get() = this == Fullscreen

    fun afterBack(previewAllowed: Boolean = true): PlayerMode = when (this) {
        Fullscreen -> if (previewAllowed) Preview else Closed
        Overlay -> Fullscreen
        else -> this
    }
}
