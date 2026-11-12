package dev.firetvlite.iptv

import java.io.File
import java.time.Instant
import dev.firetvlite.iptv.data.library.DiagnosticRedactor
import dev.firetvlite.iptv.playback.utf8Limit

internal data class ProcessExitRecord(val timestamp: Long, val reason: String, val status: Int,
    val description: String, val pss: Long, val rss: Long)

class CrashReportStore(private val file: File) {
    @Synchronized fun read(): String? = runCatching {
        if (!file.exists()) null else file.reader().use { reader ->
            val buffer = CharArray(16_384)
            val count = reader.read(buffer)
            if (count < 0) null else String(buffer, 0, count)
        }
    }.getOrNull()

    @Synchronized fun clear(): Boolean = !file.exists() || file.delete()

    @Synchronized private fun record(text: String) {
        val event = utf8Limit(DiagnosticRedactor.mask(text), 4_096)
        val history = read()
        val temporary = File(file.parentFile, "${file.name}.new")
        temporary.writeText(utf8Limit(event + (history?.let { "\n---\n$it" } ?: ""), 16_384))
        check(temporary.renameTo(file)) { "Cannot save app diagnostics" }
    }

    internal fun stall(version: String, durationMs: Long, stack: String) = record(
        "Main-thread stall: ${durationMs}ms (ANR-like) · ${Instant.now()} · FireLite $version\nMain thread: $stack")

    @Synchronized internal fun processExits(exits: List<ProcessExitRecord>) {
        val marker = File(file.parentFile, "process-exits-seen.txt")
        val seen = runCatching { marker.readText().trim().toLong() }.getOrDefault(0L)
        exits.filter { it.timestamp > seen }.sortedBy { it.timestamp }.takeLast(4).forEach { exit ->
            record("Process exit: ${exit.reason} · ${Instant.ofEpochMilli(exit.timestamp)} · status=${exit.status}" +
                " · pss=${exit.pss}KB rss=${exit.rss}KB\n${exit.description.take(512)}")
            marker.writeText(exit.timestamp.toString())
        }
    }

    fun handler(version: String, previous: Thread.UncaughtExceptionHandler?) =
        Thread.UncaughtExceptionHandler { thread, error ->
            try {
                record("FireLite $version\n${Instant.now()}\nThread: ${thread.name}\n" +
                    "Uncaught crash: ${error.javaClass.name}: ${error.message.orEmpty().take(512)}\n${error.stackTraceToString()}")
            } catch (_: Throwable) {
                // Reporting must not prevent Android's normal crash handling.
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
}
