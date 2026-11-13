package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.db.ProgrammeEntity
import dev.firetvlite.iptv.data.epg.EpgWindow
import dev.firetvlite.iptv.data.epg.XmlTvParser
import dev.firetvlite.iptv.data.film.*
import java.io.File
import java.time.Instant
import java.util.Base64
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class RealEpgMissAuditTest {
    @Test fun characterizeFrozenCandidatesWithProductionParserAndLookupQueriesOffline(): Unit = runBlocking {
        val path = System.getenv("FIRELITE_REAL_EPG")
        assumeTrue("Set FIRELITE_REAL_EPG to the frozen XMLTV gzip", path != null)
        val now = Instant.parse(System.getenv("FIRELITE_EPG_AUDIT_NOW") ?: "2026-11-12T13:02:08Z").toEpochMilli()
        val programmes = mutableListOf<ProgrammeEntity>()
        File(path!!).inputStream().use { input ->
            XmlTvParser.parse(input, EpgWindow(now - 7 * Films.DAY, now + 2 * Films.DAY), { _, _ -> }) { p ->
                programmes += ProgrammeEntity(sourceId = 1, generation = "audit", epgKey = p.channelId, start = p.start,
                    stop = p.stop, title = p.title, description = null, category = p.category, categories = p.categories,
                    originalTitle = p.originalTitle, year = p.year, episodeNum = p.episodeNum)
            }
        }
        val datasets = mutableListOf("XMLTV (production parser)" to programmes.toList())
        System.getenv("FIRELITE_MISS_AUDIT_METADATA")?.let { metadata ->
            val rows = File(metadata).readLines().map { line ->
                val v = line.split('\t').map { if (it == "-") null else String(Base64.getDecoder().decode(it), Charsets.UTF_8) }
                ProgrammeEntity(sourceId = 1, generation = "audit", epgKey = v[0]!!, start = v[1]!!.toLong(),
                    stop = v[2]!!.toLong(), title = v[3]!!, description = null, category = v[4], year = v[5]?.toInt(),
                    originalTitle = v[6], episodeNum = v[7], categories = v[8].orEmpty())
            }
            datasets += "Performance DB metadata export" to rows
        }
        val reports = datasets.map { (name, allRows) ->
            val window = allRows.filter { it.start < now + 2 * Films.DAY && it.stop > now - 7 * Films.DAY }
            val before = window.filter(Films::candidateMetadata)
            val after = before.filter(Films::candidate)
            val unique = before.distinctBy(Films::key)
            val afterKeys = after.map(Films::key).toSet()
            val removed = unique.filter { Films.key(it) !in afterKeys }
            val report = buildString {
                appendLine("$name; UTC now=${Instant.ofEpochMilli(now)}; window=-7d/+48h; all feed channels assumed 7-day archive; not user's playlist")
                appendLine("Dataset rows=${allRows.size}; channels=${allRows.map { it.epgKey }.distinct().size}; window programmes=${window.size}")
                appendLine("Before showings=${before.size}; unique=${unique.size}; after showings=${after.size}; unique=${afterKeys.size}")
                appendLine("Cold-cache saved candidates=${removed.size}; maximum saved exact-year requests=${removed.sumOf { Films.queries(it).size }}; warm-cache savings can be zero")
                appendLine("Maximum baseline title queries=${unique.sumOf { Films.queries(it).size }}; two-query candidates=${unique.count { Films.queries(it).size == 2 }}")
                OmdbMissType.entries.forEach { type -> appendLine("feature $type=${unique.count { type in OmdbMissAnalysis.features(it) }} (overlapping)") }
                appendLine("Missing year and original already excluded=${window.count { it.year == null && it.originalTitle.isNullOrBlank() }}")
                appendLine("Already excluded series/episodes are not OMDb requests; no live OMDb calls or observed miss labels in fixture")
                appendLine("Removed reasons=${before.filter { !Films.candidate(it) }.groupingBy { OmdbMissAnalysis.skipReason(it) }.eachCount()}")
                appendLine("Candidate categories (normalized; top 3)=" + unique.flatMap { OmdbMissAnalysis.categories(it).map(Films::normalized) }
                    .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3))
            }
            assertTrue(afterKeys.all { key -> unique.any { Films.key(it) == key } })
            assertTrue(after.all { Films.queries(it).isNotEmpty() })
            report
        }.joinToString("\n")
        println(reports)
        System.getenv("FIRELITE_MISS_AUDIT_REPORT")?.let { File(it).writeText(reports) }
    }
}
