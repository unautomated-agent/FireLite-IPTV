package dev.firetvlite.iptv.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.withTransaction
import dev.firetvlite.iptv.data.db.FireLiteDatabase
import dev.firetvlite.iptv.data.db.SourceEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.ln

data class ChannelUsageKey(val sourceId: Long, val streamUrl: String)

@Entity(tableName = "channel_usage", primaryKeys = ["sourceId", "streamUrl"], foreignKeys = [
    ForeignKey(entity = SourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE),
])
data class ChannelUsage(
    val sourceId: Long,
    val streamUrl: String,
    val selections: Long = 0,
    val watchMs: Long = 0,
    val score: Double = 0.0,
    val updatedAt: Long = 0,
) {
    fun scoreAt(now: Long): Double = score * exp(-ln(2.0) * (now - updatedAt).coerceAtLeast(0) / HALF_LIFE_MS)

    fun add(selections: Long, watchMs: Long, now: Long): ChannelUsage = copy(
        selections = this.selections + selections,
        watchMs = this.watchMs + watchMs,
        score = scoreAt(now) + selections + watchMs / 300_000.0,
        updatedAt = maxOf(updatedAt, now),
    )

    companion object { const val HALF_LIFE_MS = 14.0 * 24 * 60 * 60 * 1000 }
}

@Dao
interface ChannelUsageDao {
    @Query("SELECT * FROM channel_usage WHERE sourceId=:sourceId")
    suspend fun all(sourceId: Long): List<ChannelUsage>

    @Query("SELECT * FROM channel_usage WHERE sourceId=:sourceId AND streamUrl=:url")
    suspend fun get(sourceId: Long, url: String): ChannelUsage?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(usage: ChannelUsage)
}

fun interface ChannelUsageSink {
    fun record(key: ChannelUsageKey, selections: Long, watchMs: Long)

    companion object { val None = ChannelUsageSink { _, _, _ -> } }
}

internal class ChannelUsageStore(database: FireLiteDatabase, scope: CoroutineScope,
    awaitStartup: suspend () -> Unit) : ChannelUsageSink {
    private data class Delta(val key: ChannelUsageKey, val selections: Long, val watchMs: Long, val at: Long)
    private val events = Channel<Delta>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.IO) {
            awaitStartup()
            val dao = database.channelUsageDao()
            for (event in events) runCatching {
                database.withTransaction {
                    if (database.sourceDao().byId(event.key.sourceId) != null) {
                        val current = dao.get(event.key.sourceId, event.key.streamUrl)
                            ?: ChannelUsage(event.key.sourceId, event.key.streamUrl)
                        dao.save(current.add(event.selections, event.watchMs, event.at))
                    }
                }
            }
        }
    }

    override fun record(key: ChannelUsageKey, selections: Long, watchMs: Long) {
        events.trySend(Delta(key, selections, watchMs, System.currentTimeMillis()))
    }
}
