package com.example.nfctransit.data.repo

import android.database.sqlite.SQLiteDatabase
import java.io.File
import com.example.nfctransit.R
import com.example.nfctransit.util.L10n

/** TripReader（card_table / tran_table）数据库的只读解析结果 */
data class TripReaderSource(
    val cards: List<TripReaderCard>
)

/**
 * TripReader 库中的一张卡。TripReader 把卡上应用分成 TU（交通联合）与 CU（城市钱包：岭南通/深圳通/住建部/天府通…）两侧：
 * isTU 时 cdNo 为 TU 应用序列号（与 cdTURawInfo 一致），cdNo2 为城市钱包应用序列号（与 cdCURawInfo 一致，可能为空）；
 * 无 TU 时 cdNo 为城市钱包卡号。isSZT/isYCT/isCU/isTFT/isSUZ 等为来源库卡型标志；余额未知为 -99999999。
 */
data class TripReaderCard(
    val cdNo: String,
    val cdNo2: String,
    val cdBalance: Long,
    val cdTitle: String,
    val isTU: Boolean,
    val isCU: Boolean,
    val isSZT: Boolean,
    val isYCT: Boolean,
    val isLNT: Boolean?,
    val transactions: List<TripReaderTransaction>,
    val isTFT: Boolean = false,
    val isSUZ: Boolean = false,
    val cdTUBalance: Long = TripReaderDatabase.UNKNOWN_BALANCE,
    val cdCUBalance: Long = TripReaderDatabase.UNKNOWN_BALANCE,
    /** TU / 城市钱包信息文件 SFI 0x15 原始内容（与本应用读卡保存的信息槽位逐字节一致），空串为无 */
    val cdTURawInfo: String = "",
    val cdCURawInfo: String = "",
    /** TripReader 最后读取该卡的时间（毫秒），未知为 null */
    val cdLastUpdateMs: Long? = null
)

/**
 * TripReader 对同一条交易同时存两份原始记录：cuHex = 主交易（txCUResult，SFI txSFI，通常 0x18），tuHex = SFI 0x1E 旅程。
 * 逐条应用标记（表结构没有该列时为 null）：isTU18 = cuHex 属于 TU 钱包，isCU = cuHex 属于城市钱包，isYCT = 岭南通钱包。
 */
data class TripReaderTransaction(
    val dateMs: Long,
    val cuHex: String,
    val tuHex: String,
    val isLNT: Boolean? = null,
    val isCU: Boolean? = null,
    val isTU18: Boolean? = null,
    val isYCT: Boolean? = null,
    val sfi: Int? = null
)

/**
 * 只读解析 TripReader 的 SQLite 库。该软件把同一条交易按两种格式各存一行：
 *  - txCUResult / ZTXCURESULT：SFI 0x18 主交易原始记录（23B）。交通联合钱包带完整年份；岭南通（LNT）钱包
 *    年份字段为 0000，真实年份只能靠 txDate / ZTXDATE 复原；Core Data 变体的 ZTXDATE 使用 Apple NSDate 秒数。
 *  - txTUResult / ZTXTURESULT：SFI 0x1E 旅程/站点记录（48B）。
 * 卡片身份用 card_table 的 cdNo/cdNo2 对齐本应用 card_app 的应用卡号；逐条应用标记决定记录归属的应用。
 */
object TripReaderDatabase {

