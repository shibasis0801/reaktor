package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.service.FleetPlace
import dev.shibasis.reaktor.service.PlaceSource
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(DelicateCoroutinesApi::class)
class CloudflarePlaceTest {
    private val mumbai: dynamic = js("({ cf: { colo: 'BOM', country: 'IN', city: 'Mumbai', latitude: '19.07', longitude: '72.88', postalCode: '400001', region: 'Maharashtra' } })")

    @Test fun servingIsTheColoThatAnsweredTheRequest() {
        assertEquals(FleetPlace(provider = "Cloudflare", colo = "BOM", locatedBy = listOf(PlaceSource.Request)), CloudflarePlace.serving(mumbai))
        assertEquals(FleetPlace(), CloudflarePlace.serving(js("({ cf: { colo: 'Mumbai' } })")))
        assertEquals(FleetPlace(), CloudflarePlace.serving(js("({})")))
    }

    @Test fun callerIsWhereCloudflareSeesTheCaller() {
        assertEquals(FleetPlace(country = "IN", city = "Mumbai", latitude = 19.07, longitude = 72.88, locatedBy = listOf(PlaceSource.Egress)),
            CloudflarePlace.caller(mumbai))
        assertEquals(null, CloudflarePlace.caller(js("({ cf: { latitude: '19.07' } })")).latitude)
        assertEquals(null, CloudflarePlace.caller(js("({ cf: { latitude: '190', longitude: '72' } })")).longitude)
        assertEquals("", CloudflarePlace.caller(js("({ cf: { country: 'India' } })")).country)
    }

    @Test fun answerReturnsTheCallerPlaceUncached() = GlobalScope.promise {
        val response = CloudflarePlace.answer(mumbai)
        assertEquals("no-store", response.headers.get("cache-control") as String)
        val text = response.text().unsafeCast<Promise<String>>().await()
        assertEquals(CloudflarePlace.caller(mumbai), json.decodeFromString(FleetPlace.serializer(), text))
        assertFalse(text.contains("400001") || text.contains("Maharashtra") || text.contains("BOM"))
    }
}
