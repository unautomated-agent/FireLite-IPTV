package dev.firetvlite.iptv.playback

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal object PlaybackJank {
    @Volatile var atMs = Long.MIN_VALUE
    @Volatile var durationMs = 0L
}

internal data class StallSample(
    val nowMs: Long, val wallMs: Long, val channel: String,
    val active: Boolean, val buffering: Boolean, val ready: Boolean,
    val frames: Int?, val positionMs: Long, val bufferedMs: Long,
    val byteAgeMs: Long, val httpError: Int? = null, val loadMs: Long = 0,
    val bandwidth: Long = 0, val dropped: Int = 0, val decoderError: Boolean = false,
    val jankMs: Long = 0,
)

internal data class PlaybackStall(val time: Long, val channel: String, val duration: Long,
    val kind: String, val evidence: String) {
    fun line(compact: Boolean = false): String {
        val stamp = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.of("Europe/Warsaw"))
            .format(Instant.ofEpochMilli(time))
        return if (compact) "Zawieszenie: $stamp ${utf8Limit(channel, 22)} ${duration / 1000}s $kind ${evidence.substringBefore(';')}"
        else "Zawieszenie: $stamp · $channel · ${duration / 1000}s · $kind · $evidence"
    }
}

internal class StallDetector {
    private var previous: StallSample? = null
    private var since = 0L
    private var startWall = 0L
    private var lastReport = 0L
    private var lastRecord: PlaybackStall? = null
    private var jankReported = false

    fun sample(value: StallSample): PlaybackStall? {
        val old = previous
        previous = value
        if (value.jankMs == 0L) jankReported = false
        if (old?.active == true && value.active && value.jankMs >= 3_000 && !jankReported) {
            jankReported = true
            return PlaybackStall(value.wallMs - value.jankMs, value.channel, value.jankMs, "decoder/app",
                "main-thread=${value.jankMs}ms; buffer=${value.bufferedMs}ms dropped=${value.dropped}")
        }
        val frozen = old?.active == true && value.ready && ((value.frames != null && old.frames != null &&
            value.frames == old.frames) || value.positionMs == old.positionMs)
        if (!value.active || (!value.buffering && !frozen)) {
            val final = lastRecord?.copy(duration = (value.nowMs - since).coerceAtLeast(0))
            since = 0
            lastRecord = null
            lastReport = 0
            return final
        }
        if (since == 0L) {
            since = if (old?.active == true) old.nowMs else value.nowMs
            startWall = value.wallMs - (value.nowMs - since)
        }
        val duration = value.nowMs - since
        if (duration < 3_000 || (lastReport != 0L && value.nowMs - lastReport < 5_000)) return null
        val (kind, cause) = when {
            value.jankMs >= 3_000 -> "decoder/app" to "main-thread=${value.jankMs}ms"
            value.decoderError -> "decoder/app" to "decoder error"
            (frozen || value.buffering) && value.bufferedMs >= 5_000 -> "decoder/app" to "buffered media not rendering"
            value.httpError != null -> "provider" to "HTTP ${value.httpError}"
            value.bufferedMs < 1_000 && value.byteAgeMs >= 3_000 -> "provider" to "no media bytes"
            value.bufferedMs < 3_000 && value.loadMs >= 3_000 -> "provider" to "slow media load"
            else -> "unknown" to "insufficient evidence"
        }
        lastReport = value.nowMs
        return PlaybackStall(startWall, value.channel, duration, kind,
            "$cause; buffer=${value.bufferedMs}ms byteAge=${value.byteAgeMs}ms load=${value.loadMs}ms " +
                "bps=${value.bandwidth} dropped=${value.dropped} jank=${value.jankMs}ms").also { lastRecord = it }
    }
}

internal class StallHistory(private val file: File, scope: CoroutineScope, awaitStartup: suspend () -> Unit = {}) {
    private val commands = Channel<() -> Unit>(Channel.UNLIMITED)
    private val mutable = MutableStateFlow<List<PlaybackStall>>(emptyList())
    val state = mutable.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            awaitStartup()
            mutable.value = runCatching {
                check(file.length() <= 16_000)
                val rows = JSONArray(file.readText())
                (0 until rows.length()).take(5).map { index ->
                    val row = rows.getJSONObject(index)
                    PlaybackStall(row.getLong("time"), DeadChannels.safeName(row.getString("channel")),
                        row.getLong("duration"), row.getString("kind").take(16), row.getString("evidence").take(300))
                }
            }.getOrDefault(emptyList())
            for (command in commands) command()
        }
    }

    fun record(stall: PlaybackStall) {
        commands.trySend {
            val safe = stall.copy(channel = DeadChannels.safeName(stall.channel), evidence = stall.evidence.take(300))
            mutable.value = (listOf(safe) + mutable.value.filterNot { it.time == safe.time && it.channel == safe.channel }).take(5)
            runCatching {
                val rows = JSONArray()
                mutable.value.forEach { rows.put(JSONObject().put("time", it.time).put("channel", it.channel)
                    .put("duration", it.duration).put("kind", it.kind).put("evidence", it.evidence)) }
                val temporary = File(file.parentFile, "${file.name}.new")
                temporary.writeText(rows.toString())
                check(temporary.renameTo(file))
            }
        }
    }

    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        commands.send { done.complete(Unit) }
        done.await()
    }
}
