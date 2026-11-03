package dev.shibasis.reaktor.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClientDescriptionTest {
    @Test fun theHeaderRoundTripsAndDropsCharactersOutsideTheLabelSet() {
        val phone = ClientDescription("android", "1.4.2 (87)", "Android 15", "Google Pixel 8")
        assertEquals("platform=android; app=1.4.2 (87); os=Android 15; device=Google Pixel 8", phone.header())
        assertEquals(phone, ClientDescription.parse(phone.header(), "okhttp/4.12.0"))
        assertEquals(ClientDescription("ios", "45", "iOS 18", "iPhone16,2"), ClientDescription.parse("platform=ios; device=iPhone16,2<>", "BestBuds/45 CFNetwork/1568.200.51 Darwin/24.1.0"))
        assertEquals(FleetKind.Android, phone.kind)
        assertNull(ClientDescription("watch").kind)
    }

    @Test fun userAgentsNameThePlatformAndWhatTheyReveal() {
        assertEquals(ClientDescription("ios", "45", "iOS 18"), ClientDescription.fromUserAgent("BestBuds/45 CFNetwork/1568.200.51 Darwin/24.1.0"))
        assertEquals(ClientDescription("ios", "52", "iOS 26"), ClientDescription.fromUserAgent("BestBuds/52 CFNetwork/3826.400.110 Darwin/25.0.0"))
        assertEquals(ClientDescription("android"), ClientDescription.fromUserAgent("okhttp/4.12.0"))
        assertEquals(ClientDescription("web", "", "macOS", "Chrome 141"), ClientDescription.fromUserAgent(
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"))
        assertEquals(ClientDescription("web", "", "iOS 18.1", "Safari 18 on iPhone"), ClientDescription.fromUserAgent(
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1"))
        assertEquals(ClientDescription("web", "", "Android 10", "Chrome 141 on phone"), ClientDescription.fromUserAgent(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"))
        assertEquals(ClientDescription("web", "", "Windows", "Edge 141"), ClientDescription.fromUserAgent(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36 Edg/141.0.0.0"))
        listOf("Cloudflare-Workers", "k6/0.49.0 (https://k6.io/)", "Ktor client", "").forEach { assertNull(ClientDescription.fromUserAgent(it)) }
        assertNull(ClientDescription.parse("platform=watch", "Cloudflare-Workers"))
    }
}
