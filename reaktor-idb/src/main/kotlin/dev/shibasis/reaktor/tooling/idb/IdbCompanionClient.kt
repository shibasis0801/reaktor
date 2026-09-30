package dev.shibasis.reaktor.tooling.idb

import dev.shibasis.reaktor.tooling.idb.proto.CompanionServiceGrpc
import dev.shibasis.reaktor.tooling.idb.proto.IdbProto
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * A direct client for the idb companion's gRPC service.
 *
 * The Python `idb` CLI is one client of this service, not the service itself. Speaking to the
 * companion removes that install from the dependency chain — which matters, because a `pip
 * install --user` puts `idb` somewhere a login shell does not look, and the whole Apple surface
 * then reports as unavailable on a machine that has it.
 *
 * Calls run on the blocking stub dispatched to IO rather than through a future stub, which keeps
 * Guava out of the dependency set for latency nobody here is counting.
 */
class IdbCompanionClient(
    private val channel: ManagedChannel,
) : AutoCloseable {

    constructor(host: String = "127.0.0.1", port: Int = DefaultPort) : this(
        OkHttpChannelBuilder.forAddress(host, port)
            .usePlaintext()
            // The companion is on loopback or a forwarded port. Messages are small except
            // screenshots and video frames, which are why this is raised at all.
            .maxInboundMessageSize(64 * 1024 * 1024)
            .build()
    )

    private val blocking = CompanionServiceGrpc.newBlockingStub(channel)
    private val async = CompanionServiceGrpc.newStub(channel)

    suspend fun describe(fetchDiagnostics: Boolean = false): IdbProto.TargetDescription =
        io { blocking.describe(
            IdbProto.TargetDescriptionRequest.newBuilder().setFetchDiagnostics(fetchDiagnostics).build()
        ).targetDescription }

    suspend fun listApps(): List<IdbProto.InstalledAppInfo> =
        io { blocking.listApps(IdbProto.ListAppsRequest.getDefaultInstance()).appsList }

    suspend fun screenshot(): ByteArray =
        io { blocking.screenshot(IdbProto.ScreenshotRequest.getDefaultInstance()).imageData.toByteArray() }

    /**
     * The accessibility tree, as the companion's own JSON.
     *
     * Returned unparsed on purpose: the shape differs between companion versions and between the
     * legacy and nested formats, and the workbench already has a parser that copes with both.
     */
    suspend fun accessibilityInfo(nested: Boolean = true): String = io {
        blocking.accessibilityInfo(
            IdbProto.AccessibilityInfoRequest.newBuilder()
                .setFormat(
                    if (nested) IdbProto.AccessibilityInfoRequest.Format.NESTED
                    else IdbProto.AccessibilityInfoRequest.Format.LEGACY
                )
                .build()
        ).json
    }

    suspend fun terminate(bundleId: String) {
        io { blocking.terminate(IdbProto.TerminateRequest.newBuilder().setBundleId(bundleId).build()) }
    }

    suspend fun openUrl(url: String) {
        io { blocking.openUrl(IdbProto.OpenUrlRequest.newBuilder().setUrl(url).build()) }
    }

    suspend fun setLocation(latitude: Double, longitude: Double) {
        io {
            blocking.setLocation(
                IdbProto.SetLocationRequest.newBuilder()
                    .setLocation(
                        IdbProto.Location.newBuilder().setLatitude(latitude).setLongitude(longitude)
                    )
                    .build()
            )
        }
    }

    suspend fun crashes(bundleId: String = ""): List<IdbProto.CrashLogInfo> = io {
        blocking.crashList(
            IdbProto.CrashLogQuery.newBuilder().apply { if (bundleId.isNotBlank()) setBundleId(bundleId) }.build()
        ).listList
    }

    suspend fun crash(name: String): String =
        io { blocking.crashShow(IdbProto.CrashShowRequest.newBuilder().setName(name).build()).contents }

    suspend fun list(bundleId: String, path: String): List<String> = io {
        blocking.ls(
            IdbProto.LsRequest.newBuilder()
                .setPath(path)
                .setContainer(
                    IdbProto.FileContainer.newBuilder()
                        .setKind(IdbProto.FileContainer.Kind.APPLICATION)
                        .setBundleId(bundleId)
                )
                .build()
        ).filesList.map { it.path }
    }

    /**
     * The target's log, streamed.
     *
     * This is the read the CLI cannot bound and the reason the device lab reported "no bounded log
     * read" for Apple targets: as a server stream it is simply a flow, and cancelling the flow
     * cancels the call.
     */
    fun log(arguments: List<String> = emptyList()): Flow<String> = flow {
        val request = IdbProto.LogRequest.newBuilder()
            .setSource(IdbProto.LogRequest.Source.TARGET)
            .addAllArguments(arguments)
            .build()
        val responses = blocking.log(request)
        while (responses.hasNext()) {
            emit(responses.next().output.toStringUtf8())
        }
    }.flowOn(Dispatchers.IO)

    /**
     * A tap, as a press down and up at one point.
     *
     * HID is a client stream, so the whole gesture is one call: the companion replays the events
     * in the order they arrive, which is what makes a swipe a swipe rather than two taps.
     */
    suspend fun tap(x: Double, y: Double) = hid(
        press(x, y, IdbProto.HIDEvent.HIDDirection.DOWN),
        press(x, y, IdbProto.HIDEvent.HIDDirection.UP),
    )

    suspend fun swipe(
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        durationSeconds: Double = 0.3,
    ) = hid(
        IdbProto.HIDEvent.newBuilder()
            .setSwipe(
                IdbProto.HIDEvent.HIDSwipe.newBuilder()
                    .setStart(point(startX, startY))
                    .setEnd(point(endX, endY))
                    .setDuration(durationSeconds)
            )
            .build()
    )

    suspend fun pressButton(button: IdbProto.HIDEvent.HIDButtonType) = hid(
        buttonEvent(button, IdbProto.HIDEvent.HIDDirection.DOWN),
        buttonEvent(button, IdbProto.HIDEvent.HIDDirection.UP),
    )

    private suspend fun hid(vararg events: IdbProto.HIDEvent) {
        val completion = CompletableDeferred<Unit>()
        val observer = async.hid(object : StreamObserver<IdbProto.HIDResponse> {
            override fun onNext(value: IdbProto.HIDResponse) = Unit
            override fun onError(error: Throwable) {
                completion.completeExceptionally(error)
            }

            override fun onCompleted() {
                completion.complete(Unit)
            }
        })
        try {
            events.forEach(observer::onNext)
            observer.onCompleted()
        } catch (failure: Throwable) {
            observer.onError(failure)
            throw failure
        }
        completion.await()
    }

    private fun point(x: Double, y: Double): IdbProto.Point =
        IdbProto.Point.newBuilder().setX(x).setY(y).build()

    private fun press(x: Double, y: Double, direction: IdbProto.HIDEvent.HIDDirection) =
        IdbProto.HIDEvent.newBuilder()
            .setPress(
                IdbProto.HIDEvent.HIDPress.newBuilder()
                    .setAction(
                        IdbProto.HIDEvent.HIDPressAction.newBuilder()
                            .setTouch(IdbProto.HIDEvent.HIDTouch.newBuilder().setPoint(point(x, y)))
                    )
                    .setDirection(direction)
            )
            .build()

    private fun buttonEvent(
        button: IdbProto.HIDEvent.HIDButtonType,
        direction: IdbProto.HIDEvent.HIDDirection,
    ) = IdbProto.HIDEvent.newBuilder()
        .setPress(
            IdbProto.HIDEvent.HIDPress.newBuilder()
                .setAction(
                    IdbProto.HIDEvent.HIDPressAction.newBuilder()
                        .setButton(IdbProto.HIDEvent.HIDButton.newBuilder().setButton(button))
                )
                .setDirection(direction)
        )
        .build()

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    override fun close() {
        channel.shutdown()
        runCatching { channel.awaitTermination(5, TimeUnit.SECONDS) }
    }

    companion object {
        /** The companion's own default. */
        const val DefaultPort: Int = 10_882
    }
}
