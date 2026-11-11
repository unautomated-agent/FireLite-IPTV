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
