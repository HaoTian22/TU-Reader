package com.example.nfctransit.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 站点/线路/城市映射数据库。
 * 数据由 tools/build_db.py 从 tripreader-data CSV 预生成，打包为 assets/data/transit.db，
 * 首次启动经 createFromAsset 拷贝到应用私有目录。
 * 数据更新（版本号 + 增量同步）为后续阶段，当前固定 version=1。
 */
@Database(
    entities = [
        CityEntity::class,
        LineEntity::class,
        StationEntity::class,
        ReaderDeviceEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transitDao(): TransitDao

    companion object {
        private const val DB_NAME = "transit.db"
        private const val ASSET_PATH = "data/transit.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .createFromAsset(ASSET_PATH)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
