package com.example.nfctransit.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 用户数据数据库（与映射库 AppDatabase（assets/transit.db，createFromAsset）完全分离）。
 * 版本迁移见 MIGRATIONS：当前 v6，应用快照及原始槽位均使用 selected_aid 定位应用。
 */
@Database(
    entities = [
        CardEntity::class,
        RawRecordEntity::class,
        ArchivedTransactionEntity::class,
        CardAppEntity::class
    ],
    version = 6,
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

        /** v4→v5：原始槽位改用 AID，应用快照按同卡同应用合并。 */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE `raw_records_new` (
                        `row_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `card_id` TEXT NOT NULL,
                        `sfi` TEXT NOT NULL,
                        `rec_no` INTEGER NOT NULL,
                        `selected_aid` TEXT NOT NULL,
                        `hex` TEXT NOT NULL,
                        `content_hash` TEXT NOT NULL,
                        `first_seen_at` INTEGER NOT NULL,
                        `last_seen_at` INTEGER NOT NULL,
                        FOREIGN KEY(`card_id`) REFERENCES `cards`(`card_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )"""
                )
                // 旧库没有实际 AID：LNT 信息/统计属于 APPY，交易属于 TICL；
                // TU 优先使用槽位最后读取之前的应用快照，其次最新快照，最后默认 AID。
                db.execSQL(
                    """INSERT INTO `raw_records_new`
                        SELECT r.row_id, r.card_id, r.sfi, r.rec_no,
                            CASE r.legacy_protocol
                                WHEN 'LNT' THEN CASE WHEN r.sfi IN ('0x15', '0x08')
                                    THEN '5041592E41505059' ELSE '5041592E5449434C' END
                                WHEN 'YCT' THEN CASE WHEN r.sfi IN ('0x15', '0x08')
                                    THEN '5041592E41505059' ELSE '5041592E5449434C' END
                                WHEN 'TU' THEN COALESCE(
                                    (SELECT a.selected_aid FROM card_app AS a
                                        WHERE a.card_id = r.card_id
                                          AND a.selected_aid IN ('A000000632010105', 'A000000632010106')
                                          AND a.read_at <= r.last_seen_at
                                        ORDER BY a.read_at DESC, a.row_id DESC LIMIT 1),
                                    (SELECT a.selected_aid FROM card_app AS a
                                        WHERE a.card_id = r.card_id
                                          AND a.selected_aid IN ('A000000632010105', 'A000000632010106')
                                        ORDER BY a.read_at DESC, a.row_id DESC LIMIT 1),
                                    'A000000632010105')
                                WHEN 'CU' THEN 'A00000000386980701'
                                WHEN 'SZT' THEN '5041592E535A54'
                                WHEN 'SUXIN' THEN '535558494E2E4444463031'
                                WHEN 'SZTK' THEN '535A504B5F5A5959'
                                WHEN 'TFT' THEN 'D156000015B9ABB9B2D3A6D3C3'
                                ELSE ''
                            END,
                            r.hex, r.content_hash, r.first_seen_at, r.last_seen_at
                        FROM (SELECT raw_records.*,
                            CASE WHEN raw_records.protocol = '' THEN cards.card_type
                                ELSE raw_records.protocol END AS legacy_protocol
                            FROM raw_records INNER JOIN cards ON raw_records.card_id = cards.card_id) AS r"""
                )
                // 空协议和显式协议可能归到同一个 AID：合并时间范围，保留最新槽位内容。
                db.execSQL(
                    "CREATE INDEX `index_raw_records_new_slot` ON `raw_records_new` " +
                        "(`card_id`, `selected_aid`, `sfi`, `rec_no`, `last_seen_at`, `row_id`)"
                )
                db.execSQL(
                    """UPDATE `raw_records_new` SET first_seen_at = (
                        SELECT MIN(other.first_seen_at) FROM raw_records_new AS other
                        WHERE other.card_id = raw_records_new.card_id
                          AND other.selected_aid = raw_records_new.selected_aid
                          AND other.sfi = raw_records_new.sfi AND other.rec_no = raw_records_new.rec_no
                    )"""
                )
                db.execSQL(
                    """DELETE FROM `raw_records_new` WHERE EXISTS (
                        SELECT 1 FROM raw_records_new AS newer
                        WHERE newer.card_id = raw_records_new.card_id
                          AND newer.selected_aid = raw_records_new.selected_aid
                          AND newer.sfi = raw_records_new.sfi AND newer.rec_no = raw_records_new.rec_no
                          AND (newer.last_seen_at > raw_records_new.last_seen_at
                            OR (newer.last_seen_at = raw_records_new.last_seen_at AND newer.row_id > raw_records_new.row_id))
                    )"""
                )
                db.execSQL("DROP TABLE `raw_records`")
                db.execSQL("DROP INDEX `index_raw_records_new_slot`")
                db.execSQL("ALTER TABLE `raw_records_new` RENAME TO `raw_records`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_raw_records_card_id_selected_aid_sfi_rec_no` " +
                        "ON `raw_records` (`card_id`, `selected_aid`, `sfi`, `rec_no`)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_raw_records_card_id` ON `raw_records` (`card_id`)")
                db.execSQL(
                    """DELETE FROM `card_app` WHERE EXISTS (
                        SELECT 1 FROM `card_app` AS newer
                        WHERE newer.card_id = card_app.card_id
                          AND newer.selected_aid = card_app.selected_aid
                          AND (newer.read_at > card_app.read_at
                            OR (newer.read_at = card_app.read_at AND newer.row_id > card_app.row_id))
                    )"""
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_card_app_card_id_selected_aid` " +
                        "ON `card_app` (`card_id`, `selected_aid`)"
                )
            }
        }

        /** v5→v6：EC 交易日志保存读取时的 DOL 格式。 */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transactions_archive` ADD COLUMN `log_format` TEXT")
            }
        }

        /** 全部迁移：主库打开与导入旧库共用 */
        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)

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
