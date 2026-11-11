package dev.firetvlite.iptv.data.epg

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class XmlTvProgramme(
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
    val category: String?,
    val year: Int? = null,
    val originalTitle: String? = null,
    val credits: String? = null,
    val episodeNum: String? = null,
    val categories: String = "",
)

object XmlTvParser {
    private val timePattern = Regex("^(\\d{14}|\\d{12})\\s*(Z|[+-]\\d{4}|[+-]\\d{2}:\\d{2})?$")
    private val timeFormat = DateTimeFormatter.ofPattern("uuuuMMddHHmmss", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)

    /** XMLTV specifies UTC when the timezone is omitted. */
    fun time(value: String?): Long? {
        val match = value?.trim()?.let { timePattern.matchEntire(it) } ?: return null
        return runCatching {
            val digits = match.groupValues[1].padEnd(14, '0')
            val offset = match.groupValues[2].ifEmpty { "Z" }
            LocalDateTime.parse(digits, timeFormat).toInstant(ZoneOffset.of(offset)).toEpochMilli()
        }.getOrNull()
    }

    suspend fun parse(
        input: InputStream,
        window: EpgWindow,
        onChannel: (id: String, names: List<String>) -> Unit,
        onProgramme: suspend (XmlTvProgramme) -> Unit,
    ) {
        val buffered = BufferedInputStream(input, 64 * 1024)
        buffered.mark(2)
        val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        (if (gzip) GZIPInputStream(buffered, 64 * 1024) else buffered).use { stream ->
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            if (parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) {
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            }
            parser.setInput(stream, null)
            if (parser.nextTag() != XmlPullParser.START_TAG || parser.name != "tv") {
                throw IOException("Not an XMLTV document")
            }
            var closed = false
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                currentCoroutineContext().ensureActive()
                when {
                    parser.eventType == XmlPullParser.START_TAG && parser.depth == 2 -> when (parser.name) {
                        "channel" -> {
                            val id = parser.getAttributeValue(null, "id").orEmpty().trim()
                            val names = ArrayList<String>()
                            val depth = parser.depth
                            while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
                                if (parser.eventType == XmlPullParser.END_DOCUMENT) throw IOException("Truncated channel")
                                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "display-name") {
                                    names.add(text(parser, 2_048))
                                }
                            }
                            if (id.isNotEmpty()) onChannel(id, names)
                        }
                        "programme" -> programme(parser, window)?.let { onProgramme(it) }
                        else -> skip(parser)
                    }
                    parser.eventType == XmlPullParser.END_TAG && parser.depth == 1 -> closed = true
                }
            }
            if (!closed) throw IOException("Truncated XMLTV document")
        }
    }

    private fun programme(parser: XmlPullParser, window: EpgWindow): XmlTvProgramme? {
        val channel = parser.getAttributeValue(null, "channel").orEmpty().trim()
        val start = time(parser.getAttributeValue(null, "start"))
        val stop = time(parser.getAttributeValue(null, "stop"))
        if (channel.isEmpty() || start == null || stop == null || !window.contains(start, stop)) {
            skip(parser)
            return null
        }
        var title: String? = null
        var polishTitle = false
        var description: String? = null
        var category: String? = null
        var year: Int? = null
        var originalTitle: String? = null
        var originalLanguage: String? = null
        var credits: String? = null
        var episodeNum: String? = null
        val categories = ArrayList<String>()
        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.END_DOCUMENT) throw IOException("Truncated programme")
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "title" -> {
                    val lang = parser.getAttributeValue(null, "lang").orEmpty().lowercase(Locale.ROOT)
                    val polish = lang == "pl" || lang.startsWith("pl-")
                    val value = text(parser, 2_048)
                    if (lang == "xx" || (lang.startsWith("en") && originalLanguage != "xx")) {
                        originalTitle = value
                        originalLanguage = lang
                    }
                    if (title == null || (polish && !polishTitle)) {
                        title = value
                        polishTitle = polish
                    }
                }
                "desc" -> if (description == null) description = text(parser, 65_536) else skip(parser)
                "category" -> {
                    val value = text(parser, 512)
                    if (category == null) category = value
                    if (categories.size < 100) categories.add(value)
                }
                "date" -> text(parser, 32).let { if (it.matches(Regex("[0-9]{4}"))) year = it.toInt() }
                "credits" -> credits = readCredits(parser)
                "episode-num" -> episodeNum = text(parser, 512)
                else -> skip(parser)
            }
        }
        return XmlTvProgramme(channel, start, stop, title.orEmpty(), description, category,
            year, originalTitle, credits, episodeNum, categories.joinToString("\n"))
    }

    private fun text(parser: XmlPullParser, limit: Int): String {
        val depth = parser.depth
        val value = StringBuilder()
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.END_DOCUMENT) throw IOException("Truncated text")
            if (parser.eventType == XmlPullParser.TEXT && value.length < limit) {
                value.append(parser.text.take(limit - value.length))
            }
        }
        return value.toString().trim()
    }

    private fun readCredits(parser: XmlPullParser): String {
        val depth = parser.depth
        val entries = ArrayList<String>()
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.END_DOCUMENT) throw IOException("Truncated credits")
            if (parser.eventType == XmlPullParser.START_TAG) {
                val role = parser.name
                val name = text(parser, 2_048)
                if (entries.size < 100) entries.add("$role: $name")
            }
        }
        return entries.joinToString("\n")
    }

    private fun skip(parser: XmlPullParser) {
        val depth = parser.depth
        while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (parser.eventType == XmlPullParser.END_DOCUMENT) throw IOException("Truncated element")
        }
    }
}
