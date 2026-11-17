package dev.firetvlite.iptv.data

import androidx.sqlite.db.SupportSQLiteDatabase
import dev.firetvlite.iptv.data.db.FireLiteDatabase
import java.lang.reflect.Proxy
import java.sql.DriverManager
import java.sql.SQLException
import org.junit.Assert.*
import org.junit.Test

class UsageMigrationTest {
    @Test fun migrationNineToTenPreservesDataAndCreatesRoomCompatibleUsageSchema() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { sql ->
                sql.execute("PRAGMA foreign_keys=ON")
                sql.execute("CREATE TABLE source (id INTEGER NOT NULL PRIMARY KEY, name TEXT NOT NULL)")
                sql.execute("CREATE TABLE channel (id INTEGER NOT NULL PRIMARY KEY, sourceId INTEGER NOT NULL, streamUrl TEXT NOT NULL)")
                sql.execute("CREATE TABLE report_queue (id TEXT NOT NULL PRIMARY KEY)")
                sql.execute("INSERT INTO source VALUES (1, 'fixture')")
                sql.execute("INSERT INTO channel VALUES (1, 1, 'stream')")
                sql.execute("INSERT INTO report_queue VALUES ('pending-report')")
                sql.execute("PRAGMA user_version=9")
            }
            val database = Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
                check(method.name == "execSQL")
                connection.createStatement().use { it.execute(args!![0] as String) }
                null
            } as SupportSQLiteDatabase
            val migration = FireLiteDatabase.MIGRATION_9_10
            assertEquals(9, migration.startVersion)
            assertEquals(10, migration.endVersion)
            migration.migrate(database)
            connection.createStatement().use { sql ->
                val columns = mutableMapOf<String, Triple<String, Int, Int>>()
                sql.executeQuery("PRAGMA table_info(channel_usage)").use { rows ->
                    while (rows.next()) columns[rows.getString("name")] = Triple(rows.getString("type"),
                        rows.getInt("notnull"), rows.getInt("pk"))
                }
                assertEquals(mapOf("sourceId" to Triple("INTEGER", 1, 1), "streamUrl" to Triple("TEXT", 1, 2),
                    "selections" to Triple("INTEGER", 1, 0), "watchMs" to Triple("INTEGER", 1, 0),
                    "score" to Triple("REAL", 1, 0), "updatedAt" to Triple("INTEGER", 1, 0)), columns)
                sql.executeQuery("SELECT COUNT(*) FROM channel_usage").use { assertTrue(it.next()); assertEquals(0, it.getInt(1)) }
                assertThrows(SQLException::class.java) {
                    sql.execute("INSERT INTO channel_usage VALUES (999, 'unknown-source', 1, 0, 1.0, 1000)")
                }
                sql.executeQuery("SELECT c.streamUrl, s.name, r.id FROM channel c JOIN source s ON c.sourceId=s.id CROSS JOIN report_queue r").use {
                    assertTrue(it.next())
                    assertEquals("stream", it.getString(1))
                    assertEquals("fixture", it.getString(2))
                    assertEquals("pending-report", it.getString(3))
                }
                sql.execute("INSERT INTO channel_usage VALUES (1, 'stream', 2, 90000, 2.3, 1000)")
                sql.execute("DELETE FROM channel")
                sql.execute("INSERT INTO channel VALUES (2, 1, 'stream')")
                sql.executeQuery("SELECT selections, watchMs FROM channel_usage").use {
                    assertTrue(it.next())
                    assertEquals(2L, it.getLong(1))
                    assertEquals(90000L, it.getLong(2))
                }
                sql.execute("DELETE FROM source")
                sql.executeQuery("SELECT COUNT(*) FROM channel_usage").use { assertTrue(it.next()); assertEquals(0, it.getInt(1)) }
                sql.executeQuery("PRAGMA foreign_key_check").use { assertFalse(it.next()) }
            }
        }
    }
}
