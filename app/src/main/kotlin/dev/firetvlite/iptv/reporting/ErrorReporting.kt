package dev.firetvlite.iptv.reporting

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.JsonParser
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class AndroidReportPreferences(private val context: Context) : ReportPreferences {
    private val prefs: SharedPreferences by lazy { context.getSharedPreferences("error-reports", Context.MODE_PRIVATE) }
    override val install: String
        @Synchronized get() {
            val saved = prefs.getString("install", null)
            if (saved != null && saved.matches(Regex("[A-Za-z0-9_-]{8,64}"))) return saved
            val created = ReportText.installId()
            check(prefs.edit().putString("install", created).commit())
            return created
        }
    override var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { check(prefs.edit().putBoolean("enabled", value).commit()) }
    override var lastSent: Long
        get() = prefs.getLong("lastSent", 0)
        set(value) { check(prefs.edit().putLong("lastSent", value).commit()) }
    override var lastError: String
        get() = prefs.getString("lastError", "").orEmpty()
        set(value) { check(prefs.edit().putString("lastError", value.take(120)).commit()) }
}

internal class CrashReportHandoff(private val directory: File) : ReportSink {
    override fun processExit(occurredAt: Long, summary: String, detail: String): Boolean = runCatching {
        save(ReportText.create("exit", "", summary, detail)
            .copy(occurredAt = java.time.Instant.ofEpochMilli(occurredAt).toString()))
    }.isSuccess

    override fun enqueue(kind: String, channel: String, summary: String, detail: String) {
        save(ReportText.create(kind, channel, summary, detail))
    }

    @Synchronized fun save(report: QueuedReport) {
        check(directory.isDirectory || directory.mkdirs())
        val target = File(directory, "${report.id}.json")
        val temporary = File(directory, "${report.id}.new")
        temporary.outputStream().use { stream ->
            val json = ReportBatch.item(report).apply {
                addProperty("createdAt", report.createdAt)
                addProperty("build", report.build)
            }
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        check(temporary.renameTo(target))
        directory.listFiles { file -> file.extension == "json" }.orEmpty().sortedByDescending { it.lastModified() }
            .drop(500).forEach { it.delete() }
    }

    @Synchronized fun drain(queue: ReportQueue) {
        directory.listFiles { file -> file.extension == "json" }.orEmpty().sortedBy { it.lastModified() }.forEach { file ->
            val report = try {
                check(file.length() <= 128_000)
                val row = JsonParser.parseString(file.readText()).asJsonObject
                QueuedReport(row["id"].asString, row["kind"].asString, row["createdAt"].asLong,
                    row["occurredAt"].asString, row["channel"].asString, row["summary"].asString, row["detail"].asString, row["build"].asInt)
            } catch (_: RuntimeException) { null }
            if (report != null) queue.enqueue(report)
            check(file.delete())
        }
    }
}

internal class ReportUploadDebounce(private val scope: CoroutineScope, private val delayMs: Long = 30_000,
    private val upload: suspend () -> Unit) {
    private var pending: Job? = null

    @Synchronized fun enqueue() {
        pending?.cancel()
        pending = scope.launch { delay(delayMs); scope.launch { upload() } }
    }
}

internal class ErrorReporting(context: Context, private val queue: ReportQueue, scope: CoroutineScope,
    allowed: () -> Boolean) : ReportSink {
    private val io = CoroutineScope(scope.coroutineContext + Dispatchers.IO)
    private val preferences = AndroidReportPreferences(context)
    private val mutableState = MutableStateFlow(ReportingStatus())
    val state = mutableState.asStateFlow()
    private val handoff = CrashReportHandoff(File(context.filesDir, "report-crash-handoff"))
    private val uploader = ReportUploader(queue, preferences, allowed, { mutableState.value = it })
    private data class Capture(val kind: String, val channel: String, val summary: String, val detail: String,
        val capturedAt: Long = System.currentTimeMillis(), val occurredAt: Long = capturedAt)
    private val captures = Channel<Capture>(500, BufferOverflow.DROP_OLDEST)
    private val debounce = ReportUploadDebounce(io) { attempt() }
    private var retry: Job? = null

    init {
        io.launch {
            runCatching { preferences.install; handoff.drain(queue); refresh() }
            for (capture in captures) runCatching {
                val report = ReportText.create(capture.kind, capture.channel, capture.summary, capture.detail, capture.capturedAt)
                    .copy(occurredAt = java.time.Instant.ofEpochMilli(capture.occurredAt).toString())
                if (queue.enqueue(report)) {
                    refresh(); afterEnqueue()
                }
            }
        }
        io.launch { delay(20_000); attempt() }
        io.launch { while (true) { delay(15 * 60_000); attempt() } }
    }

    override fun enqueue(kind: String, channel: String, summary: String, detail: String) {
        runCatching { captures.trySend(Capture(kind, channel, summary, detail)) }
    }

    override fun crash(summary: String, detail: String) {
        runCatching { handoff.save(ReportText.create("crash", "", summary, detail)) }
        io.launch { runCatching { handoff.drain(queue); refresh(); afterEnqueue() } }
    }

    override fun processExit(occurredAt: Long, summary: String, detail: String): Boolean {
        if (!handoff.processExit(occurredAt, summary, detail)) return false
        io.launch { runCatching { handoff.drain(queue); refresh(); afterEnqueue() } }
        return true
    }

    fun setEnabled(enabled: Boolean) {
        io.launch { runCatching {
            preferences.enabled = enabled
            if (!enabled) synchronized(this@ErrorReporting) { retry?.cancel() }
            mutableState.value = uploader.status()
            if (enabled) attempt()
        } }
    }

    fun sendNow() { io.launch { attempt(true) } }

    private fun refresh() { mutableState.value = uploader.status(mutableState.value.blocked) }

    private fun afterEnqueue() = debounce.enqueue()

    private suspend fun attempt(manual: Boolean = false) {
        runCatching {
            val outcome = uploader.upload(manual)
            synchronized(this) {
                retry?.cancel()
                retry = if (outcome.retryIn > 0) io.launch { delay(outcome.retryIn); io.launch { attempt(manual) } } else null
            }
        }
    }
}
