package dev.firetvlite.iptv.playback

object ZapMath {
    /** Moves [index] by [delta] inside a list of [size] items and wraps around at both ends. */
    fun wrap(index: Int, delta: Int, size: Int): Int {
        require(size > 0) { "size must be positive" }
        return ((index + delta) % size + size) % size
    }
}
