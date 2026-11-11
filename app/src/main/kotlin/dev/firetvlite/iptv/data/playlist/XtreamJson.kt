package dev.firetvlite.iptv.data.playlist

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import dev.firetvlite.iptv.data.db.XtreamChannelEntity
import java.io.IOException
import java.io.Reader
import java.math.BigDecimal
import java.time.ZoneId

object XtreamJson {
    private fun JsonReader.scalar(): String? = when (peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> nextString().trim().takeUnless { it.isEmpty() || it.equals("null", true) }
        JsonToken.BOOLEAN -> nextBoolean().toString()
        else -> { skipValue(); null }
    }

    fun channels(input: Reader, sourceId: Long): List<XtreamChannelEntity> {
        val json = JsonReader(input)
        if (json.peek() != JsonToken.BEGIN_ARRAY) throw IOException("Expected live stream array")
        val rows = linkedMapOf<String, XtreamChannelEntity>()
        var count = 0
        json.beginArray()
        while (json.hasNext()) {
            if (++count > 200_000) throw IOException("Too many Xtream channels")
            if (json.peek() != JsonToken.BEGIN_OBJECT) { json.skipValue(); continue }
            var id: String? = null
            var archived = false
            var days = 0
            var epg: String? = null
            json.beginObject()
            while (json.hasNext()) when (json.nextName()) {
                "stream_id" -> id = json.scalar()?.takeIf { it.length <= 64 }?.let {
                    runCatching { BigDecimal(it).stripTrailingZeros().toPlainString() }.getOrNull()
                }?.takeIf { it.matches(Regex("\\d+")) }
                "tv_archive" -> archived = json.scalar()?.let { it == "true" || it.toDoubleOrNull() == 1.0 } == true
                "tv_archive_duration" -> days = json.scalar()?.toDoubleOrNull()?.takeIf { it.isFinite() }
                    ?.coerceIn(0.0, 365.0)?.toInt() ?: 0
                "epg_channel_id" -> epg = json.scalar()?.take(512)
                else -> json.skipValue()
            }
            json.endObject()
            id?.let { rows[it] = XtreamChannelEntity(sourceId, it, archived, if (archived) days else 0, epg) }
        }
        json.endArray()
        if (json.peek() != JsonToken.END_DOCUMENT) throw IOException("Trailing Xtream JSON")
        return rows.values.toList()
    }

    fun timezone(input: Reader): String? {
        val json = JsonReader(input)
        if (json.peek() != JsonToken.BEGIN_OBJECT) throw IOException("Expected server info object")
        var timezone: String? = null
        var serverInfo = false
        var denied = false
        json.beginObject()
        while (json.hasNext()) {
            val field = json.nextName()
            if (field !in setOf("server_info", "user_info") || json.peek() != JsonToken.BEGIN_OBJECT) {
                json.skipValue(); continue
            }
            if (field == "server_info") serverInfo = true
            json.beginObject()
            while (json.hasNext()) {
                val key = json.nextName()
                when {
                    field == "server_info" && key == "timezone" -> timezone = json.scalar()
                    field == "user_info" && key == "auth" -> denied = json.scalar() in setOf("0", "false")
                    else -> json.skipValue()
                }
            }
            json.endObject()
        }
        json.endObject()
        if (json.peek() != JsonToken.END_DOCUMENT || denied || !serverInfo) throw IOException("Invalid server info")
        return timezone?.let { runCatching { ZoneId.of(it).id }.getOrNull() }
    }
}
