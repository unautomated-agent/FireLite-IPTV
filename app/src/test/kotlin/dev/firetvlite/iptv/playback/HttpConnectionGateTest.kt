package dev.firetvlite.iptv.playback

import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpConnectionGateTest {
    @Test fun playlistAndSegmentConnectionsCannotOverlapAndCloseIsIdempotent() {
        val gate = HttpConnectionGate()
        val first = gate.acquire()
        val waiting = CountDownLatch(1)
        val opened = CountDownLatch(1)
        val second = Thread {
            waiting.countDown()
            gate.acquire().use { opened.countDown() }
        }
        second.start()
        try {
            assertTrue(waiting.await(3, TimeUnit.SECONDS))
            assertFalse(opened.await(50, TimeUnit.MILLISECONDS))
        } finally { first.close(); first.close() }
        assertTrue(opened.await(3, TimeUnit.SECONDS))
        second.join(3_000)
        assertFalse(second.isAlive)
    }

    @Test fun cancelledWaitersDoNotOpenOrLeakThePermit() {
        val gate = HttpConnectionGate()
        val first = gate.acquire()
        val waiting = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val waiter = Thread {
            waiting.countDown()
            try { gate.acquire().use { error("Cancelled waiter opened a connection") } }
            catch (expected: InterruptedIOException) { cancelled.countDown() }
        }
        waiter.start()
        try {
            assertTrue(waiting.await(3, TimeUnit.SECONDS))
            waiter.interrupt()
            assertTrue(cancelled.await(3, TimeUnit.SECONDS))
        } finally { first.close(); waiter.join(3_000) }
        gate.acquire().close()
    }
}
