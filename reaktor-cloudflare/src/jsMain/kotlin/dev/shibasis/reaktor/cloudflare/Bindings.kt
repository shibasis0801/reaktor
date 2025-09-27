@file:Suppress("unused", "PropertyName")

package dev.shibasis.reaktor.cloudflare

import kotlin.js.JsName
import kotlin.js.Promise
import org.w3c.dom.WebSocket
import org.w3c.fetch.RequestInit
import org.w3c.fetch.RequestInfo
import org.w3c.fetch.Response
import web.streams.ReadableStream
import web.streams.WritableStream

/** Pair of interconnected websockets provided by Cloudflare. */
external class WebSocketPair {
    @JsName("0")
    val first: WebSocket

    @JsName("1")
    val second: WebSocket
}

/** Low level TCP socket interface exposed to Workers. */
external interface Socket {
    val readable: ReadableStream<Any?>
    val writable: WritableStream<Any?>
    val closed: Promise<Unit>
    val opened: Promise<SocketInfo>
    fun close(): Promise<Unit>
    fun startTls(options: TlsOptions = definedExternally): Socket
}

/** Options that control TCP connection behavior. */
external interface SocketOptions {
    var secureTransport: String?
    var allowHalfOpen: Boolean
}

/** Host/port pair used when establishing outbound connections. */
external interface SocketAddress {
    var hostname: String
    var port: Int
}

/** TLS settings used when upgrading sockets. */
external interface TlsOptions {
    var expectedServerHostname: String?
}

/** Metadata returned once a socket is opened. */
external interface SocketInfo {
    var remoteAddress: String?
    var localAddress: String?
}

/** Common fetcher interface shared by service and durable object bindings. */
external interface Fetcher {
    fun fetch(input: RequestInfo, init: RequestInit = definedExternally): Promise<Response>
    fun connect(address: String, options: SocketOptions = definedExternally): Socket
    fun connect(address: SocketAddress, options: SocketOptions = definedExternally): Socket
}

/** Service bindings resolve to fetcher instances. */
typealias Service<T> = Fetcher

/**
 * Helper describing options accepted by cache writes performed through fetchers.
 */
external interface FetcherPutOptions {
    var expiration: Int?
    var expirationTtl: Int?
}
