package dev.shibasis.reaktor.google.connect

import com.google.api.client.googleapis.services.json.AbstractGoogleJsonClientRequest
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.HttpTransport
import com.google.api.client.http.GenericUrl
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.calendar.model.Channel
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.FreeBusyRequest
import com.google.api.services.drive.model.File
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.OAuth2Credentials
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import com.google.api.services.calendar.Calendar as CalendarClient
import com.google.api.services.calendar.model.Calendar as CalendarResource
import com.google.api.services.drive.Drive as DriveClient
import com.google.api.services.youtube.YouTube as YouTubeClient

internal val googleJson: JsonFactory = GsonFactory.getDefaultInstance()

internal class GoogleOperation(val scopes: Set<String>, val prepare: (GoogleClients, Params) -> () -> String)

internal class GoogleClients(private val transport: HttpTransport, token: String, private val root: String?) {
    private val adapter = HttpCredentialsAdapter(OAuth2Credentials.create(AccessToken(token, null)))
    private val credentials = HttpRequestInitializer { request ->
        adapter.initialize(request)
        request.unsuccessfulResponseHandler = null
    }

    val calendar: CalendarClient by lazy {
        CalendarClient.Builder(transport, googleJson, credentials).setApplicationName(APPLICATION).also { builder -> root?.let { builder.setRootUrl(it) } }.build()
    }

    val drive: DriveClient by lazy {
        DriveClient.Builder(transport, googleJson, credentials).setApplicationName(APPLICATION).also { builder -> root?.let { builder.setRootUrl(it) } }.build()
    }

    val youtube: YouTubeClient by lazy {
        YouTubeClient.Builder(transport, googleJson, credentials).setApplicationName(APPLICATION).also { builder -> root?.let { builder.setRootUrl(it) } }.build()
    }

    fun document(id: String, body: JsonObject? = null): () -> String {
        require(Regex("[A-Za-z0-9_-]{1,200}").matches(id)) { "'documentId' is a Google document id." }
        val url = GenericUrl("${root ?: "https://docs.googleapis.com/"}v1/documents/$id${if (body == null) "?includeTabsContent=true&commentsViewMode=COMMENTS_VIEW_MODE_INCLUDED&suggestionsViewMode=SUGGESTIONS_INLINE" else ":batchUpdate"}")
        return {
            val content = body?.let { ByteArrayContent("application/json", it.toString().encodeToByteArray()) }
            val request = transport.createRequestFactory(credentials).buildRequest(if (body == null) "GET" else "POST", url, content)
            val response = request.execute()
            try {
                val bytes = response.content.readNBytes(10 * 1024 * 1024 + 1)
                require(bytes.size <= 10 * 1024 * 1024) { "The document response exceeds 10 MiB." }
                bytes.decodeToString()
            } finally { response.disconnect() }
        }
    }

    private companion object {
        const val APPLICATION = "reaktor-google"
    }
}

internal class Params(private val values: JsonObject) {
    fun string(name: String): String = optionalString(name) ?: throw IllegalArgumentException("'$name' is required.")

    fun optionalString(name: String): String? = primitive(name)?.takeIf { it.isString }?.content

    fun optionalInt(name: String): Int? = primitive(name)?.intOrNull

    fun optionalLong(name: String): Long? = primitive(name)?.longOrNull

    fun optionalBoolean(name: String): Boolean? = primitive(name)?.booleanOrNull

    fun strings(name: String): List<String> = optionalStrings(name) ?: throw IllegalArgumentException("'$name' is required.")

    fun optionalStrings(name: String): List<String>? = (values[name] as? JsonArray)?.map { item ->
        (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("'$name' lists strings.")
    }

    fun section(name: String): Params? = (values[name] as? JsonObject)?.let(::Params)

    fun objectValue(name: String): JsonObject = values[name] as? JsonObject ?: throw IllegalArgumentException("'$name' is required.")

    fun <T> resource(name: String, type: Class<T>): T {
        val body = values[name] as? JsonObject ?: throw IllegalArgumentException("'$name' is required.")
        return try {
            googleJson.fromString(body.toString(), type)
        } catch (error: IOException) {
            throw IllegalArgumentException("'$name' is not a valid ${type.simpleName}.")
        }
    }

    private fun primitive(name: String): JsonPrimitive? = values[name] as? JsonPrimitive
}

internal object GoogleOperations {
    private const val AUTH = "https://www.googleapis.com/auth/"
    private const val MAX_EXPORT_BYTES = 10 * 1024 * 1024

    private fun scopes(vararg names: String): Set<String> = names.map { AUTH + it }.toSet()

    private val calendarsWrite = scopes("calendar", "calendar.app.created", "calendar.calendars")
    private val calendarsRead = calendarsWrite + scopes("calendar.readonly", "calendar.calendars.readonly")
    private val eventsWrite = scopes("calendar", "calendar.events", "calendar.app.created", "calendar.events.owned")
    private val eventsRead = eventsWrite + scopes("calendar.readonly", "calendar.events.readonly", "calendar.events.owned.readonly")
    private val freeBusy = scopes("calendar", "calendar.readonly", "calendar.freebusy", "calendar.events.freebusy")
    private val anyCalendar = calendarsRead + eventsRead + freeBusy
    private val driveWrite = scopes("drive", "drive.file")
    private val driveRead = driveWrite + scopes("drive.readonly")
    private val driveMetadata = driveRead + scopes("drive.metadata", "drive.metadata.readonly")
    private val youtubeRead = scopes("youtube", "youtube.readonly", "youtube.force-ssl", "youtubepartner")

