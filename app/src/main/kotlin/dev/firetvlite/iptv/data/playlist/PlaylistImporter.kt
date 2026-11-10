package dev.firetvlite.iptv.data.playlist

import androidx.room.withTransaction
import dev.firetvlite.iptv.data.db.ChannelEntity
import dev.firetvlite.iptv.data.db.FireLiteDatabase
import dev.firetvlite.iptv.data.db.SourceEntity
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
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
        val file = File(cacheDir, "playlist-${source.id}.m3u")
        download(source.url, source.userAgent, file)

        val channels = ArrayList<ChannelEntity>()
        val groups = HashMap<String, String>()
        file.inputStream().reader(Charsets.UTF_8).use { reader ->
            M3uParser.parse(reader) { parsed ->
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
                        catchupType = parsed.catchupType,
                        catchupSource = parsed.catchupSource,
                        catchupDays = parsed.catchupDays,
                    ),
                )
            }
        }
        file.delete()
        if (channels.isEmpty()) throw EmptyPlaylistException()

        database.withTransaction {
            val dao = database.channelDao()
            dao.deleteForSource(source.id)
            channels.chunked(1_000).forEach { dao.insertAll(it) }
            database.sourceDao().markRefreshed(source.id, System.currentTimeMillis())
        }
        channels.size
    }

    /** Streams the answer of the server into [target], following redirects. Big lists never sit in memory. */
    @Throws(IOException::class)
    private fun download(urlText: String, userAgent: String, target: File) {
        var current = urlText
        repeat(MAX_REDIRECTS + 1) {
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

    private companion object {
        const val MAX_BYTES = 200L * 1024 * 1024
        const val MAX_REDIRECTS = 6
    }
}
