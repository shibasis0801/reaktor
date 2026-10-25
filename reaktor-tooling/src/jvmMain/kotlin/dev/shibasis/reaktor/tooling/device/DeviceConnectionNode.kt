package dev.shibasis.reaktor.tooling.device

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.Type
import dev.shibasis.reaktor.portgraph.port.registerProvider
import kotlinx.serialization.json.*

fun interface PhoneConnection {
    suspend fun connect(platform: String, device: String?): JsonObject
}

class DeviceConnectionNode(graph: Graph, private val transport: List<String>) : BasicNode(graph) {
    override val label = "Physical phone connection"
    val connect = registerProvider(Key("connect"), Type.create(PhoneConnection::class), PhoneConnection(::connectDevice))

    private suspend fun connectDevice(platform: String, selected: String?): JsonObject {
        require(platform in setOf("ios", "android")) { "Choose ios or android" }
        val started = System.nanoTime()
        val checks = mutableListOf<JsonObject>()
        var device = selected.orEmpty()
        fun check(name: String, detail: String) { checks += buildJsonObject { put("check", name); put("detail", detail) } }
        fun result(state: String, detail: String) = buildJsonObject {
            put("platform", platform); put("device", device); put("state", state)
            put("ready", state == "ready"); put("detail", detail)
            put("elapsedMillis", (System.nanoTime() - started) / 1_000_000)
            put("checks", JsonArray(checks))
        }
        return try {
            if (platform == "android") {
                val devices = native(listOf("adb", "devices", "-l")).lineSequence().map { it.trim().split(Regex("\\s+")) }
                    .filter { it.size >= 2 && it[0] != "List" && !it[0].startsWith("emulator-") && it.drop(2).any { field -> field.startsWith("usb:") } }.toList()
                val found = devices.filter { selected == null || it[0] == selected }
                if (found.size != 1) return result("blocked", selection(platform, found.map { it[0] }))
                device = found.single()[0]
                if (found.single()[1] != "device") return result("blocked", "Unlock the Android phone and approve this worker's USB debugging RSA prompt (${found.single()[1]}).")
                check("transport", "Authorized physical USB device")
                native(listOf("adb", "-s", device, "shell", "input", "keyevent", "KEYCODE_WAKEUP"))
                val policy = native(listOf("adb", "-s", device, "shell", "dumpsys", "window", "policy"))
                if (Regex("(?:showing|mShowingLockscreen|isStatusBarKeyguard)\\s*=\\s*true").containsMatchIn(policy))
                    return result("blocked", "Enter the Android phone's PIN/passcode on the phone, then run connect again.")
                if (!Regex("(?:showing|mShowingLockscreen|isStatusBarKeyguard)\\s*=\\s*false").containsMatchIn(policy))
                    return result("blocked", "Android did not report its lock state; unlock the phone and retry.")
                check("unlock", "Phone reports no lock screen")
                val forwards = native(listOf("adb", "forward", "--list")).lineSequence().map { it.split(' ') }
                val local = forwards.firstOrNull { it.size == 3 && it[0] == device && it[2] == "tcp:6790" }?.get(1)?.removePrefix("tcp:")
                    ?: native(listOf("adb", "-s", device, "forward", "tcp:0", "tcp:6790")).trim()
                require(local.toIntOrNull()?.let { it in 1..65535 } == true) { "ADB did not allocate a driver forward" }
                val url = "http://127.0.0.1:$local"
                if (http("$url/status") == null) {
                    val installed = native(listOf("adb", "-s", device, "shell", "pm", "path", "io.appium.uiautomator2.server"))
                    val test = native(listOf("adb", "-s", device, "shell", "pm", "path", "io.appium.uiautomator2.server.test"))
                    if (!installed.startsWith("package:") || !test.startsWith("package:"))
                        return result("blocked", "The pinned UIAutomator2 driver is not installed. Run remote-dev mobile doctor; connect never builds or installs the application.")
                    val launch = start(platform, device, listOf("adb", "-s", device, "shell", "am", "instrument", "-w", "-e", "disableAnalytics", "true", "-e", "reaktorConnect", device,
                        "io.appium.uiautomator2.server.test/androidx.test.runner.AndroidJUnitRunner"))
                    check("driver", "UIAutomator2 starting; log: ${launch.text("log")}")
                    return result("starting", "UIAutomator2 is starting independently. Run connect again in a moment.")
                }
                val sessions = http("$url/sessions")?.get("value") as? JsonArray
                    ?: return result("blocked", "UIAutomator2 did not return its sessions; its process alone does not prove readiness.")
                var session = sessions.firstOrNull()?.jsonObject?.text("id")
                if (session.isNullOrBlank()) {
                    val created = http("$url/session", "POST", """{"capabilities":{"alwaysMatch":{"platformName":"Android","appium:automationName":"UiAutomator2"},"firstMatch":[{}]}}""")
                    session = created?.text("sessionId")?.takeIf(String::isNotBlank) ?: (created?.get("value") as? JsonObject)?.text("sessionId")
                }
                if (session.isNullOrBlank()) return result("blocked", "UIAutomator2 could not establish an automation session.")
                val source = http("$url/session/$session/source")?.get("value") as? JsonPrimitive
                if (source?.content?.contains("<hierarchy") != true) return result("blocked", "The driver is reachable but the phone did not return its UI hierarchy.")
                check("automation", "UIAutomator2 returned a live hierarchy (${source.content.length} characters; UI content omitted)")
            } else {
                val devices = (apple(listOf("list", "devices"))["devices"] as? JsonArray).orEmpty().map { it.jsonObject }
                    .filter { (it["hardwareProperties"] as? JsonObject)?.text("reality") == "physical" }
                val found = devices.filter { selected == null || it.text("identifier") == selected || (it["hardwareProperties"] as? JsonObject)?.text("udid") == selected }
                if (found.size != 1) return result("blocked", selection(platform, found.map { it.text("identifier") }))
                val details = apple(listOf("device", "info", "details", "--device", found.single().text("identifier")))
                val hardware = details.getValue("hardwareProperties").jsonObject
                device = hardware.text("udid")
                val connection = details.getValue("connectionProperties").jsonObject
                val properties = details.getValue("deviceProperties").jsonObject
                if (connection.text("pairingState") != "paired") return result("blocked", "Unlock the iPhone, approve Trust This Computer and enter its passcode on the phone.")
                if (properties.text("developerModeStatus") != "enabled") return result("blocked", "Enable Settings → Privacy & Security → Developer Mode on the iPhone, including its restart and confirmation.")
                if (connection.text("tunnelState") != "connected" || properties["ddiServicesAvailable"] != JsonPrimitive(true))
                    return result("blocked", "The iPhone's developer tunnel/disk services are unavailable. Unlock it and check its Xcode Devices connection.")
                check("transport", "Paired physical iPhone; Developer Mode, developer disk and tunnel are live")
                val lock = apple(listOf("device", "info", "lockState", "--device", device))
                if (lock["passcodeRequired"] != JsonPrimitive(false) || lock["unlockedSinceBoot"] != JsonPrimitive(true))
                    return result("blocked", "Enter the iPhone's passcode on the phone and keep it awake, then run connect again.")
                check("unlock", "iPhone reports passcode not required and unlocked since boot")
                val port = 8100 + (device.substringAfterLast('-').takeLast(12).toLongOrNull(16)?.rem(1000) ?: 0)
                val address = connection.text("tunnelIPAddress")
                val urls = listOf("http://[$address]:$port", "http://[$address]:8100")
                val url = urls.firstOrNull { http("$it/status")?.get("value") is JsonObject }
                if (url == null) {
                    val cached = python(FindXctestrun).jsonObject
                    val path = cached.text("path")
                    if (path.isBlank()) return result("blocked", "No prebuilt physical WebDriverAgent is available in the pinned runner cache. Prepare the iOS driver once; connect will not start a build.")
                    val copy = python(CopyXctestrun, path, device, port.toString()).jsonPrimitive.content
                    val launch = start(platform, device, listOf("xcodebuild", "test-without-building", "-xctestrun", copy, "-destination", "platform=iOS,id=$device", "-parallel-testing-enabled", "NO"))
                    check("driver", "XCTest starting from prebuilt WebDriverAgent; log: ${launch.text("log")}")
                    return result("starting", "Keep the iPhone awake; enter its passcode and accept Enable UI Automation if prompted. XCTest is starting independently; run connect again shortly.")
                }
                if (http("$url/wda/locked")?.get("value") == JsonPrimitive(true))
                    return result("blocked", "WebDriverAgent reports the iPhone is locked. Enter its passcode on the phone and retry.")
                val source = http("$url/source")?.get("value") as? JsonPrimitive
                if (source?.content?.contains("<") != true) return result("blocked", "WebDriverAgent answers status but cannot read the phone's UI. Accept Enable UI Automation on the phone and retry.")
                check("automation", "XCTest/WebDriverAgent returned a live hierarchy (${source.content.length} characters; UI content omitted)")
            }
            result("ready", "Phone transport, unlock and live UI automation round trip passed.")
        } catch (failure: Exception) {
            result("blocked", failure.message.orEmpty().take(1500).ifBlank { "Device host unavailable" })
        }
    }

