package dev.firetvlite.iptv.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.ParsingLoadable

internal class HlsFailover(private val session: HlsSession) : DefaultLoadErrorHandlingPolicy() {
    override fun getFallbackSelectionFor(options: LoadErrorHandlingPolicy.FallbackOptions,
        error: LoadErrorHandlingPolicy.LoadErrorInfo): LoadErrorHandlingPolicy.FallbackSelection? =
        super.getFallbackSelectionFor(options, error)?.also {
            session.fallback(error.loadEventInfo.dataSpec.uri.toString())
        }

    override fun getRetryDelayMsFor(error: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val uri = error.loadEventInfo.uri.toString()
        if (!session.archive && PlaybackHttpError.status(error.exception) in setOf(404, 410) &&
            session.kind(uri, error.mediaLoadData.dataType) in setOf("audio", "subs", "segment")) {
            return if (error.errorCount < 3) 250L else C.TIME_UNSET
        }
        return super.getRetryDelayMsFor(error)
    }
}

internal class HlsSessionParser(private val session: HlsSession) : HlsPlaylistParserFactory {
    private val delegate = DefaultHlsPlaylistParserFactory()
    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> = observe(delegate.createPlaylistParser())
    override fun createPlaylistParser(master: HlsMultivariantPlaylist, previous: HlsMediaPlaylist?): ParsingLoadable.Parser<HlsPlaylist> =
        observe(delegate.createPlaylistParser(master, previous))

    private fun observe(parser: ParsingLoadable.Parser<HlsPlaylist>) = ParsingLoadable.Parser<HlsPlaylist> { uri, input ->
        when (val playlist = parser.parse(uri, input)) {
            is HlsMultivariantPlaylist -> session.parsed(playlist)
            is HlsMediaPlaylist -> playlist.also { session.parsed(it) }
            else -> playlist
        }
    }
}
