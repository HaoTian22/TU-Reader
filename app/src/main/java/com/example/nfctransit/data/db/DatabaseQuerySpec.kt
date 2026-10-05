package com.example.nfctransit.data.db

import androidx.annotation.StringRes
import com.example.nfctransit.R
import com.example.nfctransit.util.AppLanguage

enum class DatabaseQuerySpec(
    val key: String,
    @StringRes val displayNameRes: Int,
    val fileName: String,
    private val promptZh: String,
    private val promptEn: String
) {
    USER(
        key = "user_data",
        displayNameRes = R.string.db_user_data,
        fileName = UserDatabase.DB_NAME,
        promptZh = """
            你是 SQLite 查询助手。请根据我最后追加的需求，生成一条可以直接执行的只读 SQL。
            只允许生成一条 SELECT 或 WITH ... SELECT 语句，不要生成 INSERT、UPDATE、DELETE、DDL、PRAGMA 或多个语句；不要输出 Markdown 代码围栏或解释文字，只输出 SQL。

            当前数据库：user_data.db
            表结构：
            1. cards
               card_id TEXT PRIMARY KEY NOT NULL
               card_number TEXT NOT NULL
               second_card_number TEXT NULL
               name TEXT NOT NULL
               card_type TEXT NOT NULL
               last_four TEXT NOT NULL
               gradient_start_color INTEGER NOT NULL
               gradient_end_color INTEGER NOT NULL
               latest_balance_fen INTEGER NULL（余额，单位分）
               created_at INTEGER NOT NULL（Unix 毫秒时间戳）
               last_read_at INTEGER NOT NULL（Unix 毫秒时间戳）

            2. raw_records
               row_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
               card_id TEXT NOT NULL，外键关联 cards.card_id，删除卡片时级联删除
               sfi TEXT NOT NULL（例如 0x18）
               rec_no INTEGER NOT NULL
               protocol TEXT NOT NULL
               hex TEXT NOT NULL
               content_hash TEXT NOT NULL
               first_seen_at INTEGER NOT NULL（Unix 毫秒时间戳）
               last_seen_at INTEGER NOT NULL（Unix 毫秒时间戳）

            3. transactions_archive
               row_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
               card_id TEXT NOT NULL，外键关联 cards.card_id，删除卡片时级联删除
               sfi TEXT NOT NULL
               protocol TEXT NOT NULL
               hex TEXT NOT NULL
               content_hash TEXT NOT NULL
               resolved_date TEXT NOT NULL（格式 yyyyMMdd）
               balance_after_fen INTEGER NULL（余额，单位分）
               first_seen_at INTEGER NOT NULL（Unix 毫秒时间戳）
               last_seen_at INTEGER NOT NULL（Unix 毫秒时间戳）

            4. card_app
               row_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
               card_id TEXT NOT NULL，外键关联 cards.card_id，删除卡片时级联删除
               read_at INTEGER NOT NULL（Unix 毫秒时间戳）
               selected_aid TEXT NOT NULL
               select_resp TEXT NOT NULL
               balance_fen INTEGER NULL（余额，单位分）
               balance_resp TEXT NULL

            关联关系：raw_records.card_id、transactions_archive.card_id、card_app.card_id 都关联 cards.card_id。
            金额字段以分为单位；时间字段以 Unix 毫秒为单位；NULL 表示没有值。

            我的需求：
        """.trimIndent(),
        promptEn = """
            You are an SQLite query assistant. Based on the request I append at the end, write one read-only SQL statement that can be executed directly.
            Only a single SELECT or WITH ... SELECT statement is allowed: no INSERT, UPDATE, DELETE, DDL, PRAGMA or multiple statements. Do not output Markdown code fences or explanations; output only the SQL.

            Current database: user_data.db
            Tables:
            1. cards
               card_id TEXT PRIMARY KEY NOT NULL
               card_number TEXT NOT NULL
               second_card_number TEXT NULL
               name TEXT NOT NULL
               card_type TEXT NOT NULL
               last_four TEXT NOT NULL
               gradient_start_color INTEGER NOT NULL
               gradient_end_color INTEGER NOT NULL
               latest_balance_fen INTEGER NULL (balance, in fen = 1/100 CNY)
               created_at INTEGER NOT NULL (Unix timestamp in milliseconds)
               last_read_at INTEGER NOT NULL (Unix timestamp in milliseconds)

            2. raw_records
               row_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
               card_id TEXT NOT NULL, foreign key to cards.card_id, deleted together with the card
               sfi TEXT NOT NULL (e.g. 0x18)
               rec_no INTEGER NOT NULL
               protocol TEXT NOT NULL
               hex TEXT NOT NULL
               content_hash TEXT NOT NULL
               first_seen_at INTEGER NOT NULL (Unix timestamp in milliseconds)
               last_seen_at INTEGER NOT NULL (Unix timestamp in milliseconds)

            3. transactions_archive
               row_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
               card_id TEXT NOT NULL, foreign key to cards.card_id, deleted together with the card
               sfi TEXT NOT NULL
               protocol TEXT NOT NULL
               hex TEXT NOT NULL
               content_hash TEXT NOT NULL
               resolved_date TEXT NOT NULL (format yyyyMMdd)
               balance_after_fen INTEGER NULL (balance, in fen = 1/100 CNY)
               first_seen_at INTEGER NOT NULL (Unix timestamp in milliseconds)
               last_seen_at INTEGER NOT NULL (Unix timestamp in milliseconds)

            4. card_app
               row_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
               card_id TEXT NOT NULL, foreign key to cards.card_id, deleted together with the card
               read_at INTEGER NOT NULL (Unix timestamp in milliseconds)
               selected_aid TEXT NOT NULL
               select_resp TEXT NOT NULL
               balance_fen INTEGER NULL (balance, in fen = 1/100 CNY)
               balance_resp TEXT NULL

            Relations: raw_records.card_id, transactions_archive.card_id and card_app.card_id all reference cards.card_id.
            Amounts are in fen (1/100 CNY); times are Unix milliseconds; NULL means no value.

            My request:
        """.trimIndent()
    ),
    TRANSIT(
        key = "transit",
        displayNameRes = R.string.db_transit,
        fileName = "transit.db",
        promptZh = """
            你是 SQLite 查询助手。请根据我最后追加的需求，生成一条可以直接执行的只读 SQL。
            只允许生成一条 SELECT 或 WITH ... SELECT 语句，不要生成 INSERT、UPDATE、DELETE、DDL、PRAGMA 或多个语句；不要输出 Markdown 代码围栏或解释文字，只输出 SQL。

            当前数据库：transit.db
            表结构：
            1. city
               city_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
               city_code TEXT NOT NULL（唯一）
               city_name TEXT NOT NULL
               city_name_en TEXT NULL

            2. line
               line_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
               city_id INTEGER NOT NULL，外键关联 city.city_id，删除城市时级联删除
               line_code TEXT NOT NULL
               line_name TEXT NOT NULL
               line_name_en TEXT NULL
               line_color TEXT NULL
               同一城市内 city_id + line_code 唯一

            3. station
               station_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
               city_id INTEGER NOT NULL，外键关联 city.city_id，删除城市时级联删除
               station_name TEXT NOT NULL
               station_name_en TEXT NULL
               longitude REAL NULL
               latitude REAL NULL
               同一城市内 city_id + station_name 唯一

            4. reader_device
               device_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
               standard TEXT NOT NULL（CU、TU 或 YCT）
               device_code TEXT NOT NULL
               city_id INTEGER NOT NULL，外键关联 city.city_id，删除城市时级联删除
               line_id INTEGER NULL
               station_id INTEGER NULL
               transit_type TEXT NOT NULL
               同一编号的 device_code + transit_type 唯一
               updated_at TEXT NULL

            常用关联：line.city_id = city.city_id；station.city_id = city.city_id；reader_device.city_id = city.city_id；reader_device.line_id = line.line_id；reader_device.station_id = station.station_id。
            经纬度字段为 REAL；NULL 表示没有值。

            5. protocol_city_code
               protocol TEXT NOT NULL（TU、CU 或 YCT）
               code TEXT NOT NULL
               city_id INTEGER NOT NULL，外键关联 city.city_id
               protocol + code 为联合主键

            我的需求：
        """.trimIndent(),
        promptEn = """
            You are an SQLite query assistant. Based on the request I append at the end, write one read-only SQL statement that can be executed directly.
            Only a single SELECT or WITH ... SELECT statement is allowed: no INSERT, UPDATE, DELETE, DDL, PRAGMA or multiple statements. Do not output Markdown code fences or explanations; output only the SQL.

            Current database: transit.db
            Tables:
            1. city
               city_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
               city_code TEXT NOT NULL (unique)
               city_name TEXT NOT NULL
               city_name_en TEXT NULL

            2. line
               line_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
               city_id INTEGER NOT NULL, foreign key to city.city_id, deleted together with the city
               line_code TEXT NOT NULL
               line_name TEXT NOT NULL
               line_name_en TEXT NULL
               line_color TEXT NULL
               city_id + line_code is unique within a city

            3. station
               station_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
               city_id INTEGER NOT NULL, foreign key to city.city_id, deleted together with the city
               station_name TEXT NOT NULL
               station_name_en TEXT NULL
               longitude REAL NULL
               latitude REAL NULL
               city_id + station_name is unique within a city

            4. reader_device
               device_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
               standard TEXT NOT NULL (CU, TU or YCT)
               device_code TEXT NOT NULL
               city_id INTEGER NOT NULL, foreign key to city.city_id, deleted together with the city
               line_id INTEGER NULL
               station_id INTEGER NULL
               transit_type TEXT NOT NULL
               device_code + transit_type is unique
               transit_type values are Chinese, e.g. 地铁 (metro), 公交 (bus), 充值 (top-up)
               updated_at TEXT NULL

            Common joins: line.city_id = city.city_id; station.city_id = city.city_id; reader_device.city_id = city.city_id; reader_device.line_id = line.line_id; reader_device.station_id = station.station_id.
            Longitude/latitude are REAL; NULL means no value.

            5. protocol_city_code
               protocol TEXT NOT NULL (TU, CU or YCT)
               code TEXT NOT NULL
               city_id INTEGER NOT NULL, foreign key to city.city_id
               protocol + code is the composite primary key

            My request:
        """.trimIndent()
    );

    /** 交给 AI 的提示词，跟随界面语言 */
    val prompt: String get() = if (AppLanguage.isChinese()) promptZh else promptEn

    companion object {
        fun fromKey(key: String?): DatabaseQuerySpec? =
            values().firstOrNull { it.key == key }
    }
}
