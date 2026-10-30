package dev.shibasis.reaktor.security

import java.net.URI
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private val proofJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
private fun proofObject(vararg values: Pair<String, JsonElement>) = JsonObject(values.toMap())

class DevDirectoryJvmTest {
    @Test fun twoJvmClientsUseTheDevDirectoryAndSocketAndRecoverAfterConflictAndRestart() {
        if (System.getenv("RSEC_DEV_DIRECTORY_ORIGIN") == null) return
        listOf("RSEC_DEV_CHAT_ORIGIN", "RSEC_DEV_FIXTURES", "RSEC_DEV_CHAT_ID", "RSEC_DEV_PINS", "RSEC_DEV_APP_ID").forEach {
            check(!System.getenv(it).isNullOrBlank()) { "Missing $it" }
        }
        val home = Files.createTempDirectory("rsec-dev-jvm-").toFile()
        home.setReadable(false, false); home.setWritable(false, false); home.setExecutable(false, false)
        home.setReadable(true, true); home.setWritable(true, true); home.setExecutable(true, true)
        val run = UUID.randomUUID().toString()
        val first = home.resolve("first").toPath()
        val second = home.resolve("second").toPath()
        val clients = mutableListOf<ProofProcess>()
        fun client(index: Int, path: Path) = ProofProcess(index, path, run).also { clients += it }
        try {
            var alice = client(0, first)
            var bob = client(1, second)
            val firstPid = alice.call("init").text("pid")
            assertNotEquals(firstPid, bob.call("init").text("pid"))
            alice.call("prepareCreate")
            bob.call("prepareCreate")
            assertTrue(alice.call("publish").getValue("accepted").jsonPrimitive.boolean)
            assertEquals(false, bob.call("publish").getValue("accepted").jsonPrimitive.boolean)
            bob.call("join")
            alice.call("connect"); bob.call("connect")
            val initial = alice.call("send")
            assertEquals(initial.text("digest"), bob.call("receive", initial).text("digest"))
            alice.call("prepareRotate"); bob.call("prepareRotate")
            assertTrue(bob.call("publish").getValue("accepted").jsonPrimitive.boolean)
            assertEquals(false, alice.call("publish").getValue("accepted").jsonPrimitive.boolean)
            alice.call("catchUp")
            val rotated = bob.call("send")
            assertEquals(rotated.text("digest"), alice.call("receive", rotated).text("digest"))
            alice.close()
            alice = client(0, first)
            assertNotEquals(firstPid, alice.call("init").text("pid"))
            assertEquals(initial.text("digest"), alice.call("history", initial).text("digest"))
            bob.close()
            bob = client(1, second)
            bob.call("init")
            assertEquals(rotated.text("digest"), bob.call("history", rotated).text("digest"))
            assertEquals(alice.call("epoch").text("epoch"), bob.call("epoch").text("epoch"))
            System.getenv("RSEC_DEV_PROOF_REPORT")?.let { destination ->
                Files.writeString(Path.of(destination), proofObject(
                    "chatId" to JsonPrimitive(System.getenv("RSEC_DEV_CHAT_ID")),
                    "twoJvmClients" to JsonPrimitive(true), "conflictsRecovered" to JsonPrimitive(2),
                    "processRestarts" to JsonPrimitive(2), "historyOpened" to JsonPrimitive(2),
                    "messageClientIds" to JsonArray(listOf(initial.getValue("clientId"), rotated.getValue("clientId"))),
                    "epoch" to alice.call("epoch").getValue("epoch"),
                    "transport" to JsonPrimitive("Sealed content v2 through dev directory, socket and history"),
                ).toString())
            }
        } finally {
            clients.forEach { it.close() }
            home.walkBottomUp().forEach { it.delete() }
        }
    }

    private class ProofProcess(index: Int, home: Path, run: String) : AutoCloseable {
        private val reader = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "rsec-dev-client-output").apply { isDaemon = true } }
        private val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--enable-native-access=ALL-UNNAMED", "-Xmx192m",
            "-Djna.library.path=${System.getProperty("jna.library.path")}",
            "-cp", classPath(), DevDirectoryJvmClient::class.java.name,
            index.toString(), home.toString(), run,
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        private val input = process.outputStream.bufferedWriter()
        private val output = process.inputStream.bufferedReader()
        private var closed = false

        fun call(operation: String, arguments: JsonObject = JsonObject(emptyMap())): JsonObject {
            input.write(proofObject("operation" to JsonPrimitive(operation), "arguments" to arguments).toString())
            input.newLine(); input.flush()
            val response = reader.submit<JsonObject> {
                generateSequence { output.readLine() }.first { it.startsWith("RSEC_TEST:") }
                    .removePrefix("RSEC_TEST:").let { proofJson.parseToJsonElement(it).jsonObject }
            }.get(90, TimeUnit.SECONDS)
            check(response["error"] == null) { "Dev client $operation failed: ${response["error"]?.jsonPrimitive?.content}" }
            return response.getValue("result").jsonObject
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { call("close") }
            input.close()
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
            reader.shutdownNow()
        }

        private fun classPath(): String = buildList {
            addAll(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
            var loader: ClassLoader? = Thread.currentThread().contextClassLoader
            while (loader != null) {
                if (loader is URLClassLoader) addAll(loader.urLs.map { Path.of(it.toURI()).toString() })
                loader = loader.parent
            }
        }.distinct().joinToString(java.io.File.pathSeparator)
    }
}

