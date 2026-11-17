package dev.firetvlite.iptv.playback

import dev.firetvlite.iptv.data.ChannelUsageKey
import dev.firetvlite.iptv.data.ChannelUsageSink

internal class PlaybackUsage(private val clock: () -> Long, private val sink: ChannelUsageSink) {
    private var key: ChannelUsageKey? = null
    private var countSelection = false
    private var started = false
    private var eligible = false
    private var since: Long? = null

    fun request(key: ChannelUsageKey?, userInitiated: Boolean, automatic: Boolean) {
        val continuing = key != null && this.key == key && started
        stop()
        this.key = key
        eligible = !automatic
        countSelection = userInitiated
        started = continuing
    }

    fun playing(active: Boolean) {
        sample()
        val key = key
        if (active && key != null && eligible) {
            if (!started) {
                started = true
                if (countSelection) emit(key, 1, 0)
            }
            since = clock()
        } else since = null
    }

    fun sample() {
        val start = since ?: return
        val now = clock()
        since = now
        val elapsed = (now - start).coerceAtLeast(0)
        if (elapsed > 0) key?.let { emit(it, 0, elapsed) }
    }

    fun stop() {
        playing(false)
        key = null
    }

    private fun emit(key: ChannelUsageKey, count: Long, elapsed: Long) {
        runCatching { sink.record(key, count, elapsed) }
    }
}
