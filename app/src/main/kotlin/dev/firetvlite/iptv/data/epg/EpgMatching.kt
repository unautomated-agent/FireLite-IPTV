package dev.firetvlite.iptv.data.epg

import java.net.URL
import java.text.Normalizer
import java.util.Locale
import dev.firetvlite.iptv.data.playlist.XtreamAddress

data class XmlTvChannel(val key: String, val id: String, val displayNames: List<String>)

object EpgMatching {
    fun id(value: String): String = value.trim().lowercase(Locale.ROOT).removeSuffix(".pl").trim()

    fun name(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT).replace('ł', 'l')
        .replace(Regex("^(?:pl|pol|poland)\\s*[:|·-]\\s*"), ""), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("(?:\\s+(?:sd|hd|fhd|uhd|4k|8k))+$"), "")
        .replace(Regex("\\s+"), " ")

    /** Exact IDs across all feeds win over display-name fallbacks; the first feed breaks ties. */
    class Index(channels: List<XmlTvChannel>) {
        private val ids = LinkedHashMap<String, String>()
        private val names = LinkedHashMap<String, String>()

        init {
            channels.forEach { channel ->
                id(channel.id).takeIf { it.isNotEmpty() }?.let { ids.putIfAbsent(it, channel.key) }
                channel.displayNames.forEach { display ->
                    name(display).takeIf { it.isNotEmpty() }?.let { names.putIfAbsent(it, channel.key) }
                }
            }
        }

        fun match(tvgId: String?, channelName: String, tvgName: String? = null): String? =
            tvgId?.let { ids[id(it)] } ?: tvgName?.let { names[name(it)] ?: ids[id(it)] }
                ?: names[name(channelName)] ?: ids[id(channelName)]
    }
}

internal object EpgSources {
    fun urls(userUrl: String, headerUrls: String, playlistUrl: String): List<String> =
        userUrl.trim().ifEmpty { headerUrls }.ifEmpty { XtreamAddress.playlist(playlistUrl)?.epgUrl().orEmpty() }
            .split(Regex("[,\\s]+"))
            .map { it.trim() }.filter { it.isNotEmpty() }
            .map { value ->
                val url = URL(URL(playlistUrl), value)
                require(url.protocol == "http" || url.protocol == "https")
                url.toString()
            }.distinct()
}

data class EpgWindow(val from: Long, val until: Long) {
    fun contains(start: Long, stop: Long): Boolean = start < stop && start < until && stop > from

    companion object {
        fun around(now: Long, maxCatchupDays: Int): EpgWindow {
            val day = 24L * 60 * 60 * 1000
            return EpgWindow(now - maxOf(7, maxCatchupDays).toLong() * day, now + 3 * day)
        }
    }
}
