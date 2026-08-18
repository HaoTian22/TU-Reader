package com.example.nfctransit.data.route

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TencentTransitParserTest {

    private val query = TransitRouteQuery(
        fromStationId = 1,
        toStationId = 2,
        fromName = "老街站 ↓",
        toName = "车公庙站 ↑",
        fromLineName = "地铁1号线",
        toLineName = "11号线",
        fromLat = 22.55,
        fromLng = 114.12,
        toLat = 22.53,
        toLng = 114.03,
        departureTimeSeconds = 1_700_000_000
    )

    @Test
    fun decodePolyline_usesTencentForwardDifference() {
        val encoded = JsonParser.parseString("[22.5,114.0,1000,-2000,500,500]").asJsonArray
        val points = TencentTransitParser.decodePolyline(encoded)

        assertEquals(3, points.size)
        assertEquals(22.501, points[1].lat, 0.0000001)
        assertEquals(113.998, points[1].lng, 0.0000001)
        assertEquals(22.5015, points[2].lat, 0.0000001)
        assertEquals(113.9985, points[2].lng, 0.0000001)
    }

    @Test
    fun decodePolyline_rejectsInvalidCoordinates() {
        val encoded = JsonParser.parseString("[122.5,114.0,1000,-2000]").asJsonArray
        assertTrue(TencentTransitParser.decodePolyline(encoded).isEmpty())
    }

    @Test
    fun parser_selectsCandidateMatchingEntryAndExitLines() {
        val json = """
            {
              "status":0,
              "message":"query ok",
              "result":{"routes":[
                {
                  "distance":9000,"duration":30,
                  "steps":[
                    {"mode":"TRANSIT","lines":[{
                      "vehicle":"SUBWAY","title":"地铁3号线",
                      "geton":{"title":"老街站"},"getoff":{"title":"购物公园站"},
                      "polyline":[22.55,114.12,-1000,-1000]
                    }]},
                    {"mode":"TRANSIT","lines":[{
                      "vehicle":"SUBWAY","title":"地铁7号线",
                      "geton":{"title":"购物公园站"},"getoff":{"title":"车公庙站"},
                      "polyline":[22.549,114.119,-1000,-1000]
                    }]}
                  ]
                },
                {
                  "distance":10000,"duration":35,
                  "steps":[
                    {"mode":"TRANSIT","lines":[{
                      "vehicle":"SUBWAY","title":"1号线",
                      "geton":{"title":"老街"},"getoff":{"title":"岗厦北"},
                      "polyline":[22.55,114.12,-1000,-1000]
                    }]},
                    {"mode":"WALKING","tag":"INTERNAL","distance":120,
                      "polyline":[22.549,114.119,-100,-100]},
                    {"mode":"TRANSIT","lines":[{
                      "vehicle":"SUBWAY","title":"地铁11号线",
                      "geton":{"title":"岗厦北站"},"getoff":{"title":"车公庙"},
                      "polyline":[22.5489,114.1189,-1000,-1000]
                    }]}
                  ]
                }
              ]}
            }
        """.trimIndent()

        val result = TencentTransitParser.parse(json, query, estimatedCurrentNetwork = false)
        assertTrue(result is TransitParseResult.Ready)
        val plan = (result as TransitParseResult.Ready).plan
        assertEquals(listOf("1号线", "地铁11号线"), plan.transitLegs.map { it.title })
        assertEquals(1, plan.transferCount)
        assertFalse(plan.estimatedCurrentNetwork)
    }

    @Test
    fun parser_reportsPermissionDenied() {
        val result = TencentTransitParser.parse(
            "{\"status\":199,\"message\":\"此key未开启WebserviceAPI功能\"}",
            query,
            estimatedCurrentNetwork = false
        )
        assertTrue(result is TransitParseResult.PermissionDenied)
    }

    @Test
    fun parser_reportsEmptyRoutesAsUnavailable() {
        val result = TencentTransitParser.parse(
            """{"status":0,"message":"query ok","result":{"routes":[]}}""",
            query,
            estimatedCurrentNetwork = false
        )
        assertTrue(result is TransitParseResult.NoRoute)
    }

    @Test
    fun normalization_handlesTencentAndLocalNames() {
        assertEquals("老街", TencentTransitParser.normalizeStation("地铁老街站 ↓"))
        assertEquals("1号线", TencentTransitParser.normalizeLine("地铁 1号线（罗湖方向）"))
    }

    @Test
    fun routeGeometry_interpolatesByDistance() {
        val geometry = RouteGeometry(
            listOf(RoutePoint(22.0, 114.0), RoutePoint(22.0, 114.01))
        )
        val middle = requireNotNull(geometry.pointAtFraction(0.5))
        assertEquals(22.0, middle.lat, 0.0000001)
        assertEquals(114.005, middle.lng, 0.00001)
    }

    @Test
    fun cacheKey_roundsCoordinatesToSixDecimalsAndIncludesTime() {
        val original = TransitRouteRepository.cacheKey(query)
        val belowSixDecimals = TransitRouteRepository.cacheKey(
            query.copy(fromLat = query.fromLat + 0.0000004)
        )
        val nextSixthDecimal = TransitRouteRepository.cacheKey(
            query.copy(fromLat = query.fromLat + 0.0000006)
        )
        val anotherDeparture = TransitRouteRepository.cacheKey(
            query.copy(departureTimeSeconds = query.departureTimeSeconds + 1)
        )

        assertEquals(original, belowSixDecimals)
        assertNotEquals(original, nextSixthDecimal)
        assertNotEquals(original, anotherDeparture)
    }
}
