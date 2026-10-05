package com.example.nfctransit.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TransitDataCityCodeTest {
    @Test
    fun tuAndCuInterpretSameCodeIndependentlyIncludingTuExceptions() {
        val loaded = TransitData::class.java.getDeclaredField("loaded").apply { isAccessible = true }
        val citiesField = TransitData::class.java.getDeclaredField("cityInfos").apply { isAccessible = true }
        val aliasesField = TransitData::class.java.getDeclaredField("protocolCityCodes").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cities = citiesField.get(TransitData) as MutableMap<String, Any>
        @Suppress("UNCHECKED_CAST")
        val aliases = aliasesField.get(TransitData) as MutableMap<Pair<String, String>, String>
        val previousCities = cities.toMap()
        val previousAliases = aliases.toMap()
        val wasLoaded = loaded.getBoolean(TransitData)
        val constructor = TransitData::class.java.declaredClasses.first { it.simpleName == "CityInfo" }
            .getDeclaredConstructor(String::class.java, String::class.java).apply { isAccessible = true }
        try {
            loaded.setBoolean(TransitData, true)
            cities.clear()
            aliases.clear()
            for ((code, name) in mapOf("3120" to "扬州", "3140" to "镇江", "3370" to "绍兴", "3350" to "嘉兴", "5850" to "珠海")) {
                cities[code] = constructor.newInstance(name, null)
            }
            aliases["CU" to "3120"] = "3370"
            aliases["CU" to "3140"] = "3350"
            aliases["TU" to "0755"] = "5850"
            aliases["TU" to "7835"] = "5850"
            assertEquals("镇江", TransitData.cityZh("3140", "TU"))
            assertEquals("嘉兴", TransitData.cityZh("3140", "CU"))
            assertEquals("扬州", TransitData.cityZh("3120", "TU"))
            assertEquals("绍兴", TransitData.cityZh("3120", "CU"))
            assertEquals("珠海", TransitData.cityZh("0755", "TU"))
            assertEquals("珠海", TransitData.cityZh("7835", "TU"))
            assertEquals("0755", TransitData.cityZh("0755", "CU"))
        } finally {
            cities.clear()
            cities.putAll(previousCities)
            aliases.clear()
            aliases.putAll(previousAliases)
            loaded.setBoolean(TransitData, wasLoaded)
        }
    }
}
