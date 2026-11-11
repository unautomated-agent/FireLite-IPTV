package dev.firetvlite.iptv.playback

enum class PlayerMode {
    Closed, Fullscreen, Overlay, Preview, Guide, MainMenu;

    val acceptsPreview: Boolean get() = this == Closed || this == Preview
    val fullFrame: Boolean get() = this == Fullscreen || this == Overlay
    val recordsWatch: Boolean get() = this == Fullscreen
    val continuesPlayback: Boolean get() = this != Closed

    fun onNavigate(previewAllowed: Boolean): PlayerMode =
        if (this == Preview && !previewAllowed) Closed else this

    fun afterBack(previewAllowed: Boolean = true): PlayerMode = when (this) {
        Fullscreen -> if (previewAllowed) Preview else Closed
        Overlay -> Fullscreen
        else -> this
    }
}
