@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package dev.shibasis.reaktor.board.web

import dev.shibasis.reaktor.board.BoardChange
import dev.shibasis.reaktor.board.BoardEdit
import dev.shibasis.reaktor.board.BoardItem
import dev.shibasis.reaktor.board.BoardReceipt
import dev.shibasis.reaktor.board.BoardSelect
import dev.shibasis.reaktor.board.BoardService
import dev.shibasis.reaktor.board.BoardSnapshot
import dev.shibasis.reaktor.board.BoardTheme
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import react.useEffectOnceWithCleanup
import react.useMemo
import react.useState
import kotlin.js.Promise

class BoardClient(private val client: dynamic) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun read(): Promise<BoardSnapshot> = call(BoardService.Read, js("({})"), BoardSnapshot.serializer())
    fun change(change: BoardChange): Promise<BoardReceipt> = call(BoardService.Change, encode(BoardChange.serializer(), change), BoardReceipt.serializer())
    fun select(select: BoardSelect): Promise<BoardReceipt> = call(BoardService.Select, encode(BoardSelect.serializer(), select), BoardReceipt.serializer())
    fun changes(listener: (BoardSnapshot) -> Unit): Promise<Any?> =
        client.subscribe(BoardService.Changed, { payload: dynamic -> listener(decode(BoardSnapshot.serializer(), payload)) }).unsafeCast<Promise<Any?>>()

    private fun <T> call(operation: String, payload: dynamic, serializer: KSerializer<T>): Promise<T> =
        client.invoke(operation, payload).unsafeCast<Promise<Any?>>().then { decode(serializer, it.asDynamic()) }
    private fun <T> encode(serializer: KSerializer<T>, value: T): dynamic = JSON.parse<dynamic>(json.encodeToString(serializer, value))
    private fun <T> decode(serializer: KSerializer<T>, value: dynamic): T = json.decodeFromString(serializer, JSON.stringify(value))
}

private class BridgeHost(private val client: BoardClient, private val canvas: BoardCanvas, private val revision: Long, override val places: Boolean,
    private val sending: (Int) -> Unit) : BoardHost {
    override fun select(key: String) { client.select(BoardSelect(key)) }
    override fun open(key: String) { client.select(BoardSelect(key, open = true)) }
    override fun edit(edit: BoardEdit, before: BoardItem?): BoardOutcome {
        sending(1)
        client.change(BoardChange(edit, revision)).then({ receipt ->
            sending(-1)
            receipt.error?.let { refused(edit, it) }
        }, { failure -> sending(-1); refused(edit, failure.message ?: "The change could not be sent.") })
        return BoardOutcome()
    }
    private fun refused(edit: BoardEdit, reason: String) {
        if (edit is BoardEdit.Place) canvas.unplace(edit.key)
        canvas.message(reason)
    }
}

@JsExport
object ReaktorBoardWeb {
    fun Page(props: dynamic): dynamic {
        val client = useMemo { BoardClient(props.client) }
        val kit = useMemo { BoardKit(props.Canvas, props.convert, props.neverCapture) }
        val canvas = useMemo { BoardCanvas() }
        val (snapshot, setSnapshot) = useState<BoardSnapshot?>(null)
        val (failure, setFailure) = useState<String?>(null)
        val (inFlight, setInFlight) = useState(0)
        useEffectOnceWithCleanup {
            var active = true
            var stop: dynamic = null
            client.read().then({ if (active) setSnapshot(it) }, { if (active) setFailure(it.message ?: "The board could not be read.") })
            client.changes { if (active) setSnapshot(it) }.then({ unsubscribe: Any? -> if (active) { stop = unsubscribe } else { unsubscribe.asDynamic()() } }, { _: Throwable -> })
            onCleanup { active = false; if (stop != null) stop() }
        }
        val host = useMemo(snapshot?.revision, snapshot?.places) { BridgeHost(client, canvas, snapshot?.revision ?: 0, snapshot?.places == true) { delta -> setInFlight { it + delta } } }
        val current = snapshot ?: return el("p", obj("className" to "board-waiting", "role" to "status"), failure ?: "Opening the board…")
        val theme = current.theme ?: BoardTheme()
        val model = BoardModel(current.board, current.board.items, host, kit, "reaktor-board:${current.layout}", current.active, current.view,
            BoardSync(pending = inFlight > 0), theme, BoardSlots(tabs = false, empty = current.empty), canvas = canvas)
        return el(BoardWorkbench, obj("model" to model))
    }
}
