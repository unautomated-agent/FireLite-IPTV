package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.db.ChannelEntity
import dev.firetvlite.iptv.data.db.SearchDao
import dev.firetvlite.iptv.data.db.SearchProgramme
import dev.firetvlite.iptv.playback.CatchupUrlBuilder
import dev.firetvlite.iptv.playback.ChannelTimeline
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class SearchResults(
    val channels: List<ChannelEntity> = emptyList(),
    val programmes: List<SearchProgramme> = emptyList(),
)

object SearchText {
    private val marks = Regex("\\p{M}+")
    private val spaces = Regex("\\s+")

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(marks, "").lowercase(Locale.ROOT).replace('ł', 'l').replace('ø', 'o')
        .replace('đ', 'd').replace("ß", "ss").replace("æ", "ae").replace("œ", "oe")
        .trim().replace(spaces, " ")

    fun matches(value: String, normalizedQuery: String): Boolean = normalizedQuery.isNotEmpty() &&
        normalize(value).contains(normalizedQuery)
}

object SearchWindow {
    fun includes(programme: SearchProgramme, now: Long): Boolean = with(programme) {
        stop > start && start < now + ChannelTimeline.DAY &&
            (stop > now || (CatchupUrlBuilder.isSupported(channel) &&
                start >= now - channel.catchupDays.toLong() * ChannelTimeline.DAY))
    }

    fun archiveStart(programme: SearchProgramme, now: Long): Long? =
        programme.start.takeIf { programme.stop <= now && includes(programme, now) }
}

class LocalSearch(private val dao: SearchDao) {
    suspend fun search(sourceId: Long, query: String, now: Long): SearchResults = withContext(Dispatchers.IO) {
        val text = SearchText.normalize(query)
        if (text.isEmpty()) return@withContext SearchResults()
        val channels = mutableListOf<ChannelEntity>()
        var afterId = 0L
        while (channels.size < LIMIT) {
            currentCoroutineContext().ensureActive()
            val page = dao.channels(sourceId, afterId, PAGE)
            for (channel in page) {
                if (SearchText.matches(channel.name, text)) channels += channel
                if (channels.size == LIMIT) break
            }
            if (page.size < PAGE) break
            afterId = page.last().id
        }
        val programmes = mutableListOf<SearchProgramme>()
        for (past in listOf(false, true)) {
            var afterStart = if (past) Long.MAX_VALUE else Long.MIN_VALUE
            var afterChannel = 0L
            var afterProgramme = 0L
            while (programmes.size < LIMIT) {
                currentCoroutineContext().ensureActive()
                val page = dao.programmes(sourceId, now, now + ChannelTimeline.DAY, past,
                    afterStart, afterChannel, afterProgramme, PAGE)
                for (programme in page) {
                    if (SearchWindow.includes(programme, now) && SearchText.matches(programme.title, text)) programmes += programme
                    if (programmes.size == LIMIT) break
                }
                if (page.size < PAGE) break
                val last = page.last()
                afterStart = last.start
                afterChannel = last.channel.id
                afterProgramme = last.programmeId
            }
        }
        SearchResults(channels.sortedBy { it.position }, programmes)
    }

    companion object {
        const val LIMIT = 50
        private const val PAGE = 200
    }
}
