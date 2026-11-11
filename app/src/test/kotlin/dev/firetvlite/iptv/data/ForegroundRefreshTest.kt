package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.epg.ForegroundRefresh
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundRefreshTest {
    @Test fun checksPeriodicallyOnlyInForegroundWithoutDuplicateTimers() = runBlocking {
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        var checks = 0
        val refresh = ForegroundRefresh(this, { ticks.receive() }) { checks++ }
        try {
            yield()
            assertEquals(0, checks)
            refresh.setForeground(true)
            yield()
            assertEquals(1, checks)
            refresh.setForeground(true)
            ticks.send(Unit)
            yield()
            assertEquals(2, checks)
            refresh.setForeground(false)
            yield()
            assertEquals(2, checks)
            refresh.setForeground(true)
            yield()
            assertEquals(3, checks)
        } finally {
            refresh.setForeground(false)
        }
    }

    @Test fun backgroundCancelsAnInFlightCheck() = runBlocking {
        var cancelled = false
        val refresh = ForegroundRefresh(this) {
            try { awaitCancellation() } finally { cancelled = true }
        }
        refresh.setForeground(true)
        yield()
        refresh.setForeground(false)
        yield()
        assertTrue(cancelled)
    }

    @Test fun transientCheckFailureDoesNotDisableSubsequentChecks() = runBlocking {
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        var checks = 0
        val refresh = ForegroundRefresh(this, { ticks.receive() }) {
            checks++
            if (checks == 1) error("Source read failed")
        }
        try {
            refresh.setForeground(true)
            yield()
            ticks.send(Unit)
            yield()
            assertEquals(2, checks)
        } finally {
            refresh.setForeground(false)
        }
    }
}
