package dev.firetvlite.iptv.reporting

import com.google.gson.JsonParser
import dev.firetvlite.iptv.CrashReportStore
import dev.firetvlite.iptv.ProcessExitRecord
import dev.firetvlite.iptv.playback.StallDetector
import dev.firetvlite.iptv.playback.StallSample
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal class MemoryReportDao : ReportDao() {
    val rows = linkedMapOf<String, QueuedReport>()
    private val recent = mutableMapOf<String, Long>()
    override fun pendingStall(channel: String, summary: String, build: Int, occurredAt: String) = rows.values.lastOrNull {
        it.kind == "stall" && it.channel == channel && it.summary == summary && it.build == build && it.occurredAt == occurredAt
    }
    override fun updateDetail(id: String, detail: String) { rows[id]?.let { rows[id] = it.copy(detail = detail) } }
    override fun find(id: String) = rows[id]
    override fun duplicate(fingerprint: String, since: Long, until: Long) = recent[fingerprint]?.let { it in since..until } == true
    override fun remember(fingerprint: ReportFingerprint) { recent[fingerprint.fingerprint] = fingerprint.createdAt }
    override fun expire(before: Long) { recent.entries.removeAll { it.value < before } }
    override fun insert(report: QueuedReport): Long {
        if (report.id in rows) return -1
        rows[report.id] = report
        return rows.size.toLong()
    }
    override fun trim(maximum: Int) {
        val keep = rows.values.sortedByDescending { it.createdAt }.take(maximum).map { it.id }.toSet()
        rows.keys.retainAll(keep)
    }
    override fun pending(limit: Int) = rows.values.sortedBy { it.createdAt }.take(limit)
    override fun remove(ids: List<String>) { ids.forEach { rows.remove(it) } }
    override fun count() = rows.size
}

private class MemoryPreferences : ReportPreferences {
    override val install = ReportText.installId()
    override var enabled = true
    override var lastSent = 0L
    override var lastError = ""
}

class ErrorReportingTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun exitIsDurableBeforeSeenMarkerAndRecoversWithoutTheOriginalProcess() {
        val directory = File(folder.root, "exit-handoff")
        val handoff = CrashReportHandoff(directory)
        val marker = File(folder.root, "process-exits-seen.txt")
        val sink = object : ReportSink {
            override fun enqueue(kind: String, channel: String, summary: String, detail: String) = Unit
            override fun processExit(occurredAt: Long, summary: String, detail: String): Boolean {
                val captured = handoff.processExit(occurredAt, summary, detail)
                assertFalse(marker.exists())
                assertTrue(directory.listFiles().orEmpty().any { it.extension == "json" })
                return captured
            }
        }
        val file = File(folder.root, "exits.txt")
        val exits = listOf(ProcessExitRecord(3000, "ANR", 0, "fixture", 0, 0))
        CrashReportStore(file, sink).processExits(exits)
        assertEquals("3000", marker.readText())
        CrashReportStore(file, CrashReportHandoff(directory)).processExits(exits)
        val recovered = MemoryReportDao()
        CrashReportHandoff(directory).drain(recovered)
        assertEquals("exit", recovered.pending(1).single().kind)
        assertEquals(Instant.ofEpochMilli(3000).toString(), recovered.pending(1).single().occurredAt)
        assertEquals(1, recovered.count())
    }
    private fun report(index: Int = 0, now: Long = 1_000_000) = ReportText.create("other", "Channel $index", "failure $index", "detail", now, build = 101)

    @Test fun pendingStallKeepsFinalDetectorDurationAndStableIdentityBeyondTheDedupeWindow() {
        val detector = StallDetector()
        val sample = StallSample(1000, 1_000_000, "TV", true, true, false, 0, 0, 0, 5000)
        assertNull(detector.sample(sample))
        val initial = detector.sample(sample.copy(nowMs = 4000, wallMs = 1_003_000))!!
        val final = detector.sample(sample.copy(nowMs = 9000, wallMs = 1_008_000, active = false))!!
        val queue = MemoryReportDao()
        val first = ReportText.create("stall", initial.channel, "${initial.kind} · ${initial.evidence.substringBefore(';')}", initial.line())
            .copy(occurredAt = Instant.ofEpochMilli(initial.time).toString())
        assertTrue(queue.enqueue(first))
        val updated = first.copy(id = "updated-id", detail = final.line(), createdAt = first.createdAt + 8000)
        assertTrue(queue.enqueue(updated))
        assertEquals(first.copy(detail = final.line()), queue.pending(1).single())
        assertTrue(queue.enqueue(updated.copy(createdAt = first.createdAt + 120_000, detail = "final 120s")))
        assertEquals(first.copy(detail = "final 120s"), queue.pending(1).single())
        assertFalse(queue.enqueue(updated.copy(detail = "final 120s")))
        queue.acknowledge(queue.pending(1))
        assertEquals(0, queue.count())
        assertTrue(queue.enqueue(updated.copy(id = "after-upload")))
        assertEquals("after-upload", queue.pending(1).single().id)
        assertTrue(queue.enqueue(updated.copy(id = "different-build", build = first.build + 1)))
        assertTrue(queue.enqueue(updated.copy(id = "different-channel", channel = "Other")))
        assertEquals(3, queue.count())
    }

    @Test fun separateStallsWithTheSameSummaryRemainSeparateWhileEachDurationUpdates() {
        val detector = StallDetector()
        val sample = StallSample(1000, 1_000_000, "TV", true, true, false, 0, 0, 0, 5000)
        val queue = MemoryReportDao()
        fun enqueue(stall: dev.firetvlite.iptv.playback.PlaybackStall) = queue.enqueue(
            ReportText.create("stall", stall.channel, "${stall.kind} · ${stall.evidence.substringBefore(';')}", stall.line())
                .copy(occurredAt = Instant.ofEpochMilli(stall.time).toString()))
        assertEquals(0, queue.count())
        assertNull(detector.sample(sample))
        val first = detector.sample(sample.copy(nowMs = 4000, wallMs = 1_003_000))!!
        assertTrue(enqueue(first))
        val firstFinal = detector.sample(sample.copy(nowMs = 9000, wallMs = 1_008_000, active = false))!!
        assertTrue(enqueue(firstFinal))
        assertNull(detector.sample(sample.copy(nowMs = 10_000, wallMs = 1_009_000)))
        val second = detector.sample(sample.copy(nowMs = 13_000, wallMs = 1_012_000))!!
        assertTrue(enqueue(second))
        val secondFinal = detector.sample(sample.copy(nowMs = 17_000, wallMs = 1_016_000, active = false))!!
        assertTrue(enqueue(secondFinal))
        assertFalse(enqueue(secondFinal))
        val pending = queue.pending(50)
        assertEquals(2, pending.size)
        assertEquals(pending[0].summary, pending[1].summary)
        assertNotEquals(pending[0].occurredAt, pending[1].occurredAt)
        assertEquals(listOf(firstFinal.line(), secondFinal.line()), pending.map { it.detail })
    }

    @Test fun stallUpdatedDuringHttpRequestSurvivesAcknowledgementWithANewWorkerId() = runBlocking {
        MockWebServer().use { server ->
            val release = CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    check(release.await(3, TimeUnit.SECONDS))
                    return MockResponse().setResponseCode(200)
                }
            }
            val initial = report().copy(kind = "stall", detail = "3s")
            val queue = MemoryReportDao().apply { enqueue(initial) }
            val uploader = ReportUploader(queue, MemoryPreferences(), { true }, endpoint = server.url("/").toString())
            val uploading = async(Dispatchers.Default) { uploader.upload() }
            try {
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertTrue(queue.enqueue(initial.copy(id = "final", detail = "8s")))
            } finally { release.countDown() }
            withTimeout(3000) { uploading.await() }
            val latest = queue.pending(1).single()
            assertEquals("8s", latest.detail)
            assertNotEquals(initial.id, latest.id)
            uploader.upload()
            val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            assertEquals("8s", body["reports"].asJsonArray.single().asJsonObject["detail"].asString)
            assertEquals(0, queue.count())
            queue.acknowledge(listOf(initial))
            assertEquals(0, queue.count())
        }
    }

    @Test fun queueCapsAt500DropsOldestAndDedupesEvenAfterSuccessfulRemoval() {
        val queue = MemoryReportDao()
        repeat(503) { assertTrue(queue.enqueue(report(it, 1_000_000L + it))) }
        assertEquals(500, queue.count())
        assertEquals("Channel 3", queue.pending(1).single().channel)
        val last = queue.pending(500).last()
        queue.remove(listOf(last.id))
        assertFalse(queue.enqueue(last.copy(id = "new-id", createdAt = last.createdAt + 59_999)))
        assertTrue(queue.enqueue(last.copy(id = "later-id", createdAt = last.createdAt + 60_001)))
        assertTrue(queue.enqueue(last.copy(id = "different-kind", kind = "health", createdAt = last.createdAt + 60_002)))
        assertEquals(500, queue.count())
    }

    @Test fun installIdentityIsRandomAndValidAndTimesAreIsoUtc() {
        val ids = (1..100).map { ReportText.installId() }
        assertEquals(100, ids.toSet().size)
        ids.forEach { assertTrue(it.matches(Regex("[A-Za-z0-9_-]{8,64}"))); assertFalse(it.contains('-')) }
        assertEquals(Instant.ofEpochMilli(1_000_000), Instant.parse(report().occurredAt))
    }

    @Test fun allContentIsRedactedBeforePersistenceAndPayloadRespectsLimits() {
        val input = "https://alice:secretpw@provider.test/live/alice/secretpw/123456789?token=privatetoken " +
            "token=privatetoken Authorization: Bearer very-private-bearer\n9876543210\nCookie: session=private-cookie\nBasic private-basic"
        val entry = ReportText.create("unexpected", input, input, input + "\n" + "🌍\"\\".repeat(16_000))
        val body = ReportBatch.json("install123", listOf(entry))
        listOf("https://", "/live/", "alice", "secretpw", "privatetoken", "very-private-bearer", "private-cookie", "private-basic", "9876543210", "123456789").forEach {
            assertFalse(it, body.contains(it))
        }
        assertTrue(body.contains("provider[.]test"))
        assertEquals("other", entry.kind)
        assertTrue(entry.channel.length <= 120)
        assertTrue(entry.summary.length <= 500)
        assertTrue(entry.detail.length <= 16_000)
        assertTrue(body.toByteArray().size < ReportBatch.MAX_BYTES)
    }

    @Test fun batchesAreBoundedByCountAndEncodedByteSize() {
        val entries = (0..100).map { report(it).copy(detail = "\\\"🌍".repeat(2000)) }
        val short = ReportBatch.next("install123", (0..100).map { report(it) })
        assertEquals(50, short.size)
        val batch = ReportBatch.next("install123", entries)
        assertTrue(batch.isNotEmpty() && batch.size < 50)
        assertTrue(ReportBatch.json("install123", batch).toByteArray().size < 60_000)
        assertEquals(emptyList<QueuedReport>(), ReportBatch.next("install123", emptyList()))
    }

    @Test fun successDeletesOnlySentBatchAndSendsContractHeaderAndFields() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true}"))
            val queue = MemoryReportDao().apply { repeat(51) { enqueue(report(it)) } }
            val prefs = MemoryPreferences()
            val uploader = ReportUploader(queue, prefs, { true }, endpoint = server.url("/v1/reports").toString())
            uploader.upload()
            assertEquals(1, queue.count())
            assertTrue(prefs.lastSent > 0)
            assertEquals("", prefs.lastError)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("firelite-iptv", request.getHeader("x-firelite-app"))
            val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals(101, body["build"].asInt)
            assertEquals(50, body["reports"].asJsonArray.size())
            assertEquals(setOf("id", "kind", "occurredAt", "channel", "summary", "detail"),
                body["reports"].asJsonArray[0].asJsonObject.keySet())
        }
    }

    @Test fun badRequestsSplitOnceAndDropOnlyStillRejectedParts() = runBlocking {
        for (code in listOf(400, 413)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(code))
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(MockResponse().setResponseCode(code))
            val queue = MemoryReportDao().apply { repeat(4) { enqueue(report(it)) } }
            val uploader = ReportUploader(queue, MemoryPreferences(), { true }, endpoint = server.url("/").toString())
            assertEquals(0L, uploader.upload().retryIn)
            assertEquals(0, queue.count())
            assertEquals(listOf(4, 2, 2), (1..3).map {
                JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject["reports"].asJsonArray.size()
            })
        }
    }

    @Test fun singleInvalidEntryIsDroppedWithoutRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400))
            val queue = MemoryReportDao().apply { enqueue(report()) }
            ReportUploader(queue, MemoryPreferences(), { true }, endpoint = server.url("/").toString()).upload()
            assertEquals(0, queue.count())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun throttlingServerFailuresAndIoKeepStableIdsAndBackOffUpToAnHour() = runBlocking {
        for (code in listOf(429, 500, 503, -1)) MockWebServer().use { server ->
            repeat(8) { server.enqueue(if (code == -1) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                else MockResponse().setResponseCode(code)) }
            val queue = MemoryReportDao().apply { enqueue(report()) }
            val id = queue.pending(1).single().id
            var now = 1_000_000L
            val uploader = ReportUploader(queue, MemoryPreferences(), { true }, endpoint = server.url("/").toString(), clock = { now })
            val first = uploader.upload()
            assertEquals(60_000L, first.retryIn)
            assertEquals(60_000L, uploader.upload().retryIn)
            repeat(7) {
                now += if (it == 0) first.retryIn else 3_600_000
                val outcome = uploader.upload()
                assertTrue(outcome.retryIn in 120_000L..3_600_000L)
            }
            assertEquals(id, queue.pending(1).single().id)
            assertEquals(1, queue.count())
        }
    }

    @Test fun startingPlaybackAndDisabledAutomaticSendingNeverOpenHttpButManualRespectsGuard() = runBlocking {
        MockWebServer().use { server ->
            val queue = MemoryReportDao().apply { enqueue(report()) }
            var allowed = false
            val prefs = MemoryPreferences()
            var status = ReportingStatus()
            val uploader = ReportUploader(queue, prefs, { allowed }, { status = it }, server.url("/").toString())
            assertTrue(uploader.upload().blocked)
            assertTrue(status.blocked)
            assertTrue(uploader.upload(manual = true).blocked)
            prefs.enabled = false
            allowed = true
            uploader.upload()
            assertEquals(0, server.requestCount)
            assertEquals(1, queue.count())
            server.enqueue(MockResponse().setResponseCode(200))
            uploader.upload(manual = true)
            assertEquals(0, queue.count())
        }
    }

    @Test fun crashHandoffSurvivesRecreationAndRetainsTheStableId() {
        val directory = File(folder.root, "handoff")
        val report = report().copy(kind = "crash", build = 42)
        CrashReportHandoff(directory).save(report)
        File(directory, "corrupt.json").writeText("not JSON")
        val queue = MemoryReportDao()
        CrashReportHandoff(directory).drain(queue)
        assertEquals(report, queue.pending(1).single())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        CrashReportHandoff(directory).save(report)
        CrashReportHandoff(directory).drain(queue)
        assertEquals(1, queue.count())
    }

    @Test fun uploadsCapturedBuildGroupsAfterHandoffRecoveryRatherThanTheRunningBuild() = runBlocking {
        val directory = File(folder.root, "build-handoff")
        val old = ReportText.create("other", "TV", "old", "detail", 1000, build = 41)
        CrashReportHandoff(directory).save(old)
        val queue = MemoryReportDao()
        CrashReportHandoff(directory).drain(queue)
        val current = ReportText.create("other", "TV", "current", "detail", 2000, build = 42)
        val oldAgain = ReportText.create("other", "TV", "old again", "detail", 3000, build = 41)
        queue.enqueue(current)
        queue.enqueue(oldAgain)
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setResponseCode(200)) }
            val uploader = ReportUploader(queue, MemoryPreferences(), { true }, endpoint = server.url("/").toString())
            uploader.upload()
            val first = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            assertEquals(41, first["build"].asInt)
            assertEquals(listOf(old.id, oldAgain.id), first["reports"].asJsonArray.map { it.asJsonObject["id"].asString })
            assertEquals(listOf(current), queue.pending(50))
            uploader.upload()
            val second = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            assertEquals(42, second["build"].asInt)
            assertEquals(current.id, second["reports"].asJsonArray.single().asJsonObject["id"].asString)
            assertEquals(0, queue.count())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun payloadRejectsMixedBuildEntries() {
        ReportBatch.json("install123", listOf(report().copy(build = 41), report(1).copy(build = 42)))
    }

    @Test fun startupCancelsAnUploadAlreadyInFlightAndKeepsTheEntry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val queue = MemoryReportDao().apply { enqueue(report()) }
            val allowed = AtomicBoolean(true)
            val uploader = ReportUploader(queue, MemoryPreferences(), allowed::get, endpoint = server.url("/").toString())
            val uploading = async(Dispatchers.Default) { uploader.upload() }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            allowed.set(false)
            assertTrue(withTimeout(3000) { uploading.await() }.blocked)
            assertEquals(1, queue.count())
        }
    }

    @Test fun debounceReschedulesOnlyThePendingDelay() = runBlocking {
        val delivered = Channel<Unit>(Channel.UNLIMITED)
        val debounce = ReportUploadDebounce(this, 20) { delivered.send(Unit) }
        assertTrue(delivered.tryReceive().isFailure)
        debounce.enqueue()
        debounce.enqueue()
        withTimeout(2000) { delivered.receive() }
        kotlinx.coroutines.delay(60)
        assertTrue(delivered.tryReceive().isFailure)
    }

    @Test fun enqueueDoesNotCancelActiveUploadAndPlaybackStartupStillCancelsItsHttpCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val failed = LinkedBlockingQueue<okhttp3.Call>()
            val client = ReportUploader.client().newBuilder().eventListener(object : okhttp3.EventListener() {
                override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) { failed.add(call) }
            }).build()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val allowed = AtomicBoolean(true)
                val queue = MemoryReportDao().apply { enqueue(report()) }
                val uploader = ReportUploader(queue, MemoryPreferences(), allowed::get,
                    endpoint = server.url("/").toString(), suppliedClient = client)
                val started = CompletableDeferred<Job>()
                val completed = CompletableDeferred<UploadOutcome>()
                val debounce = ReportUploadDebounce(scope, 20) {
                    started.complete(currentCoroutineContext().job)
                    completed.complete(uploader.upload())
                }
                debounce.enqueue()
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                val uploading = started.await()
                debounce.enqueue()
                assertTrue(uploading.isActive)
                allowed.set(false)
                assertTrue(withTimeout(3000) { completed.await() }.blocked)
                val cancelled = failed.poll(2, TimeUnit.SECONDS)
                assertNotNull(cancelled)
                assertTrue(cancelled!!.isCanceled())
                assertEquals(1, queue.count())
                assertEquals(1, server.requestCount)
            } finally { scope.cancel() }
        }
    }

    @Test fun cancellingUploaderCoroutineImmediatelyCancelsTheUnderlyingHttpCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val failed = LinkedBlockingQueue<okhttp3.Call>()
            val client = ReportUploader.client().newBuilder().eventListener(object : okhttp3.EventListener() {
                override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) { failed.add(call) }
            }).build()
            val queue = MemoryReportDao().apply { enqueue(report()) }
            val uploader = ReportUploader(queue, MemoryPreferences(), { true },
                endpoint = server.url("/").toString(), suppliedClient = client)
            val uploading = async(Dispatchers.Default) { uploader.upload() }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            uploading.cancel()
            withTimeout(3000) { uploading.join() }
            val cancelled = failed.poll(2, TimeUnit.SECONDS)
            assertNotNull(cancelled)
            assertTrue(cancelled!!.isCanceled())
            assertTrue(uploading.isCancelled)
            assertEquals(1, queue.count())
        }
    }

    @Test fun liveWorkerSelftest() = runBlocking {
        assumeTrue(System.getenv("FIRELITE_REPORT_SELFTEST") == "1")
        val queue = MemoryReportDao()
        val report = ReportText.create("other", "", "selftest", "FireLite uploader selftest")
        queue.enqueue(report)
        val prefs = MemoryPreferences()
        val result = ReportUploader(queue, prefs, { true }).upload(manual = true)
        assertEquals(prefs.lastError, 0L, result.retryIn)
        assertEquals(0, queue.count())
        assertTrue(prefs.lastSent > 0)
        println("Live report selftest: HTTP 200 · id=${report.id}")
    }
}
