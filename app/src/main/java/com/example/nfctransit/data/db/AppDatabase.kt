package com.example.nfctransit.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * 站点/线路/城市映射数据库。
 * 数据由 tools/update_transit_db.py 从 tripreader-data CSV 生成，打包为 assets/data/transit.db，
 * 首次启动经 createFromAsset 拷贝到应用私有目录。
 * 设置页可在线整库更新（replaceWithDownloaded），schema 由版本号 + identity_hash 校验。
 */
@Database(
    entities = [
        CityEntity::class,
        LineEntity::class,
        StationEntity::class,
        ReaderDeviceEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transitDao(): TransitDao

    companion object {
        private const val DB_NAME = "transit.db"
        private const val ASSET_PATH = "data/transit.db"

        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `reader_device` ADD COLUMN `device_location` TEXT")
            }
        }

        private fun migration2To3(context: Context) = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_reader_device_device_code`")
                db.execSQL("CREATE UNIQUE INDEX `index_reader_device_device_code_transit_type` " +
                    "ON `reader_device` (`device_code`, `transit_type`)")
                backfillSharedMappings(context, db)
            }
        }

        /** 补回旧唯一索引丢掉的类型。按城市、线路及站名复用本地 ID，不替换较新的数据库。 */
        private fun backfillSharedMappings(context: Context, db: SupportSQLiteDatabase) {
            val copy = File.createTempFile("transit-migration-", ".db", context.cacheDir)
            try {
                context.assets.open(ASSET_PATH).use { input ->
                    copy.outputStream().use { output -> input.copyTo(output) }
                }
                SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use { asset ->
                    asset.rawQuery(
                        """
                        SELECT r.standard, r.device_code, r.transit_type, r.device_location,
                               r.match_key, r.updated_at, c.city_code, l.line_name, s.station_name
                        FROM reader_device r
                        JOIN city c ON c.city_id = r.city_id
                        LEFT JOIN line l ON l.line_id = r.line_id
                        LEFT JOIN station s ON s.station_id = r.station_id
                        WHERE EXISTS (SELECT 1 FROM reader_device other
                            WHERE other.device_code = r.device_code
                              AND other.transit_type != r.transit_type)
                        """.trimIndent(), null
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            fun value(index: Int): String? = if (cursor.isNull(index)) null else cursor.getString(index)
                            val lineName = value(7)
                            val stationName = value(8)
                            db.execSQL(
                                """
                                INSERT OR IGNORE INTO reader_device
                                    (standard, device_code, city_id, line_id, station_id,
                                     transit_type, device_location, match_key, updated_at)
                                SELECT ?, ?, c.city_id, l.line_id, s.station_id, ?, ?, ?, ?
                                FROM city c
                                LEFT JOIN line l ON l.city_id = c.city_id AND l.line_name = ?
                                LEFT JOIN station s ON s.city_id = c.city_id AND s.station_name = ?
                                WHERE c.city_code = ?
                                  AND (? IS NULL OR l.line_id IS NOT NULL)
                                  AND (? IS NULL OR s.station_id IS NOT NULL)
                                """.trimIndent(),
                                arrayOf(value(0), value(1), value(2), value(3), value(4), value(5),
                                    lineName, stationName, value(6), lineName, stationName)
                            )
                        }
                    }
                }
            } finally {
                copy.delete()
            }
        }

        /** databases/transit.db 是否已存在（getDatabasePath 不会创建文件）。 */
        fun hasDatabaseFile(context: Context): Boolean =
            context.getDatabasePath(DB_NAME).exists()

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .createFromAsset(ASSET_PATH)
                    .addMigrations(MIGRATION_1_2, migration2To3(context.applicationContext))
                    .build()
                    .also { instance = it }
            }
        }

        /**
         * 用在线下载的 transit.db 替换本地库。
         * 1) 先以当前 schema 打开下载文件校验（identity_hash 不匹配 / 非 SQLite 会抛异常），原库不动；
         * 2) 关闭旧实例并清掉旧库文件（含 WAL/journal），把下载文件落到 databases/transit.db；
         * 3) 置空单例，下次 get() 重建实例直接打开新文件（文件已存在时 createFromAsset 不会覆盖）。
         */
        @Synchronized
        fun replaceWithDownloaded(context: Context, downloaded: File) {
            val appContext = context.applicationContext

            // 兼容性校验：schema 版本不同或文件损坏会在打开时抛 IllegalStateException
            val validation = Room.databaseBuilder(
                appContext, AppDatabase::class.java, downloaded.absolutePath
            ).setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .addMigrations(MIGRATION_1_2, migration2To3(appContext))
                .build()
            try {
                runBlocking { validation.transitDao().countDevices() }
            } finally {
                validation.close()
            }

            instance?.close()
            instance = null

            val dest = appContext.getDatabasePath(DB_NAME)
            val dir = dest.parentFile ?: throw IllegalStateException("无法定位数据库目录")
            val staged = File(dir, "$DB_NAME.downloaded")
            downloaded.copyTo(staged, overwrite = true)
            appContext.deleteDatabase(DB_NAME)
            if (!staged.renameTo(dest)) {
                // renameTo 失败（占用/跨卷等）时退化为直接拷贝
                staged.copyTo(dest, overwrite = true)
                staged.delete()
            }
        }

        /** 在整库替换/重置与只读查询之间共享同一把锁。 */
        fun <T> withDatabaseLock(block: () -> T): T = synchronized(this) { block() }

        /**
         * 把 transit.db 重置为打包 assets 版本：关闭旧实例、删除本地库文件（含 WAL/journal）、置空单例。
         * 下次 AppDatabase.get() / TransitData.ensureLoaded() 会因文件不存在而走 createFromAsset 重新拷贝内置库。
         */
        @Synchronized
        fun resetToAsset(context: Context) {
            val appContext = context.applicationContext
            instance?.close()
            instance = null
            appContext.deleteDatabase(DB_NAME)
        }
    }
}
