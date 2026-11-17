package dev.firetvlite.iptv

import dev.firetvlite.iptv.reporting.ReportSink
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StartupRecorderTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun monotonicPhasesAreOffsetsAndDuplicateFramesFinishOnlyOnce() {
        var clock = 1_000L
        val changed = mutableListOf<LaunchRecord>()
        val timeline = LaunchTimeline(clock, { clock }, changed::add)
        for (phase in LaunchPhase.entries.drop(1)) { clock += 100; timeline.mark(phase) }
        val record = timeline.snapshot()
        assertEquals(600L, record.total)
        assertEquals(0L, record.phases["application"])
        assertEquals(400L, record.phases["resume_play"])
        assertEquals(500L, record.phases["prepare"])
        assertEquals("video", record.outcome)
        assertEquals("startup: total=600ms; launch=${record.id}", record.summary())
        timeline.mark(LaunchPhase.FirstFrame)
        timeline.finish("failed")
        assertEquals(6, changed.size)
        assertEquals(record, timeline.snapshot())
    }

    @Test fun noResumeAndFailedStartsNeverInventAFirstFrameOrLaterAttributeManualPlayback() {
        for (reason in listOf("no_resume", "failed", "cancelled", "timeout")) {
            val timeline = LaunchTimeline(100, { 150 }) {}
            timeline.mark(LaunchPhase.FirstFrame)
            assertNull(timeline.snapshot().total)
            timeline.finish(reason)
            timeline.mark(LaunchPhase.Resume)
            timeline.mark(LaunchPhase.FirstFrame)
            assertNull(timeline.snapshot().total)
            assertEquals(reason, timeline.snapshot().outcome)
            assertTrue(timeline.snapshot().summary().contains("unavailable"))
        }
    }

    @Test fun launchHistoryRetainsTenDistinctLaunchesAndToleratesMissingOrCorruptFiles() {
        val file = File(folder.root, "launches.json")
        val history = LaunchHistory(file)
        assertTrue(history.read().isEmpty())
        val records = (1..12).map { n -> LaunchRecord("launch-$n", n.toLong(), 100,
            mapOf("application" to 0L, "first_frame" to n * 100L), "video") }
        history.save(records)
        assertEquals(records.takeLast(10), history.read())
        file.writeText("not-json")
        assertTrue(history.read().isEmpty())
        history.save(emptyList())
        assertTrue(history.read().isEmpty())
    }

    @Test fun recorderPersistsAndEnqueuesOneOtherReportAndBackgroundGateWaits() = runBlocking {
        val sent = CopyOnWriteArrayList<List<String>>()
        val sink = object : ReportSink {
            override fun enqueue(kind: String, channel: String, summary: String, detail: String) {
                sent += listOf(kind, channel, summary, detail)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val file = File(folder.root, "launches.json")
        try {
            var clock = 100L
            val recorder = StartupRecorder(file, scope, clock, { clock }) { sink }
            var completed = false
            var ready = false
            val completion = launch { recorder.awaitCompletion(); completed = true }
            val work = launch { recorder.awaitReady(); ready = true }
            delay(40)
            assertFalse(completed)
            assertFalse(ready)
            assertTrue(sent.isEmpty())
            recorder.mark(LaunchPhase.Resume)
            clock = 700
            recorder.mark(LaunchPhase.FirstFrame)
            completion.join()
            assertTrue(completed)
            assertFalse(ready)
            recorder.backgroundReady()
            work.join()
            assertTrue(ready)
            recorder.mark(LaunchPhase.FirstFrame)
            recorder.finish("failed")
            withTimeout(2_000) { while (sent.isEmpty()) delay(10) }
            assertEquals(1, sent.size)
            assertEquals(listOf("other", ""), sent.single().take(2))
            assertEquals(recorder.latest.value.summary(), sent.single()[2])
            assertTrue(sent.single()[3].contains("first_frame=600ms"))
            assertEquals("video", LaunchHistory(file).read().single().outcome)
        } finally { scope.cancel() }
    }

    @Test fun equalLaunchDurationsHaveDifferentSummariesSoQueueDedupeCannotCollapseStarts() {
        val first = LaunchTimeline(0, { 600 }) {}
        val second = LaunchTimeline(0, { 600 }) {}
        for (timeline in listOf(first, second)) {
            timeline.mark(LaunchPhase.Resume)
            timeline.mark(LaunchPhase.FirstFrame)
        }
        assertEquals(first.snapshot().total, second.snapshot().total)
        assertNotEquals(first.snapshot().summary(), second.snapshot().summary())
    }

    @Test fun historyWriteFailureStillReportsTheCompletedLaunch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sent = CopyOnWriteArrayList<String>()
        val sink = object : ReportSink {
            override fun enqueue(kind: String, channel: String, summary: String, detail: String) { sent += summary }
        }
        try {
            val file = File(folder.newFile("not-a-directory"), "launches.json")
            val recorder = StartupRecorder(file, scope, 100, { 200 }) { sink }
            recorder.finish("no_resume")
            withTimeout(2_000) { while (sent.isEmpty()) delay(10) }
            assertEquals(1, sent.size)
            assertTrue(sent.single().startsWith("startup: total=unavailable (no_resume)"))
        } finally { scope.cancel() }
    }

    @Test fun failedLaunchReleasesCompletionAndSinkFailureCannotCrashTheCaller() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val file = File(folder.root, "failed.json")
        val sink = object : ReportSink {
            override fun enqueue(kind: String, channel: String, summary: String, detail: String) { error("offline") }
        }
        try {
            val recorder = StartupRecorder(file, scope, 100, { 200 }) { sink }
            recorder.finish("failed")
            withTimeout(2_000) { recorder.awaitCompletion() }
            withTimeout(2_000) { while (LaunchHistory(file).read().lastOrNull()?.outcome != "failed") delay(10) }
            assertNull(recorder.latest.value.total)
        } finally { scope.cancel() }
    }

    @Test fun coldStartWiringDefersNonPlaybackWorkAndDoesNotWaitForAnImportedChannelList() {
        val root = File("src/main/kotlin/dev/firetvlite/iptv")
        val app = File(root, "AppContainer.kt").readText()
        assertTrue(app.contains("startup.awaitCompletion()"))
        assertTrue(app.contains("if (startup.latest.value.total != null) delay(1_500)"))
        assertTrue(app.contains("withContext(Dispatchers.IO)"))
        assertTrue(app.contains("awaitStartup = startup::awaitReady"))
        val vm = File(root, "ui/viewmodel/PlayerViewModel.kt").readText()
        val resume = vm.substringAfter("startupJob = playbackWork.launch {").substringBefore("startupPending = false")
        assertTrue(resume.contains("channelDao.startupRecent(sourceId).first()"))
        assertFalse(resume.contains("awaitRecent("))
        assertFalse(resume.contains("epg.refresh("))
    }
}
