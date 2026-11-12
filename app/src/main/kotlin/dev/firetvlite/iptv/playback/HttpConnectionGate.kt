package dev.firetvlite.iptv.playback

import java.io.Closeable
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

internal class HttpConnectionGate {
    private val lock = Object()
    private var generation = 0L
    private var active = 0
    private var activeSession = 0L

    fun nextSession(): Long = synchronized(lock) {
        (++generation).also { lock.notifyAll() }
    }

    fun acquire(session: Long): Closeable {
        synchronized(lock) {
            try {
                while (session == generation && active > 0 && activeSession != session) lock.wait()
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Stream connection cancelled").apply { initCause(error) }
            }
            if (session != generation) throw InterruptedIOException("Stream session superseded")
            active++
            activeSession = session
        }
        val released = AtomicBoolean()
        return Closeable {
            if (released.compareAndSet(false, true)) synchronized(lock) { active--; lock.notifyAll() }
        }
    }
}
