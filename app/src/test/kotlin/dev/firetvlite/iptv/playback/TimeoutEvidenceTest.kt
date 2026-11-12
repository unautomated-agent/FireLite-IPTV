package dev.firetvlite.iptv.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import java.lang.reflect.Proxy
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeoutEvidenceTest {
    @Test fun rendererCountersRatesAndUnknownValuesStayOnShortLines() {
        val counters = DecoderCounters().apply { queuedInputBufferCount = 50; renderedOutputBufferCount = 0; skippedOutputBufferCount = 3 }
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, _ ->
            when (method.name) {
                "getAudioDecoderCounters" -> counters
                "getAudioFormat" -> Format.Builder().setSampleMimeType("audio/mpeg-L2").setSampleRate(48000).setChannelCount(2).build()
                "getCurrentTimeline" -> Timeline.EMPTY
                else -> null
            }
        } as ExoPlayer
        val lines = TimeoutEvidence.lines(player, mapOf(C.TRACK_TYPE_AUDIO to false, C.TRACK_TYPE_VIDEO to true))
        for (value in listOf("A ready=false in=50 out=0", "A skipIn=0 skipOut=3 drop=0", "V ready=true in=? out=?",
                "A rate=48000 ch=2", "Window: unknown")) assertTrue(lines.toString(), value in lines)
        assertTrue(lines.all { it.toByteArray().size < 100 })
        assertTrue(TimeoutEvidence.lines(null, emptyMap()).contains("A ready=? in=? out=?"))
    }

    @Test fun liveWindowPdtPresenceAndUnsetTimesAreExplicit() {
        val window = Timeline.Window().set(Unit, MediaItem.EMPTY, null, C.TIME_UNSET, 1_800_000_000_000,
            C.TIME_UNSET, true, true, MediaItem.LiveConfiguration.Builder().build(), 15_000_000, 30_000_000, 0, 0, 10_000_000)
        for (pdt in listOf(true, false)) {
            val lines = TimeoutEvidence.windowLines(window, C.TIME_UNSET, pdt)
            assertTrue(lines.contains("Window start=1800000000000 default=15000"))
            assertTrue(lines.contains("Window duration=30000 live=true PDT=${if (pdt) "y" else "n"}"))
            assertTrue(lines.contains("Window offset=10000 liveOffset=?"))
            assertTrue(lines.all { it.toByteArray().size < 100 })
        }
    }
}
