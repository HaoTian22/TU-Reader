package com.example.nfctransit.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.nfctransit.CardProfiles
import com.example.nfctransit.OctopusReader
import com.example.nfctransit.data.RecordDecoder

/**
 * 用户库 v6 → v7：原始记录与交易归档改挂卡上应用（card_app.app_id），卡号/余额改存各应用。
 *  - cards 去掉 card_number / second_card_number / last_four / latest_balance_fen；未知卡的 UID 存 chip_uid；
 *  - card_app.app_card_number 承接卡号：读卡建立的卡，card_number 是主应用卡号、second_card_number 是 TU 卡号；
 *    只由 TripReader 导入、从未读卡的卡沿用 TripReader 语义（cdNo = TU 卡号，cdNo2 = 城市钱包卡号）；
 *  - transactions_archive 去掉 card_id / protocol / balance_after_fen / log_format：
 *    所属应用优先取同卡读出同一内容的原始槽位，其次 EC 日志 → EC 应用，最后按旧协议标签推断；
 *    EC 日志的 DOL 本就存于该应用该文件的 rec_no=0 槽位，缺失时由归档行补回；content_hash 统一为 SHA-256(hex)。
 */
internal object PerAppMigration {

    private class LegacyCard(
        val cardId: String, val cardNumber: String, val secondCardNumber: String?, val name: String,
        val cardType: String, val startColor: Long, val endColor: Long, val latestBalanceFen: Long?,
        val createdAt: Long, val lastReadAt: Long
    )

    private class LegacyRaw(
        val rowId: Long, val cardId: String, val sfi: String, val recNo: Int, val aid: String,
        val hex: String, val contentHash: String, val firstSeenAt: Long, val lastSeenAt: Long
    )

    /**
     * 旧归档行所属应用。[rawAids] 为同卡同扇区同内容的原始槽位所属 AID（读卡证据）；
     * [importOnly] 表示该卡没有任何读卡数据（只来自 TripReader 导入）。
     */
    fun legacyArchiveAid(
        cardType: String,
        protocol: String,
        sfi: Int,
        hasLogFormat: Boolean,
        rawAids: List<String>,
        importOnly: Boolean
    ): String {
        rawAids.firstOrNull { CardProfiles.decodeProtocol(it, sfi) == protocol }?.let { return it }
        rawAids.firstOrNull()?.let { return it }
        if (hasLogFormat) return CardProfiles.TU_EC_AID
        // 旧版 TripReader 导入曾把住建部卡的主交易标成 TU（原启动修复 repairImportedCuArchives）
        if (importOnly && cardType == "CU" && protocol == "TU" && sfi == 0x18) return CardProfiles.CU_AID
        return CardProfiles.defaultAidForProtocol(protocol, cardType)
    }

