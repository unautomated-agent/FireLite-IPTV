package dev.firetvlite.iptv.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

internal class PlaybackMediaSourceFactory(dataSource: DataSource.Factory) : MediaSource.Factory {
    private val hls = HlsMediaSource.Factory(HlsDataSourceFactory { type ->
        if (dataSource is ClassifiedHttpFactory) dataSource.createDataSource(type) else dataSource.createDataSource()
    }).setExtractorFactory(hlsExtractors())
    private val other = DefaultMediaSourceFactory(dataSource, progressiveExtractors())

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val config = checkNotNull(mediaItem.localConfiguration)
        return if (Util.inferContentTypeForUriAndMimeType(config.uri, config.mimeType) == C.CONTENT_TYPE_HLS)
            hls.createMediaSource(mediaItem) else other.createMediaSource(mediaItem)
    }

    override fun getSupportedTypes(): IntArray = other.supportedTypes

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = apply {
        hls.setDrmSessionManagerProvider(provider)
        other.setDrmSessionManagerProvider(provider)
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = apply {
        hls.setLoadErrorHandlingPolicy(policy)
        other.setLoadErrorHandlingPolicy(policy)
    }

    override fun setCmcdConfigurationFactory(factory: CmcdConfiguration.Factory) = apply {
        hls.setCmcdConfigurationFactory(factory)
        other.setCmcdConfigurationFactory(factory)
    }

    override fun setSubtitleParserFactory(factory: SubtitleParser.Factory) = apply {
        hls.setSubtitleParserFactory(factory)
        other.setSubtitleParserFactory(factory)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun experimentalParseSubtitlesDuringExtraction(parse: Boolean) = apply {
        hls.experimentalParseSubtitlesDuringExtraction(parse)
        other.experimentalParseSubtitlesDuringExtraction(parse)
    }

    companion object {
        fun hlsExtractors() = DefaultHlsExtractorFactory(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES, true)

        fun progressiveExtractors() = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
    }
}