object DevDirectoryJvmProof {
    @JvmStatic fun main(args: Array<String>) {
        DevDirectoryJvmTest().twoJvmClientsUseTheDevDirectoryAndSocketAndRecoverAfterConflictAndRestart()
    }
}

object DevDirectoryJvmClient {
    @JvmStatic fun main(args: Array<String>) {
        val client = Client(args[0].toInt(), Path.of(args[1]), args[2])
        try {
            System.`in`.bufferedReader().forEachLine { line ->
                val command = proofJson.parseToJsonElement(line).jsonObject
                val operation = command.text("operation")
                val response = try {
                    proofObject("result" to client.call(operation, command.getValue("arguments").jsonObject))
                } catch (error: Throwable) {
                    val detail = error.message?.takeIf { it.startsWith("Directory status ") || it.startsWith("History status ") }
                    proofObject("error" to JsonPrimitive(listOfNotNull(error.javaClass.simpleName, detail).joinToString(": ")))
                }
                println("RSEC_TEST:$response")
                System.out.flush()
            }
        } finally { client.close() }
    }

    private class Client(index: Int, private val home: Path, run: String) : AutoCloseable {
        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
        private val fixtures = proofJson.parseToJsonElement(Files.readString(Path.of(System.getenv("RSEC_DEV_FIXTURES")))).jsonArray
        private val fixture = fixtures[index].jsonObject
        private val user = fixture.text("userId")
        private val token = fixture.text("accessToken")
        private val memberCount = (System.getenv("RSEC_DEV_MEMBER_COUNT")?.toInt() ?: 2).also { require(it in 2..3) }
        private val members = fixtures.take(memberCount).map { it.jsonObject.text("userId") }.toSet().also { require(it.size == memberCount) }
        private val app = System.getenv("RSEC_DEV_APP_ID")
        private val chat = System.getenv("RSEC_DEV_CHAT_ID")
        private val origin = System.getenv("RSEC_DEV_DIRECTORY_ORIGIN").trimEnd('/')
        private val device = "rsec-proof-$run-$index"
        private val policy = EnginePolicy(app, app, proofJson.parseToJsonElement(Files.readString(Path.of(System.getenv("RSEC_DEV_PINS"))))
            .jsonObject.getValue("keys").jsonArray.associate { element ->
                val key = element.jsonObject
                key.text("kid") to (byteArrayOf(4) + Base64.getUrlDecoder().decode(key.text("x")) + Base64.getUrlDecoder().decode(key.text("y")))
            })
        private lateinit var engine: NativeSecurityEngine
        private var pending: EngineCommit? = null
        private var publication: CommitPublication? = null
        private var socket: WebSocket? = null
        private val frames = LinkedBlockingQueue<String>()
        private val storageKey: ByteArray

        init {
            Files.createDirectories(home)
            Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwx------"))
            val keyFile = home.resolve("storage-key")
            storageKey = if (Files.exists(keyFile)) Files.readAllBytes(keyFile) else NativeSecurityEngine.storageKey().also { privateWrite(keyFile, it) }
        }

