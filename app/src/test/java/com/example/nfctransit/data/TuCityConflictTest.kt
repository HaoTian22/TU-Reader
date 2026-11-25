package com.example.nfctransit.data

import com.example.nfctransit.ApduUtil
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** TU 例外城市码与他城标准码冲突：交易实际城市由 line_rules.json 规则按 1E 机构码给出。 */
class TuCityConflictTest {
    // 大同真实 1E 公交记录：城市码 byte 32–33 = 8040（延安标准码），机构码 byte 34–41 = 12981620FFFFFFFF
    private val datong = ApduUtil.hexToBytes(
        "06000041310134038802004200003039090000006400003A5C20260924184541804012981620FFFFFFFF000000000000"
    )

    @Before
    fun installBundled() = TuLineRules.install(TuLineRules.parse(File("src/main/assets/data/line_rules.json").readText()))

    @After
    fun reset() = TuLineRules.install(TuLineRules.RuleSet.EMPTY)

    @Test
    fun datongRecordWithYananCodeResolvesToDatongButSearches8040() {
        val plan = TuLineRules.solve(datong, null)
        assertEquals("1620", plan?.city)
        assertEquals(listOf("8040"), plan?.targets?.map { it.code })
        // 规则给出的城市即最终城市（传作 institutionCity），不再按库别名改写
        assertEquals("1620", TransitData.resolveCityCode("TU", "1620", null, true, plan?.city))
    }

    @Test
    fun institutionWithoutFfffOrTruncatedRecordLeavesCityToDbMapping() {
        assertNull(TuLineRules.solve(datong.copyOfRange(0, 39), null)?.city)
        val notFfff = datong.copyOf().also { it[38] = 0x00 }
        assertNull(TuLineRules.solve(notFfff, null)?.city)
        // 无规则城市：沿用库映射 TU/8040 → 大同；TU/1730 → 太原
        assertEquals("1620", TransitData.resolveCityCode("TU", "8040", "1620", true, null))
        assertEquals("1610", TransitData.resolveCityCode("TU", "1730", "1610", true, null))
    }

    @Test
    fun cuCodesNeverTreatedAsConflict() {
        // 现有库：CU/1100 → 沈阳(2210)；CU 代码自成体系，即使与天津 TU 标准码同值也直接映射
        assertEquals("2210", TransitData.resolveCityCode("CU", "1100", "2210", true, "1100"))
    }
}
