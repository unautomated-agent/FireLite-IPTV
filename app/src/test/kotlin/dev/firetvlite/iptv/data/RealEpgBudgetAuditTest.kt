package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.db.ChannelEntity
import dev.firetvlite.iptv.data.db.FilmRatingEntity
import dev.firetvlite.iptv.data.db.ProgrammeEntity
import dev.firetvlite.iptv.data.epg.EpgWindow
import dev.firetvlite.iptv.data.epg.XmlTvParser
import dev.firetvlite.iptv.data.film.*
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class RealEpgBudgetAuditTest {
    private fun beforeQueries(p: ProgrammeEntity): List<Pair<String, Int>> {
        val year = p.year ?: return emptyList()
        val titles = listOfNotNull(p.originalTitle?.takeIf(String::isNotBlank), p.title.takeIf(String::isNotBlank))
            .distinctBy(Films::normalized)
        return listOf(year, year - 1, year + 1).flatMap { y -> titles.map { it to y } }
    }

    private fun beforeCandidates(showings: List<FilmShowing>, now: Long) = showings
        .filter { Films.candidate(it.programme) && ((it.programme.stop > now && it.programme.start < now + 2 * Films.DAY) ||
            Films.archived(it.channel, it.programme, now)) }
        .sortedBy { if (it.programme.stop > now) 0 else 1 }.map { it.programme }.distinctBy(Films::key)

    private data class Coverage(val requests: Int, val started: Set<String>, val completed: Set<String>)

    private fun beforeCoverage(candidates: List<ProgrammeEntity>, hit: Boolean): Coverage {
        var requests = 0
        val started = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        for (p in candidates) {
            val cost = if (hit) 1 else beforeQueries(p).size
            if (requests == 400) break
            started.add(Films.key(p))
            if (requests + cost > 400) { requests = 400; break }
            requests += cost
            completed.add(Films.key(p))
        }
        return Coverage(requests, started, completed)
    }

    private suspend fun afterCoverage(candidates: List<ProgrammeEntity>, now: Long, hit: Boolean): Coverage {
        var requests = 0
        val started = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        val rows = mutableMapOf<String, FilmRatingEntity>()
        val store = object : FilmStore {
            override suspend fun cached(key: String) = rows[key]
            override suspend fun save(rating: FilmRatingEntity) { rows[rating.cacheKey] = rating }
            override suspend fun reserve(day: Long): Boolean = (requests < 400).also { if (it) requests++ }
        }
        for (p in candidates) {
            val lookup = FilmLookup(OmdbClient { _, _, year ->
                started.add(Films.key(p))
                if (hit) OmdbResult.Found(OmdbMovie("movie", year, 7.0, 100, "tt123")) else OmdbResult.Missing
            }, store, { now }) {}
            if (lookup.lookup(p, "offline-fixture", now) != null) completed.add(Films.key(p))
            if (lookup.lastError == "LOCAL_BUDGET") break
        }
        return Coverage(requests, started, completed)
    }

    @Test fun auditMatchedPlaylistsAndBudgetWithProductionLookupAndOfflineResponses() = runBlocking {
        val path = System.getenv("FIRELITE_REAL_EPG")
        assumeTrue("Set FIRELITE_REAL_EPG to a downloaded XMLTV gzip", path != null)
        val now = Instant.parse(System.getenv("FIRELITE_EPG_AUDIT_NOW") ?: Instant.now().toString()).toEpochMilli()
        val channels = linkedMapOf<String, ChannelEntity>()
        val all = mutableListOf<FilmShowing>()
        File(path!!).inputStream().use { input ->
            XmlTvParser.parse(input, EpgWindow(now - 7 * Films.DAY, now + 2 * Films.DAY), { id, _ ->
                channels[id] = ChannelEntity(id = channels.size.toLong() + 1, sourceId = 1, position = channels.size,
                    name = id, groupName = "", streamUrl = "unused", logoUrl = null, tvgId = id,
                    catchupType = "shift", catchupSource = null, catchupDays = 7)
            }) { p ->
                val programme = ProgrammeEntity(sourceId = 1, generation = "audit", epgKey = p.channelId,
                    start = p.start, stop = p.stop, title = p.title, description = p.description, category = p.category,
                    year = p.year, originalTitle = p.originalTitle, episodeNum = p.episodeNum, categories = p.categories)
                if (Films.candidate(programme)) channels[p.channelId]?.let { all.add(FilmShowing(it, programme, null)) }
            }
        }
        val ordered = all.sortedWith(compareBy<FilmShowing> { it.programme.start }.thenBy { it.channel.id })
        val core = setOf("TVP1.pl", "Polsat.pl", "TVN.pl", "TVN7.pl", "KinoPolska.pl", "CANAL+1.pl", "HBO.pl")
        assertTrue(channels.keys.containsAll(core))
        val representative = (core + channels.keys.sorted().filter { it !in core }.take(229 - core.size)).toSet()
        val report = buildString {
            appendLine("Window: ${Instant.ofEpochMilli(now)} +48h; archive: available feed history up to 7 days")
            appendLine("Assumptions: cold cache, all selected channels support 7-day shift catch-up; synthetic responses, no network")
            appendLine("Synthetic229 = seven core IDs plus lexicographically first 222 other feed IDs; NOT user's actual playlist")
            appendLine("Feed channels=${channels.size}; film showings=${all.size}")
            for ((name, ids) in listOf("core7" to core, "synthetic229" to representative, "all447-counterfactual" to channels.keys)) {
                val visible = ordered.filter { it.channel.tvgId in ids }
                val before = beforeCandidates(visible, now)
                val after = ratingCandidates(visible, now)
                val tabs = (0..2).map { tab -> rankedFilms(visible, now, tab).map { Films.key(it.programme) }.toSet() }
                assertEquals(tabs.flatten().toSet(), after.map(Films::key).toSet())
                assertEquals(before.map(Films::key).toSet(), after.map(Films::key).toSet())
                appendLine("$name: selected=${ids.size}; unique=${after.size}; per-tab unique=${tabs.map { it.size }}")
                for (hit in listOf(true, false)) {
                    val old = beforeCoverage(before, hit)
                    val new = afterCoverage(after, now, hit)
                    appendLine("  ${if (hit) "exact-first-hit" else "all-miss"}: before requests=${old.requests} queried=${old.started.size} completed=${old.completed.size} per-tab queried=${tabs.map { (it intersect old.started).size }}; after requests=${new.requests} queried=${new.started.size} completed=${new.completed.size} per-tab queried=${tabs.map { (it intersect new.started).size }}")
                }
                for (id in core.sorted()) {
                    val channelKeys = visible.filter { it.channel.tvgId == id }.map { Films.key(it.programme) }.toSet()
                    for ((label, candidates) in listOf("before" to before, "after" to after)) {
                        val first = candidates.indexOfFirst { Films.key(it) in channelKeys }
                        val cost = if (first < 0) null else candidates.take(first).sumOf {
                            if (label == "before") beforeQueries(it).size else Films.queries(it).size
                        }
                        appendLine("  $id $label: first benefiting index=${if (first < 0) "none" else first + 1}; requests before first best=${if (first < 0) "none" else first}, all-miss=$cost")
                    }
                }
            }
            appendLine("synthetic229 IDs=${representative.sorted().joinToString(",")}")
        }
        File(System.getenv("FIRELITE_BUDGET_AUDIT_OUTPUT") ?: "build/real-epg-budget-audit.txt").writeText(report)
        println(report)
        assertTrue(all.isNotEmpty())
    }
}
