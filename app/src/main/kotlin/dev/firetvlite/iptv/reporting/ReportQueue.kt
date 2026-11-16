package dev.firetvlite.iptv.reporting

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import dev.firetvlite.iptv.data.library.DiagnosticRedactor
import dev.firetvlite.iptv.playback.utf8Limit
import java.net.URI
import java.time.Instant
import java.security.MessageDigest
import java.util.UUID

interface ReportSink {
    fun enqueue(kind: String, channel: String, summary: String, detail: String)
    fun crash(summary: String, detail: String) = enqueue("crash", "", summary, detail)
    fun processExit(occurredAt: Long, summary: String, detail: String) = enqueue("exit", "", summary, detail)
    fun midPlaybackStall(channel: String, summary: String, detail: String) = enqueue("stall", channel, summary, detail)

    object None : ReportSink {
        override fun enqueue(kind: String, channel: String, summary: String, detail: String) = Unit
    }
}

@Entity(tableName = "report_queue")
data class QueuedReport(
    @PrimaryKey val id: String,
    val kind: String,
    val createdAt: Long,
    val occurredAt: String,
    val channel: String,
    val summary: String,
    val detail: String,
)

interface ReportQueue {
    fun enqueue(report: QueuedReport): Boolean
    fun pending(limit: Int): List<QueuedReport>
    fun remove(ids: List<String>)
    fun count(): Int
}

@Entity(tableName = "report_recent")
data class ReportFingerprint(@PrimaryKey val fingerprint: String, val createdAt: Long)

@Dao
abstract class ReportDao : ReportQueue {
    @Query("SELECT EXISTS(SELECT 1 FROM report_recent WHERE fingerprint=:fingerprint AND createdAt >= :since AND createdAt <= :until)")
    abstract fun duplicate(fingerprint: String, since: Long, until: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun remember(fingerprint: ReportFingerprint)

    @Query("DELETE FROM report_recent WHERE createdAt < :before")
    abstract fun expire(before: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insert(report: QueuedReport): Long

    @Query("DELETE FROM report_queue WHERE id NOT IN (SELECT id FROM report_queue ORDER BY createdAt DESC, rowid DESC LIMIT :maximum)")
    abstract fun trim(maximum: Int)

    @Transaction
    override fun enqueue(report: QueuedReport): Boolean {
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest("${report.kind}\u0000${report.channel}\u0000${report.summary}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (duplicate(fingerprint, report.createdAt - 60_000, report.createdAt)) return false
        val added = insert(report) != -1L
        if (added) remember(ReportFingerprint(fingerprint, report.createdAt))
        expire(report.createdAt - 60_000)
        trim(500)
        return added
    }

    @Query("SELECT * FROM report_queue ORDER BY createdAt, rowid LIMIT :limit")
    abstract override fun pending(limit: Int): List<QueuedReport>

    @Query("DELETE FROM report_queue WHERE id IN (:ids)")
    abstract override fun remove(ids: List<String>)

    @Query("SELECT COUNT(*) FROM report_queue")
    abstract override fun count(): Int
}

internal object ReportText {
    private val urls = Regex("(?i)https?://[^\\s<>\"']+")
    private val kinds = setOf("playback_error", "stall", "crash", "anr", "exit", "health", "dead_channel", "other")

    fun installId(): String = UUID.randomUUID().toString().replace("-", "")

    fun safe(text: String, bytes: Int, known: Set<String> = emptySet()): String {
        val normalized = text.replace(Regex("(?i)(https?)\\[:\\]//"), "$1://")
        val bearerHidden = normalized.replace(Regex("(?i)\\b(Bearer|Basic)\\s+[^\\s,;]+"), "$1 ***")
            .replace(Regex("(?im)\\b(Authorization|Cookie|Set-Cookie)\\s*[:=]\\s*[^\\r\\n]+"), "$1=***")
        val masked = DiagnosticRedactor.mask(bearerHidden, known)
        val hosts = urls.replace(masked) { match ->
            runCatching { URI(match.value).host?.replace(".", "[.]") }.getOrNull() ?: "[adres ukryty]"
        }
        return utf8Limit(hosts.replace(Regex("\\d{7,}"), "***")
            .replace(Regex("[\\p{Cc}&&[^\\n\\t]]"), " "), bytes)
    }

    fun create(kind: String, channel: String, summary: String, detail: String, now: Long = System.currentTimeMillis()): QueuedReport {
        val known = DiagnosticRedactor.secrets(urls.findAll("$channel\n$summary\n$detail").map { it.value }.toList())
        return QueuedReport(UUID.randomUUID().toString(), kind.takeIf { it in kinds } ?: "other", now,
            Instant.ofEpochMilli(now).toString(), safe(channel, 120, known), safe(summary, 500, known), safe(detail, 16_000, known))
    }
}
