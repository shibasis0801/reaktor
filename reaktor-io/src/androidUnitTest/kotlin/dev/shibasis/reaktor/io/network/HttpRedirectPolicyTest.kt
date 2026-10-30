package dev.shibasis.reaktor.io.network

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpRedirectPolicyTest {
    @Test fun aClonedClientCanRefuseRedirectsInTheRealEngine() = runBlocking {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        val requests = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        val listener = executor.submit {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    val first = requests.incrementAndGet() == 1
                    val status = if (first) "302 Found" else "200 OK"
                    val location = if (first) "Location: http://127.0.0.1:${server.localPort}/other\r\n" else ""
                    socket.getOutputStream().write(
                        "HTTP/1.1 $status\r\n${location}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                    )
                }
            } catch (error: SocketException) {
                if (!server.isClosed) throw error
            }
        }
        val client = http.config { followRedirects = false }
        try {
            assertEquals(HttpStatusCode.Found, client.get("http://127.0.0.1:${server.localPort}/chat/photo").status)
            assertEquals(1, requests.get())
        } finally {
            client.close()
            server.close()
            try { listener.get(5, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
        }
    }
}
