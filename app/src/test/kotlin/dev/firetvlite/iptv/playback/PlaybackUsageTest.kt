package dev.firetvlite.iptv.playback

import dev.firetvlite.iptv.data.ChannelUsageKey
import dev.firetvlite.iptv.data.ChannelUsageSink
import org.junit.Assert.*
import org.junit.Test

class PlaybackUsageTest {
    private val key = ChannelUsageKey(1, "local:channel")

    @Test fun countsOnlyFirstActualStartAndAccumulatesOnlyPlayingIntervals() {
        var now = 0L
        val counts = mutableListOf<Long>()
        val times = mutableListOf<Long>()
        val usage = PlaybackUsage({ now }, ChannelUsageSink { _, count, elapsed -> counts += count; times += elapsed })
        usage.request(key, userInitiated = true, automatic = false)
        now = 10_000
        assertTrue(counts.isEmpty())
        usage.playing(true)
        now += 1000
        usage.sample()
        now += 2000
        usage.playing(false)
        now += 60_000
        usage.playing(true)
        now += 4000
        usage.stop()
        assertEquals(1L, counts.sum())
        assertEquals(7000L, times.sum())
    }

    @Test fun reconnectAndUnidentifiedHealthRequestsNeverCountButResumedPlaybackAddsTime() {
        var now = 0L
        var count = 0L
        var time = 0L
        val usage = PlaybackUsage({ now }, ChannelUsageSink { _, selections, elapsed -> count += selections; time += elapsed })
        for ((request, automatic) in listOf(key to true, null to false)) {
            usage.request(request, userInitiated = true, automatic = automatic)
            usage.playing(true)
            now += 1000
            usage.stop()
        }
        assertEquals(0L, count)
        assertEquals(0L, time)
        usage.request(key, userInitiated = false, automatic = false)
        usage.playing(true)
        now += 2000
        usage.stop()
        assertEquals(0L, count)
        assertEquals(2000L, time)
    }

    @Test fun failedStartupAndPreviewWithoutRealStartAreIgnoredAndSinkErrorsCannotAffectPlayback() {
        val usage = PlaybackUsage({ 0 }, ChannelUsageSink { _, _, _ -> error("database") })
        usage.request(key, userInitiated = true, automatic = false)
        usage.playing(false)
        usage.stop()
        usage.request(key, userInitiated = true, automatic = false)
        usage.playing(true)
        usage.stop()
    }
}
