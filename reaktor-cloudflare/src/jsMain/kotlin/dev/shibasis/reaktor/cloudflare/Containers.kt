@file:JsModule("@cloudflare/containers")
@file:JsNonModule
@file:Suppress("INTERFACE_WITH_SUPERCLASS", "unused", "FunctionName")

package dev.shibasis.reaktor.cloudflare

import js.core.ReadonlyArray
import js.core.ReadonlyRecord
import kotlin.js.Promise
import org.w3c.fetch.Request
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import web.abort.AbortSignal

/** Options accepted when constructing a container durable object. */
external interface ContainerOptions {
    var id: String?
    var defaultPort: Int?
    var sleepAfter: dynamic /* String | Number */
    var envVars: ReadonlyRecord<String, String>?
    var entrypoint: ReadonlyArray<String>?
    var enableInternet: Boolean?
}

/** Configuration overrides supplied to [Container.start]. */
external interface ContainerStartConfigOptions {
    var envVars: ReadonlyRecord<String, String>?
    var entrypoint: ReadonlyArray<String>?
    var enableInternet: Boolean?
}

/** Options accepted by [Container.startAndWaitForPorts]. */
external interface StartAndWaitForPortsOptions {
    var startOptions: ContainerStartConfigOptions?
    var ports: dynamic /* Int | ReadonlyArray<Int> */
    var cancellationOptions: CancellationOptions?
}

/** Cancellation configuration used when waiting for container ports. */
external interface CancellationOptions {
    var abort: AbortSignal?
    var instanceGetTimeoutMS: Int?
    var portReadyTimeoutMS: Int?
    var waitInterval: Int?
}

/** Retry configuration supplied to [Container.start]. */
external interface WaitOptions {
    var abort: AbortSignal?
    var retries: Int?
    var waitInterval: Int?
    var portToCheck: Int?
}

/** Parameters passed to [Container.onStop]. */
external interface StopParams {
    var exitCode: Int
    var reason: String
}

/** Alarm payload delivered to container durable objects. */
external interface AlarmProps {
    var isRetry: Boolean
    var retryCount: Int
}

/** Representation of a scheduled task maintained by the container runtime. */
external interface Schedule<T> {
    var taskId: String
    var callback: String
    var payload: T
    var type: String
    var time: Double
    var delayInSeconds: Double?
}

/** Container execution state exposed via [Container.getState]. */
external interface State {
    var lastChange: Double
    var status: String
    var exitCode: Int?
}

/** Cloudflare durable object that manages an isolated container instance. */
external open class Container<Env>(
    ctx: DurableObjectState,
    env: Env,
    options: ContainerOptions = definedExternally,
) : DurableObject<Env> {
    var defaultPort: Int?
    var requiredPorts: ReadonlyArray<Int>?
    var sleepAfter: dynamic /* String | Number */
    var envVars: ReadonlyRecord<String, String>?
    var entrypoint: ReadonlyArray<String>?
    var enableInternet: Boolean?

    fun getState(): Promise<State>
    fun start(
        options: ContainerStartConfigOptions = definedExternally,
        waitOptions: WaitOptions = definedExternally,
    ): Promise<Unit>

    fun startAndWaitForPorts(args: StartAndWaitForPortsOptions): Promise<Unit>
    fun startAndWaitForPorts(
        ports: Int,
        cancellationOptions: CancellationOptions = definedExternally,
        startOptions: ContainerStartConfigOptions = definedExternally,
    ): Promise<Unit>

    fun startAndWaitForPorts(
        ports: ReadonlyArray<Int>,
        cancellationOptions: CancellationOptions = definedExternally,
        startOptions: ContainerStartConfigOptions = definedExternally,
    ): Promise<Unit>

    fun stop(signal: dynamic = definedExternally): Promise<Unit>
    fun destroy(): Promise<Unit>

    open fun onStart(): dynamic
    open fun onStop(params: StopParams): dynamic
    open fun onActivityExpired(): Promise<Unit>
    open fun onError(error: Any?): Any?

    fun renewActivityTimeout()

    fun <T> schedule(when: dynamic, callback: String, payload: T = definedExternally): Promise<Schedule<T>>

    fun containerFetch(request: Request, port: Int = definedExternally): Promise<Response>
    fun containerFetch(
        url: String,
        init: RequestInit = definedExternally,
        port: Int = definedExternally,
    ): Promise<Response>

    fun fetch(request: Request): Promise<Response>
    fun alarm(alarmProps: AlarmProps): Promise<Unit>
    fun listSchedules(name: String): Promise<ReadonlyArray<Schedule<Any?>>> 
    fun deleteSchedules(name: String)
    fun scheduleNextAlarm(ms: Int = definedExternally): Promise<Unit>
}
