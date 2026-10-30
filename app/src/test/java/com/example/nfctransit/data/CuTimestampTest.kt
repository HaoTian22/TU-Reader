package com.example.nfctransit.data

import com.example.nfctransit.CardProfiles
import org.junit.Assert.assertEquals
import org.junit.Test

class CuTimestampTest {
    @Test
    fun cu18PreservesRecordedYearAndSecondsDuringReadAndArchiveReload() {
        for (seconds in listOf("00", "02", "11", "17", "28", "31", "59")) {
            val hex = "00070000C80000019009413100889437202506061942$seconds"
            val record = RecordDecoder.ZoneRecord(0x18, 1, "CU", hex)
            val read = RecordDecoder.decodeCard("CU", listOf(record), null, 2026).display.single()
            // 旧 CU 解析曾把完整日期当成无年份字段，并把秒数当 subtype。
            val oldArchive = RecordDecoder.ArchivedRecord(0x18, CardProfiles.CU_AID, hex, "20260606")
            for (transaction in listOf(read, RecordDecoder.decodeArchive("CU", listOf(oldArchive)).single())) {
                assertEquals("20250606", transaction.date)
                assertEquals("1942$seconds", transaction.time)
                assertEquals("CU", transaction.protocol)
                assertEquals("09", transaction.typeHex)
            }
        }
    }
}
