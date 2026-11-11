package dev.firetvlite.iptv.data.playlist

import dev.firetvlite.iptv.data.db.ChannelEntity
import dev.firetvlite.iptv.data.db.SourceEntity
import dev.firetvlite.iptv.data.db.XtreamChannelEntity
import dev.firetvlite.iptv.playback.CatchupUrlBuilder
import java.io.IOException
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.CancellationException

interface XtreamMetadataReader {
    fun <T> read(url: String, agent: String, parse: (Reader) -> T): T
}

class HttpXtreamMetadataReader(private val ensureActive: () -> Unit = {}) : XtreamMetadataReader {
    override fun <T> read(url: String, agent: String, parse: (Reader) -> T): T {
        ensureActive()
        val deadline = System.nanoTime() + 15_000_000_000L
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", agent)
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            connection.inputStream.reader(Charsets.UTF_8).use { input ->
                val bounded = object : Reader() {
                    var chars = 0L
                    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                        ensureActive()
                        if (System.nanoTime() > deadline) throw IOException("Metadata deadline exceeded")
                        val read = input.read(buffer, offset, length)
                        if (read > 0) chars += read
                        ensureActive()
                        if (chars > 32L * 1024 * 1024 || System.nanoTime() > deadline) throw IOException("Metadata limit exceeded")
                        return read
                    }
                    override fun close() = input.close()
                }
                return parse(bounded)
            }
        } finally { connection.disconnect() }
    }
}

data class XtreamUpdate(val account: String, val status: String, val timezone: String,
    val rows: List<XtreamChannelEntity>, val refreshed: Boolean) {
    private fun serverKey(origin: String): Triple<String, String, Int>? = runCatching {
        val uri = URI(origin)
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        Triple(scheme, uri.host.lowercase(Locale.ROOT),
            uri.port.takeIf { it >= 0 } ?: if (scheme == "https") 443 else 80)
    }.getOrNull()

    fun enrich(channels: List<ChannelEntity>, address: XtreamAddress): List<ChannelEntity> {
        val byId = rows.associateBy { it.streamId }
        val server = serverKey(address.origin)
        return channels.map { channel ->
            val stream = XtreamAddress.stream(channel.streamUrl)
            if (stream == null || server == null || serverKey(stream.origin) != server ||
                stream.user != address.user || stream.password != address.password) channel
            else {
                val metadata = byId[stream.streamId]
                val apiDays = metadata?.days ?: 0
                val useApi = apiDays > 0 && (!CatchupUrlBuilder.isSupported(channel) || apiDays > channel.catchupDays)
                channel.copy(
                    tvgId = channel.tvgId?.takeIf { it.isNotBlank() } ?: metadata?.epgId,
                    catchupType = if (useApi) "xc" else channel.catchupType,
                    catchupDays = if (useApi) apiDays else channel.catchupDays,
                    catchupSource = if (useApi) null else channel.catchupSource,
                    catchupTimezone = timezone,
                )
            }
        }
    }
}

class XtreamApi(private val reader: XtreamMetadataReader) {
    fun refresh(source: SourceEntity, address: XtreamAddress, previous: List<XtreamChannelEntity>): XtreamUpdate {
        val sameAccount = source.xtreamAccount == address.accountKey()
        var rows = if (sameAccount) previous else emptyList()
        var timezone = if (sameAccount) source.xtreamTimezone else "UTC"
        var refreshed = false
        fun error(e: Exception): String = if (e.message?.startsWith("HTTP ") == true) e.message!! else e.javaClass.simpleName
        val liveStatus = try {
            rows = reader.read(address.apiUrl(true), source.userAgent) { XtreamJson.channels(it, source.id) }
            refreshed = true
            "Lista API: OK"
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { "Lista API: błąd ${error(e)} (zachowano poprzednie dane)" }
        val zoneStatus = try {
            val zone = reader.read(address.apiUrl(), source.userAgent, XtreamJson::timezone)
            timezone = zone ?: "UTC"
            if (zone == null) "Strefa API: brak poprawnej strefy, UTC" else "Strefa API: OK"
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { "Strefa API: błąd ${error(e)} (zachowano $timezone)" }
        return XtreamUpdate(address.accountKey(), "$liveStatus; $zoneStatus", timezone, rows, refreshed)
    }
}
