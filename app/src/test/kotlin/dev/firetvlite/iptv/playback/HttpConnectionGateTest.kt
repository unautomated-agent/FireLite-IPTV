package dev.firetvlite.iptv.playback

import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpConnectionGateTest {
    @Test fun requestsWithinOneSessionWaitForThePreviousConnectionToClose() {
        val gate = HttpConnectionGate()
        val session = gate.nextSession()
        val first = gate.acquire(session)
        val waiting = CountDownLatch(1)
        val opened = CountDownLatch(1)
        val second = Thread {
            waiting.countDown()
            gate.acquire(session).use { opened.countDown() }
        }
        second.start()
        try {
            assertTrue(waiting.await(3, TimeUnit.SECONDS))
            assertFalse(opened.await(50, TimeUnit.MILLISECONDS))
            first.close(); first.close()
        } finally { first.close() }
        assertTrue(opened.await(3, TimeUnit.SECONDS))
        second.join(3_000)
        assertFalse(second.isAlive)
    }

    @Test fun changingChannelCancelsAQueuedRequestFromTheOldSession() {
        val gate = HttpConnectionGate()
        val old = gate.nextSession()
        val first = gate.acquire(old)
        val cancelled = CountDownLatch(1)
        val waiter = Thread {
            try { gate.acquire(old).use { error("Old session opened") } }
            catch (expected: InterruptedIOException) { cancelled.countDown() }
        }
        waiter.start()
        val next = gate.nextSession()
        try { assertTrue(cancelled.await(3, TimeUnit.SECONDS)) }
        finally { first.close(); waiter.join(3_000) }
        gate.acquire(next).close()
    }

    @Test fun cancelledWaitersDoNotOpenOrLeakThePermit() {
        val gate = HttpConnectionGate()
        val first = gate.acquire(gate.nextSession())
        val next = gate.nextSession()
        val waiting = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val waiter = Thread {
            waiting.countDown()
            try { gate.acquire(next).use { error("Cancelled waiter opened a connection") } }
            catch (expected: InterruptedIOException) { cancelled.countDown() }
        }
        waiter.start()
        try {
            assertTrue(waiting.await(3, TimeUnit.SECONDS))
            waiter.interrupt()
            assertTrue(cancelled.await(3, TimeUnit.SECONDS))
        } finally { first.close(); waiter.join(3_000) }
        gate.acquire(next).close()
    }

    @Test(expected = InterruptedIOException::class)
    fun lateRequestsFromSupersededMediaSourcesAreRejected() {
        val gate = HttpConnectionGate()
        val old = gate.nextSession()
        gate.nextSession()
        gate.acquire(old)
    }
}
