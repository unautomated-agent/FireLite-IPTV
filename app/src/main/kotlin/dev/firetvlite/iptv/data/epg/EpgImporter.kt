package dev.firetvlite.iptv.data.epg

import androidx.room.withTransaction
import dev.firetvlite.iptv.data.db.ChannelEpgEntity
import dev.firetvlite.iptv.data.db.FireLiteDatabase
import dev.firetvlite.iptv.data.db.ProgrammeEntity
import dev.firetvlite.iptv.data.db.SourceEntity
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class EpgImporter(private val database: FireLiteDatabase) {
    suspend fun import(source: SourceEntity, urls: List<String>): Int = withContext(Dispatchers.IO) {
        val dao = database.epgDao()
        val generation = UUID.randomUUID().toString()
        val window = EpgWindow.around(System.currentTimeMillis(), dao.maxCatchupDays(source.id))
        val xmlChannels = LinkedHashMap<String, XmlTvChannel>()
        val batch = ArrayList<ProgrammeEntity>(500)
        var count = 0
        var published = false
        try {
            database.withTransaction {
                dao.deleteOtherMappings(source.id, source.epgGeneration)
                dao.deleteOtherProgrammes(source.id, source.epgGeneration)
            }
            urls.distinct().forEach { url ->
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", source.userAgent)
                connection.setRequestProperty("Accept-Encoding", "identity")
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        throw IOException("EPG HTTP ${connection.responseCode}")
                    }
                    connection.inputStream.use { input ->
                        XmlTvParser.parse(
                            input, window,
                            onChannel = { id, names ->
                                val key = EpgMatching.id(id)
                                xmlChannels[key] = XmlTvChannel(key, id,
                                    (xmlChannels[key]?.displayNames.orEmpty() + names).distinct())
                            },
                            onProgramme = { programme ->
                                val key = EpgMatching.id(programme.channelId)
                                xmlChannels.putIfAbsent(key, XmlTvChannel(key, programme.channelId, emptyList()))
                                batch.add(ProgrammeEntity(
                                    sourceId = source.id, generation = generation, epgKey = key,
                                    start = programme.start, stop = programme.stop, title = programme.title,
                                    description = programme.description, category = programme.category,
                                    year = programme.year, originalTitle = programme.originalTitle,
                                    credits = programme.credits, episodeNum = programme.episodeNum,
                                    categories = programme.categories,
                                ))
                                if (batch.size == 500) {
                                    count += dao.insertProgrammes(batch).count { it != -1L }
                                    batch.clear()
                                }
                            },
                        )
                    }
                } finally {
                    connection.disconnect()
                }
            }
            if (batch.isNotEmpty()) count += dao.insertProgrammes(batch).count { it != -1L }
            val index = EpgMatching.Index(xmlChannels.values.toList())
            val mappings = dao.channels(source.id).mapNotNull { channel ->
                index.match(channel.tvgId, channel.name, channel.tvgName)?.let { key ->
                    ChannelEpgEntity(source.id, generation, channel.streamUrl, key)
                }
            }.distinctBy { it.streamUrl }
            mappings.chunked(500).forEach { dao.insertMappings(it) }
            currentCoroutineContext().ensureActive()
            database.withTransaction {
                val current = database.sourceDao().byId(source.id)
                if (current == null || current.url != source.url || current.userAgent != source.userAgent ||
                    current.epgUrl != source.epgUrl || current.discoveredEpgUrls != source.discoveredEpgUrls ||
                    current.refreshedAt != source.refreshedAt
                ) throw CancellationException("Playlist changed during EPG import")
                database.sourceDao().publishEpg(source.id, generation, System.currentTimeMillis(), count)
                dao.deleteOtherMappings(source.id, generation)
                dao.deleteOtherProgrammes(source.id, generation)
            }
            published = true
            count
        } finally {
            if (!published) withContext(NonCancellable) {
                database.withTransaction {
                    if (database.sourceDao().byId(source.id)?.epgGeneration != generation) {
                        dao.deleteMappings(source.id, generation)
                        dao.deleteProgrammes(source.id, generation)
                    }
                }
            }
        }
    }
}
