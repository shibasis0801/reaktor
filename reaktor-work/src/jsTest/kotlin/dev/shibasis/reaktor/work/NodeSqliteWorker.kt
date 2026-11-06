package dev.shibasis.reaktor.work

@JsModule("node:sqlite")
private external object NodeSqlite {
    class DatabaseSync(path: String) {
        fun prepare(sql: String): dynamic
        fun exec(sql: String)
        fun close()
    }
}

internal class NodeSqliteWorker {
    private val database = NodeSqlite.DatabaseSync(":memory:")
    private val listeners = mutableListOf<dynamic>()

    @JsName("addEventListener")
    fun addEventListener(type: String, listener: dynamic) {
        if (type == "message") listeners.add(listener)
    }

    @JsName("removeEventListener")
    fun removeEventListener(type: String, listener: dynamic) {
        listeners.remove(listener)
    }

    @JsName("postMessage")
    fun postMessage(message: dynamic) {
        val reply: dynamic = js("({})")
        reply.id = message.id
        try {
            reply.results = handle(message)
        } catch (failure: Throwable) {
            val error: dynamic = js("({})")
            error.name = failure::class.simpleName
            error.message = failure.message
            reply.error = error
        }
        val event: dynamic = js("({})")
        event.data = reply
        listeners.toList().forEach { listener -> if (jsTypeOf(listener) == "function") listener(event) else listener.handleEvent(event) }
    }

    @JsName("terminate")
    fun terminate() = database.close()

    private fun handle(message: dynamic): dynamic = when (val action = message.action as String) {
        "exec" -> execute(message.sql as String, (message.params ?: emptyArray<Any?>()).unsafeCast<Array<Any?>>())
        "begin_transaction" -> changed("BEGIN TRANSACTION")
        "end_transaction" -> changed("COMMIT")
        "rollback_transaction" -> changed("ROLLBACK")
        else -> error("Unknown SQLDelight action: $action")
    }

    private fun execute(sql: String, parameters: Array<Any?>): dynamic {
        val statement = database.prepare(sql)
        val bound = parameters.map(::integral).toTypedArray()
        val result: dynamic = js("({})")
        result.values = if ((statement.columns().length as Int) > 0) {
            statement.setReturnArrays(true)
            statement.all.apply(statement, bound)
        } else {
            arrayOf(arrayOf(statement.run.apply(statement, bound).changes))
        }
        return result
    }

    private fun changed(sql: String): dynamic {
        database.exec(sql)
        val result: dynamic = js("({})")
        result.values = arrayOf(arrayOf(0))
        return result
    }

    private fun integral(value: Any?): Any? =
        if (jsTypeOf(value) == "number" && js("Number").isSafeInteger(value) as Boolean) js("BigInt")(value) else value
}
