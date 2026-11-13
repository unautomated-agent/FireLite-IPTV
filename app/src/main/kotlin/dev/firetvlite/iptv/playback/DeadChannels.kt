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
    val keys: Set<String> = entries.mapTo(HashSet()) { it.key }

    fun summary(): String = buildList {
        add("Martwe kanały: ${entries.size}")
        entries.forEach {
            val time = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Europe/Warsaw"))
                .format(Instant.ofEpochMilli(it.last))
            add("Martwe: ${it.name} · ${it.reason} · ×${it.count} · last $time · ${it.source}")
        }
        if (check.isNotBlank()) add("Zdrowie kanałów: $check")
    }.joinToString("\n")

    fun qrSummary(bytes: Int = 1650): String {
        fun reason(value: String): String = Regex("HTTP (\\d{3})").find(value)?.groupValues?.get(1) ?: when {
            value.contains("no response", true) -> "brak"
            value.contains("timeout", true) -> "czas"
            value.contains("network", true) -> "sieć"
            value.contains("playlist", true) -> "HLS"
            value.contains("empty", true) -> "pusty"
            else -> "błąd"
        }
        val items = entries.map { DeadChannels.safeName(it.name).replace(".", "[.]").replace(';', ',') to reason(it.reason) }
        fun pack(nameBytes: Int, count: Int = items.size): String = "Martwe (${entries.size}): " +
            items.take(count).joinToString("; ") { (name, code) -> "${utf8Limit(name, nameBytes)} $code" } +
            if (count < items.size) "; +${items.size - count} więcej" else ""
        var low = 7
        var high = 360
        var result = pack(low)
        if (result.toByteArray(Charsets.UTF_8).size <= bytes) {
            while (low <= high) {
                val middle = (low + high) / 2
                val candidate = pack(middle)
                if (candidate.toByteArray(Charsets.UTF_8).size <= bytes) {
                    result = candidate
                    low = middle + 1
                } else high = middle - 1
            }
        } else {
            var count = items.size
            do { result = pack(7, --count) } while (count > 0 && result.toByteArray(Charsets.UTF_8).size > bytes)
        }
        return result + if (check.isNotBlank()) "\nZdrowie kanałów: $check" else ""
    }
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
