package dev.firetvlite.iptv.playback

import android.os.Handler
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Test

class FfmpegArtifactTest {
    @Test fun publishedRendererHasTheConstructorExpectedByMedia3() {
        val constructor = FfmpegAudioRenderer::class.java.getConstructor(Handler::class.java,
            AudioRendererEventListener::class.java, AudioSink::class.java)
        assertEquals(3, constructor.parameterCount)
    }

    @Test fun nativeAvailabilityAndVersionEntryPointsArePresent() {
        assertEquals(java.lang.Boolean.TYPE, FfmpegLibrary::class.java.getMethod("isAvailable").returnType)
        assertEquals(String::class.java, FfmpegLibrary::class.java.getMethod("getVersion").returnType)
    }
}
