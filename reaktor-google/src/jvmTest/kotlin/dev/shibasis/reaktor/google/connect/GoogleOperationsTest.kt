package dev.shibasis.reaktor.google.connect

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import java.net.URI
import java.util.zip.GZIPInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GoogleOperationsTest {
    private val google = FakeGoogle()
    private val connector = GoogleConnector(google.client(), MemoryGrantStore(), GrantSealer(GoogleConnectorTest.SECRET), google.endpoints(), TestClock())
    private val key = GrantKey("service-a", "person-1")
    private val tested = mutableSetOf<String>()

    @AfterTest
    fun stop() {
        google.server.shutdown()
    }

    private suspend fun connected() {
        val url = connector.begin(key, listOf(CALENDAR, FREEBUSY, DRIVE, YOUTUBE), "https://app.example.test/settings/connections", null)
        val (state, code) = google.consent(url)
        assertEquals(GoogleOutcome.Connected, connector.complete(state, code, null).outcome)
    }

    private fun run(name: String, params: String, response: MockResponse = FakeGoogle.json(200, "{}")): Pair<RecordedRequest, JsonObject> = runBlocking {
        connected()
        google.apiRequests.clear()
        google.api = { response }
        val (api, operation) = name.split('/')
        val result = connector.call(key, api, operation, Json.parseToJsonElement(params).jsonObject)
        assertIs<GoogleCall.Done>(result, "$name answered $result")
        tested += name
        google.apiRequests.single() to Json.parseToJsonElement(result.json).jsonObject
    }

    private fun RecordedRequest.query(): Map<String, String> = FakeGoogle.query(requestUrl!!.encodedQuery)

    private fun RecordedRequest.text(): String {
        val bytes = body.readByteArray()
        return if (getHeader("Content-Encoding") == "gzip") GZIPInputStream(bytes.inputStream()).readBytes().decodeToString() else bytes.decodeToString()
    }

    private fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(text()).jsonObject

    @Test
    fun `calendars insert and get`() {
        val (insert, created) = run("calendar/calendars.insert", """{"subject":"x","body":{"summary":"Plans","description":"Blocks","timeZone":"Asia/Kolkata"}}""", FakeGoogle.json(200, """{"kind":"calendar#calendar","id":"cal-1@group.calendar.google.com","summary":"Plans"}"""))
        assertEquals("POST", insert.method)
        assertEquals("/calendar/v3/calendars", insert.requestUrl!!.encodedPath)
        assertEquals("Asia/Kolkata", insert.json()["timeZone"]?.jsonPrimitive?.content)
        assertEquals("cal-1@group.calendar.google.com", created["id"]?.jsonPrimitive?.content)
        val (get, _) = run("calendar/calendars.get", """{"calendarId":"cal-1@group.calendar.google.com"}""", FakeGoogle.json(200, """{"id":"cal-1@group.calendar.google.com"}"""))
        assertEquals("GET", get.method)
        assertEquals("/calendar/v3/calendars/cal-1@group.calendar.google.com", URI(get.requestUrl.toString()).path)
    }

    @Test
    fun `events insert with a conference request, patch and delete`() {
        val event = """{"id":"mnabc123","summary":"Deep work","start":{"dateTime":"2026-10-05T09:00:00Z"},"end":{"dateTime":"2026-10-05T10:00:00Z"},"extendedProperties":{"private":{"ref":"link-1"}},"conferenceData":{"createRequest":{"requestId":"mnabc123-meet","conferenceSolutionKey":{"type":"hangoutsMeet"}}}}"""
        val (insert, inserted) = run(
            "calendar/events.insert",
            """{"calendarId":"cal-1","body":$event,"conferenceDataVersion":1,"sendUpdates":"none"}""",
            FakeGoogle.json(200, """{"id":"mnabc123","etag":"\"e1\"","status":"confirmed","hangoutLink":"https://meet.google.com/abc-defg-hij"}"""),
        )
        assertEquals("POST", insert.method)
        assertEquals("/calendar/v3/calendars/cal-1/events", insert.requestUrl!!.encodedPath)
        assertEquals(mapOf("conferenceDataVersion" to "1", "sendUpdates" to "none"), insert.query())
        val sent = insert.json()
        assertEquals("hangoutsMeet", sent["conferenceData"]!!.jsonObject["createRequest"]!!.jsonObject["conferenceSolutionKey"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("link-1", sent["extendedProperties"]!!.jsonObject["private"]!!.jsonObject["ref"]!!.jsonPrimitive.content)
        assertEquals("https://meet.google.com/abc-defg-hij", inserted["hangoutLink"]?.jsonPrimitive?.content)
        val (patch, _) = run("calendar/events.patch", """{"calendarId":"cal-1","eventId":"mnabc123","body":{"summary":"Moved"},"sendUpdates":"none"}""", FakeGoogle.json(200, """{"id":"mnabc123"}"""))
        assertEquals("PATCH", patch.getHeader("X-HTTP-Method-Override") ?: patch.method)
        assertEquals("/calendar/v3/calendars/cal-1/events/mnabc123", patch.requestUrl!!.encodedPath)
        assertEquals("Moved", patch.json()["summary"]?.jsonPrimitive?.content)
        val (delete, deleted) = run("calendar/events.delete", """{"calendarId":"cal-1","eventId":"mnabc123","sendUpdates":"none"}""", MockResponse().setResponseCode(204))
        assertEquals("DELETE", delete.method)
        assertEquals("/calendar/v3/calendars/cal-1/events/mnabc123", delete.requestUrl!!.encodedPath)
        assertEquals(mapOf("sendUpdates" to "none"), delete.query())
        assertEquals(JsonObject(emptyMap()), deleted)
    }

    @Test
    fun `events list incrementally with a sync token`() {
        val (list, page) = run(
            "calendar/events.list",
            """{"calendarId":"cal-1","syncToken":"sync-7","pageToken":"page-2","showDeleted":true,"maxResults":250}""",
            FakeGoogle.json(200, """{"kind":"calendar#events","items":[{"id":"e1","status":"cancelled"}],"nextSyncToken":"sync-8"}"""),
        )
        assertEquals("GET", list.method)
        assertEquals(mapOf("syncToken" to "sync-7", "pageToken" to "page-2", "showDeleted" to "true", "maxResults" to "250"), list.query())
        assertEquals("sync-8", page["nextSyncToken"]?.jsonPrimitive?.content)
        assertEquals("cancelled", page["items"]!!.jsonArray.single().jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `events watch and channels stop`() {
        val (watch, channel) = run(
            "calendar/events.watch",
            """{"calendarId":"cal-1","body":{"id":"mn-channel-1","type":"web_hook","address":"https://app.example.test/hooks/google/calendar","token":"v1.person-1.opaque","params":{"ttl":"604800"}}}""",
            FakeGoogle.json(200, """{"kind":"api#channel","id":"mn-channel-1","resourceId":"res-1","expiration":"1760000000000"}"""),
        )
        assertEquals("POST", watch.method)
        assertEquals("/calendar/v3/calendars/cal-1/events/watch", watch.requestUrl!!.encodedPath)
        assertEquals("v1.person-1.opaque", watch.json()["token"]?.jsonPrimitive?.content)
        assertEquals("res-1", channel["resourceId"]?.jsonPrimitive?.content)
        val (stop, _) = run("calendar/channels.stop", """{"body":{"id":"mn-channel-1","resourceId":"res-1"}}""", MockResponse().setResponseCode(204))
        assertEquals("POST", stop.method)
        assertEquals("/calendar/v3/channels/stop", stop.requestUrl!!.encodedPath)
        assertEquals("res-1", stop.json()["resourceId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `freebusy query`() {
        val (query, answer) = run(
            "calendar/freebusy.query",
            """{"body":{"timeMin":"2026-10-05T00:00:00Z","timeMax":"2026-10-06T00:00:00Z","items":[{"id":"primary"}]}}""",
            FakeGoogle.json(200, """{"kind":"calendar#freeBusy","calendars":{"primary":{"busy":[{"start":"2026-10-05T09:00:00Z","end":"2026-10-05T10:00:00Z"}]}}}"""),
        )
        assertEquals("POST", query.method)
        assertEquals("/calendar/v3/freeBusy", query.requestUrl!!.encodedPath)
        assertEquals("primary", query.json()["items"]!!.jsonArray.single().jsonObject["id"]?.jsonPrimitive?.content)
        assertEquals(1, answer["calendars"]!!.jsonObject["primary"]!!.jsonObject["busy"]!!.jsonArray.size)
    }

    @Test
    fun `files create converts HTML into a Doc in a folder, and creates the folder`() {
        val (create, file) = run(
            "drive/files.create",
            """{"body":{"name":"Session notes","mimeType":"application/vnd.google-apps.document","parents":["folder-1"]},"media":{"mimeType":"text/html","text":"<h1>Notes</h1>"},"fields":"id,name,mimeType,webViewLink"}""",
            FakeGoogle.json(200, """{"id":"doc-1","name":"Session notes","mimeType":"application/vnd.google-apps.document","webViewLink":"https://docs.google.com/document/d/doc-1/edit"}"""),
        )
        assertEquals("POST", create.method)
        assertEquals("/upload/drive/v3/files", create.requestUrl!!.encodedPath)
        assertEquals("multipart", create.query()["uploadType"])
        assertEquals("id,name,mimeType,webViewLink", create.query()["fields"])
        val body = create.text()
        assertTrue(body.contains("\"parents\":[\"folder-1\"]"))
        assertTrue(body.contains("<h1>Notes</h1>"))
        assertTrue(body.contains("content-type: text/html", ignoreCase = true))
        assertEquals("doc-1", file["id"]?.jsonPrimitive?.content)
        val (folder, _) = run("drive/files.create", """{"body":{"name":"Plans","mimeType":"application/vnd.google-apps.folder"},"fields":"id"}""", FakeGoogle.json(200, """{"id":"folder-1"}"""))
        assertEquals("/drive/v3/files", folder.requestUrl!!.encodedPath)
        assertEquals("application/vnd.google-apps.folder", folder.json()["mimeType"]?.jsonPrimitive?.content)
    }

    @Test
    fun `files get and export as text`() {
        val (get, file) = run("drive/files.get", """{"fileId":"doc-1","fields":"id,name,mimeType,webViewLink,trashed"}""", FakeGoogle.json(200, """{"id":"doc-1","trashed":false}"""))
        assertEquals("/drive/v3/files/doc-1", get.requestUrl!!.encodedPath)
        assertEquals("id,name,mimeType,webViewLink,trashed", get.query()["fields"])
        assertEquals("false", file["trashed"]?.jsonPrimitive?.content)
        val (export, text) = run("drive/files.export", """{"fileId":"doc-1","mimeType":"text/plain"}""", MockResponse().setResponseCode(200).setHeader("Content-Type", "text/plain").setBody("Notes\n\nWritten later."))
        assertEquals("/download/drive/v3/files/doc-1/export", export.requestUrl!!.encodedPath)
        assertEquals("text/plain", export.query()["mimeType"])
        assertEquals("Notes\n\nWritten later.", text["text"]?.jsonPrimitive?.content)
        val refused = runBlocking { connector.call(key, "drive", "files.export", Json.parseToJsonElement("""{"fileId":"doc-1","mimeType":"application/pdf"}""").jsonObject) }
        assertIs<GoogleCall.Invalid>(refused)
    }

    @Test
    fun `playlists, channel sections and playlist items list read-only and paged`() {
        val (mine, playlists) = run(
            "youtube/playlists.list",
            """{"part":["snippet","contentDetails"],"mine":true,"maxResults":50,"pageToken":"p1"}""",
            FakeGoogle.json(200, """{"items":[{"id":"PL1","snippet":{"title":"Linear Algebra"}}],"nextPageToken":"p2"}"""),
        )
        assertEquals("GET", mine.method)
        assertEquals("/youtube/v3/playlists", mine.requestUrl!!.encodedPath)
        assertEquals(listOf("snippet", "contentDetails"), mine.requestUrl!!.queryParameterValues("part"))
        assertEquals(mapOf("mine" to "true", "maxResults" to "50", "pageToken" to "p1"), mine.query() - "part")
        assertEquals("p2", playlists["nextPageToken"]?.jsonPrimitive?.content)
        val (byId, _) = run("youtube/playlists.list", """{"part":["snippet"],"id":["PL1","PL2"]}""", FakeGoogle.json(200, """{"items":[]}"""))
        assertEquals(listOf("PL1", "PL2"), byId.requestUrl!!.queryParameterValues("id"))
        val (sections, _) = run("youtube/channelSections.list", """{"part":["contentDetails"],"mine":true}""", FakeGoogle.json(200, """{"items":[{"contentDetails":{"playlists":["PL3"]}}]}"""))
        assertEquals("/youtube/v3/channelSections", sections.requestUrl!!.encodedPath)
        assertEquals(mapOf("part" to "contentDetails", "mine" to "true"), sections.query())
        val (items, page) = run(
            "youtube/playlistItems.list",
            """{"part":["snippet","contentDetails"],"playlistId":"PL1","maxResults":50,"pageToken":"p3"}""",
            FakeGoogle.json(200, """{"items":[{"contentDetails":{"videoId":"vid00000001"}}]}"""),
        )
        assertEquals("/youtube/v3/playlistItems", items.requestUrl!!.encodedPath)
        assertEquals(listOf("snippet", "contentDetails"), items.requestUrl!!.queryParameterValues("part"))
        assertEquals(mapOf("playlistId" to "PL1", "maxResults" to "50", "pageToken" to "p3"), items.query() - "part")
        assertEquals("vid00000001", page["items"]!!.jsonArray.single().jsonObject["contentDetails"]!!.jsonObject["videoId"]?.jsonPrimitive?.content)
        assertTrue(google.apiRequests.none { it.method != "GET" })
    }

    @Test
    fun `every allow-listed operation is exercised here`() {
        `calendars insert and get`()
        `events insert with a conference request, patch and delete`()
        `events list incrementally with a sync token`()
        `events watch and channels stop`()
        `freebusy query`()
        `files create converts HTML into a Doc in a folder, and creates the folder`()
        `files get and export as text`()
        `playlists, channel sections and playlist items list read-only and paged`()
        assertEquals(GoogleOperations.names, tested)
    }

    companion object {
        const val CALENDAR = "https://www.googleapis.com/auth/calendar.app.created"
        const val FREEBUSY = "https://www.googleapis.com/auth/calendar.freebusy"
        const val DRIVE = "https://www.googleapis.com/auth/drive.file"
        const val YOUTUBE = "https://www.googleapis.com/auth/youtube.readonly"
    }
}
