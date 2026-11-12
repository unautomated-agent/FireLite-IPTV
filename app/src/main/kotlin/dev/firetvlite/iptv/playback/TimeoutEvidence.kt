package dev.firetvlite.iptv.playback

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest

internal object TimeoutEvidence {
    fun lines(player: ExoPlayer?, readiness: Map<Int, Boolean>): List<String> = buildList {
        fun counters(label: String, type: Int, counters: DecoderCounters?) {
            counters?.ensureUpdated()
            add("$label ready=${readiness[type] ?: "?"} in=${counters?.queuedInputBufferCount ?: "?"}" +
                " out=${counters?.renderedOutputBufferCount ?: "?"}")
            add("$label skipIn=${counters?.skippedInputBufferCount ?: "?"}" +
                " skipOut=${counters?.skippedOutputBufferCount ?: "?"} drop=${counters?.droppedBufferCount ?: "?"}")
        }
        counters("A", C.TRACK_TYPE_AUDIO, player?.audioDecoderCounters)
        counters("V", C.TRACK_TYPE_VIDEO, player?.videoDecoderCounters)
        add("A rate=${player?.audioFormat?.sampleRate ?: "?"} ch=${player?.audioFormat?.channelCount ?: "?"}")
        if (player == null || player.currentTimeline.isEmpty) {
            add("Window: unknown")
        } else {
            val window = player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
            val pdt = (player.currentManifest as? HlsManifest)?.mediaPlaylist?.hasProgramDateTime
            addAll(windowLines(window, player.currentLiveOffset, pdt))
        }
    }

    fun windowLines(window: Timeline.Window, liveOffset: Long, pdt: Boolean?): List<String> {
        fun time(value: Long) = if (value == C.TIME_UNSET) "?" else value.toString()
        return listOf(
            "Window start=${time(window.windowStartTimeMs)} default=${time(window.defaultPositionMs)}",
            "Window duration=${time(window.durationMs)} live=${window.isLive} PDT=${pdt?.let { if (it) "y" else "n" } ?: "?"}",
            "Window offset=${window.positionInFirstPeriodMs} liveOffset=${time(liveOffset)}",
        )
    }
}
