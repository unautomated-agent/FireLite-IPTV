package dev.firetvlite.iptv.playback

import androidx.media3.exoplayer.ExoPlayer
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

    private class FakePlayer {
        val calls = mutableListOf<String>()
        val player = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "FakePlayer"
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
