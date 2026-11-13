package dev.firetvlite.iptv.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class HealthPlaylist(val variant: String? = null, val segments: Int = 0)

internal class ChannelHealthProbe(private val client: OkHttpClient = PlaybackHttp.client, private val timeoutMs: Long = 15_000,
    private val parse: (String, ByteArray) -> HealthPlaylist = { url, bytes ->
        when (val playlist = HlsPlaylistParser().parse(Uri.parse(url), bytes.inputStream())) {
            is HlsMultivariantPlaylist -> HealthPlaylist(variant = playlist.variants.firstOrNull()?.url?.toString()
                ?: throw IOException("empty playlist"))
            is HlsMediaPlaylist -> HealthPlaylist(segments = playlist.segments.size)
            else -> throw IOException("invalid playlist")
        }
    }) {
    fun check(channel: StreamRequest, onCall: (Call?) -> Unit, ensureActive: () -> Unit): String? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        fun get(url: String, playlistRequired: Boolean): HealthPlaylist? {
            ensureActive()
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remaining <= 0) throw SocketTimeoutException()
            val transport = client.newBuilder().connectTimeout(timeoutMs, TimeUnit.MILLISECONDS).readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(remaining, TimeUnit.MILLISECONDS).build()
            val builder = Request.Builder().url(url)
            channel.headers.forEach { (name, value) -> builder.header(name, value) }
            builder.header("Connection", "close")
            val call = PlaybackHttp.calls(channel.url, C.DATA_TYPE_UNKNOWN, transport, channel.headers, channel.userAgent) {}
                .newCall(builder.build())
            onCall(call)
            try {
                ensureActive()
                return call.execute().use { response ->
                    if (response.code != 200) throw ProbeFailure("HTTP ${response.code}")
                    val input = response.body?.byteStream() ?: throw ProbeFailure("empty response")
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (bytes.size() < 4096) {
                        val read = input.read(buffer, 0, 4096 - bytes.size())
                        if (read < 0) break
                        bytes.write(buffer, 0, read)
                    }
                    if (bytes.size() == 0) throw ProbeFailure("empty response")
                    val hls = playlistRequired || response.request.url.encodedPath.endsWith(".m3u8", true) ||
                        response.header("Content-Type").orEmpty().contains("mpegurl", true) ||
                        bytes.toString("UTF-8").trimStart('\uFEFF', ' ', '\n', '\r').startsWith("#EXTM3U")
                    if (!hls) return@use null
                    while (bytes.size() <= 262_144) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        bytes.write(buffer, 0, read)
                    }
                    if (bytes.size() > 262_144) throw ProbeFailure("playlist too large")
                    try { parse(response.request.url.toString(), bytes.toByteArray()) }
                    catch (_: IOException) { throw ProbeFailure("invalid playlist") }
                }
            } finally {
                call.cancel()
                onCall(null)
            }
        }
        return try {
            val first = get(channel.url, false)
            val media = first?.variant?.let { get(it, true) } ?: first
            if (media != null && (media.variant != null || media.segments < 1)) "empty media playlist" else null
        } catch (error: ProbeFailure) { error.message }
        catch (_: SocketTimeoutException) { "no response 15s" }
        catch (_: java.io.InterruptedIOException) { "no response 15s" }
        catch (_: IOException) { "network error" }
        catch (_: IllegalArgumentException) { "invalid URL/playlist" }
    }

    private class ProbeFailure(reason: String) : IOException(reason)
}

data class ChannelCheckProgress(val running: Boolean = false, val scope: String = "", val total: Int = 0,
    val checked: Int = 0, val dead: Int = 0, val healthy: Int = 0, val current: String = "", val complete: Boolean = false,
    val error: Boolean = false)
