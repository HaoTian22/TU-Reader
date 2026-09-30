package com.example.nfctransit.ui

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardStateCoordinatorTest {
    @Test
    fun earlyReadWaitsForRestoreAndReusesSavedIdentity() = runBlocking {
        val coordinator = CardStateCoordinator()
        val finishRestore = CompletableDeferred<Unit>()
        val cards = mutableMapOf<String, String>()
        var writes = 0
        var readCardId: String? = null

        // NFC can finish even before the startup coroutine has begun.
        val read = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.withRestoredState {
                readCardId = cards.getOrPut("TU-card-number") { "duplicate-uuid" }
                writes++
            }
        }
        val restore = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.initialize {
                finishRestore.await()
                cards["TU-card-number"] = "original-uuid"
            }
        }

        assertEquals(0, writes)
        assertTrue(cards.isEmpty())
        finishRestore.complete(Unit)
        restore.join()
        read.join()

        assertEquals("original-uuid", readCardId)
        assertEquals(1, cards.size)
        assertEquals(1, writes)
    }

    @Test
    fun secondReadWaitsUntilFirstSaveAndRebuildFinish() = runBlocking {
        val coordinator = CardStateCoordinator()
        coordinator.initialize {}
        val finishFirstSave = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var balance = 0
        val archive = mutableListOf<String>()

        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.withRestoredState {
                events.add("first matched")
                finishFirstSave.await()
                archive.add("first transaction")
                balance = 100
                events.add("first rebuilt")
            }
        }
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.withRestoredState {
                events.add("second matched")
                archive.add("second transaction")
                balance = 200
                events.add("second rebuilt")
            }
        }

        assertEquals(listOf("first matched"), events)
        finishFirstSave.complete(Unit)
        first.join()
        second.join()

        assertEquals(
            listOf("first matched", "first rebuilt", "second matched", "second rebuilt"),
            events
        )
        assertEquals(listOf("first transaction", "second transaction"), archive)
        assertEquals(200, balance)
    }

    @Test
    fun failedRestoreNeverAllowsMatchingAgainstIncompleteState() = runBlocking {
        val coordinator = CardStateCoordinator()
        val failure = IOException("Cannot load saved cards")
        var matched = false
        val read = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                coordinator.withRestoredState { matched = true }
                throw AssertionError("Read should fail when restore fails")
            } catch (error: IOException) {
                assertEquals(failure.message, error.message)
            }
        }

        try {
            coordinator.initialize { throw failure }
            throw AssertionError("Restore should fail")
        } catch (error: IOException) {
            assertEquals(failure.message, error.message)
        }
        read.join()
        assertFalse(matched)
    }
}
