package dev.firetvlite.iptv.playback

import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpConnectionGateTest {
    @Test fun requestsWithinOneSessionOverlapButANewChannelWaitsForAllOfThem() {
        val gate = HttpConnectionGate()
        val session = gate.nextSession()
        val first = gate.acquire(session)
        val segment = gate.acquire(session)
        val next = gate.nextSession()
        val waiting = CountDownLatch(1)
        val opened = CountDownLatch(1)
        val second = Thread {
            waiting.countDown()
            gate.acquire(next).use { opened.countDown() }
        }
        second.start()
        try {
            assertTrue(waiting.await(3, TimeUnit.SECONDS))
            assertFalse(opened.await(50, TimeUnit.MILLISECONDS))
            first.close(); first.close()
            assertFalse(opened.await(50, TimeUnit.MILLISECONDS))
        } finally { first.close(); segment.close() }
        assertTrue(opened.await(3, TimeUnit.SECONDS))
        second.join(3_000)
        assertFalse(second.isAlive)
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
