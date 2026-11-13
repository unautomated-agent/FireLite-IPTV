package dev.firetvlite.iptv.playback

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.Timeline
import android.os.Bundle
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackControllerTest {
    private val request = StreamRequest("https://example.org/live", "FireLite")

    @Test fun selectedPlaybackCanRestartAfterRunningOrCompletedHealthChecks(): Unit = runBlocking {
        for (complete in listOf(false, true)) {
            val fake = FakePlayer()
            val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY },
                evictConnections = {}, healthProbe = { _, _, _ -> error("Cancelled before probing") })
            try {
                assertEquals(false, controller.hasActiveStream)
                controller.play(request)
                delay(450)
                assertEquals(true, controller.hasActiveStream)
                controller.startHealthCheck("Wszystkie", if (complete) 0 else 1) { request }
                if (complete) withTimeout(1000) { while (controller.healthCheck.value.running) delay(10) }
                assertEquals(false, controller.hasActiveStream)
                if (!controller.hasActiveStream) controller.play(request)
                delay(450)
                assertEquals(true, controller.hasActiveStream)
                assertEquals(false, controller.healthCheck.value.running)
                assertEquals(2, fake.calls.count { it == "prepare" })
                if (!controller.hasActiveStream) controller.play(request)
                assertEquals(2, fake.calls.count { it == "prepare" })
            } finally { controller.stop() }
        }
    }

    @Test fun deadRegistryCountsOnlyStartupFailuresOnceAndClearsAtFirstRendering(): Unit = runBlocking {
        val fake = FakePlayer()
        val results = mutableListOf<Pair<StreamRequest, String?>>()
        val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY },
            channelResult = { channel, reason -> results += channel to reason }, startupTimeoutMs = 550)
        controller.play(request)
        delay(650)
        assertEquals(listOf("timeout"), results.map { it.second })
        fake.listener!!.onPlayerError(TestError(2001))
        assertEquals(1, results.size)
        fake.state = Player.STATE_READY
        fake.listener!!.onRenderedFirstFrame()
        assertNull(results.last().second)
        val count = results.size
        fake.state = Player.STATE_BUFFERING
        fake.listener!!.onPlaybackStateChanged(fake.state)
        delay(100)
        fake.listener!!.onPlayerError(TestError(2001))
        assertEquals(count, results.size)
        controller.stop()
        controller.play(request.copy(archive = true))
        fake.state = Player.STATE_READY
        fake.listener!!.onRenderedFirstFrame()
        assertEquals(request.channelKey, results.last().first.channelKey)
        assertNull(results.last().second)
        controller.stop()
    }

    @Test fun stoppedSwitchedAndAutomaticStartsNeverCountAsDead(): Unit = runBlocking {
        val fake = FakePlayer()
        val results = mutableListOf<String?>()
        val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY },
            channelResult = { _, reason -> results += reason }, startupTimeoutMs = 100)
        controller.play(request)
        controller.stop()
        fake.listener!!.onPlayerError(TestError(2001))
        controller.play(request)
        controller.play(StreamRequest("https://example.org/second", "TV"))
        controller.stop()
        delay(150)
        controller.play(StreamRequest("https://example.org/other", "TV", userInitiated = false))
        delay(150)
        fake.listener!!.onPlayerError(TestError(2001))
        controller.stop()
        assertEquals(emptyList<String?>(), results)
    }

    @Test fun preparationAndHttpStartupErrorsReportTheirReasons(): Unit = runBlocking {
        val fake = FakePlayer()
        val reasons = mutableListOf<String?>()
        val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY },
            channelResult = { _, reason -> reasons += reason },
            preparationError = { TestError(PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK, it) })
        controller.play(request)
        fake.listener!!.onPlayerError(TestError(2004, httpFailure(404)))
        assertEquals(listOf("HTTP 404"), reasons)
        controller.stop()
        fake.prepareFailure = IllegalStateException("fixture")
        controller.play(request)
        delay(450)
        assertEquals("ERROR_CODE_FAILED_RUNTIME_CHECK", reasons.last())
        controller.stop()
    }

    @Test fun cancelledProbeMustFinishBeforePlaybackCanPrepare(): Unit = runBlocking {
        val fake = FakePlayer()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val results = mutableListOf<String?>()
        val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY },
            evictConnections = {}, healthResult = { _, reason -> results += reason }, healthProbe = { _, _, _ ->
                entered.complete(Unit)
                release.await(3, TimeUnit.SECONDS)
                null
            })
        try {
            controller.startHealthCheck("Filmy", 2) { request }
            withTimeout(3000) { entered.await() }
            controller.play(request)
            delay(450)
            assertEquals(0, fake.calls.count { it == "prepare" })
            release.countDown()
            withTimeout(3000) { while (fake.calls.none { it == "prepare" }) delay(10) }
            assertTrue(results.isEmpty())
            assertEquals(false, controller.healthCheck.value.running)
        } finally { release.countDown(); controller.stop() }
    }

    @Test fun playbackCancelsProbeSocketBeforeOpeningItsOwnConnection(): Unit = runBlocking {
        HealthConnectionFixture().use { fixture ->
            val fake = FakePlayer()
            val results = mutableListOf<String?>()
            val client = OkHttpClient()
            val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY },
                setStream = { _, _, _ -> client.newCall(Request.Builder().url("${fixture.base}/good")
                    .header("Connection", "close").build()).execute().use { it.body!!.bytes() }; Unit },
                evictConnections = {}, healthResult = { _, reason -> results += reason })
            try {
                controller.startHealthCheck("Wszystkie", 1) { StreamRequest("${fixture.base}/slow", "TV") }
                withTimeout(3000) { while ("/slow" !in fixture.paths) delay(10) }
                controller.play(request)
                withTimeout(3000) { while (fake.calls.none { it == "prepare" }) delay(10) }
                assertEquals(listOf("/slow", "/good"), fixture.paths.toList())
                assertEquals(1, fixture.peak.get())
                assertTrue(results.isEmpty())
                assertEquals(0, controller.healthCheck.value.checked)
            } finally { controller.stop(); client.connectionPool.evictAll() }
        }
    }

    @Test fun healthStopsPlaybackAndSequentialResultsKeepScopeCountsAndPartialResultsOnBackground(): Unit = runBlocking {
        HealthConnectionFixture().use { fixture ->
            val fake = FakePlayer()
            val summaries = mutableListOf<String>()
            val results = mutableListOf<Pair<StreamRequest, String?>>()
            val client = OkHttpClient()
            var liveCall: okhttp3.Call? = null
            var liveResponse: okhttp3.Response? = null
            lateinit var connections: AtomicInteger
            val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY },
                setStream = { _, _, _ ->
                    liveCall = client.newCall(Request.Builder().url("${fixture.base}/hold").build())
                    liveResponse = liveCall!!.execute()
                    connections.incrementAndGet()
                }, evictConnections = {}, healthResult = { channel, reason -> results += channel to reason },
                healthSummary = { summaries += it })
            connections = PlaybackController::class.java.getDeclaredField("connections").let {
                it.isAccessible = true; it.get(controller) as AtomicInteger
            }
            fake.onStop = {
                val call = liveCall
                liveCall = null
                if (call != null) launch(Dispatchers.IO) {
                    call.cancel()
                    liveResponse?.close()
                    connections.decrementAndGet()
                }
            }
            try {
                controller.play(request)
                withTimeout(3000) { while (connections.get() == 0) delay(10) }
                val indexes = mutableListOf<Int>()
                controller.startHealthCheck("Filmy", 3) { index ->
                    indexes += index
                    StreamRequest("${fixture.base}/${if (index == 0) "bad" else "good"}", "TV", channelName = "Kanał $index")
                }
                withTimeout(5000) { while (controller.healthCheck.value.checked < 2) delay(10) }
                controller.suspendStream()
                withTimeout(3000) { while (!summaries.last().endsWith("stopped")) delay(10) }
                assertEquals(listOf(0, 1), indexes)
                assertEquals(listOf("HTTP 404", null), results.map { it.second })
                assertEquals(3, controller.healthCheck.value.total)
                assertEquals(2, controller.healthCheck.value.checked)
                assertEquals(1, controller.healthCheck.value.dead)
                assertEquals(1, controller.healthCheck.value.healthy)
                assertTrue(summaries.last().contains("Filmy · 2/3 · martwe 1 · stopped"))
                assertEquals(listOf("/hold", "/bad", "/good"), fixture.paths.toList())
                assertEquals(1, fixture.peak.get())
                assertEquals(1, fake.calls.count { it == "prepare" })
            } finally { controller.stop(); client.connectionPool.evictAll() }
        }
    }

    @Test fun emptyAndMissingChannelChecksCompleteOrFailWithoutOpeningConnections(): Unit = runBlocking {
        val summaries = mutableListOf<String>()
        val controller = PlaybackController(this, { error("No playback") }, {}, { MediaItem.EMPTY },
            healthProbe = { _, _, _ -> error("No probe") }, healthSummary = { summaries += it })
        controller.startHealthCheck("Wszystkie", 0) { error("No load") }
        withTimeout(1000) { while (controller.healthCheck.value.running) delay(10) }
        assertEquals(true, controller.healthCheck.value.complete)
        assertTrue(summaries.last().contains("0/0 · martwe 0 · complete"))
        controller.startHealthCheck("Usunięta grupa", 1) { null }
        withTimeout(1000) { while (controller.healthCheck.value.running) delay(10) }
        assertEquals(true, controller.healthCheck.value.error)
        assertEquals(false, controller.healthCheck.value.complete)
        controller.stop()
    }

    @Test fun finalNetworkErrorUsesRecordedHttpFailureAndNewChannelClearsIt() = runBlocking {
        val fake = FakePlayer()
        val records = mutableListOf<String>()
        val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY },
            recordPlayback = { failed, text -> if (failed) records += text })
        controller.play(request)
        delay(450)
        val evidence = HttpRequestEvidence(404, "application/vnd.apple.mpegurl", "media playlist",
            "…/[redacted].m3u8", 1, 2, setOf("User-Agent", "Referer"), "original", "final")
        PlaybackController::class.java.getDeclaredField("lastHttpFailure").apply { isAccessible = true }
            .set(controller, 1L to evidence)
        repeat(4) { fake.listener!!.onPlayerError(TestError(2001)) }
        assertEquals(PlaybackStatus.Failed("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", 404), controller.status.value)
        assertEquals(true, records.last().contains("HTTP: HTTP=404"))
        assertEquals(true, records.last().contains("Screen: Kanał niedostępny u dostawcy (HTTP 404)"))
        assertEquals(true, records.last().contains("Request: media playlist"))
        assertEquals(1, fake.calls.count { it == "prepare" })
        controller.play(request.copy(url = "https://example.org/other"))
        delay(450)
        repeat(4) { fake.listener!!.onPlayerError(TestError(2001)) }
        assertEquals(PlaybackStatus.Failed("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"), controller.status.value)
        controller.stop()
    }

    @Test fun finalTypedHttpErrorPropagatesToScreenStateWithoutAdditionalStreamSources() = runBlocking {
        val fake = FakePlayer()
        val records = mutableListOf<String>()
        val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY },
            recordPlayback = { failed, text -> if (failed) records += text })
        controller.play(request)
        delay(450)
        repeat(4) { fake.listener!!.onPlayerError(TestError(2004, httpFailure(410))) }
        assertEquals(PlaybackStatus.Failed("ERROR_CODE_IO_BAD_HTTP_STATUS", 410), controller.status.value)
        assertEquals(true, records.last().contains("HTTP: HTTP=410"))
        assertEquals(true, records.last().contains("Screen: Kanał niedostępny u dostawcy (HTTP 410)"))
        assertEquals(1, fake.calls.count { it == "prepare" })
        controller.stop()
    }

    @Test fun explicitSelectionOfTheActiveArchiveSeeksToZeroWithoutReopening() = runBlocking {
        val fake = FakePlayer()
        val trace = mutableListOf<String>()
        var creations = 0
        val controller = PlaybackController(this, { creations++; fake.player }, { trace += it }, { MediaItem.EMPTY })
        val archive = request.copy(url = "https://example.org/archive/123", archive = true)
        controller.playArchiveFromStart(archive)
        delay(450)
        fake.position = 75_000L
        fake.seekPosition = 75_000L
        val streamCalls = fake.calls.filter { it in listOf("stop", "clearMediaItems", "setMediaItem", "prepare") }

        controller.playArchiveFromStart(archive)

        assertEquals(0L, fake.seekPosition)
        assertEquals(1, fake.calls.count { it == "seekTo" })
        assertEquals(streamCalls, fake.calls.filter { it in listOf("stop", "clearMediaItems", "setMediaItem", "prepare") })
        assertEquals(1, creations)
        assertEquals(1, trace.count { it.startsWith("PREPARE") })
        controller.stop()
    }

    @Test fun archiveReselectionAfterAutomaticResumeIgnoresMetadataAndRestoresUserIntent(): Unit = runBlocking {
        for (preparing in listOf(false, true)) {
            val fake = FakePlayer()
            val results = mutableListOf<Pair<StreamRequest, String?>>()
            val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY },
                channelResult = { stream, reason -> results += stream to reason })
            val archive = request.copy(url = "https://example.org/archive/123", archive = true)
            try {
                controller.playArchiveFromStart(archive)
                delay(450)
                controller.suspendStream()
                controller.resumeStream()
                if (!preparing) delay(450)
                fake.seekPosition = 75_000
                controller.playArchiveFromStart(archive.copy(channelKey = DeadChannels.key("new metadata"), channelName = "Renamed"))
                assertEquals(if (preparing) 0 else 1, fake.calls.count { it == "seekTo" })
                if (!preparing) assertEquals(0L, fake.seekPosition)
                if (preparing) delay(450)
                assertEquals(2, fake.calls.count { it == "prepare" })
                fake.listener!!.onPlayerError(TestError(2004, httpFailure(404)))
                assertEquals("HTTP 404", results.single().second)
                assertEquals(true, results.single().first.userInitiated)
                assertEquals(archive.channelKey, results.single().first.channelKey)
                fake.listener!!.onPlayerError(TestError(2004, httpFailure(404)))
                assertEquals(1, results.size)
            } finally { controller.stop() }
        }
    }

    @Test fun ordinaryDuplicateArchiveAndLiveRequestsRemainNoOps() = runBlocking {
        val fake = FakePlayer()
        val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY })
        for (archive in listOf(false, true)) {
            val stream = request.copy(archive = archive)
            controller.play(stream)
            delay(450)
            val calls = fake.calls.toList()
            controller.play(stream)
            if (!archive) controller.playArchiveFromStart(stream)
            assertEquals(calls, fake.calls)
            controller.stop()
        }
    }

    @Test fun matchingUrlWithoutAnIdenticalArchiveRequestDoesNotRestart() = runBlocking {
        val fake = FakePlayer()
        val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY })
        val archive = request.copy(archive = true)
        controller.playArchiveFromStart(archive)
        delay(450)
        val calls = fake.calls.toList()
        controller.playArchiveFromStart(archive.copy(userAgent = "Other"))
        controller.playArchiveFromStart(archive.copy(archive = false))
        assertEquals(calls, fake.calls)
        controller.stop()
    }

    @Test fun duplicateArchiveSelectionWhilePreparingDoesNotSeekOrScheduleAnotherPrepare() = runBlocking {
        val fake = FakePlayer()
        val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY })
        val archive = request.copy(archive = true)
        controller.playArchiveFromStart(archive)
        controller.playArchiveFromStart(archive)
        assertEquals(0, fake.calls.count { it == "seekTo" || it == "prepare" })
        delay(450)
        assertEquals(1, fake.calls.count { it == "prepare" })
        controller.stop()
    }

    @Test fun differentArchiveSelectionsStillStopBeforeOneNewPrepare() = runBlocking {
        val fake = FakePlayer()
        val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY })
        controller.playArchiveFromStart(request.copy(url = "https://example.org/archive/first", archive = true))
        delay(450)
        fake.calls.clear()
        controller.playArchiveFromStart(request.copy(url = "https://example.org/archive/second", archive = true))
        assertEquals(1, fake.calls.count { it == "stop" })
        assertEquals(0, fake.calls.count { it == "prepare" || it == "seekTo" })
        delay(450)
        assertEquals(1, fake.calls.count { it == "prepare" })
        assertEquals(true, fake.calls.indexOf("stop") < fake.calls.indexOf("prepare"))
        controller.stop()
    }

    @Test fun startupAndPreviousChannelRequestsReuseThePlayerAndAlwaysStopBeforePrepare() = runBlocking {
        val fake = FakePlayer()
        val trace = mutableListOf<String>()
        var creations = 0
        val controller = PlaybackController(this, { creations++; fake.player }, { trace += it }, { MediaItem.EMPTY })
        val previous = PreviousChannel<String> { it }
        for (index in 0..3) {
            val url = if (index == 0) request.url else if (index == 1) "https://example.org/second" else previous.previous!!
            fake.calls.clear()
            controller.play(request.copy(url = url))
            previous.watched(url)
            assertEquals(0, fake.calls.count { it == "prepare" })
            delay(450)
            assertEquals(1, fake.calls.count { it == "prepare" })
            assertEquals(true, fake.calls.indexOf("stop") < fake.calls.indexOf("prepare"))
        }
        assertEquals(1, creations)
        assertEquals(4, trace.count { it.startsWith("PREPARE") })
        val calls = fake.calls.filter { it in listOf("stop", "setMediaItem", "prepare", "clearMediaItems") }
        controller.setPreview(true)
        controller.setPreview(false)
        assertEquals(calls, fake.calls.filter { it in listOf("stop", "setMediaItem", "prepare", "clearMediaItems") })
        controller.stop()
    }

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
        fake.listener!!.onRenderedFirstFrame()
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

    @Test fun connectedProviderWaitDoesNotRestartAtTheNormalDeadlineAndChannelSwitchCancelsIt(): Unit = runBlocking {
        val fake = FakePlayer()
        val records = mutableListOf<String>()
        val trace = mutableListOf<String>()
        val controller = PlaybackController(this, { fake.player }, { trace += it }, { MediaItem.EMPTY },
            recordPlayback = { failed, text -> if (failed) records += text }, startupTimeoutMs = 650)
        var time = 0L
        val oldSession = HlsSession(clock = { time })
        val id = oldSession.begin("https://provider/start.m3u8", androidx.media3.common.C.DATA_TYPE_MANIFEST)
        oldSession.transport(id, TransportSnapshot(TransportPhase.Waiting, 0, sentMs = 0, protocol = "http/1.1"))
        controller.play(request)
        PlaybackController::class.java.getDeclaredField("hlsSession").apply { isAccessible = true }.set(controller, oldSession)
        time = 6_000
        delay(900)
        assertEquals(true, controller.providerWaiting.value)
        assertEquals(false, controller.startupDelayed.value)
        assertEquals(emptyList<String>(), records)
        assertEquals(1, fake.calls.count { it == "stop" })
        assertEquals(1, fake.calls.count { it == "prepare" })
        assertEquals(0, trace.count { it.startsWith("RECOVER") })

        controller.play(request.copy(url = "https://provider/new.m3u8"))
        assertEquals(false, controller.providerWaiting.value)
        assertEquals(false, controller.startupDelayed.value)
        oldSession.transport(id, TransportSnapshot(TransportPhase.Body, time, status = 404, headersMs = time))
        delay(450)
        assertEquals(2, fake.calls.count { it == "prepare" })
        assertEquals(emptyList<String>(), records)
        assertEquals(false, controller.providerWaiting.value)
        controller.stop()
        delay(300)
        assertEquals(emptyList<String>(), records)
    }

    @Test fun providerResponseAndFailureClearTheWaitingMessage(): Unit = runBlocking {
        for (phase in listOf(TransportPhase.Body, TransportPhase.Failed)) {
            val fake = FakePlayer()
            val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY }, startupTimeoutMs = 5_000)
            var time = 0L
            val session = HlsSession(clock = { time })
            val id = session.begin("https://provider/start.m3u8", androidx.media3.common.C.DATA_TYPE_MANIFEST)
            session.transport(id, TransportSnapshot(TransportPhase.Waiting, 0, sentMs = 0, protocol = "http/1.1"))
            controller.play(request)
            PlaybackController::class.java.getDeclaredField("hlsSession").apply { isAccessible = true }.set(controller, session)
            time = 5_000
            delay(300)
            assertEquals(true, controller.providerWaiting.value)
            session.transport(id, TransportSnapshot(phase, time))
            delay(300)
            assertEquals(false, controller.providerWaiting.value)
            controller.stop()
            assertEquals(false, controller.providerWaiting.value)
        }
    }

    @Test fun startupDeadlineRecordsIdleOnceWithoutStoppingOrReopeningTheStream(): Unit = runBlocking {
        val fake = FakePlayer()
        val records = mutableListOf<String>()
        val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY },
            recordPlayback = { failed, text -> if (failed) records += text }, startupTimeoutMs = 60)
        val channel = request.copy(url = "https://provider/play/hls-custom-c123-private-code.m3u8", channelName = "TVP Seriale")
        controller.play(channel)
        delay(100)
        assertEquals(true, controller.startupDelayed.value)
        assertEquals(1, records.size)
        for (value in listOf("START_TIMEOUT", "TVP Seriale", "IDLE", "buffered=2500", "HLS", "HTTP: not available", "__path_hidden__")) {
            assertEquals(records.single(), true, records.single().contains(value))
        }
        assertEquals(false, records.single().contains("private-code"))
        assertEquals(PlaybackStatus.Connecting, controller.status.value)
        controller.play(channel)
        delay(450)
        assertEquals(1, records.size)
        assertEquals(1, fake.calls.count { it == "prepare" })
        assertEquals(1, fake.calls.count { it == "stop" })
        fake.state = Player.STATE_READY
        fake.listener!!.onRenderedFirstFrame()
        assertEquals(false, controller.startupDelayed.value)
        assertEquals(1, records.size)
        controller.stop()
    }

    @Test fun startupDeadlineIdentifiesPreviousConnectionsWithoutOpeningTheRequestedStream(): Unit = runBlocking {
        val fake = FakePlayer()
        val records = mutableListOf<String>()
        val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY },
            recordPlayback = { failed, text -> if (failed) records += text }, startupTimeoutMs = 650)
        val connections = PlaybackController::class.java.getDeclaredField("connections").let {
            it.isAccessible = true
            it.get(controller) as AtomicInteger
        }
        connections.set(1)
        controller.play(request.copy(channelName = "requested channel"))
        delay(750)
        assertEquals(1, records.size)
        for (value in listOf("START_TIMEOUT", "phase=WAITING_PREVIOUS_CONNECTION", "openHTTP=1",
                "requested stream not prepared", "PREPARING_BUFFERING=0ms")) {
            assertEquals(records.single(), true, records.single().contains(value))
        }
        assertEquals(0, fake.calls.count { it == "prepare" })
        assertEquals(1, fake.calls.count { it == "stop" })
        connections.decrementAndGet()
        delay(100)
        assertEquals(0, fake.calls.count { it == "prepare" })
        delay(400)
        assertEquals(1, fake.calls.count { it == "prepare" })
        assertEquals(1, records.size)
        controller.stop()
    }

    @Test fun sessionEvictsOnlyAfterAllOldSourcesCloseThenWaitsTheFullGraceBeforePrepare() = runBlocking {
        val fake = FakePlayer()
        val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY },
            evictConnections = { fake.calls += "evict" })
        val connections = PlaybackController::class.java.getDeclaredField("connections").let {
            it.isAccessible = true
            it.get(controller) as AtomicInteger
        }
        connections.set(2)
        controller.play(request.copy(archive = true))
        delay(50)
        assertEquals(0, fake.calls.count { it == "evict" || it == "prepare" })
        connections.decrementAndGet()
        delay(50)
        assertEquals(0, fake.calls.count { it == "evict" || it == "prepare" })
        connections.decrementAndGet()
        delay(100)
        assertEquals(1, fake.calls.count { it == "evict" })
        assertEquals(0, fake.calls.count { it == "prepare" })
        delay(400)
        assertEquals(1, fake.calls.count { it == "prepare" })
        assertEquals(true, fake.calls.indexOf("stop") < fake.calls.indexOf("evict"))
        assertEquals(true, fake.calls.indexOf("evict") < fake.calls.indexOf("prepare"))
        val before = fake.calls.toList()
        controller.play(request.copy(archive = true))
        assertEquals(before, fake.calls)
        controller.stop()
    }

    @Test fun evictionRunsOffControllerThreadAndPrepareWaitsForCompletionAndGrace(): Unit = runBlocking {
        val fake = FakePlayer()
        val mainThread = Thread.currentThread()
        val evictionThread = AtomicReference<Thread>()
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY },
            setStream = { _, _, _ -> assertSame(mainThread, Thread.currentThread()) },
            evictConnections = {
                evictionThread.set(Thread.currentThread())
                started.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
            })
        try {
            controller.play(request)
            withTimeout(2000) { started.await() }
            assertNotSame(mainThread, evictionThread.get())
            assertEquals(1, fake.calls.count { it == "stop" })
            delay(450)
            assertEquals(0, fake.calls.count { it == "prepare" })
            release.countDown()
            delay(100)
            assertEquals(0, fake.calls.count { it == "prepare" })
            delay(400)
            assertEquals(1, fake.calls.count { it == "prepare" })
        } finally {
            release.countDown()
            controller.stop()
        }
    }

    @Test fun rapidSwitchWaitsForCancelledEvictionBeforePreparingOnlyTheLatestSession(): Unit = runBlocking {
        val fake = FakePlayer()
        val firstStarted = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val evictions = AtomicInteger()
        val prepared = mutableListOf<MediaItem>()
        val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY },
            setStream = { _, item, _ -> prepared += item },
            evictConnections = {
                if (evictions.incrementAndGet() == 1) {
                    firstStarted.complete(Unit)
                    check(release.await(5, TimeUnit.SECONDS))
                }
            })
        try {
            controller.play(request)
            withTimeout(2000) { firstStarted.await() }
            controller.play(request.copy(url = "https://example.org/latest"))
            delay(450)
            assertEquals(1, evictions.get())
            assertEquals(0, fake.calls.count { it == "prepare" })
            release.countDown()
            delay(100)
            assertEquals(2, evictions.get())
            assertEquals(0, fake.calls.count { it == "prepare" })
            delay(400)
            assertEquals(1, fake.calls.count { it == "prepare" })
            assertEquals(1, prepared.size)
        } finally {
            release.countDown()
            controller.stop()
        }
    }

    @Test fun cancelledSessionCannotEvictAndEvictionFailureIsRecordedWithoutPreparing() = runBlocking {
        val fake = FakePlayer()
        var evictions = 0
        val records = mutableListOf<String>()
        val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY },
            recordPlayback = { failed, record -> if (failed) records += record },
            preparationError = { TestError(PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK, it) },
            evictConnections = { evictions++; throw IllegalStateException("fixture eviction failed") })
        controller.play(request)
        controller.stop()
        delay(50)
        assertEquals(0, evictions)
        controller.play(request)
        delay(100)
        assertEquals(1, evictions)
        assertEquals(0, fake.calls.count { it == "prepare" })
        assertEquals(1, records.size)
        assertEquals(true, records.single().contains("fixture eviction failed"))
        controller.stop()
    }

    @Test fun startupDeadlineAfterPreparationAttributesTheRequestedSession(): Unit = runBlocking {
        val fake = FakePlayer()
        val records = mutableListOf<String>()
        val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY },
            recordPlayback = { failed, text -> if (failed) records += text }, startupTimeoutMs = 650)
        controller.play(request)
        delay(750)
        assertEquals(1, fake.calls.count { it == "prepare" })
        assertEquals(1, records.size)
        assertEquals(true, records.single().contains("phase=PREPARING_BUFFERING"))
        assertEquals(true, records.single().contains("Ownership: requested stream session"))
        controller.stop()
    }

    @Test fun bufferingAndReadyWithoutAFrameStillReportDetectedVideoFormat(): Unit = runBlocking {
        for (state in listOf(Player.STATE_BUFFERING, Player.STATE_READY)) {
            val fake = FakePlayer()
            val format = Format.Builder().setSampleMimeType("video/hevc").setCodecs("hvc1").setWidth(1920).setHeight(1080).build()
            fake.videoFormat = format
            val records = mutableListOf<String>()
            val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY },
                recordPlayback = { failed, text -> if (failed) records += text }, startupTimeoutMs = 60)
            controller.play(request)
            fake.state = state
            fake.listener!!.onPlaybackStateChanged(state)
            delay(100)
            assertEquals(1, records.size)
            for (value in listOf("video/hevc", "hvc1", "1920x1080")) {
                assertEquals(records.single(), true, records.single().contains(value))
            }
            controller.stop()
        }
    }

    @Test fun renderingStopAndChannelReplacementCancelOldStartupDeadlines(): Unit = runBlocking {
        val fake = FakePlayer()
        val records = mutableListOf<String>()
        val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY },
            recordPlayback = { failed, text -> if (failed) records += text }, startupTimeoutMs = 150)
        controller.play(request)
        fake.state = Player.STATE_READY
        fake.listener!!.onRenderedFirstFrame()
        delay(180)
        assertEquals(emptyList<String>(), records)
        controller.stop()
        controller.play(request)
        controller.stop()
        delay(180)
        assertEquals(emptyList<String>(), records)
        controller.play(request.copy(channelName = "old"))
        delay(70)
        controller.play(request.copy(url = "https://provider/next", channelName = "new"))
        delay(100)
        assertEquals(emptyList<String>(), records)
        delay(90)
        assertEquals(1, records.size)
        assertEquals(true, records.single().contains("Channel: new"))
        controller.stop()
        assertEquals(false, controller.startupDelayed.value)
    }

    @Test fun bufferingFirstFrameCannotHideTheDeadlineAndLateHttpFailureReplacesTheSnapshot(): Unit = runBlocking {
        val fake = FakePlayer()
        val records = mutableListOf<String>()
        val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY },
            recordPlayback = { failed, text -> if (failed) records += text }, startupTimeoutMs = 60)
        controller.play(request)
        fake.state = Player.STATE_BUFFERING
        fake.listener!!.onRenderedFirstFrame()
        delay(100)
        assertEquals(true, records.single().contains("START_TIMEOUT"))
        assertEquals(true, records.single().contains("HTTP: not available"))
        assertEquals(1, fake.calls.count { it == "stop" })
        fake.listener!!.onPlayerError(TestError(2004, httpFailure(404)))
        assertEquals(true, records.last().contains("HTTP: HTTP=404"))
        assertEquals(true, records.last().contains("Screen: Kanał niedostępny u dostawcy (HTTP 404)"))
        assertEquals(2, fake.calls.count { it == "stop" })
        assertEquals(0, fake.calls.count { it == "prepare" })
        controller.stop()
    }

    @Test fun readyWithoutRenderingCannotResetTheRecoveryBudgetForever(): Unit = runBlocking {
        val fake = FakePlayer()
        val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY })
        controller.play(request)
        repeat(4) {
            fake.state = Player.STATE_READY
            fake.listener!!.onPlaybackStateChanged(fake.state)
            fake.listener!!.onPlayerError(TestError(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
            delay(450)
        }
        assertEquals(PlaybackStatus.Failed("ERROR_CODE_BEHIND_LIVE_WINDOW"), controller.status.value)
        assertEquals(false, controller.startupDelayed.value)
        controller.stop()
    }

    @Test fun seekWatchRecordsOnceAndClearsOnProgressWithoutOpeningOrStopping(): Unit = runBlocking {
        val fake = FakePlayer()
        val records = mutableListOf<String>()
        val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY },
            recordPlayback = { failed, text -> if (failed) records += text }, startupTimeoutMs = 60)
        controller.play(request)
        fake.state = Player.STATE_READY
        fake.listener!!.onRenderedFirstFrame()
        controller.seekTo(5000)
        fake.state = Player.STATE_BUFFERING
        delay(180)
        assertEquals(1, records.size)
        assertEquals(true, records.single().contains("SEEK_TIMEOUT"))
        assertEquals(true, controller.startupDelayed.value)
        assertEquals(1, fake.calls.count { it == "stop" })
        assertEquals(0, fake.calls.count { it == "prepare" })
        fake.state = Player.STATE_READY
        fake.position += 1000
        delay(80)
        assertEquals(false, controller.startupDelayed.value)
        controller.stop()
    }

    @Test fun rebufferWatchHonorsPauseAndSeekGenerationsAndCancelsOnStop(): Unit = runBlocking {
        val fake = FakePlayer()
        val records = mutableListOf<String>()
        val controller = PlaybackController(this, { fake.player }, {}, { MediaItem.EMPTY },
            recordPlayback = { failed, text -> if (failed) records += text }, startupTimeoutMs = 80)
        controller.play(request)
        fake.state = Player.STATE_READY
        fake.listener!!.onRenderedFirstFrame()
        fake.state = Player.STATE_BUFFERING
        fake.listener!!.onPlaybackStateChanged(fake.state)
        fake.playing = false
        delay(120)
        assertEquals(0, records.size)
        fake.playing = true
        delay(180)
        assertEquals(true, records.single().contains("REBUFFER_TIMEOUT"))
        controller.seekToLiveEdge()
        delay(40)
        controller.seekTo(1000)
        delay(50)
        assertEquals(1, records.size)
        controller.stop()
        delay(120)
        assertEquals(1, records.size)
        assertEquals(false, controller.startupDelayed.value)
    }

    @Test fun seeksRejectUnsupportedAndClampToCurrentWindow(): Unit = runBlocking {
        val fake = FakePlayer()
        val controller = PlaybackController(this) { fake.player }
        assertEquals(false, controller.seekTo(1000))
        controller.play(request)
        controller.seekTo(-10)
        assertEquals(0L, fake.seekPosition)
        controller.seekTo(60_000)
        assertEquals(29_999L, fake.seekPosition)
        controller.stop()
    }

    private class FakePlayer {
        var onStop: () -> Unit = {}
        val calls = mutableListOf<String>()
        var parameters = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
        var listener: Player.Listener? = null
        var state = Player.STATE_IDLE
        var prepareFailure: Throwable? = null
        var tracks: Tracks = Tracks.EMPTY
        var videoFormat: Format? = null
        var position = 0L
        var seekPosition = 0L
        var playing = true
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
                "getCurrentTracks" -> tracks
                "getVideoFormat" -> videoFormat
                "getBufferedPosition" -> 2500L
                "getCurrentPosition" -> position
                "getCurrentTimeline" -> Timeline.EMPTY
                "getPlayWhenReady" -> playing
                "getDuration" -> 30_000L
                "isCurrentMediaItemSeekable" -> true
                "seekTo" -> { seekPosition = args!![0] as Long; calls += method.name; null }
                "prepare" -> { calls += method.name; prepareFailure?.let { throw it }; null }
                "stop" -> { calls += method.name; onStop(); null }
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
