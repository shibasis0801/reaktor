package dev.shibasis.reaktor.cloud.place

import com.sun.net.httpserver.HttpServer
import dev.shibasis.reaktor.service.FleetPlace
import dev.shibasis.reaktor.service.PlaceSource
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

class CloudLocatorTest {
    private fun serve(block: (URI) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/computeMetadata/v1/instance/zone") { exchange ->
            val google = exchange.requestHeaders.getFirst("Metadata-Flavor") == "Google"
            val body = if (google) "projects/1234/zones/asia-south1-a".toByteArray() else ByteArray(0)
            exchange.sendResponseHeaders(if (google) 200 else 403, body.size.toLong().takeIf { it > 0 } ?: -1)
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/fleet/place") { exchange ->
            val body = """{"country":"IN","city":"Mumbai","latitude":19.07,"longitude":72.88,"colo":"BOM","located_by":["Egress"]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try { block(URI("http://127.0.0.1:${server.address.port}/")) } finally { server.stop(0) }
    }

    @Test fun aGcpInstanceIsLocatedByItsMetadataAndItsEgress() = serve { base ->
        val place = CloudLocator(base.resolve("fleet/place"), base.resolve("computeMetadata/v1/")).locate()
        assertEquals(FleetPlace("GCP", "asia-south1", "asia-south1-a", "", "IN", "Mumbai", 19.07, 72.88, listOf(PlaceSource.Metadata, PlaceSource.Egress)), place)
    }

    @Test fun withoutAnswersNothingIsClaimed() = serve { base ->
        val nowhere = URI("http://127.0.0.1:1/")
        assertEquals(FleetPlace(), CloudLocator(nowhere.resolve("fleet/place"), nowhere).locate())
        assertEquals(FleetPlace(), CloudLocator(base.resolve("missing"), base.resolve("other/")).locate())
    }

    @Test fun dependenciesArePlacedByHowTheyAreReached() {
        val observer = FleetPlace("GCP", "asia-south1", "asia-south1-a", "", "IN", "Mumbai", 19.07, 72.88, listOf(PlaceSource.Metadata))
        val inCluster = CloudLocator(cluster = true)
        assertEquals(observer.copy(zone = "", locatedBy = listOf(PlaceSource.Cluster)), inCluster.reached(URI("http://clickhouse:8123"), observer))
        assertEquals(observer.copy(zone = "", locatedBy = listOf(PlaceSource.Cluster)), inCluster.reached(URI("bolt://memgraph.reaktor.svc.cluster.local:7687"), observer))
        assertEquals(FleetPlace(), CloudLocator(cluster = false).reached(URI("http://clickhouse:8123"), observer))
        assertEquals(FleetPlace(provider = "AWS", region = "ap-south-1", locatedBy = listOf(PlaceSource.Endpoint)),
            inCluster.reached(URI("jdbc:postgresql://aws-0-ap-south-1.pooler.supabase.com:5432/postgres".removePrefix("jdbc:")), observer))
        assertEquals(FleetPlace(provider = "GCP", region = "global", locatedBy = listOf(PlaceSource.Endpoint)),
            inCluster.reached(URI("https://pubsub.googleapis.com"), observer))
        assertEquals(FleetPlace(), inCluster.reached(URI("https://example.com"), observer))
    }
}
