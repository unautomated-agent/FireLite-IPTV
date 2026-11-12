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
        val previous = mutableListOf<ProgrammeEntity>()
        var programmes = 0
        var originalTitles = 0
        File(path!!).inputStream().use { input ->
            XmlTvParser.parse(input, EpgWindow(now, now + 2 * Films.DAY), { _, _ -> }) { p ->
                programmes++
                val programme = ProgrammeEntity(sourceId = 1, generation = "audit", epgKey = p.channelId,
                    start = p.start, stop = p.stop, title = p.title, description = p.description, category = p.category,
                    year = p.year, originalTitle = p.originalTitle, episodeNum = p.episodeNum, categories = p.categories)
                if (programme.stop - programme.start in 60 * 60_000L..240 * 60_000L &&
                    Films.candidate(programme.copy(stop = programme.start + 120 * 60_000L))) previous.add(programme)
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
        val future = ratingCandidates(showings, now, futureWindow = true)
        val allCandidates = (candidates + future).distinctBy(Films::key)
        val previousCandidates = previous.distinctBy(Films::key)
        val report = buildString {
            appendLine("Window UTC: ${Instant.ofEpochMilli(now)} to ${Instant.ofEpochMilli(now + 2 * Films.DAY)}")
            appendLine("Programmes=$programmes; film showings=${showings.size}; showings with original=$originalTitles")
            appendLine("Background candidates=${candidates.size}; max requests=${candidates.sumOf { Films.queries(it).size }}")
            appendLine("On-demand 24–48h candidates=${future.size}; standalone max requests=${future.sumOf { Films.queries(it).size }}")
            appendLine("Duration filter before/after (both windows): showings=${previous.size}/${showings.size}; unique=${previousCandidates.size}/${allCandidates.size}; maximum exact-year requests=${previousCandidates.sumOf { Films.queries(it).size }}/${allCandidates.sumOf { Films.queries(it).size }}")
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
        assertTrue(allCandidates.all { p -> Films.queries(p).size <= 2 && Films.queries(p).all { it.second == p.year } })
    }
}
