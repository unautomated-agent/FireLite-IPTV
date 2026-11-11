package dev.firetvlite.iptv.playback

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.MediaItem
import java.lang.reflect.Proxy
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackControllerTest {
    private val request = StreamRequest("https://example.org/live", "FireLite")

    @Test
    fun stopAllowsTheSameChannelToReopenOnTheExistingPlayer() = runBlocking {
        val fake = FakePlayer()
        var creations = 0
        val controller = PlaybackController(this) { creations++; fake.player }

        controller.play(request)
        controller.stop()
        controller.play(request)

        assertEquals(1, creations)
        assertEquals(PlaybackStatus.Connecting, controller.status.value)
        assertSame(fake.player, controller.player.value)
        controller.stop()
    }

    @Test
    fun releasingCancelsPendingStartAndAllowsTheSameChannelOnANewPlayer() = runBlocking {
        val first = FakePlayer()
        val second = FakePlayer()
        var creations = 0
        val controller = PlaybackController(this) {
            if (creations++ == 0) first.player else second.player
        }

        controller.play(request)
        controller.releasePlayer()
        delay(450)

        assertEquals(1, first.calls.count { it == "release" })
        assertEquals(0, first.calls.count { it == "setMediaItem" || it == "prepare" })
        assertNull(controller.player.value)
        assertEquals(PlaybackStatus.Idle, controller.status.value)

        controller.play(request)
        assertEquals(2, creations)
        assertSame(second.player, controller.player.value)
        controller.stop()
    }

    @Test
    fun explicitStopWhileSuspendedDoesNotResurrectTheClosedChannel() = runBlocking {
        val fake = FakePlayer()
        val controller = PlaybackController(this) { fake.player }
        controller.play(request)
        controller.suspendStream()
        controller.stop()
        val stops = fake.calls.count { it == "stop" }

        controller.resumeStream()

        assertEquals(stops, fake.calls.count { it == "stop" })
        assertEquals(PlaybackStatus.Idle, controller.status.value)
    }

    @Test
    fun pauseReleaseAndResumeRestoresTheSuspendedChannel() = runBlocking {
        var creations = 0
        val controller = PlaybackController(this) { creations++; FakePlayer().player }
        controller.play(request)
        controller.suspendStream()
        controller.suspendStream()
        controller.releasePlayer()
        controller.resumeStream()

        assertEquals(2, creations)
        assertEquals(PlaybackStatus.Connecting, controller.status.value)
        controller.stop()
    }

    @Test
    fun stoppingAndReleasingAnEmptyControllerIsSafe() = runBlocking {
        var creations = 0
        val controller = PlaybackController(this) { creations++; FakePlayer().player }
        controller.stop()
        controller.releasePlayer()
        controller.resumeStream()

        assertEquals(0, creations)
        assertNull(controller.player.value)
        assertEquals(PlaybackSnapshot.EMPTY, controller.snapshot())
    }

    @Test
    fun previewCapsTracksAndFullscreenLiftsCapsWithoutReconnecting() = runBlocking {
        val fake = FakePlayer()
        val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY })
        controller.setPreview(true)
        controller.play(request)
        delay(450)
        assertEquals(960, fake.parameters.maxVideoWidth)
        assertEquals(540, fake.parameters.maxVideoHeight)
        assertEquals(1_500_000, fake.parameters.maxVideoBitrate)
        val calls = fake.calls.count { it == "stop" || it == "prepare" || it == "setMediaItem" }

        controller.setPreview(false)
        controller.play(request)

        assertEquals(Int.MAX_VALUE, fake.parameters.maxVideoWidth)
        assertEquals(Int.MAX_VALUE, fake.parameters.maxVideoBitrate)
        assertEquals(calls, fake.calls.count { it == "stop" || it == "prepare" || it == "setMediaItem" })
        assertSame(fake.player, controller.player.value)
        controller.stop()
    }

    @Test
    fun switchingStopsBeforeTheNextPrepareAndRapidRequestsMerge() = runBlocking {
        val fake = FakePlayer()
        val trace = mutableListOf<String>()
        val controller = PlaybackController(this, { fake.player }, { trace += it }, { MediaItem.EMPTY })
        controller.play(request)
        delay(450)
        fake.calls.clear()

        controller.play(request.copy(url = "https://example.org/second"))
        controller.play(request.copy(url = "https://example.org/third"))
        assertEquals(0, fake.calls.count { it == "prepare" })
        delay(450)

        assertEquals(1, fake.calls.count { it == "prepare" })
        assertEquals(2, fake.calls.count { it == "stop" })
        assertEquals(true, fake.calls.indexOf("stop") < fake.calls.indexOf("prepare"))
        assertEquals(2, trace.count { it.startsWith("PREPARE") })
        controller.stop()
    }

    private class FakePlayer {
        val calls = mutableListOf<String>()
        var parameters = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
        val player = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "FakePlayer"
                "getTrackSelectionParameters" -> parameters
                "setTrackSelectionParameters" -> { parameters = args!![0] as TrackSelectionParameters; null }
                else -> {
                    calls += method.name
                    when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        java.lang.Float.TYPE -> 0f
                        else -> null
                    }
                }
            }
        } as ExoPlayer
    }
}
