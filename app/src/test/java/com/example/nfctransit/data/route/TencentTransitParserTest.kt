package com.example.nfctransit.data.route

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

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
    fun decodePolyline_rejectsMalformedValuesAndDeduplicatesAdjacentPoints() {
        assertTrue(TencentTransitParser.decodePolyline(null).isEmpty())
        assertTrue(
            TencentTransitParser.decodePolyline(
                JsonParser.parseString("[22.5,114.0,1000]").asJsonArray
            ).isEmpty()
        )
        val nonFinite = com.google.gson.JsonArray().apply {
            add(22.5)
            add(114.0)
            add(Double.NaN)
            add(1.0)
        }
        assertTrue(TencentTransitParser.decodePolyline(nonFinite).isEmpty())

        val withDuplicate = JsonParser.parseString(
            "[22.5,114.0,0,0,1000,1000]"
        ).asJsonArray
        val points = TencentTransitParser.decodePolyline(withDuplicate)
        assertEquals(2, points.size)
        assertEquals(RoutePoint(22.501, 114.001), points.last())
    }

    @Test
    fun webServiceSigner_sortsRawUtf8ParametersAndUsesExactPath() {
        val requestPath = "/ws/place/v1/suggestion/"
        val reverseOrder = linkedMapOf(
            "b" to "深圳&福田#",
            "a" to "key+value"
        )
        val sortedOrder = linkedMapOf(
            "a" to "key+value",
            "b" to "深圳&福田#"
        )

        val signature = TencentWebServiceSigner.sign(requestPath, reverseOrder, "secret123")

        assertEquals("49cca76fe34a47b54504cdf0d727d219", signature)
        assertEquals(
            signature,
            TencentWebServiceSigner.sign(requestPath, sortedOrder, "secret123")
        )
        assertNotEquals(
            signature,
            TencentWebServiceSigner.sign(requestPath.removeSuffix("/"), reverseOrder, "secret123")
        )
        assertNotEquals(
            signature,
            TencentWebServiceSigner.sign(requestPath, reverseOrder, "secret124")
        )

        assertEquals(
            "https://apis.map.qq.com/ws/place/v1/suggestion/?" +
                "a=key%2Bvalue&b=%E6%B7%B1%E5%9C%B3%26%E7%A6%8F%E7%94%B0%23&" +
                "sig=49cca76fe34a47b54504cdf0d727d219",
            TencentWebServiceSigner.buildGetUrl(
                "https://apis.map.qq.com",
                requestPath,
                reverseOrder,
                "secret123"
            )
        )
    }

    @Test
    fun requestBuilder_usesLatLngAndRequiredTransitFields() {
        val withTime = TencentTransitRequestBuilder.buildUrl(
            query = query,
            key = "key+value",
            secretKey = "secret123",
            includeDepartureTime = true
        )

        assertTrue(withTime.startsWith("https://apis.map.qq.com/ws/direction/v1/transit/?"))
        assertTrue(withTime.contains("from=22.550000%2C114.120000"))
        assertTrue(withTime.contains("to=22.530000%2C114.030000"))
        assertTrue(withTime.contains("policy=LEAST_WALKING"))
        assertTrue(withTime.contains("get_mp=1"))
        assertTrue(withTime.contains("added_fields=line_color"))
        assertTrue(withTime.contains("output=json"))
        assertTrue(withTime.contains("key=key%2Bvalue"))
        assertTrue(withTime.contains("departure_time=1700000000"))
        assertTrue(Regex("&sig=[0-9a-f]{32}$").containsMatchIn(withTime))

        val currentNetwork = TencentTransitRequestBuilder.buildUrl(
            query,
            "key+value",
            "secret123",
            false
        )
        assertFalse(currentNetwork.contains("departure_time="))
        assertNotEquals(
            withTime.substringAfterLast("&sig="),
            currentNetwork.substringAfterLast("&sig=")
        )

        val subwayOnly = TencentTransitRequestBuilder.buildUrl(
            query.copy(requiredTransitFamily = TransitFamily.SUBWAY),
            "key",
            "secret123",
            false
        )
        assertTrue(subwayOnly.contains("policy=LEAST_WALKING%2CSUBWAY_FIRST"))

        val busOnly = TencentTransitRequestBuilder.buildUrl(
            query.copy(requiredTransitFamily = TransitFamily.BUS),
            "key",
            "secret123",
            false
        )
        assertTrue(busOnly.contains("policy=LEAST_WALKING%2CNO_SUBWAY"))
    }

    @Test
    fun representativeDepartureTime_usesNextShanghaiNoon() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val morning = Calendar.getInstance(zone).apply {
            set(2026, Calendar.AUGUST, 19, 0, 55, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val afternoon = Calendar.getInstance(zone).apply {
            set(2026, Calendar.AUGUST, 19, 18, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val sameDay = Calendar.getInstance(zone).apply {
            timeInMillis = TransitRouteRepository.representativeDepartureTimeSeconds(morning) * 1000L
        }
        val nextDay = Calendar.getInstance(zone).apply {
            timeInMillis = TransitRouteRepository.representativeDepartureTimeSeconds(afternoon) * 1000L
        }

        assertEquals(19, sameDay.get(Calendar.DAY_OF_MONTH))
        assertEquals(12, sameDay.get(Calendar.HOUR_OF_DAY))
        assertEquals(20, nextDay.get(Calendar.DAY_OF_MONTH))
        assertEquals(12, nextDay.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun stationSearchBuilder_usesRecordedNameCityAndCachedCoordinateBias() {
        val search = StationSearchQuery(
            stationName = "罗湖站 ↑",
            cityName = "深圳",
            lineName = "1号线",
            family = TransitFamily.SUBWAY,
            biasLat = 22.533312,
            biasLng = 114.113179
        )

        val url = TencentStationSearchRequestBuilder.buildUrl(search, "key+value", "secret123")

        assertTrue(url.startsWith("https://apis.map.qq.com/ws/place/v1/suggestion/?"))
        assertTrue(url.contains("region=%E6%B7%B1%E5%9C%B3"))
        assertTrue(url.contains("region_fix=1"))
        assertTrue(url.contains("keyword=%E7%BD%97%E6%B9%96%E5%9C%B0%E9%93%81%E7%AB%99"))
        assertTrue(url.contains("location=22.533312%2C114.113179"))
        assertTrue(url.contains("key=key%2Bvalue"))
        assertTrue(Regex("&sig=[0-9a-f]{32}$").containsMatchIn(url))
    }

    @Test
    fun stationSearchParser_requiresExactNameAndTransitFamilyBeforeLineTieBreak() {
        val search = StationSearchQuery(
            stationName = "罗湖站 ↑",
            cityName = "深圳",
            lineName = "1号线",
            family = TransitFamily.SUBWAY,
            biasLat = 22.533312,
            biasLng = 114.113179
        )
        val json = """
            {
              "status":0,
              "data":[
                {"title":"人民南[地铁站]","address":"地铁9号线","type":2,
                 "category":"基础设施:交通设施:地铁站",
                 "location":{"lat":22.538100,"lng":114.113128}},
                {"title":"罗湖公交站","address":"1路","type":1,
                 "category":"基础设施:交通设施:公交站",
                 "location":{"lat":22.533300,"lng":114.113100}},
                {"title":"罗湖[地铁站]","address":"深圳地铁1号线","type":2,
                 "category":"基础设施:交通设施:地铁站",
                 "location":{"lat":22.531650,"lng":114.117000}}
              ]
            }
        """.trimIndent()

        val result = TencentStationSearchParser.parse(json, search)

        assertTrue(result is StationSearchResult.Found)
        val coordinate = (result as StationSearchResult.Found).coordinate
        assertEquals("罗湖[地铁站]", coordinate.matchedTitle)
        assertEquals(22.531650, coordinate.lat, 0.0000001)
        assertEquals(114.117000, coordinate.lng, 0.0000001)

        val wrongOnly = TencentStationSearchParser.parse(
            """{"status":0,"data":[{"title":"人民南[地铁站]","type":2,"category":"地铁站","location":{"lat":22.5381,"lng":114.1131}}]}""",
            search
        )
        assertTrue(wrongOnly is StationSearchResult.NoMatch)
    }

    @Test
    fun parser_allowsSameFamilyTransfersAndRejectsMixedModes() {
        val json = """
            {
              "status":0,
              "result":{"routes":[
                {
                  "distance":8000,"duration":25,
                  "steps":[
                    {"mode":"TRANSIT","lines":[{
                      "vehicle":"SUBWAY","title":"1号线",
                      "geton":{"title":"老街站"},"getoff":{"title":"换乘站"},
                      "polyline":[22.55,114.12,-1000,-1000]
                    }]},
                    {"mode":"TRANSIT","lines":[{
                      "vehicle":"BUS","title":"M100",
                      "geton":{"title":"换乘站"},"getoff":{"title":"车公庙站"},
                      "polyline":[22.549,114.119,-1000,-1000]
                    }]}
                  ]
                },
                {
                  "distance":9000,"duration":30,
                  "steps":[
                    {"mode":"TRANSIT","lines":[{
                      "vehicle":"SUBWAY","title":"1号线",
                      "geton":{"title":"老街站"},"getoff":{"title":"岗厦北站"},
                      "polyline":[22.55,114.12,-1000,-1000]
                    }]},
                    {"mode":"WALKING","tag":"INTERNAL","distance":80,
                      "polyline":[22.549,114.119,-100,-100]},
                    {"mode":"TRANSIT","lines":[{
                      "vehicle":"SUBWAY","title":"11号线",
                      "geton":{"title":"岗厦北站"},"getoff":{"title":"车公庙站"},
                      "polyline":[22.5489,114.1189,-1000,-1000]
                    }]}
                  ]
                }
              ]}
            }
        """.trimIndent()
        val subwayQuery = query.copy(requiredTransitFamily = TransitFamily.SUBWAY)

        val result = TencentTransitParser.parse(json, subwayQuery, estimatedCurrentNetwork = true)
        assertTrue(result is TransitParseResult.Ready)
        val plan = (result as TransitParseResult.Ready).plan
        assertEquals(listOf(RouteMode.SUBWAY, RouteMode.SUBWAY), plan.transitLegs.map { it.mode })
        assertEquals(listOf("1号线", "11号线"), plan.transitLegs.map { it.title })
        assertEquals(1, plan.transferCount)
    }

    @Test
    fun parser_keepsBusTransfersPureBus() {
        val json = """
            {
              "status":0,
              "result":{"routes":[{
                "steps":[
                  {"mode":"TRANSIT","lines":[{
                    "vehicle":"BUS","title":"M100",
                    "geton":{"title":"公交上车站"},"getoff":{"title":"公交换乘站"},
                    "polyline":[22.55,114.12,-1000,-1000]
                  }]},
                  {"mode":"WALKING","distance":50,
                    "polyline":[22.549,114.119,-100,-100]},
                  {"mode":"TRANSIT","lines":[{
                    "vehicle":"BUS","title":"M200",
                    "geton":{"title":"公交换乘站"},"getoff":{"title":"公交下车站"},
                    "polyline":[22.5489,114.1189,-1000,-1000]
                  }]}
                ]
              }]}
            }
        """.trimIndent()
        val busQuery = query.copy(
            fromName = "公交上车站",
            toName = "公交下车站",
            fromLineName = "M100",
            toLineName = "M200",
            requiredTransitFamily = TransitFamily.BUS
        )

        val result = TencentTransitParser.parse(json, busQuery, estimatedCurrentNetwork = true)
        assertTrue(result is TransitParseResult.Ready)
        val plan = (result as TransitParseResult.Ready).plan
        assertEquals(listOf(RouteMode.BUS, RouteMode.BUS), plan.transitLegs.map { it.mode })
        assertEquals(1, plan.transferCount)
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
            "{\"status\":199,\"message\":\"此key未开启WebserviceAPI功能\"," +
                "\"request_id\":\"req-199\"}",
            query,
            estimatedCurrentNetwork = false
        )
        assertTrue(result is TransitParseResult.PermissionDenied)
        result as TransitParseResult.PermissionDenied
        assertEquals(199, result.status)
        assertEquals("req-199", result.requestId)
    }

    @Test
    fun parser_preservesTencentFailureDiagnostics() {
        val result = TencentTransitParser.parse(
            "{\"status\":321,\"message\":\"参数错误\",\"request_id\":\"req-321\"}",
            query,
            estimatedCurrentNetwork = false
        )

        assertTrue(result is TransitParseResult.Failure)
        result as TransitParseResult.Failure
        assertEquals("参数错误", result.message)
        assertEquals(321, result.status)
        assertEquals("req-321", result.requestId)

        val httpError = TencentTransitParser.parseServiceError(
            "{\"status\":199,\"message\":\"无权限\",\"request_id\":\"http-199\"}"
        )
        assertTrue(httpError is TransitParseResult.PermissionDenied)
        assertEquals("http-199", (httpError as TransitParseResult.PermissionDenied).requestId)

        val quotaError = TencentTransitParser.parseServiceError(
            "{\"status\":121,\"message\":\"此key每日调用量已达到上限\"," +
                "\"request_id\":\"quota-121\"}"
        )
        assertTrue(quotaError is TransitParseResult.QuotaExceeded)
        quotaError as TransitParseResult.QuotaExceeded
        assertEquals(121, quotaError.status)
        assertEquals("quota-121", quotaError.requestId)
    }

    @Test
    fun parser_parsesCompleteTransitPlanAndOptionalLineFields() {
        val json = """
            {
              "status":0,
              "message":"query ok",
              "result":{"routes":[{
                "distance":12000,
                "duration":46,
                "steps":[
                  {"mode":"WALKING","distance":180,"duration":3,
                   "polyline":[22.55,114.12,-100,-100]},
                  {"mode":"TRANSIT","lines":[{
                    "vehicle":"SUBWAY","title":"地铁1号线","line_color":"00AAFF",
                    "running_status":300,"distance":5000,"duration":15,
                    "geton":{"title":"老街站"},"getoff":{"title":"大剧院站"},
                    "polyline":[22.5499,114.1199,-1000,-1000]
                  }]},
                  {"mode":"WALKING","tag":"INTERNAL","distance":90,"duration":2,
                   "polyline":[22.5489,114.1189,-100,-100]},
                  {"mode":"TRANSIT","lines":[{
                    "vehicle":"BUS","title":"11号线","line_color":"#FF6600",
                    "running_status":301,"distance":6000,"duration":24,
                    "geton":{"title":"大剧院站"},"getoff":{"title":"车公庙站"},
                    "polyline":[22.5488,114.1188,-1000,-1000]
                  }]},
                  {"mode":"WALKING","distance":100,"duration":2,
                   "polyline":[22.5478,114.1178,-100,-100]}
                ]
              }]}
            }
        """.trimIndent()

        val result = TencentTransitParser.parse(json, query, estimatedCurrentNetwork = false)
        assertTrue(result is TransitParseResult.Ready)
        val plan = (result as TransitParseResult.Ready).plan
        assertEquals(
            listOf(RouteMode.WALKING, RouteMode.SUBWAY, RouteMode.WALKING, RouteMode.BUS, RouteMode.WALKING),
            plan.legs.map { it.mode }
        )
        assertEquals(370, plan.walkingDistanceMeters)
        assertTrue(plan.legs[2].internalTransfer)
        assertEquals("00AAFF", plan.transitLegs[0].lineColor)
        assertEquals(300, plan.transitLegs[0].runningStatus)
        assertEquals(RouteGeometryKind.FULL_POLYLINE, plan.transitLegs[0].geometryKind)
        assertFalse(plan.hasApproximateRailGeometry)
    }

    @Test
    fun parser_marksRailPolylineAndStationFallbackAsApproximate() {
        val json = """
            {
              "status":0,
              "result":{"routes":[{
                "steps":[
                  {"mode":"TRANSIT","lines":[{
                    "vehicle":"RAIL","title":"城际铁路",
                    "geton":{"title":"老街站","location":{"lat":22.55,"lng":114.12}},
                    "getoff":{"title":"换乘站","location":{"lat":22.54,"lng":114.08}},
                    "stations":[
                      {"title":"中间站","location":{"lat":22.545,"lng":114.10}}
                    ]
                  }]},
                  {"mode":"TRANSIT","lines":[{
                    "vehicle":"FERRY","title":"11号线",
                    "geton":{"title":"换乘站"},"getoff":{"title":"车公庙站"},
                    "polyline":[22.54,114.08,-1000,-1000]
                  }]}
                ]
              }]}
            }
        """.trimIndent()

        val result = TencentTransitParser.parse(json, query, estimatedCurrentNetwork = true)
        assertTrue(result is TransitParseResult.Ready)
        val plan = (result as TransitParseResult.Ready).plan
        val rail = plan.legs.first()
        assertEquals(RouteMode.RAIL, rail.mode)
        assertEquals(RouteGeometryKind.STATION_SEQUENCE, rail.geometryKind)
        assertEquals(3, rail.points.size)
        assertEquals(RouteMode.OTHER_TRANSIT, plan.legs.last().mode)
        assertTrue(plan.hasApproximateRailGeometry)
        assertTrue(plan.estimatedCurrentNetwork)
    }

    @Test
    fun parser_prefersRecordedLineBeforeRunningStatus() {
        val json = """
            {
              "status":0,
              "result":{"routes":[{
                "steps":[{"mode":"TRANSIT","lines":[
                  {
                    "vehicle":"SUBWAY","title":"1号线","running_status":303,
                    "geton":{"title":"老街站"},"getoff":{"title":"车公庙站"},
                    "polyline":[22.55,114.12,-1000,-1000]
                  },
                  {
                    "vehicle":"SUBWAY","title":"2号线","running_status":300,
                    "geton":{"title":"老街站"},"getoff":{"title":"车公庙站"},
                    "polyline":[22.55,114.12,-2000,-2000]
                  }
                ]}]
              }]}
            }
        """.trimIndent()
        val sameLineQuery = query.copy(toLineName = "地铁1号线")

        val result = TencentTransitParser.parse(json, sameLineQuery, estimatedCurrentNetwork = false)
        assertTrue(result is TransitParseResult.Ready)
        assertEquals("1号线", (result as TransitParseResult.Ready).plan.transitLegs.single().title)
    }

    @Test
    fun parser_rejectsRouteEndingAtDifferentStation() {
        val json = """
            {
              "status":0,
              "result":{"routes":[{
                "steps":[{"mode":"TRANSIT","lines":[{
                  "vehicle":"SUBWAY","title":"1号线",
                  "geton":{"title":"老街站"},"getoff":{"title":"人民南站"},
                  "polyline":[22.55,114.12,-1000,-1000]
                }]}]
              }]}
            }
        """.trimIndent()
        val luohuQuery = query.copy(
            toName = "罗湖站 ↑",
            toLineName = "1号线",
            requiredTransitFamily = TransitFamily.SUBWAY
        )

        val result = TencentTransitParser.parse(json, luohuQuery, estimatedCurrentNetwork = false)

        assertTrue(result is TransitParseResult.NoRoute)
        assertEquals(
            "腾讯未返回与交易记录起终点一致的路线",
            (result as TransitParseResult.NoRoute).message
        )
    }

    @Test
    fun parser_selectsOnlyLineWithExactRecordedEndpoints() {
        val json = """
            {
              "status":0,
              "result":{"routes":[{
                "steps":[{"mode":"TRANSIT","lines":[
                  {
                    "vehicle":"SUBWAY","title":"1号线","running_status":300,
                    "geton":{"title":"老街站"},"getoff":{"title":"人民南站"},
                    "polyline":[22.55,114.12,-1000,-1000]
                  },
                  {
                    "vehicle":"SUBWAY","title":"1号线","running_status":303,
                    "geton":{"title":"老街"},"getoff":{"title":"罗湖站"},
                    "polyline":[22.55,114.12,-2000,-2000]
                  }
                ]}]
              }]}
            }
        """.trimIndent()
        val luohuQuery = query.copy(
            toName = "罗湖站 ↑",
            toLineName = "1号线",
            requiredTransitFamily = TransitFamily.SUBWAY
        )

        val result = TencentTransitParser.parse(json, luohuQuery, estimatedCurrentNetwork = false)

        assertTrue(result is TransitParseResult.Ready)
        assertEquals("罗湖站", (result as TransitParseResult.Ready).plan.transitLegs.single().toName)
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
        assertTrue(TencentTransitParser.strictStationNamesMatch("罗湖站 ↑", "地铁罗湖站"))
        assertFalse(TencentTransitParser.strictStationNamesMatch("人民南站", "人民站"))
        assertFalse(TencentTransitParser.strictStationNamesMatch("人民南站", "罗湖站"))
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
        assertEquals(7, TransitRouteRepository.CACHE_FORMAT_VERSION)
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
        assertNotEquals(
            TransitRouteRepository.currentNetworkCacheKey(query),
            TransitRouteRepository.currentNetworkCacheKey(
                query.copy(requiredTransitFamily = TransitFamily.SUBWAY)
            )
        )
        assertEquals(
            TencentStationCoordinateResolver.cacheKey(
                StationSearchQuery("罗湖站 ↑", "深圳", "1号线", TransitFamily.SUBWAY, 22.5, 114.1)
            ),
            TencentStationCoordinateResolver.cacheKey(
                StationSearchQuery("地铁罗湖站", "深圳", "9号线", TransitFamily.SUBWAY, 23.0, 115.0)
            )
        )
        assertNotEquals(
            TencentStationCoordinateResolver.cacheKey(
                StationSearchQuery("罗湖站", "深圳", "1号线", TransitFamily.SUBWAY, 22.5, 114.1)
            ),
            TencentStationCoordinateResolver.cacheKey(
                StationSearchQuery("罗湖站", "深圳", "1号线", TransitFamily.BUS, 22.5, 114.1)
            )
        )

        assertEquals(
            TransitRouteRepository.currentNetworkCacheKey(query),
            TransitRouteRepository.currentNetworkCacheKey(
                query.copy(departureTimeSeconds = query.departureTimeSeconds + 86_400)
            )
        )
    }
}
