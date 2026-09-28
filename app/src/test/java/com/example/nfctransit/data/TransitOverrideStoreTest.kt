package com.example.nfctransit.data

import com.example.nfctransit.data.db.ReaderDeviceEntity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TransitOverrideStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val bus = TransitOverrideRow("3320", "0120", "公交", "12", "")
    private val metro = TransitOverrideRow("3320", "0120", "地铁", "1号线", "东环南路")

    @Test
    fun sharedCodeStoresEditsAndRemovesEachTypeIndependently() {
        val original = ReaderDeviceEntity(deviceId = 100, standard = "TU", deviceCode = bus.deviceCode,
            cityId = 1, lineId = 12, transitType = "公交", deviceLocation = "3320")
        TransitOverrideStore.upsert(folder.root, FeedbackOverride(bus, "TU", false, "3320"), original)
        TransitOverrideStore.upsert(folder.root, FeedbackOverride(metro, "TU", false))
        val changed = metro.copy(station = "更正站名")
        TransitOverrideStore.upsert(folder.root, FeedbackOverride(changed, "TU", false))
        val snapshot = TransitOverrideStore.read(folder.root)
        assertEquals(2, snapshot.rows.size)
        assertEquals(bus, snapshot.rows[bus.mappingKey])
        assertEquals(changed, snapshot.rows[metro.mappingKey])
        assertEquals("3320", snapshot.locations[bus.mappingKey])
        assertEquals(original, snapshot.originals[bus.mappingKey])
        assertTrue(snapshot.originals.containsKey(metro.mappingKey))
        assertNull(snapshot.originals[metro.mappingKey])
        val removal = TransitOverrideStore.remove(folder.root, metro.mappingKey)
        assertTrue(removal!!.hasOriginal)
        assertNull(removal.original)
        assertEquals(listOf(bus), TransitOverrideStore.read(folder.root).rows.values.toList())
    }

    @Test
    fun legacySidecarKeysMigrateWithoutGivingBusMetadataToMetro() {
        File(folder.root, "overrides.csv").writeText("$OVERRIDE_HEADER\n3320,0120,公交,12,\n")
        File(folder.root, "overrides.meta.json").writeText("""{"33200120":"TU"}""")
        File(folder.root, "overrides.locations.json").writeText("""{"33200120":"3320"}""")
        File(folder.root, "overrides.originals.json").writeText("""{"33200120":null}""")
        val legacy = TransitOverrideStore.read(folder.root)
        assertEquals("TU", legacy.standards[bus.mappingKey])
        assertEquals("3320", legacy.locations[bus.mappingKey])
        assertTrue(legacy.originals.containsKey(bus.mappingKey))
        assertFalse(legacy.originals.containsKey(bus.deviceCode))
        TransitOverrideStore.upsert(folder.root, FeedbackOverride(metro, "OVERRIDE", false))
        val updated = TransitOverrideStore.read(folder.root)
        assertEquals(2, updated.rows.size)
        assertEquals("TU", updated.standards[bus.mappingKey])
        assertEquals("OVERRIDE", updated.standards[metro.mappingKey])
        assertEquals("3320", updated.locations[bus.mappingKey])
        assertFalse(updated.locations.containsKey(metro.mappingKey))
        assertTrue(updated.originals.containsKey(bus.mappingKey))
    }
}