    fun migrate(db: SupportSQLiteDatabase) {
        val cards = mutableListOf<LegacyCard>()
        db.query("SELECT * FROM cards ORDER BY rowid").use { c ->
            while (c.moveToNext()) {
                cards.add(
                    LegacyCard(
                        cardId = c.getString(c.getColumnIndexOrThrow("card_id")),
                        cardNumber = c.getString(c.getColumnIndexOrThrow("card_number")).orEmpty().trim(),
                        secondCardNumber = c.getColumnIndexOrThrow("second_card_number").let { i ->
                            if (c.isNull(i)) null else c.getString(i).trim()
                        },
                        name = c.getString(c.getColumnIndexOrThrow("name")),
                        cardType = c.getString(c.getColumnIndexOrThrow("card_type")),
                        startColor = c.getLong(c.getColumnIndexOrThrow("gradient_start_color")),
                        endColor = c.getLong(c.getColumnIndexOrThrow("gradient_end_color")),
                        latestBalanceFen = c.getColumnIndexOrThrow("latest_balance_fen").let { i ->
                            if (c.isNull(i)) null else c.getLong(i)
                        },
                        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                        lastReadAt = c.getLong(c.getColumnIndexOrThrow("last_read_at"))
                    )
                )
            }
        }

        db.execSQL(
            """CREATE TABLE `cards_v7` (`card_id` TEXT NOT NULL, `name` TEXT NOT NULL,
                `card_type` TEXT NOT NULL, `chip_uid` TEXT, `gradient_start_color` INTEGER NOT NULL,
                `gradient_end_color` INTEGER NOT NULL, `created_at` INTEGER NOT NULL,
                `last_read_at` INTEGER NOT NULL, PRIMARY KEY(`card_id`))"""
        )
        db.execSQL(
            """CREATE TABLE `card_app_v7` (`app_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `card_id` TEXT NOT NULL, `selected_aid` TEXT NOT NULL, `app_card_number` TEXT,
                `select_resp` TEXT, `balance_fen` INTEGER, `balance_resp` TEXT, `read_at` INTEGER NOT NULL,
                FOREIGN KEY(`card_id`) REFERENCES `cards`(`card_id`) ON UPDATE NO ACTION ON DELETE CASCADE)"""
        )
        db.execSQL(
            """CREATE TABLE `raw_records_v7` (`row_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `app_id` INTEGER NOT NULL, `sfi` TEXT NOT NULL, `rec_no` INTEGER NOT NULL, `hex` TEXT NOT NULL,
                `content_hash` TEXT NOT NULL, `first_seen_at` INTEGER NOT NULL, `last_seen_at` INTEGER NOT NULL,
                FOREIGN KEY(`app_id`) REFERENCES `card_app`(`app_id`) ON UPDATE NO ACTION ON DELETE CASCADE)"""
        )
        db.execSQL(
            """CREATE TABLE `transactions_archive_v7` (`row_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `app_id` INTEGER NOT NULL, `sfi` TEXT NOT NULL, `hex` TEXT NOT NULL, `content_hash` TEXT NOT NULL,
                `resolved_date` TEXT NOT NULL, `first_seen_at` INTEGER NOT NULL, `last_seen_at` INTEGER NOT NULL,
                FOREIGN KEY(`app_id`) REFERENCES `card_app`(`app_id`) ON UPDATE NO ACTION ON DELETE CASCADE)"""
        )

        for (card in cards) {
            db.insert("cards_v7", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("card_id", card.cardId)
                put("name", card.name)
                put("card_type", card.cardType)
                // 未知卡没有应用卡号，旧 card_number 是芯片 UID
                put("chip_uid", card.cardNumber.takeIf { card.cardType == CardProfiles.UNKNOWN.cardType && it.isNotEmpty() })
                put("gradient_start_color", card.startColor)
                put("gradient_end_color", card.endColor)
                put("created_at", card.createdAt)
                put("last_read_at", card.lastReadAt)
            })
        }

