package com.example.nfctransit.data.repo

import android.database.sqlite.SQLiteDatabase
import java.io.File

/** TripReader（card_table / tran_table）数据库的只读解析结果 */
data class TripReaderSource(
    val cards: List<TripReaderCard>
)

/** TripReader 库中的一张卡：cdNo = 19 位交通联合应用序列号，cdNo2 = 10 位岭南通应用序列号（可能为空） */
data class TripReaderCard(
    val cdNo: String,
    val cdNo2: String,
    val cdBalance: Long,
    val cdTitle: String,
    val transactions: List<TripReaderTransaction>
)

/** TripReader 对同一条交易同时存两份原始记录：cuHex = SFI 0x18 主交易，tuHex = SFI 0x1E 旅程 */
data class TripReaderTransaction(
    val dateMs: Long,
    val cuHex: String,
    val tuHex: String
)

/**
 * 只读解析 TripReader 的 SQLite 库。该软件把同一条交易按两种格式各存一行：
 *  - txCUResult：SFI 0x18 主交易原始记录（23B）。交通联合钱包带完整年份；岭南通（LNT）钱包
 *    年份字段为 0000，真实年份只能靠 txDate（毫秒时间戳）复原。
 *  - txTUResult：SFI 0x1E 旅程/站点记录（48B）。
 * 卡片身份用 card_table 的 cdNo/cdNo2 对齐本应用 cards 的 card_number/second_card_number。
 */
object TripReaderDatabase {

    private const val UNKNOWN_BALANCE = -99999999L

    fun read(file: File): TripReaderSource {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val cards = mutableListOf<TripReaderCard>()
            db.rawQuery(
                "SELECT cdRawNo, cdNo, cdNo2, cdBalance, cdTitle FROM card_table",
                null
            ).use { c ->
                val iRaw = c.getColumnIndex("cdRawNo")
                val iNo = c.getColumnIndex("cdNo")
                val iNo2 = c.getColumnIndex("cdNo2")
                val iBal = c.getColumnIndex("cdBalance")
                val iTitle = c.getColumnIndex("cdTitle")
                while (c.moveToNext()) {
                    // 缺 cdRawNo/cdNo 的异常版本直接跳过，避免后续无法归属交易
                    if (iRaw < 0 || iNo < 0) continue
                    val rawNo = c.getString(iRaw)
                    val transactions = mutableListOf<TripReaderTransaction>()
                    db.rawQuery(
                        "SELECT txDate, txCUResult, txTUResult FROM tran_table WHERE cdRawNo = ?",
                        arrayOf(rawNo)
                    ).use { t ->
                        val iDate = t.getColumnIndex("txDate")
                        val iCu = t.getColumnIndex("txCUResult")
                        val iTu = t.getColumnIndex("txTUResult")
                        while (t.moveToNext()) {
                            val cuHex = if (iCu >= 0) t.getString(iCu) else ""
                            val tuHex = if (iTu >= 0) t.getString(iTu) else ""
                            if (cuHex.isEmpty() && tuHex.isEmpty()) continue
                            transactions.add(
                                TripReaderTransaction(
                                    dateMs = if (iDate >= 0) t.getLong(iDate) else 0L,
                                    cuHex = cuHex,
                                    tuHex = tuHex
                                )
                            )
                        }
                    }
                    cards.add(
                        TripReaderCard(
                            cdNo = c.getString(iNo),
                            cdNo2 = if (iNo2 >= 0) c.getString(iNo2) else "",
                            cdBalance = if (iBal >= 0) c.getLong(iBal) else -99999999L,
                            cdTitle = if (iTitle >= 0) c.getString(iTitle) else "",
                            transactions = transactions
                        )
                    )
                }
            }
            return TripReaderSource(cards)
        } finally {
            db.close()
        }
    }

    /** 判读文件是否为 TripReader 库（含 card_table 且无本应用 cards 表） */
    fun isTripReaderDatabase(file: File): Boolean {
        return try {
            val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val tables = mutableSetOf<String>()
                db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
                    while (c.moveToNext()) tables.add(c.getString(0))
                }
                "card_table" in tables && "cards" !in tables
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            false
        }
    }
}
