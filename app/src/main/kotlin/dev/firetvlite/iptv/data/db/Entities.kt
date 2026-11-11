package dev.firetvlite.iptv.data.db

import androidx.room.Embedded
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A saved playlist. The most recently used source is active. */
@Entity(tableName = "source")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val userAgent: String,
    /** Epoch milliseconds of the last successful import, 0 if never. */
    val refreshedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val lastUsedAt: Long = 0,
    @ColumnInfo(defaultValue = "''") val epgUrl: String = "",
    @ColumnInfo(defaultValue = "''") val discoveredEpgUrls: String = "",
    @ColumnInfo(defaultValue = "0") val epgRefreshedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val epgProgrammeCount: Int = 0,
    @ColumnInfo(defaultValue = "''") val epgGeneration: String = "",
    @ColumnInfo(defaultValue = "''") val xtreamAccount: String = "",
    @ColumnInfo(defaultValue = "''") val xtreamStatus: String = "",
    @ColumnInfo(defaultValue = "'UTC'") val xtreamTimezone: String = "UTC",
    @ColumnInfo(defaultValue = "0") val xtreamArchiveCount: Int = 0,
)

data class SavedSource(
    @Embedded val source: SourceEntity,
    val channelCount: Int,
)

/**
 * One live channel. [position] keeps the order of the playlist file.
 * [groupName] is empty for channels without a group; the UI shows a localised label for that.
 */
@Entity(
    tableName = "channel",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sourceId", "groupName", "position"]),
        Index(value = ["streamUrl"]),
    ],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val position: Int,
    val name: String,
    val groupName: String,
    val streamUrl: String,
    val logoUrl: String?,
    val tvgId: String?,
    /** Catch-up (archive) settings from the playlist: type, URL template and how many days are kept. */
    val catchupType: String?,
    val catchupSource: String?,
    val catchupDays: Int,
    val tvgName: String? = null,
    @ColumnInfo(defaultValue = "'UTC'") val catchupTimezone: String = "UTC",
)

@Entity(tableName = "xtream_channel", primaryKeys = ["sourceId", "streamId"],
    foreignKeys = [ForeignKey(entity = SourceEntity::class, parentColumns = ["id"],
        childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)])
data class XtreamChannelEntity(val sourceId: Long, val streamId: String, val archived: Boolean,
    val days: Int, val epgId: String?)

/** Favourites are keyed by stream URL, so they survive re-importing the playlist. */
@Entity(tableName = "favorite")
data class FavoriteEntity(
    @PrimaryKey val streamUrl: String,
    val addedAt: Long,
)

@Entity(tableName = "recent")
data class RecentEntity(
    @PrimaryKey val streamUrl: String,
    val watchedAt: Long,
)

/** A channel together with its favourite flag, as shown in lists. */
data class ChannelRow(
    @Embedded val channel: ChannelEntity,
    val isFavorite: Boolean,
)

data class GroupCount(
    val groupName: String,
    val channelCount: Int,
)
