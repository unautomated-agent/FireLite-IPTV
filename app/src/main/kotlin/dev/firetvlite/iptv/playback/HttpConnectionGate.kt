package dev.firetvlite.iptv.playback

import java.io.Closeable
import java.io.InterruptedIOException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

internal class HttpConnectionGate {
    private val permit = Semaphore(1, true)

    fun acquire(): Closeable {
        try {
            permit.acquire()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Stream connection cancelled").apply { initCause(error) }
        }
        val released = AtomicBoolean()
        return Closeable { if (released.compareAndSet(false, true)) permit.release() }
    }
}
