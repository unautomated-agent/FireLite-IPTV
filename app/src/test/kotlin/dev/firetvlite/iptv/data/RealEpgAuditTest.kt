package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.db.ChannelEntity
import dev.firetvlite.iptv.data.db.ProgrammeEntity
import dev.firetvlite.iptv.data.epg.EpgWindow
import dev.firetvlite.iptv.data.epg.XmlTvParser
import dev.firetvlite.iptv.data.film.FilmShowing
import dev.firetvlite.iptv.data.film.Films
import dev.firetvlite.iptv.data.film.ratingCandidates
import java.io.File
import dev.firetvlite.iptv.data.film.OmdbHttpClient
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RealEpgAuditTest {
    @Test fun auditPublicFeedUsingProductionParserDetectionAndQueries() = runBlocking {
        val path = System.getenv("FIRELITE_REAL_EPG")
        assumeTrue("Set FIRELITE_REAL_EPG to a downloaded XMLTV gzip", path != null)
        val now = Instant.parse(System.getenv("FIRELITE_EPG_AUDIT_NOW") ?: Instant.now().toString()).toEpochMilli()
        val showings = mutableListOf<FilmShowing>()
        var programmes = 0
        var originalTitles = 0
        File(path!!).inputStream().use { input ->
            XmlTvParser.parse(input, EpgWindow(now, now + 2 * Films.DAY), { _, _ -> }) { p ->
                programmes++
                val programme = ProgrammeEntity(sourceId = 1, generation = "audit", epgKey = p.channelId,
                    start = p.start, stop = p.stop, title = p.title, description = p.description, category = p.category,
                    year = p.year, originalTitle = p.originalTitle, episodeNum = p.episodeNum, categories = p.categories)
                if (Films.candidate(programme)) {
                    if (!p.originalTitle.isNullOrBlank()) originalTitles++
                    val channel = ChannelEntity(id = showings.size.toLong() + 1, sourceId = 1, position = 0,
                        name = p.channelId, groupName = "", streamUrl = "unused", logoUrl = null, tvgId = p.channelId,
                        catchupType = null, catchupSource = null, catchupDays = 0)
                    showings.add(FilmShowing(channel, programme, null))
                }
            }
        }
        val candidates = ratingCandidates(showings, now)
        val report = buildString {
            appendLine("Window UTC: ${Instant.ofEpochMilli(now)} to ${Instant.ofEpochMilli(now + 2 * Films.DAY)}")
            appendLine("Programmes=$programmes; film showings=${showings.size}; showings with original=$originalTitles")
            appendLine("Unique enrichment candidates=${candidates.size}; with original=${candidates.count { !it.originalTitle.isNullOrBlank() }}")
            candidates.forEachIndexed { index, p ->
                appendLine("${index + 1}. ${p.title} | original=${p.originalTitle ?: "—"} | year=${p.year} | channel=${p.epgKey}")
                appendLine(Films.queries(p).joinToString(" ; ") { (title, year) ->
                    OmdbHttpClient.movieQuery(title, year)
                })
            }
        }
        File(System.getenv("FIRELITE_EPG_AUDIT_OUTPUT") ?: "build/real-epg-audit.txt").writeText(report)
        println(report.lineSequence().take(33).joinToString("\n"))
        assertTrue("Public feed had no film candidates in the requested window", candidates.isNotEmpty())
    }
}
