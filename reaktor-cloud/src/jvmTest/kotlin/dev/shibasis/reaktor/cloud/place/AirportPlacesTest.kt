package dev.shibasis.reaktor.cloud.place

import com.sun.net.httpserver.HttpServer
import dev.shibasis.reaktor.service.FleetPlace
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AirportPlacesTest {
    private val csv = """
        "id","ident","type","name","latitude_deg","longitude_deg","elevation_ft","continent","iso_country","iso_region","municipality","scheduled_service","icao_code","iata_code"
        1,"VABB","large_airport","Chhatrapati Shivaji Maharaj International Airport",19.0887,72.8679,39,"AS","IN","IN-MM","Mumbai","yes","VABB","BOM"
        2,"XBOM","closed","Old field, ""north"" strip",10.0,10.0,0,"AS","IN","IN-MM","Elsewhere","no",,"BOM"
        3,"WSSS","large_airport","Singapore Changi Airport",1.35019,103.994003,22,"AS","SG","SG-04","Singapore","yes","WSSS","SIN"
        4,"0X0","heliport","Pad",1.0,1.0,0,"AS","IN","IN-MM","Pad","no",,
    """.trimIndent()

    @Test fun codesResolveToTheBusiestAirportWithThatCode() {
        val places = airportPlaces(csv.lineSequence())
        assertEquals(FleetPlace(country = "IN", city = "Mumbai", latitude = 19.0887, longitude = 72.8679), places["BOM"])
        assertEquals("Singapore", places["SIN"]?.city)
        assertEquals(setOf("BOM", "SIN"), places.keys)
        assertEquals(listOf("2", "Old field, \"north\"", "", "x"), csvFields("2,\"Old field, \"\"north\"\"\",,x"))
    }

    @Test fun theTableIsFetchedOnceThenServedFromItsCache() {
        val fetches = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/airports.csv") { exchange ->
            fetches.incrementAndGet()
            val body = csv.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val directory = Files.createTempDirectory("airport-places")
        val cache = directory.resolve("airports.csv")
        val source = URI("http://127.0.0.1:${server.address.port}/airports.csv")
        try {
            assertEquals("Mumbai", AirportPlaces(cache, source).place("BOM")?.city)
            assertEquals("Singapore", AirportPlaces(cache, source).place("SIN")?.city)
            assertEquals(1, fetches.get())
            Files.setLastModifiedTime(cache, FileTime.from(Instant.now().minus(Duration.ofDays(40))))
            assertEquals("Mumbai", AirportPlaces(cache, source).place("BOM")?.city)
            assertEquals(2, fetches.get())
            server.stop(0)
            Files.setLastModifiedTime(cache, FileTime.from(Instant.now().minus(Duration.ofDays(40))))
            assertEquals("Mumbai", AirportPlaces(cache, source).place("BOM")?.city)
            assertNull(AirportPlaces(directory.resolve("absent.csv"), source).place("BOM"))
        } finally {
            server.stop(0)
        }
    }
}
