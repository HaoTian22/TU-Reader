package com.example.nfctransit.ui

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcReadGateTest {
    @Test fun readingAndSavingRejectAllOverlappingCallbacks() {
        val gate = NfcReadGate()
        assertTrue(gate.tryBegin("card-a", 0))
        assertFalse(gate.tryBegin("card-a", 100))
        assertFalse(gate.tryBegin("card-b", 10000))
        gate.finish(10000)
        assertFalse(gate.tryBegin("card-a", 11499))
        assertTrue(gate.tryBegin("card-a", 11500))
    }

    @Test fun anotherCardOnlyNeedsShortGeneralCooldown() {
        val gate = NfcReadGate()
        assertTrue(gate.tryBegin("card-a", 0))
        gate.finish(100)
        assertFalse(gate.tryBegin("card-b", 399))
        assertTrue(gate.tryBegin("card-b", 400))
    }

    @Test fun failedSaveReleasesBothCoordinatorAndReadGate() = runBlocking {
        val gate = NfcReadGate()
        val coordinator = CardStateCoordinator()
        coordinator.initialize {}
        assertTrue(gate.tryBegin("card-a", 0))
        try {
            coordinator.withRestoredState { throw IOException("disk full") }
        } catch (_: IOException) {
            // 同生产路径一样，失败后必须允许下一次贴卡。
        } finally {
            gate.finish(100)
        }
        assertTrue(gate.tryBegin("card-a", 1600))
        assertTrue(coordinator.withRestoredState { true })
    }
}
