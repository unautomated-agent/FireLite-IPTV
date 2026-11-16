package dev.firetvlite.iptv.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query

data class SearchProgramme(
    @Embedded val channel: ChannelEntity,
    val programmeId: Long,
    val title: String,
    val start: Long,
    val stop: Long,
)

@Dao
interface SearchDao {
    @Query("SELECT * FROM channel WHERE sourceId=:sourceId AND id>:afterId ORDER BY id LIMIT :limit")
    suspend fun channels(sourceId: Long, afterId: Long, limit: Int): List<ChannelEntity>

    @Query("SELECT c.*, p.id AS programmeId, p.title, p.start, p.stop " +
        "FROM channel c JOIN source s ON s.id=c.sourceId " +
        "JOIN channel_epg m ON m.sourceId=c.sourceId AND m.streamUrl=c.streamUrl AND m.generation=s.epgGeneration " +
        "JOIN programme p ON p.sourceId=m.sourceId AND p.generation=m.generation AND p.epgKey=m.epgKey " +
        "WHERE c.sourceId=:sourceId AND p.stop>p.start AND " +
        "((:past=0 AND p.stop>:now AND p.start<:until) OR " +
        "(:past=1 AND p.stop<=:now AND c.catchupDays>0 AND p.start>=:now-c.catchupDays*86400000)) AND " +
        "((:past=0 AND p.start>:afterStart) OR (:past=1 AND p.start<:afterStart) OR " +
        "(p.start=:afterStart AND (c.id>:afterChannel OR (c.id=:afterChannel AND p.id>:afterProgramme)))) " +
        "ORDER BY CASE WHEN :past=1 THEN -p.start ELSE p.start END, c.id, p.id LIMIT :limit")
    suspend fun programmes(sourceId: Long, now: Long, until: Long, past: Boolean, afterStart: Long,
        afterChannel: Long, afterProgramme: Long, limit: Int): List<SearchProgramme>
}
