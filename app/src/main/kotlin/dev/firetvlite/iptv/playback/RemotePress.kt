package dev.firetvlite.iptv.playback

class RemotePress {
    private var active = false
    private var longFired = false

    fun begin() {
        active = true
        longFired = false
    }

    fun longPress(): Boolean {
        if (!active || longFired) return false
        longFired = true
        return true
    }

    fun release(): Boolean {
        val shortPress = active && !longFired
        active = false
        return shortPress
    }
}
