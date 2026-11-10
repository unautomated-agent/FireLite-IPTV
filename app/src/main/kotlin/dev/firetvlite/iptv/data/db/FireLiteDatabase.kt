package dev.firetvlite.iptv.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SourceEntity::class,
        ChannelEntity::class,
        FavoriteEntity::class,
        RecentEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class FireLiteDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun channelDao(): ChannelDao
    abstract fun userDao(): UserDao

    companion object {
        fun create(context: Context): FireLiteDatabase =
            Room.databaseBuilder(context, FireLiteDatabase::class.java, "firelite.db").build()
    }
}
