package dev.firetvlite.iptv.data.playlist

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.net.URLDecoder

object PlaylistHttpHeaders {
    private val names = listOf("User-Agent", "Referer", "Origin", "Cookie", "Authorization", "Accept", "Accept-Language")
    private val gson = Gson()

    fun put(headers: MutableMap<String, String>, name: String, value: String) {
        val canonical = names.firstOrNull { it.equals(name, true) ||
            (it == "Referer" && name.equals("Referrer", true)) } ?: return
        if (value.isNotBlank() && value.none { it == '\r' || it == '\n' || it == '\u0000' }) headers[canonical] = value
    }

    fun parameters(text: String, headers: MutableMap<String, String>) {
        text.split('&').forEach { pair ->
            if ('=' in pair) runCatching {
                put(headers, URLDecoder.decode(pair.substringBefore('='), "UTF-8"),
                    URLDecoder.decode(pair.substringAfter('='), "UTF-8"))
            }
        }
    }

    fun splitUrl(url: String): Pair<String, Map<String, String>> {
        val headers = linkedMapOf<String, String>()
        if ('|' in url) parameters(url.substringAfter('|'), headers)
        return url.substringBefore('|') to headers
    }

    fun encode(headers: Map<String, String>): String = if (headers.isEmpty()) "" else gson.toJson(headers)

    fun decode(text: String): Map<String, String> = runCatching {
        val values: Map<String, String> = gson.fromJson(text, object : TypeToken<Map<String, String>>() {}.type)
            ?: emptyMap()
        linkedMapOf<String, String>().apply { values.forEach { (name, value) -> put(this, name, value) } }
    }.getOrDefault(emptyMap())
}
