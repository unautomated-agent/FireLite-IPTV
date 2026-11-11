package dev.firetvlite.iptv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface XtreamDao {
    @Query("SELECT * FROM xtream_channel WHERE sourceId=:sourceId")
    suspend fun channels(sourceId: Long): List<XtreamChannelEntity>

    @Query("DELETE FROM xtream_channel WHERE sourceId=:sourceId")
    suspend fun clear(sourceId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(channels: List<XtreamChannelEntity>)

    @Query("UPDATE source SET xtreamAccount=:account, xtreamStatus=:status, xtreamTimezone=:timezone, " +
        "xtreamArchiveCount=:count WHERE id=:sourceId")
    suspend fun publish(sourceId: Long, account: String, status: String, timezone: String, count: Int)
}
