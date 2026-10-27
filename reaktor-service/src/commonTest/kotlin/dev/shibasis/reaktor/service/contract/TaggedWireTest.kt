package dev.shibasis.reaktor.service.contract

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.service.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Serializable
@SerialName("test.LibraryResponse")
sealed class LibraryResponse {
    @Serializable
    @SerialName("test.LibraryResponse.Shelf")
    data class Shelf(val books: List<String>) : LibraryResponse()

    @Serializable
    @SerialName("test.LibraryResponse.Lent")
    data object Lent : LibraryResponse()

    @Serializable
    @SerialName("test.LibraryResponse.Waitlist")
    data class Waitlist(val place: Int) : LibraryResponse()

    @Serializable
    @SerialName("test.LibraryResponse.Shelved")
    data object Shelved : LibraryResponse()

    @Serializable
    @SerialName("test.LibraryResponse.Fined")
    data class Fined(val amount: Int) : LibraryResponse()

    @Serializable
    @SerialName("test.LibraryResponse.Refused")
    data class Refused(val reason: String, val status: Int) : LibraryResponse()

    @Serializable
    @SerialName("test.LibraryResponse.NotFound")
    data object NotFound : LibraryResponse()

    @Serializable
    @SerialName("test.LibraryResponse.Unauthorized")
    data object Unauthorized : LibraryResponse()

    @Serializable
    @SerialName("test.LibraryResponse.Failed")
    data class Failed(val reason: String, val status: Int) : LibraryResponse()
}

@Serializable
@SerialName("test.LibraryResponse.Shelf")
data class Shelf(val books: List<String>)

@Serializable
@SerialName("test.LibraryResponse.Lent")
data object Lent

@Serializable
@SerialName("test.LibraryResponse.Waitlist")
data class Waitlist(val place: Int)

@Serializable
@SerialName("test.LibraryResponse.Returned")
sealed interface Returned {
    @Serializable
    @SerialName("test.LibraryResponse.Shelved")
    data object Shelved : Returned

    @Serializable
    @SerialName("test.LibraryResponse.Fined")
    data class Fined(val amount: Int) : Returned
}

open class Library : Service("test.library") {
    override val wire = Wire.Tagged(
        variant = { status -> when (status) { 401 -> "Unauthorized"; 404 -> "NotFound"; in 400..499 -> "Refused"; else -> "Failed" } },
        reason = "reason",
        fields = { status -> mapOf("status" to JsonPrimitive(status)) },
    )

    open val shelf by get<String, Shelf>("/shelves/{shelf}")
    open val lend by post<String, Lent>("/books/{book}/lend")
    open val giveBack by post<String, Returned>("/books/{book}/return")
}

class LibraryServer : Library() {
    override val shelf = super.shelf.handle { shelf ->
        when (shelf) {
            "fiction" -> Shelf(listOf("Dune"))
            "closed" -> reject(401, "Sign in again.")
            "rare" -> reject(403, "Ask a librarian.")
            "burnt" -> error("The shelf is on fire")
            else -> reject(404, "No such shelf")
        }
    }

    override val lend = super.lend.handle { book -> if (book == "dune") reject(Waitlist(3), 409, "Dune is out.") else Lent }

    override val giveBack = super.giveBack.handle { book ->
        when (book) {
            "emma" -> Returned.Shelved
            "dune" -> Returned.Fined(2)
            else -> reject(404, "Not ours")
        }
    }
}

class TaggedWireTest {
    private val served = Served(LibraryServer())

    private suspend fun legacy(method: HttpMethod, path: String, body: String? = null): Pair<Int, LibraryResponse> =
        served.dispatch(method, path, Inbound(body = body)).let { it.status to json.decodeFromString(LibraryResponse.serializer(), it.body) }

    @Test
    fun aLegacyClientReadsEveryAnswerAsItsOldVariant() = runTest {
        assertEquals(200 to LibraryResponse.Shelf(listOf("Dune")), legacy(HttpMethod.GET, "/shelves/fiction"))
        assertEquals(401 to LibraryResponse.Unauthorized, legacy(HttpMethod.GET, "/shelves/closed"))
        assertEquals(403 to LibraryResponse.Refused("Ask a librarian.", 403), legacy(HttpMethod.GET, "/shelves/rare"))
        assertEquals(404 to LibraryResponse.NotFound, legacy(HttpMethod.GET, "/shelves/poetry"))
        assertEquals(500 to LibraryResponse.Failed("Internal server error", 500), legacy(HttpMethod.GET, "/shelves/burnt"))
        assertEquals(200 to LibraryResponse.Lent, legacy(HttpMethod.POST, "/books/emma/lend", """{"headers":{},"queryParams":{},"pathParams":{"book":"emma"}}"""))
        assertEquals(409 to LibraryResponse.Waitlist(3), legacy(HttpMethod.POST, "/books/dune/lend", "{}"))
        assertEquals(200 to LibraryResponse.Shelved, legacy(HttpMethod.POST, "/books/emma/return", "{}"))
        assertEquals(200 to LibraryResponse.Fined(2), legacy(HttpMethod.POST, "/books/dune/return"))
        assertEquals(404 to LibraryResponse.NotFound, legacy(HttpMethod.POST, "/books/ulysses/return"))
    }

    @Test
    fun aCurrentClientReadsTheSameAnswersAsTypedValuesAndFailures() = runTest {
        withContext(Dispatchers.Default) {
            val api = Library() over HttpLink("https://library.test", loopback(served))
            assertEquals(Shelf(listOf("Dune")), api.shelf("fiction"))
            assertEquals(Lent, api.lend("emma"))
            val waiting = assertFailsWith<Rejected> { api.lend("dune") }
            assertEquals(409 to "Dune is out.", waiting.status to waiting.message)
            assertEquals(Waitlist(3), waiting.problemAs<Waitlist>())
            assertEquals(404, assertFailsWith<Rejected> { api.shelf("poetry") }.status)
            assertEquals(Returned.Fined(2), api.giveBack("dune"))
        }
    }
}
