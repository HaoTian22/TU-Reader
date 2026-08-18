package com.example.nfctransit.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 用户数据数据库（与映射库 AppDatabase（assets/transit.db，createFromAsset）完全分离）。
 * 版本迁移见 MIGRATIONS：当前 v5，sfi 列以 hex 字符串（"0x19"）存储，并含路线规划缓存。
 */
@Database(
    entities = [
        CardEntity::class,
        RawRecordEntity::class,
        ArchivedTransactionEntity::class,
        CardAppEntity::class,
        RouteCacheEntity::class
    ],
    version = 5,
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

        /** v2→v3：transactions_archive 去重键放宽为 (content_hash, protocol, sfi)，保留同内容不同协议/扇区的变体 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_transactions_archive_card_id_content_hash`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_transactions_archive_card_id_content_hash_protocol_sfi` " +
                        "ON `transactions_archive` (`card_id`, `content_hash`, `protocol`, `sfi`)"
                )
            }
        }

        /** v3→v4：raw_records / transactions_archive 的 sfi 列改存 hex 字符串（"0x19"），INTEGER 迁移为 TEXT */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `raw_records_new` (
                        `row_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `card_id` TEXT NOT NULL,
                        `sfi` TEXT NOT NULL,
                        `rec_no` INTEGER NOT NULL,
                        `protocol` TEXT NOT NULL,
                        `hex` TEXT NOT NULL,
                        `content_hash` TEXT NOT NULL,
                        `first_seen_at` INTEGER NOT NULL,
                        `last_seen_at` INTEGER NOT NULL,
                        FOREIGN KEY(`card_id`) REFERENCES `cards`(`card_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )"""
                )
                db.execSQL(
                    """INSERT INTO `raw_records_new` (`row_id`,`card_id`,`sfi`,`rec_no`,`protocol`,`hex`,`content_hash`,`first_seen_at`,`last_seen_at`)
                        SELECT `row_id`,`card_id`, printf('0x%02X',`sfi`), `rec_no`,`protocol`,`hex`,`content_hash`,`first_seen_at`,`last_seen_at` FROM `raw_records`"""
                )
                db.execSQL("DROP TABLE `raw_records`")
                db.execSQL("ALTER TABLE `raw_records_new` RENAME TO `raw_records`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_raw_records_card_id_protocol_sfi_rec_no` " +
                        "ON `raw_records` (`card_id`,`protocol`,`sfi`,`rec_no`)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_raw_records_card_id` ON `raw_records` (`card_id`)")

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `transactions_archive_new` (
                        `row_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `card_id` TEXT NOT NULL,
                        `sfi` TEXT NOT NULL,
                        `protocol` TEXT NOT NULL,
                        `hex` TEXT NOT NULL,
                        `content_hash` TEXT NOT NULL,
                        `resolved_date` TEXT NOT NULL,
                        `balance_after_fen` INTEGER,
                        `first_seen_at` INTEGER NOT NULL,
                        `last_seen_at` INTEGER NOT NULL,
                        FOREIGN KEY(`card_id`) REFERENCES `cards`(`card_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )"""
                )
                db.execSQL(
                    """INSERT INTO `transactions_archive_new` (`row_id`,`card_id`,`sfi`,`protocol`,`hex`,`content_hash`,`resolved_date`,`balance_after_fen`,`first_seen_at`,`last_seen_at`)
                        SELECT `row_id`,`card_id`, printf('0x%02X',`sfi`), `protocol`,`hex`,`content_hash`,`resolved_date`,`balance_after_fen`,`first_seen_at`,`last_seen_at` FROM `transactions_archive`"""
                )
                db.execSQL("DROP TABLE `transactions_archive`")
                db.execSQL("ALTER TABLE `transactions_archive_new` RENAME TO `transactions_archive`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_transactions_archive_card_id_content_hash_protocol_sfi` " +
                        "ON `transactions_archive` (`card_id`,`content_hash`,`protocol`,`sfi`)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_archive_card_id` ON `transactions_archive` (`card_id`)")
            }
        }

        /** v4→v5：增加腾讯公交路线规划缓存；缓存为派生数据，不关联卡片外键。 */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
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
                    "CREATE INDEX IF NOT EXISTS `index_route_cache_expires_at` ON `route_cache` (`expires_at`)"
                )
            }
        }

        /** 全部迁移：主库打开与导入旧库共用 */
        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        @Volatile
        private var instance: UserDatabase? = null

        fun get(context: Context): UserDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    UserDatabase::class.java,
                    DB_NAME
                ).addMigrations(*MIGRATIONS).build().also { instance = it }
            }
        }
    }
}
