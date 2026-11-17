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
import kotlin.math.abs
import kotlin.math.ln1p

data class SearchResults(
    val channels: List<ChannelEntity> = emptyList(),
    val programmes: List<SearchProgramme> = emptyList(),
)

object SearchText {
    private val marks = Regex("\\p{M}+")
    private val spaces = Regex("\\s+")
    private val wordBreaks = Regex("[^\\p{L}\\p{N}]+")

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(marks, "").lowercase(Locale.ROOT).replace('ł', 'l').replace('ø', 'o')
        .replace('đ', 'd').replace("ß", "ss").replace("æ", "ae").replace("œ", "oe")
        .trim().replace(spaces, " ")

    fun matches(value: String, normalizedQuery: String): Boolean = normalizedQuery.isNotEmpty() &&
        normalize(value).contains(normalizedQuery)

    fun quality(value: String, query: String): Int {
        val text = normalize(value)
        if (query.isEmpty()) return 0
        if (text.startsWith(query)) return 400
        var index = text.indexOf(query)
        val substring = index >= 0
        while (index >= 0) {
            if (index > 0 && !text[index - 1].isLetterOrDigit()) return 300
            index = text.indexOf(query, index + 1)
        }
        if (substring) return 200
        if (query.length in 4..32 && text.splitToSequence(wordBreaks).any { word ->
                (query.length - 1..query.length + 1).any { length ->
                    word.length >= length && oneEdit(query, word.take(length))
                }
            }) return 100
        return 0
    }

    private fun oneEdit(a: String, b: String): Boolean {
        if (abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                b.length > a.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }
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

class LocalSearch(private val dao: SearchDao,
    private val usage: suspend (Long) -> List<ChannelUsage> = { emptyList() }) {
    suspend fun search(sourceId: Long, query: String, now: Long,
        onChannels: (List<ChannelEntity>) -> Unit = {}): SearchResults = withContext(Dispatchers.IO) {
        val text = SearchText.normalize(query)
        val scores = usage(sourceId).filter { it.sourceId == sourceId }
            .associate { it.streamUrl to ln1p(it.scoreAt(now)).coerceAtMost(50.0) }
        val channels = Best<ChannelEntity>()
        var afterId = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.channels(sourceId, afterId, PAGE)
            for (channel in page) {
                currentCoroutineContext().ensureActive()
                val quality = if (text.isEmpty()) 1 else SearchText.quality(channel.name, text)
                if (quality > 0) channels.add(channel, quality + (scores[channel.streamUrl] ?: 0.0), channel.position.toLong())
            }
            if (page.size < PAGE) break
            afterId = page.last().id
        }
        val channelResults = channels.values()
        onChannels(channelResults)
        if (text.isEmpty()) return@withContext SearchResults(channelResults)
        val programmes = Best<SearchProgramme>()
        var programmeOrder = 0L
        for (past in listOf(false, true)) {
            var afterStart = if (past) Long.MAX_VALUE else Long.MIN_VALUE
            var afterChannel = 0L
            var afterProgramme = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = dao.programmes(sourceId, now, now + ChannelTimeline.DAY, past,
                    afterStart, afterChannel, afterProgramme, PAGE)
                for (programme in page) {
                    currentCoroutineContext().ensureActive()
                    val quality = SearchText.quality(programme.title, text)
                    if (quality > 0 && SearchWindow.includes(programme, now)) programmes.add(programme,
                        quality + (scores[programme.channel.streamUrl] ?: 0.0), programmeOrder)
                    programmeOrder++
                }
                if (page.size < PAGE) break
                val last = page.last()
                afterStart = last.start
                afterChannel = last.channel.id
                afterProgramme = last.programmeId
            }
        }
        SearchResults(channelResults, programmes.values())
    }

    private class Best<T> {
        private data class Row<T>(val value: T, val score: Double, val order: Long, val serial: Long)
        private val comparator = compareByDescending<Row<T>> { it.score }.thenBy { it.order }.thenBy { it.serial }
        private val rows = mutableListOf<Row<T>>()
        private var serial = 0L
        fun add(value: T, score: Double, order: Long) {
            val row = Row(value, score, order, serial++)
            val index = rows.binarySearch(row, comparator).let { if (it < 0) -it - 1 else it }
            if (index < LIMIT) {
                rows.add(index, row)
                if (rows.size > LIMIT) rows.removeAt(LIMIT)
            }
        }
        fun values(): List<T> = rows.map { it.value }
    }

    companion object {
        const val LIMIT = 50
        private const val PAGE = 200
    }
}