        fun call(operation: String, arguments: JsonObject): JsonObject = when (operation) {
            "init" -> {
                val snapshot = home.resolve("state")
                val restoring = Files.exists(snapshot)
                engine = if (restoring) {
                    val clear = NativeSecurityEngine.openStorage(storageKey, device.encodeToByteArray(), Files.readAllBytes(snapshot))
                    try { NativeSecurityEngine.restore(clear, policy) } finally { clear.fill(0) }
                } else NativeSecurityEngine.create(policy)
                val registered = post<DeviceRegistration, DirectoryDevice>("/security/devices", DeviceRegistration(device, engine.publicKey(), 3, engine.proveDevice(user, device)))
                if (!restoring) engine.installCredential(registered.credential)
                engine.setMembers(chat, members)
                post<KeyPackagePublication, Int>("/security/key-packages", KeyPackagePublication(device,
                    List(3) { PublishedKeyPackage(UUID.randomUUID().toString(), engine.keyPackage()) }))
                persist()
                proofObject("pid" to JsonPrimitive(ProcessHandle.current().pid()))
            }
            "directory" -> {
                val devices = post<DirectoryLookup, List<DirectoryDevice>>("/security/devices/list", DirectoryLookup(chat, members))
                val info = post<String, DirectoryGroupInfo>("/security/group-info", chat)
                proofObject("membersReady" to JsonPrimitive(devices.map { it.userId }.toSet() == members),
                    "activeDevices" to JsonPrimitive(devices.size), "epoch" to (info.epoch?.let(::JsonPrimitive) ?: JsonNull),
                    "rosterDevices" to JsonPrimitive(info.roster.size))
            }
            "prepareCreate" -> {
                val packages = post<DirectoryLookup, List<DirectoryKeyPackage>>("/security/key-packages/claim", DirectoryLookup(chat, members))
                check(packages.map { it.device.userId }.toSet() == members)
                if (memberCount == 2) check(packages.map { it.device.deviceId }.toSet().size == 2)
                pending = engine.prepareCreate(chat, packages.filter { it.device.deviceId != device }.map { it.bytes })
                val prepared = checkNotNull(pending)
                publication = CommitPublication(chat, device, null, prepared.epoch, prepared.commit, prepared.groupInfo,
                    packages.map { it.device.deviceId }.toSet(), emptySet(), packages.filter { it.device.deviceId != device }.map {
                        DirectoryWelcome(UUID.randomUUID().toString(), it.device.deviceId, chat, prepared.epoch, prepared.welcome)
                    })
                persist(); JsonObject(emptyMap())
            }
            "prepareRotate" -> {
                val previous = engine.epoch(chat)
                pending = engine.prepareRotate(chat)
                val prepared = checkNotNull(pending)
                publication = CommitPublication(chat, device, previous, prepared.epoch, prepared.commit, prepared.groupInfo, emptySet(), emptySet(), emptyList())
                persist(); JsonObject(emptyMap())
            }
            "publish" -> {
                val result = post<CommitPublication, CommitAcceptance>("/security/commits", checkNotNull(publication))
                if (result.accepted) engine.accept(chat, checkNotNull(pending).commit) else engine.discard(chat)
                pending = null; publication = null; persist()
                proofJson.encodeToJsonElement(result).jsonObject
            }
            "join" -> {
                val welcomes = post<String, List<DirectoryWelcome>>("/security/welcomes", device)
                val welcome = welcomes.single { it.conversationId == chat }
                engine.join(chat, welcome.bytes)
                persist()
                post<WelcomeConsumption, Unit>("/security/welcomes/consume", WelcomeConsumption(device, setOf(welcome.id)))
                JsonObject(emptyMap())
            }
            "catchUp" -> {
                post<CommitCursor, List<DirectoryCommit>>("/security/commits/list", CommitCursor(chat, engine.epoch(chat)))
                    .sortedBy { it.epoch }.forEach {
                        engine.setMembers(chat, it.members + members)
                        check(engine.process(chat, it.bytes))
                    }
                persist(); JsonObject(emptyMap())
            }
            "connect" -> {
                val listener = object : WebSocket.Listener {
                    private val partial = StringBuilder()
                    override fun onOpen(webSocket: WebSocket) { webSocket.request(1) }
                    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                        partial.append(data)
                        if (last) { frames.put(partial.toString()); partial.setLength(0) }
                        webSocket.request(1)
                        return null
                    }
                }
                val address = System.getenv("RSEC_DEV_CHAT_ORIGIN").trimEnd('/').replaceFirst("https://", "wss://")
                socket = http.newWebSocketBuilder().header("Authorization", "Bearer $token").header("X-Environment", "STAGE")
                    .header("User-Agent", "BestBuds-E2EE-Dev-Check")
                    .buildAsync(URI.create("$address/parties/chat-service/$chat?_pk=${UUID.randomUUID()}"), listener).get(30, TimeUnit.SECONDS)
                JsonObject(emptyMap())
            }
            "send" -> {
                val id = UUID.randomUUID().toString()
                val value = arguments["value"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
                require(value.isNotBlank() && value.length <= 4096)
                val body = proofObject("kind" to JsonPrimitive("text"), "value" to JsonPrimitive(value)).toString().encodeToByteArray()
                val epoch = engine.epoch(chat)
                val sealed = engine.seal(chat, envelope(id, user, device, epoch), body)
                val frame = proofObject("publish" to proofObject("chatId" to JsonPrimitive(chat), "value" to JsonPrimitive(""),
                    "kind" to JsonPrimitive("text"), "clientId" to JsonPrimitive(id), "contentVersion" to JsonPrimitive(2),
                    "epoch" to JsonPrimitive(epoch), "senderDeviceId" to JsonPrimitive(device),
                    "contentHeader" to encoded(sealed.header), "commitment" to encoded(sealed.commitment),
                    "ciphertext" to encoded(sealed.ciphertext)))
                checkNotNull(socket).sendText(frame.toString(), true).get(30, TimeUnit.SECONDS)
                val result = proofObject("clientId" to JsonPrimitive(id), "digest" to JsonPrimitive(digest(body)))
                body.fill(0); persist(); result
            }
            "receive" -> {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
                var found: JsonObject? = null
                while (found == null && System.nanoTime() < deadline) {
                    val frame = frames.poll(2, TimeUnit.SECONDS) ?: continue
                    found = findMessage(proofJson.parseToJsonElement(frame), arguments.text("clientId"))
                }
                opened(checkNotNull(found), arguments.text("clientId"))
            }
            "history" -> {
                val request = request("$origin/messages/$chat?offset=0&limit=100").GET().build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                check(response.statusCode() == 200) { "History status ${response.statusCode()}" }
                opened(checkNotNull(findMessage(proofJson.parseToJsonElement(response.body()), arguments.text("clientId"))), arguments.text("clientId"))
            }
            "epoch" -> proofObject("epoch" to JsonPrimitive(engine.epoch(chat)))
            "close" -> { close(); JsonObject(emptyMap()) }
            else -> error("Unknown proof command")
        }

