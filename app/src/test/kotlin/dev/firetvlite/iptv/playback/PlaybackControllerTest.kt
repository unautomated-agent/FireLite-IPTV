package dev.firetvlite.iptv.playback

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import android.os.Bundle
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
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

    @Test fun liveWindowRecoveryStopsThenSeeksAndPreparesWithoutANewSession() = runBlocking {
        val fake = FakePlayer()
        val trace = mutableListOf<String>()
        val controller = PlaybackController(this, { fake.player }, { trace += it }, { MediaItem.EMPTY })
        controller.play(request)
        delay(450)
        fake.calls.clear()
        fake.listener!!.onPlayerError(TestError(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
        assertEquals(listOf("stop"), fake.calls.filter { it in listOf("stop", "prepare", "seekToDefaultPosition") })
        delay(450)
        assertEquals(listOf("stop", "seekToDefaultPosition", "prepare"),
            fake.calls.filter { it in listOf("stop", "prepare", "seekToDefaultPosition") })
        assertEquals(1, trace.count { it.startsWith("PREPARE session=1") })
        assertEquals(1, trace.count { it == "RECOVER session=1 action=LiveEdge" })
        controller.stop()
    }

    @Test fun formatFallbackKeepsTheUrlAndSessionAndIsOnlySelectedOnce() = runBlocking {
        val fake = FakePlayer()
        val trace = mutableListOf<String>()
        val urls = mutableListOf<String>()
        val hlsUrls = mutableListOf<String>()
        var sources = 0
        val controller = PlaybackController(this, { fake.player }, { trace += it }, { hlsUrls += it; MediaItem.EMPTY },
            setStream = { _, _, _ -> sources++ }, progressiveItem = { urls += it; MediaItem.EMPTY })
        val hls = request.copy(url = "https://example.org/live.m3u8")
        controller.play(hls)
        delay(450)
        fake.listener!!.onPlayerError(TestError(3002))
        delay(450)
        assertEquals(listOf(hls.url), urls)
        assertEquals(2, sources)
        assertEquals(1, trace.count { it == "RECOVER session=1 action=Progressive" })
        fake.listener!!.onPlayerError(TestError(3002))
        val connections = PlaybackController::class.java.getDeclaredField("connections").let {
            it.isAccessible = true
            it.get(controller) as AtomicInteger
        }
        connections.set(1)
        delay(450)
        assertEquals(2, sources)
        connections.decrementAndGet()
        delay(50)
        assertEquals(3, sources)
        assertEquals(listOf(hls.url, hls.url), hlsUrls)
        assertEquals(listOf(hls.url), urls)
        assertEquals(1, trace.count { it == "RECOVER session=1 action=RestoreHls" })
        fake.listener!!.onPlayerError(TestError(3002))
        delay(450)
        assertEquals(3, sources)
        assertEquals(listOf(hls.url), urls)
        controller.stop()
    }

    @Test fun successfulProgressivePlaybackRetriesItsSourceInTheSameSessionAfterTransientErrors() = runBlocking {
        val fake = FakePlayer()
        val trace = mutableListOf<String>()
        val hlsUrls = mutableListOf<String>()
        val progressiveUrls = mutableListOf<String>()
        var sources = 0
        val controller = PlaybackController(this, { fake.player }, { trace += it }, { hlsUrls += it; MediaItem.EMPTY },
            setStream = { _, _, _ -> sources++ }, progressiveItem = { progressiveUrls += it; MediaItem.EMPTY })
        val hls = request.copy(url = "https://example.org/live.m3u8")
        controller.play(hls)
        delay(450)
        fake.listener!!.onPlayerError(TestError(3002))
        delay(450)
        fake.state = Player.STATE_READY
        fake.listener!!.onPlaybackStateChanged(fake.state)
        fake.state = Player.STATE_BUFFERING
        fake.listener!!.onPlaybackStateChanged(fake.state)
        val connections = PlaybackController::class.java.getDeclaredField("connections").let {
            it.isAccessible = true
            it.get(controller) as AtomicInteger
        }
        connections.set(1)
        fake.calls.clear()
        fake.listener!!.onPlayerError(TestError(2001))
        delay(1600)
        assertEquals(0, fake.calls.count { it == "prepare" })
        connections.decrementAndGet()
        delay(50)
        assertEquals(1, fake.calls.count { it == "prepare" })
        fake.listener!!.onPlayerError(TestError(3002))
        delay(3100)
        assertEquals(2, trace.count { it == "RECOVER session=1 action=Retry" })
        assertEquals(0, trace.count { it.endsWith("action=RestoreHls") })
        assertEquals(2, sources)
        assertEquals(listOf(hls.url), hlsUrls)
        assertEquals(listOf(hls.url), progressiveUrls)
        controller.stop()
    }

    @Test fun segmentErrorsAfterReadyKeepTheHlsSourceEvenAfterBufferingAgain() = runBlocking {
        val fake = FakePlayer()
        val trace = mutableListOf<String>()
        var progressive = 0
        var sources = 0
        val controller = PlaybackController(this, { fake.player }, { trace += it }, { MediaItem.EMPTY },
            setStream = { _, _, _ -> sources++ }, progressiveItem = { progressive++; MediaItem.EMPTY })
        controller.play(request.copy(url = "https://example.org/live.m3u8"))
        delay(450)
        fake.state = Player.STATE_READY
        fake.listener!!.onPlaybackStateChanged(fake.state)
        fake.state = Player.STATE_BUFFERING
        fake.listener!!.onPlaybackStateChanged(fake.state)
        fake.listener!!.onPlayerError(TestError(3001))
        delay(1600)
        assertEquals(0, progressive)
        assertEquals(1, sources)
        assertEquals(1, trace.count { it == "RECOVER session=1 action=Retry" })
        controller.stop()
    }

    @Test fun switchingOrStoppingCancelsPendingRecoveryAndOldPlayerCannotPrepare() = runBlocking {
        val fake = FakePlayer()
        val trace = mutableListOf<String>()
        val controller = PlaybackController(this, { fake.player }, { trace += it }, { MediaItem.EMPTY })
        controller.play(request)
        delay(450)
        fake.listener!!.onPlayerError(TestError(1002))
        controller.play(request.copy(url = "https://example.org/second"))
        delay(450)
        assertEquals(0, trace.count { it.startsWith("RECOVER") })
        assertEquals(1, trace.count { it.startsWith("PREPARE session=2") })
        fake.listener!!.onPlayerError(TestError(1002))
        controller.stop()
        delay(450)
        assertEquals(0, trace.count { it.startsWith("RECOVER") })
    }

    @Test fun bothRecoveriesWaitForEveryOldConnectionToClose() = runBlocking {
        for (code in listOf(1002, 3002)) {
            val fake = FakePlayer()
            val trace = mutableListOf<String>()
            val controller = PlaybackController(this, { fake.player }, { trace += it }, { MediaItem.EMPTY },
                progressiveItem = { MediaItem.EMPTY })
            controller.play(request.copy(url = "https://example.org/live.m3u8"))
            delay(450)
            val connections = PlaybackController::class.java.getDeclaredField("connections").let {
                it.isAccessible = true
                it.get(controller) as AtomicInteger
            }
            connections.set(2)
            fake.calls.clear()
            fake.listener!!.onPlayerError(TestError(code))
            delay(450)
            assertEquals(0, fake.calls.count { it == "prepare" })
            connections.decrementAndGet()
            delay(50)
            assertEquals(0, fake.calls.count { it == "prepare" })
            connections.decrementAndGet()
            delay(50)
            assertEquals(1, fake.calls.count { it == "prepare" })
            assertEquals(1, trace.count { it.startsWith("RECOVER session=1") })
            controller.stop()
        }
    }

    @Test fun sourceCreationAndPreparationExceptionsAreRecordedInsteadOfEscaping() = runBlocking {
        for (stage in listOf("player", "item", "source", "prepare", "recovery", "recoveryPrepare", "linkage")) {
            val fake = FakePlayer()
            val records = mutableListOf<String>()
            val failure = if (stage == "linkage") NoClassDefFoundError("DashMediaSource\$Factory") else
                IllegalStateException("fixture", ClassNotFoundException("DashMediaSource\$Factory"))
            val controller = PlaybackController(this,
                { if (stage == "player") throw failure else fake.player }, {},
                { if (stage == "item" || stage == "linkage") throw failure else MediaItem.EMPTY },
                setStream = { _, _, _ -> if (stage == "source") throw failure },
                progressiveItem = { if (stage == "recovery") throw failure else MediaItem.EMPTY },
                recordPlayback = { failed, record -> if (failed) records += record },
                preparationError = { TestError(PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK, it) })
            if (stage == "prepare") fake.prepareFailure = failure
            controller.play(request.copy(url = "https://example.org/live.m3u8"))
            delay(450)
            if (stage == "recovery" || stage == "recoveryPrepare") {
                if (stage == "recoveryPrepare") fake.prepareFailure = failure
                fake.listener!!.onPlayerError(TestError(3002))
                delay(450)
            }
            assertEquals(PlaybackStatus.Failed("ERROR_CODE_FAILED_RUNTIME_CHECK"), controller.status.value)
            assertEquals(true, records.last().contains(if (stage == "linkage") "NoClassDefFoundError" else "ClassNotFoundException"))
            assertEquals(true, records.last().contains("DashMediaSource"))
            controller.stop()
        }
    }

    @Test fun cancellingSourcePreparationIsNotReportedAsAPlaybackFailure() = runBlocking {
        val fake = FakePlayer()
        val records = mutableListOf<String>()
        val controller = PlaybackController(this, { fake.player }, {},
            { throw kotlinx.coroutines.CancellationException("fixture") },
            recordPlayback = { _, record -> records += record },
            preparationError = { TestError(PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK, it) })
        controller.play(request)
        delay(450)
        assertEquals(emptyList<String>(), records)
        assertEquals(PlaybackStatus.Connecting, controller.status.value)
        controller.stop()
    }

    private class TestError(code: Int, cause: Throwable? = null) : PlaybackException("fixture", cause, code, Bundle.EMPTY, 0)

    private class FakePlayer {
        val calls = mutableListOf<String>()
        var parameters = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
        var listener: Player.Listener? = null
        var state = Player.STATE_IDLE
        var prepareFailure: Throwable? = null
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
                "addListener" -> { listener = args!![0] as Player.Listener; null }
                "getPlaybackState" -> state
                "prepare" -> { calls += method.name; prepareFailure?.let { throw it }; null }
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