    private fun selection(platform: String, devices: List<String>) = if (devices.isEmpty())
        "No matching physical $platform phone. Connect USB, unlock it and approve the host's trust/debugging prompt."
    else "Multiple physical phones: ${devices.joinToString()}. Select one with --device ID."

    private suspend fun native(argv: List<String>, timeout: Long = 7): String =
        CommandRunner.run(transport + argv, timeout).requireSuccess("Device host")

    private suspend fun python(code: String, vararg args: String) = Json.parseToJsonElement(native(listOf("python3", "-c", code) + args))

    private suspend fun apple(argv: List<String>): JsonObject = python(AppleJson, *argv.toTypedArray()).jsonObject.let {
        require(it["info"]?.jsonObject?.text("outcome") == "success") { "iPhone developer connection failed: ${it["error"] ?: it["info"]}" }
        it.getValue("result").jsonObject
    }

    private suspend fun http(url: String, method: String = "GET", body: String = ""): JsonObject? =
        runCatching { python(HttpJson, url, method, body) as? JsonObject }.getOrNull()?.takeIf {
            it["value"]?.let { value -> (value as? JsonObject)?.get("error") } == null
        }

    private suspend fun start(platform: String, device: String, argv: List<String>) =
        python(StartProcess, platform, device, *argv.toTypedArray()).jsonObject