        private inline fun <reified I, reified O> post(path: String, body: I): O {
            val request = request(origin + path).POST(HttpRequest.BodyPublishers.ofString(proofJson.encodeToString(body))).build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() in 200..299) { "Directory status ${response.statusCode()} for $path" }
            return proofJson.decodeFromString(response.body())
        }

        private fun request(url: String) = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $token").header("X-Environment", "STAGE").header("Content-Type", "application/json")
            .header("User-Agent", "BestBuds-E2EE-Dev-Check")

        private fun findMessage(element: JsonElement, clientId: String): JsonObject? = when (element) {
            is JsonObject -> if (element["clientId"]?.jsonPrimitive?.contentOrNull == clientId && element["value"] != null) element
                else element.values.firstNotNullOfOrNull { findMessage(it, clientId) }
            is JsonArray -> element.firstNotNullOfOrNull { findMessage(it, clientId) }
            else -> null
        }

        private fun envelope(id: String, sender: String, senderDevice: String, epoch: Long): ByteArray =
            ("bestbuds/chat/content/v2:" + proofObject("contentVersion" to JsonPrimitive(2),
                "messageId" to JsonPrimitive(id), "chatId" to JsonPrimitive(chat), "senderId" to JsonPrimitive(sender),
                "senderDeviceId" to JsonPrimitive(senderDevice), "kind" to JsonPrimitive("text"),
                "replyToId" to JsonNull, "epoch" to JsonPrimitive(epoch), "editNumber" to JsonPrimitive(0),
                "objects" to JsonArray(emptyList()), "mentions" to JsonArray(emptyList()))).encodeToByteArray()

        private fun opened(message: JsonObject, id: String): JsonObject {
            check(message.getValue("contentVersion").jsonPrimitive.int == 2)
            check(message.text("value") == "Update BestBuds to read this message")
            check(message.text("frankTag").isNotBlank())
            val sealed = EngineSealed(decoded(message, "contentHeader"), decoded(message, "commitment"), decoded(message, "ciphertext"))
            val clear = engine.open(chat, envelope(id, message.text("senderId"), message.text("senderDeviceId"),
                message.getValue("epoch").jsonPrimitive.long), sealed)
            check(clear.authenticated)
            return try { proofObject("digest" to JsonPrimitive(digest(clear.plaintext))) }
            finally { clear.plaintext.fill(0); clear.frankingKey.fill(0); persist() }
        }

        private fun persist() {
            val snapshot = engine.snapshot()
            try { privateWrite(home.resolve("state"), NativeSecurityEngine.sealStorage(storageKey, device.encodeToByteArray(), snapshot)) }
            finally { snapshot.fill(0) }
        }

        private fun privateWrite(path: Path, bytes: ByteArray) {
            if (!Files.exists(path)) Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            Files.write(path, bytes)
        }

        private var closed = false
        override fun close() {
            if (closed) return
            closed = true
            socket?.abort(); socket = null
            if (::engine.isInitialized) { persist(); engine.close() }
            storageKey.fill(0)
        }

        private fun encoded(value: ByteArray) = JsonPrimitive(Base64.getEncoder().encodeToString(value))
        private fun decoded(value: JsonObject, field: String) = Base64.getDecoder().decode(value.text(field))
        private fun digest(value: ByteArray) = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value))
    }
}
