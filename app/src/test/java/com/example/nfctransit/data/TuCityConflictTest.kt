package com.example.nfctransit.data

import com.example.nfctransit.ApduUtil
import org.junit.Assert.assertEquals
import org.junit.Test

/** TU 例外城市码与他城标准码冲突：城市名按 1E 记录机构码第 3–4 字节二选一。 */
class TuCityConflictTest {
    // 大同真实 1E 公交记录：城市码 byte 32–33 = 8040（延安标准码），机构码 byte 34–41 = 12981620FFFFFFFF
    private val datong = ApduUtil.hexToBytes(
        "06000041310134038802004200003039090000006400003A5C20260924184541804012981620FFFFFFFF000000000000"
    )

    @Test
    fun institutionCityIsBytes3To4OfRecordInstitution() {
        assertEquals("1620", TransitData.tuInstitutionCity(datong))
        assertEquals(null, TransitData.tuInstitutionCity(datong.copyOfRange(0, 36)))
    }

    @Test
    fun datongRecordWithYananCodeResolvesToDatong() {
        val city = ApduUtil.bcdToString(datong.copyOfRange(32, 34))
        assertEquals("8040", city)
        // 库中 TU/8040 → 大同(1620)，8040 又是延安标准码 → 冲突，按机构城市取大同
        assertEquals("1620", TransitData.resolveCityCode("TU", city, "1620", true, TransitData.tuInstitutionCity(datong)))
    }

    @Test
    fun conflictWithoutUsableInstitutionKeepsDbMapping() {
        // 现有库：TU/1730（吕梁标准码）→ 太原(1610)；机构城市未知或不属两城时沿用映射
        assertEquals("1610", TransitData.resolveCityCode("TU", "1730", "1610", true, null))
        assertEquals("1610", TransitData.resolveCityCode("TU", "1730", "1610", true, "1620"))
    }

    @Test
    fun cuCodesNeverTreatedAsConflict() {
        // 现有库：CU/1100 → 沈阳(2210)；CU 代码自成体系，即使与天津 TU 标准码同值也直接映射
        assertEquals("2210", TransitData.resolveCityCode("CU", "1100", "2210", true, "1100"))
    }
}
