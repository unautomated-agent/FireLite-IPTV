package dev.firetvlite.iptv.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSourceCompatibilityTest {
    @Test fun adaptiveFactoriesHaveTheConstructorsUsedReflectivelyByMedia3() {
        for (name in listOf("dash.DashMediaSource", "smoothstreaming.SsMediaSource", "hls.HlsMediaSource")) {
            val factory = Class.forName("androidx.media3.exoplayer.$name\$Factory")
            assertTrue(MediaSource.Factory::class.java.isAssignableFrom(factory))
            factory.getConstructor(DataSource.Factory::class.java)
        }
    }

    @Test fun dashSelectionComesFromMpdOrExplicitMimeNotTheHlsOrTsFallback() {
        assertEquals(C.CONTENT_TYPE_DASH, Util.inferContentTypeForExtension("mpd"))
        assertEquals(C.CONTENT_TYPE_HLS, Util.inferContentTypeForExtension("m3u8"))
        assertEquals(C.CONTENT_TYPE_OTHER, Util.inferContentTypeForExtension("ts"))
        assertEquals(C.CONTENT_TYPE_DASH, Util.inferContentTypeForUriAndMimeType(Uri.EMPTY, MimeTypes.APPLICATION_MPD))
        assertEquals(C.CONTENT_TYPE_OTHER, Util.inferContentTypeForUriAndMimeType(Uri.EMPTY, MimeTypes.VIDEO_MP2T))
    }
}
