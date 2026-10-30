package dev.shibasis.reaktor.tooling

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.tooling.device.AppleDeviceBackend
import dev.shibasis.reaktor.tooling.device.DeviceConnectionNode
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.koin.dsl.koinApplication
import kotlin.test.*

class DeviceToolsTest {
    @Test fun inventoryRoundTripsAndPutsReadyTargetsFirst() {
        val devices = listOf(
            DevelopmentDevice("usb-device", "Tablet", DeviceTransport.Adb, "unauthorized"),
            DevelopmentDevice("emulator-5554", "Pixel 9", DeviceTransport.Adb, "device", "sdk"),
            DevelopmentDevice("emulator-5554", "Pixel 9", DeviceTransport.Adb, "device", "sdk"),
        )
        val parsed = DeviceTools.parseInventory(DeviceTransport.Adb, DeviceTools.inventory(devices))
        assertEquals(listOf("emulator-5554", "usb-device"), parsed.map { it.id })
        assertEquals(1, parsed.count { it.ready })
        assertEquals("sdk", parsed.first().runtime)
    }

    @Test fun anInventoryForAnotherTransportIsRefused() {
        val simulator = DeviceTools.inventory(listOf(DevelopmentDevice("A-B", "Phone", DeviceTransport.Simctl, "Booted")))
        assertFails { DeviceTools.parseInventory(DeviceTransport.Adb, simulator) }
    }

    @Test fun coreDeviceInventoryExcludesTheMacHostAndKeepsPhysicalAppleDevices() {
        val devices = AppleDeviceBackend().parse("""
            {"result":{"devices":[
              {"identifier":"host","hardwareProperties":{"platform":"macOS"},
               "deviceProperties":{"bootState":"booted"},"connectionProperties":{"pairingState":"unsupported"}},
              {"identifier":"phone-core-id","hardwareProperties":{"platform":"iOS","udid":"phone-udid"},
               "deviceProperties":{"name":"Phone","bootState":"booted","osVersionNumber":"26.7.1"},
               "connectionProperties":{"pairingState":"paired"}},
              {"identifier":"tablet-core-id","hardwareProperties":{"platform":"iOS"},
               "deviceProperties":{"name":"Tablet","osVersionNumber":"26.5.2"},
               "connectionProperties":{"pairingState":"paired"}}
            ]}}
        """.trimIndent())
        assertEquals(listOf("phone-core-id", "tablet-core-id"), devices.map { it.id })
        assertEquals("Phone", devices.first().name)
        assertEquals("iOS 26.7.1", devices.first().runtime)
        assertTrue(devices.first().ready)
        assertEquals("paired", devices.last().state)
        assertTrue(devices.all { it.transport == DeviceTransport.Idb })
    }

    @Test fun oneConnectionStartsXCTestAndRequestsAutomationWhileAllowingPhoneApproval() = runBlocking {
        for (alreadyRunning in listOf(false, true)) {
            val root = Files.createTempDirectory("phone-connect").toFile()
            val dependencies = koinApplication {}
            val graph = Graph(dependencyAdapter = KoinDependencyAdapter(dependencies))
            try {
                val ready = File(root, "ready")
                if (alreadyRunning) ready.writeText("")
                val requests = ConcurrentLinkedQueue<String>()
                MockWebServer().use { server ->
                    server.dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val path = request.path.orEmpty()
                            requests += path
                            return when {
                                path == "/status" && ready.exists() -> MockResponse().setBody("""{"value":{}}""")
                                path == "/wda/locked" -> MockResponse().setBody("""{"value":false}""")
                                path.startsWith("/source?") -> MockResponse()
                                    .setBody("""{"value":"<Application name='private UI content'/>"}""")
                                    .setBodyDelay(1500, TimeUnit.MILLISECONDS)
                                else -> MockResponse().setResponseCode(503)
                            }
                        }
                    }
                    server.start()
                    val worker = File(root, "worker.py").apply {
                        writeText("""
                            import json,pathlib,sys,urllib.parse
                            root=pathlib.Path(sys.argv[1]); endpoint=sys.argv[2]
                            argv=sys.argv[3:]; assert argv[:2]==['python3','-c']
                            code=argv[2]; args=argv[3:]
                            if args[:2]==['list','devices']:
                                value={'devices':[{'identifier':'phone','hardwareProperties':{'reality':'physical','udid':'phone-1'}}]}
                            elif args[:3]==['device','info','details']:
                                value={'hardwareProperties':{'udid':'phone-1'},
                                       'connectionProperties':{'pairingState':'paired','tunnelState':'connected','tunnelIPAddress':'::1'},
                                       'deviceProperties':{'developerModeStatus':'enabled','ddiServicesAvailable':True}}
                            elif args[:3]==['device','info','lockState']:
                                value={'passcodeRequired':False,'unlockedSinceBoot':True}
                            elif args and args[0].startswith('http'):
                                url=urllib.parse.urlsplit(args[0])
                                sys.argv=['http',endpoint.rstrip('/')+url.path+('?' + url.query if url.query else ''),*args[1:]]
                                exec(code)
                                sys.exit(0)
                            elif args and args[0]=='ios':
                                (root/'ready').write_text('')
                                with (root/'launches').open('a') as f: f.write(json.dumps(args)+chr(10))
                                print(json.dumps({'log':str(root/'driver.log')}));sys.exit(0)
                            elif not args:
                                print(json.dumps({'path':'/prebuilt/WDA.xctestrun'}));sys.exit(0)
                            elif args[0]=='/prebuilt/WDA.xctestrun':
                                print(json.dumps('/prebuilt/connection.xctestrun'));sys.exit(0)
                            else:
                                raise AssertionError(args)
                            print(json.dumps({'info':{'outcome':'success'},'result':value}))
                        """.trimIndent())
                    }
                    val node = graph.Node { DeviceConnectionNode(it, listOf("python3", worker.path, root.path, server.url("/").toString())) }
                    val result = node.connect.impl.connect("ios", null)
                    assertEquals(JsonPrimitive(true), result["ready"], result.toString())
                    assertEquals(1, requests.count { it.startsWith("/source?") })
                    val launches = File(root, "launches").takeIf(File::exists)?.readLines().orEmpty()
                    assertEquals(if (alreadyRunning) 0 else 1, launches.size)
                    if (!alreadyRunning) assertContains(launches.single(), "test-without-building")
                    assertFalse(result.toString().contains("private UI content"))
                }
            } finally {
                graph.close()
                dependencies.close()
                root.deleteTreeSafely(within = root.parentFile)
            }
        }
    }
}
