package com.example.nfctransit.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserDatabaseMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun migrate5To4_preservesUserDataAndDropsRouteCache() {
        createVersion4DatabaseWithUserData()
        upgradeFixtureToVersion5()

        val room = Room.databaseBuilder(context, UserDatabase::class.java, TEST_DB)
            .allowMainThreadQueries()
            .addMigrations(*UserDatabase.MIGRATIONS)
            .build()
        try {
            val db = room.openHelper.writableDatabase

            assertEquals(4, db.version)
            assertEquals(1, db.rowCount("cards"))
            assertEquals(1, db.rowCount("raw_records"))
            assertEquals(1, db.rowCount("transactions_archive"))
            assertEquals(1, db.rowCount("card_app"))
            assertFalse(db.hasTable("route_cache"))
        } finally {
            room.close()
        }
    }

    /** 先由当前 Room v4 创建精确 schema，避免测试里复制生产建表 SQL。 */
    private fun createVersion4DatabaseWithUserData() {
        val room = Room.databaseBuilder(context, UserDatabase::class.java, TEST_DB)
            .allowMainThreadQueries()
            .build()
        try {
            room.openHelper.writableDatabase.apply {
                execSQL(
                    """INSERT INTO cards (
                        card_id, card_number, second_card_number, name, card_type, last_four,
                        gradient_start_color, gradient_end_color, latest_balance_fen, created_at, last_read_at
                    ) VALUES ('card-1','12345678',NULL,'测试卡','TU','5678',1,2,100,10,20)"""
                )
                execSQL(
                    """INSERT INTO raw_records (
                        card_id, sfi, rec_no, protocol, hex, content_hash, first_seen_at, last_seen_at
                    ) VALUES ('card-1','0x1E',1,'TU','AA','raw-hash',10,20)"""
                )
                execSQL(
                    """INSERT INTO transactions_archive (
                        card_id, sfi, protocol, hex, content_hash, resolved_date,
                        balance_after_fen, first_seen_at, last_seen_at
                    ) VALUES ('card-1','0x1E','TU','BB','archive-hash','2026-08-19',100,10,20)"""
                )
                execSQL(
                    """INSERT INTO card_app (
                        card_id, read_at, selected_aid, select_resp, balance_fen, balance_resp
                    ) VALUES ('card-1',20,'A000000632010105','9000',100,'000000649000')"""
                )
            }
        } finally {
            room.close()
        }
    }

    /** 复现历史 v5：它只在 v4 上增加了 route_cache 表和索引。 */
    private fun upgradeFixtureToVersion5() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) {
                        assertEquals(4, oldVersion)
                        assertEquals(5, newVersion)
                        db.execSQL(
                            """CREATE TABLE IF NOT EXISTS `route_cache` (
                                `cache_key` TEXT NOT NULL,
                                `from_station_id` INTEGER NOT NULL,
                                `to_station_id` INTEGER NOT NULL,
                                `departure_time` INTEGER NOT NULL,
                                `policy` TEXT NOT NULL,
                                `status` TEXT NOT NULL,
                                `response_json` TEXT,
                                `is_estimate` INTEGER NOT NULL,
                                `fetched_at` INTEGER NOT NULL,
                                `expires_at` INTEGER NOT NULL,
                                PRIMARY KEY(`cache_key`)
                            )"""
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_route_cache_expires_at` " +
                                "ON `route_cache` (`expires_at`)"
                        )
                    }
                })
                .build()
        )

        helper.writableDatabase.execSQL(
            """INSERT INTO route_cache (
                cache_key, from_station_id, to_station_id, departure_time, policy,
                status, response_json, is_estimate, fetched_at, expires_at
            ) VALUES ('route-1',1,2,3,'LEAST_TIME','SUCCESS','{}',0,10,20)"""
        )
        helper.close()
    }

    private fun SupportSQLiteDatabase.rowCount(table: String): Int =
        query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun SupportSQLiteDatabase.hasTable(table: String): Boolean =
        query(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(table)
        ).use { cursor -> cursor.moveToFirst() }

    companion object {
        private const val TEST_DB = "user-database-5-to-4-test"
    }
}
