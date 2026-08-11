package com.example.nfctransit.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 用户数据数据库（全新库，无迁移；旧数据由用户自行清除）。
 * 与映射库 AppDatabase（assets/transit.db，createFromAsset）完全分离。
 */
@Database(
    entities = [
        CardEntity::class,
        RawRecordEntity::class,
        ArchivedTransactionEntity::class,
        CardAppEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class UserDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao

    companion object {
        const val DB_NAME = "user_data.db"

        /** v1→v2：新增 card_app 表（卡上应用 SELECT/BALANCE 记录） */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `card_app` (
                        `row_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `card_id` TEXT NOT NULL,
                        `read_at` INTEGER NOT NULL,
                        `selected_aid` TEXT NOT NULL,
                        `select_resp` TEXT NOT NULL,
                        `balance_fen` INTEGER,
                        `balance_resp` TEXT,
                        FOREIGN KEY(`card_id`) REFERENCES `cards`(`card_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )"""
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_card_app_card_id_read_at` ON `card_app` (`card_id`, `read_at`)"
                )
            }
        }

        @Volatile
        private var instance: UserDatabase? = null

        fun get(context: Context): UserDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    UserDatabase::class.java,
                    DB_NAME
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
        }
    }
}
