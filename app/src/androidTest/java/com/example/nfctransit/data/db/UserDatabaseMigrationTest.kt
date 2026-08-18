package com.example.nfctransit.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        UserDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate4To5_preservesCardsAndCreatesRouteCache() {
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL(
                """INSERT INTO cards (
                    card_id, card_number, second_card_number, name, card_type, last_four,
                    gradient_start_color, gradient_end_color, latest_balance_fen, created_at, last_read_at
                ) VALUES ('card-1','12345678',NULL,'测试卡','TU','5678',1,2,NULL,10,20)"""
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 5, true, *UserDatabase.MIGRATIONS).use { db ->
            db.query("SELECT COUNT(*) FROM cards WHERE card_id='card-1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM route_cache").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }

        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(TEST_DB)
    }

    companion object {
        private const val TEST_DB = "migration-route-cache-test"
    }
}
