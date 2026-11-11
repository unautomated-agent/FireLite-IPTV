package dev.firetvlite.iptv.data.playlist

import androidx.room.withTransaction
import dev.firetvlite.iptv.BuildConfig
import dev.firetvlite.iptv.data.db.ChannelEntity
import dev.firetvlite.iptv.data.db.FireLiteDatabase
import dev.firetvlite.iptv.data.db.SourceEntity
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The download worked, but it contained no channels (wrong address, or not an M3U file). */
class EmptyPlaylistException : IOException("The playlist contains no channels")

/**
 * Downloads a playlist and replaces the channels of its source in one database transaction,
 * so a failed import never leaves a half-empty list behind.
 */
class PlaylistImporter(
    private val database: FireLiteDatabase,
    private val cacheDir: File,
) {

    /** @return the number of imported channels. */
    @Throws(IOException::class)
    suspend fun import(source: SourceEntity): Int = withContext(Dispatchers.IO) {
        val file = File.createTempFile("playlist-${source.id}-", ".m3u", cacheDir)
        try {
            download(source.url, source.userAgent, file)
            val context = currentCoroutineContext()
            context.ensureActive()
            val channels = ArrayList<ChannelEntity>()
            val groups = HashMap<String, String>()
            var epgUrls = emptyList<String>()
            var playlistMetadata = M3uParser.Metadata()
            file.inputStream().reader(Charsets.UTF_8).use { reader ->
                M3uParser.parse(reader, onEpgUrls = { epgUrls = it }, onMetadata = { playlistMetadata = it }) { parsed ->
                    context.ensureActive()
                    channels.add(
                        ChannelEntity(
                            sourceId = source.id,
                            position = channels.size,
                            name = parsed.name,
                            // One shared String per group name instead of thousands of copies.
                            groupName = groups.getOrPut(parsed.group) { parsed.group },
                            streamUrl = parsed.url,
                            logoUrl = parsed.logoUrl,
                            tvgId = parsed.tvgId,
                            tvgName = parsed.tvgName,
                            catchupType = parsed.catchupType,
                            catchupSource = parsed.catchupSource,
                            catchupDays = parsed.catchupDays,
                        ),
                    )
                }
            }
            if (channels.isEmpty()) throw EmptyPlaylistException()
            val address = XtreamAddress.playlist(source.url)
                ?: channels.firstNotNullOfOrNull { XtreamAddress.stream(it.streamUrl) }
            if (epgUrls.isEmpty()) {
                epgUrls = listOfNotNull(address?.epgUrl())
            }
            val xtream = address?.let {
                XtreamApi(HttpXtreamMetadataReader { context.ensureActive() })
                    .refresh(source, it, database.xtreamDao().channels(source.id))
            }
            val enriched = if (xtream != null && address != null) xtream.enrich(channels, address) else channels
            context.ensureActive()

            database.withTransaction {
                val dao = database.channelDao()
                val retained = PlaylistIdentity.retain(enriched, dao.identities(source.id))
                dao.deleteForSource(source.id)
                retained.chunked(1_000).forEach { dao.insertAll(it) }
                val metadata = database.xtreamDao()
                if (xtream == null) {
                    metadata.clear(source.id)
                    metadata.publish(source.id, "", "", "UTC", 0)
                } else {
                    if (xtream.refreshed || source.xtreamAccount != xtream.account) {
                        metadata.clear(source.id)
                        xtream.rows.chunked(1_000).forEach { metadata.insert(it) }
                    }
                    metadata.publish(source.id, xtream.account, xtream.status, xtream.timezone,
                        xtream.rows.count { it.archived })
                }
                database.sourceDao().markImported(source.id, System.currentTimeMillis(), BuildConfig.VERSION_CODE,
                    PARSER_REVISION, playlistMetadata.catchupChannels, source.userAgent, playlistMetadata.deviceType)
                database.sourceDao().updateDiscoveredEpg(source.id, epgUrls.joinToString("\n"))
            }
            channels.size
        } finally {
            file.delete()
        }
    }

    /** Streams the answer of the server into [target], following redirects. Big lists never sit in memory. */
    @Throws(IOException::class)
    private suspend fun download(urlText: String, userAgent: String, target: File) {
        var current = urlText
        repeat(MAX_REDIRECTS + 1) {
            currentCoroutineContext().ensureActive()
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", userAgent)
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Redirect without a target (HTTP $code)")
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                if (code != HttpURLConnection.HTTP_OK) throw IOException("The server answered HTTP $code")

                var total = 0L
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_BYTES) throw IOException("The playlist is larger than 200 MB")
                            output.write(buffer, 0, read)
                        }
                    }
                }
                return
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    companion object {
        const val PARSER_REVISION = 1
        const val MAX_BYTES = 200L * 1024 * 1024
        const val MAX_REDIRECTS = 6
    }
}
