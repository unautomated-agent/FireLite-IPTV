package dev.firetvlite.iptv

import dev.firetvlite.iptv.reporting.ReportSink
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal enum class LaunchPhase(val label: String) {
    Application("application"), Activity("activity"), Compose("compose"), Database("db_playlist"),
    Resume("resume_play"), Prepare("prepare"), FirstFrame("first_frame"),
}

internal data class LaunchRecord(val id: String, val startedAt: Long, val elapsedOrigin: Long,
    val phases: Map<String, Long>, val outcome: String = "pending") {
    val total: Long? get() = phases[LaunchPhase.FirstFrame.label]
    fun detail(): String = "outcome=$outcome; " + phases.entries.joinToString("; ") { "${it.key}=${it.value}ms" }
    fun summary(): String = "startup: total=${total?.let { "${it}ms" } ?: "unavailable ($outcome)"}; launch=$id"
}

internal class LaunchTimeline(private val origin: Long, private val clock: () -> Long,
    private val changed: (LaunchRecord) -> Unit) {
    private var record = LaunchRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), origin,
        mapOf(LaunchPhase.Application.label to 0L))
    @Synchronized fun snapshot(): LaunchRecord = record
    @Synchronized fun mark(phase: LaunchPhase) {
        if (record.outcome != "pending" || phase.label in record.phases) return
        if (phase == LaunchPhase.FirstFrame && LaunchPhase.Resume.label !in record.phases) return
        record = record.copy(phases = record.phases + (phase.label to (clock() - origin).coerceAtLeast(0)))
        if (phase == LaunchPhase.FirstFrame) record = record.copy(outcome = "video")
        changed(record)
    }
    @Synchronized fun finish(outcome: String) {
        if (record.outcome != "pending") return
        record = record.copy(outcome = outcome)
        changed(record)
    }
}

internal class LaunchHistory(private val file: File) {
    fun read(): List<LaunchRecord> = runCatching {
        check(file.length() <= 128_000)
        val rows = JSONArray(file.readText())
        (maxOf(0, rows.length() - 10) until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val phases = row.getJSONObject("phases")
            LaunchRecord(row.getString("id"), row.getLong("startedAt"), row.getLong("elapsedOrigin"),
                phases.keys().asSequence().associateWith { phases.getLong(it) }, row.getString("outcome"))
        }
    }.getOrDefault(emptyList())

    fun save(records: List<LaunchRecord>) {
        val rows = JSONArray()
        records.takeLast(10).forEach { record -> rows.put(JSONObject().put("id", record.id)
            .put("startedAt", record.startedAt).put("elapsedOrigin", record.elapsedOrigin)
            .put("phases", JSONObject(record.phases)).put("outcome", record.outcome)) }
        val temporary = File(file.parentFile, "${file.name}.new")
        file.parentFile?.mkdirs()
        temporary.writeText(rows.toString())
        check(temporary.renameTo(file))
    }
}

internal class StartupRecorder(file: File, scope: CoroutineScope, origin: Long, clock: () -> Long,
    private val reports: () -> ReportSink) {
    private val events = Channel<LaunchRecord>(Channel.UNLIMITED)
    private val completed = CompletableDeferred<Unit>()
    private val mutableReady = MutableStateFlow(false)
    val ready = mutableReady.asStateFlow()
    private val timeline: LaunchTimeline = LaunchTimeline(origin, clock) { record ->
        mutableLatest.value = record
        events.trySend(record)
        if (record.outcome != "pending") completed.complete(Unit)
    }
    private val mutableLatest: MutableStateFlow<LaunchRecord> = MutableStateFlow(timeline.snapshot())
    val latest = mutableLatest.asStateFlow()

    init {
        events.trySend(timeline.snapshot())
        scope.launch(Dispatchers.IO) {
            val history = LaunchHistory(file)
            var records = history.read()
            for (record in events) {
                records = (records.filterNot { it.id == record.id } + record).takeLast(10)
                runCatching { history.save(records) }
                if (record.outcome != "pending") runCatching {
                    reports().enqueue("other", "", record.summary(), record.detail())
                }
            }
        }
        scope.launch { delay(90_000); finish("timeout") }
    }

    fun mark(phase: LaunchPhase) = timeline.mark(phase)
    fun finish(outcome: String) = timeline.finish(outcome)
    suspend fun awaitCompletion() = completed.await()
    suspend fun awaitReady() { ready.first { it } }
    fun backgroundReady() { mutableReady.value = true }
}