    private fun JsonObject.text(name: String) = (get(name) as? JsonPrimitive)?.content.orEmpty()

    private companion object {
        val AppleJson = """
            import json,subprocess,sys,tempfile
            with tempfile.NamedTemporaryFile(suffix='.json') as f:
                p=subprocess.run(['xcrun','devicectl',*sys.argv[1:],'--quiet','--timeout','5','--json-output',f.name],capture_output=True,text=True)
                data=f.read().decode()
                if not data: raise RuntimeError(p.stderr or p.stdout)
                print(data)
        """.trimIndent()
        val HttpJson = """
            import json,sys,urllib.request
            request=urllib.request.Request(sys.argv[1],method=sys.argv[2],data=sys.argv[3].encode() if sys.argv[3] else None,headers={'Content-Type':'application/json'})
            opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with opener.open(request,timeout=1) as response: print(response.read(2097152).decode())
        """.trimIndent()
        val FindXctestrun = """
            import json,pathlib,shutil
            runner=shutil.which('maestro-runner')
            paths=list((pathlib.Path(runner).resolve().parent.parent/'cache').rglob('*.xctestrun')) if runner else []
            paths=[p for p in paths if 'iphoneos' in p.name and not p.name.startswith('reaktor-connect-')]
            print(json.dumps({'path':str(max(paths,key=lambda p:p.stat().st_mtime)) if paths else ''}))
        """.trimIndent()
        val CopyXctestrun = """
            import json,pathlib,plistlib,sys
            source=pathlib.Path(sys.argv[1]); data=plistlib.loads(source.read_bytes())
            for target in data.values():
                if isinstance(target,dict) and target.get('IsUITestBundle'):
                    target.setdefault('EnvironmentVariables',{})['USE_PORT']=sys.argv[3]
            output=source.with_name('reaktor-connect-'+sys.argv[2]+'.xctestrun')
            output.write_bytes(plistlib.dumps(data));print(json.dumps(str(output)))
        """.trimIndent()
        val StartProcess = """
            import fcntl,json,os,pathlib,subprocess,sys,tempfile
            root=pathlib.Path(tempfile.gettempdir())/('reaktor-connect-'+str(os.getuid()));root.mkdir(mode=0o700,exist_ok=True)
            key=sys.argv[1]+'-'+sys.argv[2]; state=root/(key+'.json'); log=root/(key+'.log')
            with (root/(key+'.lock')).open('w') as lock:
                fcntl.flock(lock,fcntl.LOCK_EX)
                old=json.loads(state.read_text()) if state.exists() else {}
                command=subprocess.run(['ps','-p',str(old.get('pid',0)),'-o','command='],capture_output=True,text=True).stdout
                if sys.argv[2] not in command:
                    with log.open('w') as output:
                        process=subprocess.Popen(sys.argv[3:],stdin=subprocess.DEVNULL,stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
                    old={'pid':process.pid,'platform':sys.argv[1],'device':sys.argv[2],'log':str(log)};state.write_text(json.dumps(old))
                print(json.dumps(old))
        """.trimIndent()
    }
}