    private fun json(request: AbstractGoogleJsonClientRequest<*>): () -> String = {
        request.execute()?.let(googleJson::toString) ?: "{}"
    }

    private val operations: Map<String, GoogleOperation> = mapOf(
        "calendar/calendars.insert" to GoogleOperation(calendarsWrite) { clients, params ->
            json(clients.calendar.calendars().insert(params.resource("body", CalendarResource::class.java)))
        },
        "calendar/calendars.get" to GoogleOperation(calendarsRead) { clients, params ->
            json(clients.calendar.calendars().get(params.string("calendarId")))
        },
        "calendar/events.insert" to GoogleOperation(eventsWrite) { clients, params ->
            json(
                clients.calendar.events().insert(params.string("calendarId"), params.resource("body", Event::class.java))
                    .setConferenceDataVersion(params.optionalInt("conferenceDataVersion"))
                    .setSendUpdates(params.optionalString("sendUpdates")),
            )
        },
        "calendar/events.patch" to GoogleOperation(eventsWrite) { clients, params ->
            json(
                clients.calendar.events().patch(params.string("calendarId"), params.string("eventId"), params.resource("body", Event::class.java))
                    .setConferenceDataVersion(params.optionalInt("conferenceDataVersion"))
                    .setSendUpdates(params.optionalString("sendUpdates")),
            )
        },
        "calendar/events.delete" to GoogleOperation(eventsWrite) { clients, params ->
            json(clients.calendar.events().delete(params.string("calendarId"), params.string("eventId")).setSendUpdates(params.optionalString("sendUpdates")))
        },
        "calendar/events.list" to GoogleOperation(eventsRead) { clients, params ->
            json(
                clients.calendar.events().list(params.string("calendarId"))
                    .setSyncToken(params.optionalString("syncToken"))
                    .setPageToken(params.optionalString("pageToken"))
                    .setShowDeleted(params.optionalBoolean("showDeleted"))
                    .setMaxResults(params.optionalInt("maxResults")),
            )
        },
        "calendar/events.watch" to GoogleOperation(eventsRead) { clients, params ->
            json(clients.calendar.events().watch(params.string("calendarId"), params.resource("body", Channel::class.java)))
        },
        "calendar/channels.stop" to GoogleOperation(anyCalendar) { clients, params ->
            json(clients.calendar.channels().stop(params.resource("body", Channel::class.java)))
        },
        "calendar/freebusy.query" to GoogleOperation(freeBusy) { clients, params ->
            json(clients.calendar.freebusy().query(params.resource("body", FreeBusyRequest::class.java)))
        },
        "drive/files.create" to GoogleOperation(driveWrite) { clients, params ->
            val metadata = params.resource("body", File::class.java)
            val media = params.section("media")
            val request = if (media == null) clients.drive.files().create(metadata)
            else clients.drive.files().create(metadata, ByteArrayContent(media.string("mimeType"), media.string("text").encodeToByteArray()))
            request.mediaHttpUploader?.setDirectUploadEnabled(true)
            json(request.setFields(params.optionalString("fields")))
        },
        "drive/files.get" to GoogleOperation(driveMetadata) { clients, params ->
            json(clients.drive.files().get(params.string("fileId")).setFields(params.optionalString("fields")))
        },
        "drive/files.export" to GoogleOperation(driveRead) { clients, params ->
            val mimeType = params.string("mimeType")
            require(mimeType.startsWith("text/")) { "Export returns text, so 'mimeType' starts with text/." }
            val request = clients.drive.files().export(params.string("fileId"), mimeType)
            val export: () -> String = {
                val bytes = request.executeMediaAsInputStream().use { stream -> stream.readNBytes(MAX_EXPORT_BYTES + 1) }
                if (bytes.size > MAX_EXPORT_BYTES) throw IllegalArgumentException("The export is larger than $MAX_EXPORT_BYTES bytes.")
                buildJsonObject { put("text", bytes.decodeToString()) }.toString()
            }
            export
        },
        "docs/documents.get" to GoogleOperation(driveRead + scopes("documents", "documents.readonly")) { clients, params ->
            clients.document(params.string("documentId"))
        },
        "docs/documents.batchUpdate" to GoogleOperation(driveWrite + scopes("documents")) { clients, params ->
            val body = params.objectValue("body")
            val revision = (body["writeControl"] as? JsonObject)?.get("requiredRevisionId") as? JsonPrimitive
            require(revision?.isString == true && revision.content.isNotBlank()) { "A document write requires 'writeControl.requiredRevisionId'." }
            clients.document(params.string("documentId"), body)
        },
        "youtube/playlists.list" to GoogleOperation(youtubeRead) { clients, params ->
            json(
                clients.youtube.playlists().list(params.strings("part"))
                    .setMine(params.optionalBoolean("mine"))
                    .setId(params.optionalStrings("id"))
                    .setMaxResults(params.optionalLong("maxResults"))
                    .setPageToken(params.optionalString("pageToken")),
            )
        },
        "youtube/channelSections.list" to GoogleOperation(youtubeRead) { clients, params ->
            json(clients.youtube.channelSections().list(params.strings("part")).setMine(params.optionalBoolean("mine")))
        },
        "youtube/playlistItems.list" to GoogleOperation(youtubeRead) { clients, params ->
            json(
                clients.youtube.playlistItems().list(params.strings("part"))
                    .setPlaylistId(params.string("playlistId"))
                    .setMaxResults(params.optionalLong("maxResults"))
                    .setPageToken(params.optionalString("pageToken")),
            )
        },
    )

    operator fun get(name: String): GoogleOperation? = operations[name]

    val names: Set<String> get() = operations.keys
}
