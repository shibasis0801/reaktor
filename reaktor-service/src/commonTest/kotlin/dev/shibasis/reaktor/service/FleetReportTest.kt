package dev.shibasis.reaktor.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FleetReportTest {
    private val report = FleetReport(1_000, "bestbuds", "chat", FleetKind.Worker, colo = "BOM", startedMillis = 400)

    @Test fun serialNamesAreTheClickHouseColumns() {
        val encoded = Json { encodeDefaults = true }.encodeToJsonElement(FleetReport.serializer(), report).jsonObject
        assertEquals(setOf("reported_ms", "system", "component", "kind", "instance", "environment", "build", "provider", "region",
            "colo", "country", "started_ms", "status", "observer", "samples", "timings", "zone", "city", "latitude", "longitude", "located_by",
            "user_id", "device", "os"),
            encoded.keys)
        assertEquals("Worker", encoded.getValue("kind").toString().trim('"'))
    }

    @Test fun measuredSummarisesDurationsAsPercentiles() {
        val measured = report.measured("request", (1..100).map { it.toDouble() } + Double.NaN)
        assertEquals(100, measured.samples)
        assertEquals(mapOf("request_p50_ms" to 51.0, "request_p95_ms" to 95.0, "request_max_ms" to 100.0), measured.timings)
        assertEquals(report, report.measured("request", emptyList()))
    }

    @Test fun validationRefusesUnboundedOrMisshapenFields() {
        report.validated()
        listOf(report.copy(system = "BestBuds"), report.copy(colo = "Mumbai"), report.copy(country = "India"),
            report.copy(timings = mapOf("latency" to 1.0)), report.copy(timings = mapOf("request_ms" to -1.0)),
            report.copy(instance = "x".repeat(129)), report.copy(startedMillis = 70_000)).forEach {
            assertFailsWith<IllegalArgumentException> { it.validated() }
        }
    }

    @Test fun placesFillGapsFromFallbacksAndKeepCoordinatesTogether() {
        val metadata = FleetPlace(provider = "GCP", region = "asia-south1", zone = "asia-south1-a", locatedBy = listOf(PlaceSource.Metadata))
        val egress = FleetPlace(country = "IN", city = "Mumbai", latitude = 19.07, longitude = 72.88, locatedBy = listOf(PlaceSource.Egress))
        val place = metadata or egress
        assertEquals(FleetPlace("GCP", "asia-south1", "asia-south1-a", "", "IN", "Mumbai", 19.07, 72.88, listOf(PlaceSource.Metadata, PlaceSource.Egress)), place)
        assertEquals(place, report.at(place).place)
        assertEquals(null, (FleetPlace(latitude = 1.0) or FleetPlace()).latitude)
    }

    @Test fun clientDevicesCarryOnlyACountryOrAColo() {
        val phone = FleetReport(1_000, "bestbuds", "bestbuds-client", FleetKind.Android, country = "IN")
        phone.validated()
        phone.copy(country = "", colo = "BOM").validated()
        listOf(phone.copy(colo = "BOM"), phone.copy(city = "Mumbai"), phone.copy(latitude = 19.0, longitude = 72.0),
            phone.copy(region = "Karnataka"), phone.copy(provider = "Jio"), phone.copy(zone = "a")).forEach {
            assertFailsWith<IllegalArgumentException> { it.validated() }
        }
        report.copy(city = "São Paulo", latitude = -23.4, longitude = -46.5).validated()
        listOf(report.copy(latitude = 91.0, longitude = 0.0), report.copy(latitude = 1.0), report.copy(latitude = Double.NaN, longitude = 0.0),
            report.copy(city = "x".repeat(65)), report.copy(city = "<b>")).forEach {
            assertFailsWith<IllegalArgumentException> { it.validated() }
        }
    }

    @Test fun onlyClientsCarryAUserAndTheirDevice() {
        val phone = FleetReport(1_000, "bestbuds", "bestbuds-app", FleetKind.iOS, country = "IN", user = "3f2b1c9e-7a4d-4e21-9b0a-5d6c7e8f9a01",
            device = "iPhone16,2", os = "iOS 18.1")
        phone.validated()
        listOf(report.copy(user = "someone"), report.copy(device = "Pixel 8"), report.copy(os = "Android 15"), phone.copy(device = "<script>"),
            phone.copy(os = "x".repeat(65)), phone.copy(user = "a\"b")).forEach {
            assertFailsWith<IllegalArgumentException> { it.validated() }
        }
    }

    @Test fun timingsDrainOnceAndRespectCapacity() {
        val timings = FleetTimings(2)
        listOf(3.0, 1.0, 2.0).forEach(timings::record)
        assertEquals(listOf(3.0, 1.0), timings.drain())
        assertEquals(emptyList(), timings.drain())
    }
}
