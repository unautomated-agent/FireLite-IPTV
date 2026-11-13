package dev.firetvlite.iptv.playback

import dev.firetvlite.iptv.data.library.DiagnosticRedactor
import java.io.File
import java.security.MessageDigest
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

internal data class DeadChannel(val key: String, val name: String, val first: Long, val last: Long,
    val count: Int, val reason: String, val source: String)

internal data class ChannelHealthState(val entries: List<DeadChannel> = emptyList(), val check: String = "") {
    fun summary(): String = buildList {
        add("Martwe kanały: ${entries.size}")
        entries.take(3).forEach {
            val time = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Europe/Warsaw"))
                .format(Instant.ofEpochMilli(it.last))
            add("Martwe: ${utf8Limit(it.name, 26)} · ${utf8Limit(it.reason, 24)} · ×${it.count} · last $time")
        }
        if (entries.size > 3) add("Martwe: +${entries.size - 3} więcej")
        if (check.isNotBlank()) add("Zdrowie kanałów: $check")
    }.joinToString("\n")
}

internal class DeadChannels(private val file: File, scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis) {
    private val mutableState = MutableStateFlow(ChannelHealthState())
    val state = mutableState.asStateFlow()
    private val commands = Channel<() -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.IO) {
            mutableState.value = runCatching {
                check(file.length() <= 128_000)
                val saved = JSONObject(file.readText())
                val rows = saved.optJSONArray("entries") ?: JSONArray()
                val entries = (0 until rows.length()).map { index ->
                    val row = rows.getJSONObject(index)
                    DeadChannel(row.getString("key"), row.getString("name"), row.getLong("first"), row.getLong("last"),
                        row.getInt("count"), row.getString("reason"), row.getString("source"))
                }.filter { it.key.matches(Regex("[a-f0-9]{64}")) }.sortedByDescending { it.last }.take(100)
                ChannelHealthState(entries, saved.optString("check").replace(" · running", " · stopped"))
            }.getOrDefault(ChannelHealthState())
            for (command in commands) command()
        }
    }

    fun result(request: StreamRequest, reason: String?, source: String = "playback") {
        val now = clock()
        commands.trySend {
            val previous = mutableState.value.entries.firstOrNull { it.key == request.channelKey }
            val remaining = mutableState.value.entries.filterNot { it.key == request.channelKey }
            val entries = if (reason == null) remaining else remaining + DeadChannel(request.channelKey,
                safeName(request.channelName.ifBlank { "Nieznany kanał" }, request.url), previous?.first ?: now,
                now, ((previous?.count ?: 0) + 1).coerceAtMost(9999), safeName(reason, request.url).take(64), source)
            save(mutableState.value.copy(entries = entries.sortedByDescending { it.last }.take(100)))
        }
    }

    fun checkSummary(summary: String) {
        commands.trySend { save(mutableState.value.copy(check = summary.take(180))) }
    }

    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        commands.send { done.complete(Unit) }
        done.await()
    }

    private fun save(state: ChannelHealthState) {
        runCatching {
            val temporary = File(file.parentFile, "${file.name}.new")
            val entries = JSONArray()
            state.entries.forEach { entry -> entries.put(JSONObject().put("key", entry.key).put("name", entry.name)
                .put("first", entry.first).put("last", entry.last).put("count", entry.count)
                .put("reason", entry.reason).put("source", entry.source)) }
            temporary.writeText(JSONObject().put("entries", entries).put("check", state.check).toString())
            check(temporary.renameTo(file))
        }
        mutableState.value = state
    }

    companion object {
        fun key(url: String): String = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }

        fun safeName(name: String, url: String = ""): String = utf8Limit(
            DiagnosticRedactor.mask(name, DiagnosticRedactor.secrets(listOf(url)))
                .replace(Regex("(?i)https?://\\S+"), "[adres ukryty]")
                .replace(Regex("\\d{7,}"), "***").replace(Regex("\\s+"), " ").trim(), 120)
    }
}
