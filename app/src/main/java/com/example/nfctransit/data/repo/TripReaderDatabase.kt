package com.example.nfctransit.data.repo

import android.database.sqlite.SQLiteDatabase
import java.io.File

/** TripReader（card_table / tran_table）数据库的只读解析结果 */
data class TripReaderSource(
    val cards: List<TripReaderCard>
)

/** TripReader 库中的一张卡：cdNo = 19 位交通联合应用序列号，cdNo2 = 10 位岭南通应用序列号（可能为空）；isSZT/isYCT 等为来源库卡型标志 */
data class TripReaderCard(
    val cdNo: String,
    val cdNo2: String,
    val cdBalance: Long,
    val cdTitle: String,
    val isTU: Boolean,
    val isCU: Boolean,
    val isSZT: Boolean,
    val isYCT: Boolean,
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
 *  - txCUResult / ZTXCURESULT：SFI 0x18 主交易原始记录（23B）。交通联合钱包带完整年份；岭南通（LNT）钱包
 *    年份字段为 0000，真实年份只能靠 txDate / ZTXDATE 复原；Core Data 变体的 ZTXDATE 使用 Apple NSDate 秒数。
 *  - txTUResult / ZTXTURESULT：SFI 0x1E 旅程/站点记录（48B）。
 * 卡片身份用 card_table 的 cdNo/cdNo2 对齐本应用 cards 的 card_number/second_card_number。
 */
object TripReaderDatabase {

    private const val UNKNOWN_BALANCE = -99999999L

    private data class Schema(
        val cardTable: String,
        val transactionTable: String,
        val cardRawNo: String,
        val transactionRawNo: String,
        val cardNo: String,
        val cardNo2: String,
        val cardBalance: String,
        val cardTitle: String,
        val isTU: String,
        val isCU: String,
        val isSZT: String,
        val isYCT: String,
        val txDate: String,
        val txCUResult: String,
        val txTUResult: String,
        val dateIsAppleReference: Boolean
    )

    private val tripReaderSchema = Schema(
        cardTable = "card_table", transactionTable = "tran_table",
        cardRawNo = "cdRawNo", transactionRawNo = "cdRawNo",
        cardNo = "cdNo", cardNo2 = "cdNo2", cardBalance = "cdBalance", cardTitle = "cdTitle",
        isTU = "isTU", isCU = "isCU", isSZT = "isSZT", isYCT = "isYCT",
        txDate = "txDate", txCUResult = "txCUResult", txTUResult = "txTUResult",
        dateIsAppleReference = false
    )

    private val coreDataSchema = Schema(
        cardTable = "ZCARDDATA", transactionTable = "ZTRANDATA",
        cardRawNo = "ZCDRAWNO", transactionRawNo = "ZCDRAWNO",
        cardNo = "ZCDNO", cardNo2 = "ZCDNO2", cardBalance = "ZCDBALANCE", cardTitle = "ZCDTITLE",
        isTU = "ZISTU", isCU = "ZISCU", isSZT = "ZISSZT", isYCT = "ZISYCT",
        txDate = "ZTXDATE", txCUResult = "ZTXCURESULT", txTUResult = "ZTXTURESULT",
        dateIsAppleReference = true
    )

    fun read(file: File): TripReaderSource {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val schema = schemaFor(db)
                ?: throw IllegalArgumentException("不支持的 TripReader 数据库结构")
            val cards = mutableListOf<TripReaderCard>()
            db.rawQuery("SELECT * FROM ${schema.cardTable}", null).use { c ->
                val iRaw = c.getColumnIndex(schema.cardRawNo)
                val iNo = c.getColumnIndex(schema.cardNo)
                val iNo2 = c.getColumnIndex(schema.cardNo2)
                val iBal = c.getColumnIndex(schema.cardBalance)
                val iTitle = c.getColumnIndex(schema.cardTitle)
                val iTuFlag = c.getColumnIndex(schema.isTU)
                val iCuFlag = c.getColumnIndex(schema.isCU)
                val iSztFlag = c.getColumnIndex(schema.isSZT)
                val iYctFlag = c.getColumnIndex(schema.isYCT)
                while (c.moveToNext()) {
                    if (iRaw < 0 || iNo < 0) continue
                    val rawNo = c.getString(iRaw)
                    val transactions = mutableListOf<TripReaderTransaction>()
                    db.rawQuery(
                        "SELECT * FROM ${schema.transactionTable} WHERE ${schema.transactionRawNo} = ?",
                        arrayOf(rawNo)
                    ).use { t ->
                        val iDate = t.getColumnIndex(schema.txDate)
                        val iCu = t.getColumnIndex(schema.txCUResult)
                        val iTu = t.getColumnIndex(schema.txTUResult)
                        while (t.moveToNext()) {
                            val cuHex = if (iCu >= 0) t.getString(iCu) else ""
                            val tuHex = if (iTu >= 0) t.getString(iTu) else ""
                            if (cuHex.isEmpty() && tuHex.isEmpty()) continue
                            transactions.add(
                                TripReaderTransaction(
                                    dateMs = if (iDate >= 0) dateToMillis(t, iDate, schema.dateIsAppleReference) else 0L,
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
                            cdBalance = if (iBal >= 0) c.getLong(iBal) else UNKNOWN_BALANCE,
                            cdTitle = if (iTitle >= 0) c.getString(iTitle) else "",
                            isTU = iTuFlag >= 0 && c.getInt(iTuFlag) != 0,
                            isCU = iCuFlag >= 0 && c.getInt(iCuFlag) != 0,
                            isSZT = iSztFlag >= 0 && c.getInt(iSztFlag) != 0,
                            isYCT = iYctFlag >= 0 && c.getInt(iYctFlag) != 0,
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

    /** 判读文件是否为 TripReader 库或其 Core Data 表结构变体。 */
    fun isTripReaderDatabase(file: File): Boolean {
        return try {
            val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                schemaFor(db) != null && !hasTable(db, "cards")
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            false
        }
    }

    private const val APPLE_REFERENCE_EPOCH_SECONDS = 978307200.0

    private fun dateToMillis(cursor: android.database.Cursor, index: Int, appleReference: Boolean): Long {
        return if (appleReference) {
            ((cursor.getDouble(index) + APPLE_REFERENCE_EPOCH_SECONDS) * 1000.0).toLong()
        } else {
            cursor.getLong(index)
        }
    }

    private fun schemaFor(db: SQLiteDatabase): Schema? = when {
        hasTable(db, "card_table") && hasTable(db, "tran_table") -> tripReaderSchema
        hasTable(db, "ZCARDDATA") && hasTable(db, "ZTRANDATA") -> coreDataSchema
        else -> null
    }

    private fun hasTable(db: SQLiteDatabase, name: String): Boolean {
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(name)
        ).use { return it.moveToFirst() }
    }
}
