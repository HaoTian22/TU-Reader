package com.example.nfctransit.data

import com.example.nfctransit.ApduUtil
import com.example.nfctransit.CardProfiles
import org.junit.Assert.*
import org.junit.Test

class CardMetadataParserTest {
    @Test fun cuInfoHasIndependentIssuerCityAndInteroperability() {
        val info = ByteArray(30)
        ApduUtil.hexToBytes("123431408110").copyInto(info)
        info[8] = 1; info[9] = 2
        ApduUtil.hexToBytes("4321").copyInto(info, 10)
        ApduUtil.hexToBytes("2025010120351231").copyInto(info, 20)
        val metadata = CardMetadataParser.parse("CU", listOf(RawRecord(0x15, 0, "CU", ApduUtil.bytesToHex(info), CardProfiles.CU_AID)))
        assertEquals("1234", metadata.issuer)
        assertEquals("3140", metadata.cityCode)
        assertEquals("2025-01-01", metadata.issueDate)
        assertEquals("2035-12-31", metadata.validUntil)
        assertEquals("4321", metadata.interoperabilityCode)
        assertEquals(true, metadata.interoperabilityEnabled)
        assertEquals("81", metadata.algorithmSupport)
        assertEquals("10", metadata.industryCode)
    }

    @Test fun tuManagementFileIsNotConfusedWithCuCompositeRecord() {
        val info = ByteArray(30)
        ApduUtil.hexToBytes("00010001FFFFFFFF0201").copyInto(info)
        ApduUtil.hexToBytes("2025010120351231").copyInto(info, 20)
        val management = ByteArray(60)
        ApduUtil.hexToBytes("0000015658005840000102").copyInto(management)
        val metadata = CardMetadataParser.parse("TU", listOf(
            RawRecord(0x15, 0, "TU", ApduUtil.bytesToHex(info), CardProfiles.TU_EP_AID),
            RawRecord(0x17, 0, "CU", "092D00", CardProfiles.CU_AID),
            RawRecord(0x17, 0, "TU", ApduUtil.bytesToHex(management), CardProfiles.TU_EP_AID)
        ))
        assertEquals("5840", metadata.cityCode)
        assertEquals("00010001FFFFFFFF", metadata.issuer)
        assertEquals(2, metadata.cardKind)
        assertEquals("00000156", metadata.countryCode)
        assertEquals("5800", metadata.provinceCode)
        assertEquals(true, metadata.interoperabilityEnabled)
    }

    @Test fun ecOnlyUsesDf11AndTlvDatesWithoutFixedEpOffsets() {
        val df11 = "00010001FFFFFFFF006211223344556677889F03010000015658005840000100"
        val select = "DF1120$df11"
        val raw = "70185A0A6211223344556677889F5F24033512315F2503250101"
        val metadata = CardMetadataParser.parse("TU", listOf(RawRecord(2, 1, "TU", raw, CardProfiles.TU_EC_AID)), select)
        assertEquals("5840", metadata.cityCode)
        assertEquals(3, metadata.cardKind)
        assertEquals("2035-12-31", metadata.validUntil)
        assertEquals("2025-01-01", metadata.issueDate)
        assertEquals("0001", metadata.interoperabilityCode)
    }

    @Test fun legacyInfoWithoutAidFallsBackOnlyForPrimaryApplication() {
        val info = ByteArray(30)
        ApduUtil.hexToBytes("2025010120351231").copyInto(info, 20)
        val records = listOf(RawRecord(0x15, 0, "", ApduUtil.bytesToHex(info), ""))
        assertEquals("2025-01-01", CardMetadataParser.parse("SZT", records).issueDate)
        assertEquals("2035-12-31", CardMetadataParser.parse("TU", records).validUntil)
        assertNull(CardMetadataParser.parse("TU", records, allowBlankFallback = false).issueDate)
    }

    @Test fun malformedDatesRemainUnknown() {
        assertNull(CardMetadataParser.date(ApduUtil.hexToBytes("20250230"), 0))
        assertNull(CardMetadataParser.date(ApduUtil.hexToBytes("20250A01"), 0))
    }
}
