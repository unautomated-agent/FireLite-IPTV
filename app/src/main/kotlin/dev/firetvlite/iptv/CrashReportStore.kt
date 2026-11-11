package dev.firetvlite.iptv

import java.io.File
import java.time.Instant

class CrashReportStore(private val file: File) {
    fun read(): String? = runCatching { if (file.exists()) file.readText() else null }.getOrNull()

    fun clear(): Boolean = !file.exists() || file.delete()

    fun handler(version: String, previous: Thread.UncaughtExceptionHandler?) =
        Thread.UncaughtExceptionHandler { thread, error ->
            try {
                file.writeText("FireLite $version\n${Instant.now()}\nThread: ${thread.name}\n\n${error.stackTraceToString()}")
            } catch (_: Exception) {
                // Reporting must not prevent Android's normal crash handling.
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
}
