package com.example.nfctransit.data.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDatabaseSchemaTest {
    @Test
    fun incompatibleMappingCacheMustBeRebuilt() {
        val current = "6faef85bfabe32fba63ee2cb3c1a496e"
        assertTrue(AppDatabase.acceptsSchema(5, current))
        assertFalse(AppDatabase.acceptsSchema(3, current))
        assertFalse(AppDatabase.acceptsSchema(5, "incompatible-schema"))
        assertFalse(AppDatabase.acceptsSchema(5, null))
    }
}