        // ── 应用：保留原 row_id 作 app_id；八达通卡号即 Polling 响应中的 IDm ──
        class App(val appId: Long, var number: String?, var balanceFen: Long?, val aid: String)
        val appsByCard = mutableMapOf<String, MutableList<App>>()
        db.query("SELECT * FROM card_app ORDER BY row_id").use { c ->
            while (c.moveToNext()) {
                val rowId = c.getLong(c.getColumnIndexOrThrow("row_id"))
                val cardId = c.getString(c.getColumnIndexOrThrow("card_id"))
                val aid = c.getString(c.getColumnIndexOrThrow("selected_aid"))
                val selectResp = c.getString(c.getColumnIndexOrThrow("select_resp"))
                val balanceIndex = c.getColumnIndexOrThrow("balance_fen")
                val balance = if (c.isNull(balanceIndex)) null else c.getLong(balanceIndex)
                val number = if (aid == OctopusReader.SYSTEM_CODE) OctopusReader.idmFromPolling(selectResp) else null
                db.insert("card_app_v7", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                    put("app_id", rowId)
                    put("card_id", cardId)
                    put("selected_aid", aid)
                    put("app_card_number", number)
                    put("select_resp", selectResp)
                    put("balance_fen", balance)
                    put("balance_resp", c.getString(c.getColumnIndexOrThrow("balance_resp")))
                    put("read_at", c.getLong(c.getColumnIndexOrThrow("read_at")))
                })
                appsByCard.getOrPut(cardId) { mutableListOf() }.add(App(rowId, number, balance, aid))
            }
        }
        val hadApps = appsByCard.keys.toSet()

        fun ensureApp(cardId: String, aid: String): App {
            appsByCard[cardId]?.firstOrNull { it.aid == aid }?.let { return it }
            val appId = db.insert("card_app_v7", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("card_id", cardId)
                put("selected_aid", aid)
                put("read_at", 0L)
            })
            return App(appId, null, null, aid).also { appsByCard.getOrPut(cardId) { mutableListOf() }.add(it) }
        }

        // ── 原始槽位：旧唯一键 (card_id, selected_aid, sfi, rec_no) 与新键 (app_id, sfi, rec_no) 一一对应 ──
        val raws = mutableListOf<LegacyRaw>()
        db.query("SELECT * FROM raw_records ORDER BY row_id").use { c ->
            while (c.moveToNext()) {
                raws.add(
                    LegacyRaw(
                        rowId = c.getLong(c.getColumnIndexOrThrow("row_id")),
                        cardId = c.getString(c.getColumnIndexOrThrow("card_id")),
                        sfi = c.getString(c.getColumnIndexOrThrow("sfi")),
                        recNo = c.getInt(c.getColumnIndexOrThrow("rec_no")),
                        aid = c.getString(c.getColumnIndexOrThrow("selected_aid")),
                        hex = c.getString(c.getColumnIndexOrThrow("hex")),
                        contentHash = c.getString(c.getColumnIndexOrThrow("content_hash")),
                        firstSeenAt = c.getLong(c.getColumnIndexOrThrow("first_seen_at")),
                        lastSeenAt = c.getLong(c.getColumnIndexOrThrow("last_seen_at"))
                    )
                )
            }
        }
        val rawSlots = mutableSetOf<Triple<Long, String, Int>>()
        for (raw in raws) {
            val app = ensureApp(raw.cardId, raw.aid)
            if (!rawSlots.add(Triple(app.appId, raw.sfi, raw.recNo))) continue
            db.insert("raw_records_v7", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("row_id", raw.rowId)
                put("app_id", app.appId)
                put("sfi", raw.sfi)
                put("rec_no", raw.recNo)
                put("hex", raw.hex)
                put("content_hash", raw.contentHash)
                put("first_seen_at", raw.firstSeenAt)
                put("last_seen_at", raw.lastSeenAt)
            })
        }
        val rawCards = raws.mapTo(mutableSetOf()) { it.cardId }
        val rawAidsByContent = raws.groupBy({ Triple(it.cardId, it.sfi, it.contentHash) }, { it.aid })

        // ── 卡号与余额归入应用 ──
        fun setNumber(app: App, number: String?) {
            if (number.isNullOrEmpty() || app.number != null) return
            app.number = number
            db.execSQL("UPDATE card_app_v7 SET app_card_number = ? WHERE app_id = ?", arrayOf<Any>(number, app.appId))
        }
        for (card in cards) {
            if (card.cardType == CardProfiles.UNKNOWN.cardType) continue
            val importOnly = card.cardId !in hadApps && card.cardId !in rawCards
            val apps = { appsByCard[card.cardId].orEmpty() }
            // TU 有 EP/EC 两个 AID：只读到 EC 的卡，卡号与余额在 EC 应用上
            fun appOf(protocol: String, preferredAid: String): App =
                apps().firstOrNull { it.aid == preferredAid }
                    ?: apps().firstOrNull { protocol == "TU" && CardProfiles.protocolForAid(it.aid) == "TU" }
                    ?: ensureApp(card.cardId, preferredAid)
            val primary = CardProfiles.primaryProtocol(card.cardType)
            val tuApp = { appOf("TU", CardProfiles.TU_EP_AID) }
            val numberApp = { appOf(primary, CardProfiles.infoAid(card.cardType)) }
            if (importOnly && !card.secondCardNumber.isNullOrEmpty()) {
                setNumber(tuApp(), card.cardNumber)
                setNumber(numberApp(), card.secondCardNumber)
            } else {
                if (card.cardNumber.isNotEmpty() && apps().none { it.number == card.cardNumber }) {
                    setNumber(numberApp(), card.cardNumber)
                }
                if (!card.secondCardNumber.isNullOrEmpty() && card.secondCardNumber != card.cardNumber) {
                    setNumber(tuApp(), card.secondCardNumber)
                }
            }
            val balance = card.latestBalanceFen
            val primaryApps = appsByCard[card.cardId].orEmpty().filter { CardProfiles.protocolForAid(it.aid) == primary }
            if (balance != null && primaryApps.none { it.balanceFen != null }) {
                val wallet = appOf(primary, CardProfiles.walletAid(card.cardType))
                wallet.balanceFen = balance
                db.execSQL("UPDATE card_app_v7 SET balance_fen = ? WHERE app_id = ?", arrayOf<Any>(balance, wallet.appId))
            }
        }

        // ── 交易归档 ──
        val cardTypes = cards.associate { it.cardId to it.cardType }
        val archiveRows = mutableMapOf<Triple<Long, String, String>, LongArray>()  // key → [row_id, first, last]
        db.query("SELECT * FROM transactions_archive ORDER BY row_id").use { c ->
            while (c.moveToNext()) {
                val cardId = c.getString(c.getColumnIndexOrThrow("card_id"))
                val sfi = c.getString(c.getColumnIndexOrThrow("sfi"))
                val hex = c.getString(c.getColumnIndexOrThrow("hex"))
                val logFormatIndex = c.getColumnIndexOrThrow("log_format")
                val logFormat = if (c.isNull(logFormatIndex)) null else c.getString(logFormatIndex)
                val first = c.getLong(c.getColumnIndexOrThrow("first_seen_at"))
                val last = c.getLong(c.getColumnIndexOrThrow("last_seen_at"))
                val hash = RecordDecoder.contentHash(hex)
                val sfiInt = sfi.removePrefix("0x").toIntOrNull(16) ?: 0
                val aid = legacyArchiveAid(
                    cardType = cardTypes[cardId].orEmpty(),
                    protocol = c.getString(c.getColumnIndexOrThrow("protocol")),
                    sfi = sfiInt,
                    hasLogFormat = logFormat != null,
                    rawAids = rawAidsByContent[Triple(cardId, sfi, hash)].orEmpty(),
                    importOnly = cardId !in hadApps && cardId !in rawCards
                )
                val app = ensureApp(cardId, aid)
                if (logFormat != null && rawSlots.add(Triple(app.appId, sfi, 0))) {
                    db.insert("raw_records_v7", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                        put("app_id", app.appId)
                        put("sfi", sfi)
                        put("rec_no", 0)
                        put("hex", logFormat)
                        put("content_hash", RecordDecoder.contentHash(logFormat))
                        put("first_seen_at", first)
                        put("last_seen_at", last)
                    })
                }
                val key = Triple(app.appId, sfi, hash)
                val existing = archiveRows[key]
                if (existing != null) {
                    existing[1] = minOf(existing[1], first)
                    existing[2] = maxOf(existing[2], last)
                    continue
                }
                val rowId = c.getLong(c.getColumnIndexOrThrow("row_id"))
                archiveRows[key] = longArrayOf(rowId, first, last)
                db.insert("transactions_archive_v7", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                    put("row_id", rowId)
                    put("app_id", app.appId)
                    put("sfi", sfi)
                    put("hex", hex)
                    put("content_hash", hash)
                    put("resolved_date", c.getString(c.getColumnIndexOrThrow("resolved_date")))
                    put("first_seen_at", first)
                    put("last_seen_at", last)
                })
            }
        }
        for ((rowId, first, last) in archiveRows.values.map { Triple(it[0], it[1], it[2]) }) {
            db.execSQL(
                "UPDATE transactions_archive_v7 SET first_seen_at = ?, last_seen_at = ? WHERE row_id = ?",
                arrayOf<Any>(first, last, rowId)
            )
        }

        // 外键在迁移期间未启用：先删子表再删父表，再把新表改回正式表名。
        db.execSQL("DROP TABLE `transactions_archive`")
        db.execSQL("DROP TABLE `raw_records`")
        db.execSQL("DROP TABLE `card_app`")
        db.execSQL("DROP TABLE `cards`")
        db.execSQL("ALTER TABLE `cards_v7` RENAME TO `cards`")
        db.execSQL("ALTER TABLE `card_app_v7` RENAME TO `card_app`")
        db.execSQL("ALTER TABLE `raw_records_v7` RENAME TO `raw_records`")
        db.execSQL("ALTER TABLE `transactions_archive_v7` RENAME TO `transactions_archive`")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_card_app_card_id_selected_aid` ON `card_app` (`card_id`, `selected_aid`)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_app_app_card_number` ON `card_app` (`app_card_number`)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_raw_records_app_id_sfi_rec_no` ON `raw_records` (`app_id`, `sfi`, `rec_no`)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_transactions_archive_app_id_sfi_content_hash` " +
                "ON `transactions_archive` (`app_id`, `sfi`, `content_hash`)"
        )
    }
}