    const val UNKNOWN_BALANCE = -99999999L

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
        val isTFT: String,
        val isSUZ: String,
        val cardTUBalance: String,
        val cardCUBalance: String,
        val cardTURawInfo: String,
        val cardCURawInfo: String,
        val cardLastUpdate: String,
        val cardIsLNT: String,
        val txIsCU: String,
        val txIsTU18: String,
        val txIsYCT: String,
        val txSfi: String,
        val txIsLNT: String,
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
        isTFT = "isTFT", isSUZ = "isSUZ", cardTUBalance = "cdTUBalance", cardCUBalance = "cdCUBalance",
        cardTURawInfo = "cdTURawInfo", cardCURawInfo = "cdCURawInfo", cardLastUpdate = "cdLastUpdate",
        cardIsLNT = "isLNT", txIsLNT = "isLNT",
        txIsCU = "txCU", txIsTU18 = "txTU18", txIsYCT = "txYCT", txSfi = "txSFI",
        txDate = "txDate", txCUResult = "txCUResult", txTUResult = "txTUResult",
        dateIsAppleReference = false
    )

    private val coreDataSchema = Schema(
        cardTable = "ZCARDDATA", transactionTable = "ZTRANDATA",
        cardRawNo = "ZCDRAWNO", transactionRawNo = "ZCDRAWNO",
        cardNo = "ZCDNO", cardNo2 = "ZCDNO2", cardBalance = "ZCDBALANCE", cardTitle = "ZCDTITLE",
        isTU = "ZISTU", isCU = "ZISCU", isSZT = "ZISSZT", isYCT = "ZISYCT",
        isTFT = "ZISTFT", isSUZ = "ZISSUZ", cardTUBalance = "ZCDTUBALANCE", cardCUBalance = "ZCDCUBALANCE",
        cardTURawInfo = "ZCDTURAWINFO", cardCURawInfo = "ZCDCURAWINFO", cardLastUpdate = "ZCDLASTUPDATE",
        cardIsLNT = "ZISLNT", txIsLNT = "ZISLNT",
        txIsCU = "ZTXCU", txIsTU18 = "ZTXTU18", txIsYCT = "ZTXYCT", txSfi = "ZTXSFI",
        txDate = "ZTXDATE", txCUResult = "ZTXCURESULT", txTUResult = "ZTXTURESULT",
        dateIsAppleReference = true
    )

    fun read(file: File): TripReaderSource {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val schema = schemaFor(db)
                ?: throw IllegalArgumentException(L10n.str(R.string.err_tripreader_schema))
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
                val iLntFlag = c.getColumnIndex(schema.cardIsLNT)
                val iTftFlag = c.getColumnIndex(schema.isTFT)
                val iSuzFlag = c.getColumnIndex(schema.isSUZ)
                val iTuBal = c.getColumnIndex(schema.cardTUBalance)
                val iCuBal = c.getColumnIndex(schema.cardCUBalance)
                val iTuInfo = c.getColumnIndex(schema.cardTURawInfo)
                val iCuInfo = c.getColumnIndex(schema.cardCURawInfo)
                val iLastUpdate = c.getColumnIndex(schema.cardLastUpdate)
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
                        val iTxLntFlag = t.getColumnIndex(schema.txIsLNT)
                        val iTxCu = t.getColumnIndex(schema.txIsCU)
                        val iTxTu18 = t.getColumnIndex(schema.txIsTU18)
                        val iTxYct = t.getColumnIndex(schema.txIsYCT)
                        val iTxSfi = t.getColumnIndex(schema.txSfi)
                        fun flag(index: Int) = if (index >= 0) t.getInt(index) != 0 else null
                        while (t.moveToNext()) {
                            val cuHex = if (iCu >= 0) t.getString(iCu) else ""
                            val tuHex = if (iTu >= 0) t.getString(iTu) else ""
                            if (cuHex.isEmpty() && tuHex.isEmpty()) continue
                            transactions.add(
                                TripReaderTransaction(
                                    dateMs = if (iDate >= 0) dateToMillis(t, iDate, schema.dateIsAppleReference) else 0L,
                                    cuHex = cuHex,
                                    tuHex = tuHex,
                                    isLNT = flag(iTxLntFlag),
                                    isCU = flag(iTxCu),
                                    isTU18 = flag(iTxTu18),
                                    isYCT = flag(iTxYct),
                                    sfi = if (iTxSfi >= 0) t.getInt(iTxSfi).takeIf { it in 1..30 } else null
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
                            isLNT = if (iLntFlag >= 0) c.getInt(iLntFlag) != 0 else null,
                            transactions = transactions,
                            isTFT = iTftFlag >= 0 && c.getInt(iTftFlag) != 0,
                            isSUZ = iSuzFlag >= 0 && c.getInt(iSuzFlag) != 0,
                            cdTUBalance = if (iTuBal >= 0) c.getLong(iTuBal) else UNKNOWN_BALANCE,
                            cdCUBalance = if (iCuBal >= 0) c.getLong(iCuBal) else UNKNOWN_BALANCE,
                            cdTURawInfo = if (iTuInfo >= 0) c.getString(iTuInfo).orEmpty() else "",
                            cdCURawInfo = if (iCuInfo >= 0) c.getString(iCuInfo).orEmpty() else "",
                            cdLastUpdateMs = if (iLastUpdate >= 0 && !c.isNull(iLastUpdate)) {
                                dateToMillis(c, iLastUpdate, schema.dateIsAppleReference)
                            } else null
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
