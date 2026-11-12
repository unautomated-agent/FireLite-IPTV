package dev.firetvlite.iptv

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashReportStoreTest {
    @Test fun failedWritePreservesThePreviousReportAndStillDelegates() {
        val file = File(folder.root, "atomic.txt").apply { writeText("previous report") }
        File(folder.root, "atomic.txt.new").mkdir()
        var delegated = false
        CrashReportStore(file).handler("test") { _, _ -> delegated = true }
            .uncaughtException(Thread.currentThread(), RuntimeException("write fixture"))
        assertTrue(delegated)
        assertTrue(file.readText() == "previous report")
    }

    @Test fun uncaughtWorkerCrashIsRecordedAndDelegated() {
        val store = CrashReportStore(File(folder.root, "worker.txt"))
        var delegated = false
        val worker = Thread({ throw IllegalStateException("worker failure") }, "background-worker")
        worker.uncaughtExceptionHandler = store.handler("test") { _, _ -> delegated = true }
        worker.start()
        worker.join()
        assertTrue(delegated)
        assertTrue(store.read()!!.contains("Thread: background-worker"))
        assertTrue(store.read()!!.contains("Uncaught crash: java.lang.IllegalStateException: worker failure"))
    }

    @Test fun healthHistoryIsBoundedRedactedAndContainsStallStackAndExitReason() {
        val file = File(folder.root, "health.txt")
        val store = CrashReportStore(file)
        repeat(20) { store.stall("test", 3000, "blockedMethod\n" + "frame\n".repeat(2000)) }
        assertTrue(file.length() <= 16_384)
        store.processExits(listOf(ProcessExitRecord(1000, "LOW_MEMORY", 0,
            "https://example.test/live/test-user/test-password/a token=test-token", 100, 200)))
        val report = store.read()!!
        assertTrue(report.contains("Process exit: LOW_MEMORY"))
        assertTrue(report.contains("Main-thread stall: 3000ms"))
        assertTrue(report.contains("Main thread: blockedMethod"))
        assertTrue(!report.contains("test-password") && !report.contains("test-token"))
        assertTrue(file.length() <= 16_384)
    }

    @Test fun historicalExitsAreDeduplicatedAcrossRestartsAndClearingDoesNotResurrectThem() {
        val file = File(folder.root, "exits.txt")
        val exits = listOf(ProcessExitRecord(2000, "ANR", 0, "blocked", 1, 2),
            ProcessExitRecord(1000, "CRASH", 1, "crashed", 3, 4))
        CrashReportStore(file).processExits(exits)
        val once = file.readText()
        CrashReportStore(file).processExits(exits)
        assertTrue(once == file.readText())
        assertTrue(once.indexOf("ANR") < once.indexOf("CRASH"))
        val store = CrashReportStore(file)
        store.clear()
        store.processExits(exits)
        assertNull(store.read())
        store.processExits(emptyList())
        assertNull(store.read())
    }

    @get:Rule val folder = TemporaryFolder()

    @Test fun recordsCrashAndDelegates() {
        val store = CrashReportStore(File(folder.root, "crash.txt"))
        val error = IllegalStateException("test crash")
        var delegated: Throwable? = null
        store.handler("2.2 (101)") { _, caught -> delegated = caught }
            .uncaughtException(Thread.currentThread(), error)
        val report = store.read()!!
        assertTrue(report.startsWith("FireLite 2.2 (101)\n"))
        assertNotNull(java.time.Instant.parse(report.lineSequence().drop(1).first()))
        assertTrue(report.contains("IllegalStateException: test crash"))
        assertTrue(report.contains("recordsCrashAndDelegates"))
        assertSame(error, delegated)
        assertTrue(store.clear())
        assertNull(store.read())
    }

    @Test fun missingReportIsEmptyAndClearIsIdempotent() {
        val store = CrashReportStore(File(folder.root, "missing"))
        assertNull(store.read())
        assertTrue(store.clear())
    }

    @Test fun writeFailureStillDelegates() {
        val store = CrashReportStore(File(folder.root, "missing/crash.txt"))
        var delegated = false
        store.handler("test") { _, _ -> delegated = true }
            .uncaughtException(Thread.currentThread(), RuntimeException("failure"))
        assertTrue(delegated)
    }
}
