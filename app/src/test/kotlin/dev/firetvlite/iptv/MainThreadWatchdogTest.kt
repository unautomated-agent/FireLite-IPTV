package dev.firetvlite.iptv

import org.junit.Assert.*
import org.junit.Test

class MainThreadWatchdogTest {
    @Test fun reportsOneStackPerStallWithoutGrowingMainQueueAndCanReportAfterRecovery() {
        val posts = mutableListOf<() -> Unit>()
        val reports = mutableListOf<Pair<Long, String>>()
        val watchdog = MainThreadWatchdog({ posts.add(it) }, { "blockedMethod\ncaller" }, { ms, stack -> reports.add(ms to stack) })
        watchdog.tick(0, true)
        watchdog.tick(2999, true)
        assertTrue(reports.isEmpty())
        watchdog.tick(3000, true)
        watchdog.tick(6000, true)
        assertEquals(listOf(3000L to "blockedMethod\ncaller"), reports)
        assertEquals(1, posts.size)
        posts[0]()
        watchdog.tick(6100, true)
        watchdog.tick(9100, true)
        assertEquals(2, reports.size)
        assertEquals(2, posts.size)
    }

    @Test fun responsiveMainAndBackgroundNeverReportAndStaleAcknowledgementsCannotHideNewStalls() {
        val posts = mutableListOf<() -> Unit>()
        var reported = 0
        val watchdog = MainThreadWatchdog({ posts.add(it) }, { "stack" }, { _, _ -> reported++ })
        watchdog.tick(0, false)
        assertTrue(posts.isEmpty())
        watchdog.tick(10, true)
        posts.last()()
        watchdog.tick(4000, true)
        assertEquals(0, reported)
        watchdog.tick(4100, false)
        watchdog.tick(9000, false)
        watchdog.tick(10000, true)
        posts[1]()
        watchdog.tick(13000, true)
        assertEquals(1, reported)
    }
}
