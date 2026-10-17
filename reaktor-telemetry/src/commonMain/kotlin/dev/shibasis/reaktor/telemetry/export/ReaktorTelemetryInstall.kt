package dev.shibasis.reaktor.telemetry.export

import io.opentelemetry.kotlin.ExperimentalApi
import io.opentelemetry.kotlin.OpenTelemetry
import io.opentelemetry.kotlin.createOpenTelemetry
import io.opentelemetry.kotlin.export.OperationResultCode
import io.opentelemetry.kotlin.tracing.export.SpanExporter
import kotlinx.coroutines.CoroutineScope

/** Standard OTel resource keys, plus the Reaktor identities that belong on the resource. */
object ResourceKeys {
    const val ServiceName = "service.name"
    const val ServiceVersion = "service.version"
    const val DeploymentEnvironment = "deployment.environment"

    /** Stable across a release, so it is safe here and safe as a metric dimension. */
    const val ReaktorSnapshotId = "reaktor.snapshot.id"

    /** One running instance. On the resource, never a metric dimension. */
    const val ReaktorActivationId = "reaktor.activation.id"
}

/**
 * A live telemetry installation: the OpenTelemetry instance to hand to [dev.shibasis.reaktor.telemetry.TelemetryAdapter],
 * and the processor whose [ReaktorSpanProcessor.health] answers whether anything is actually leaving.
 */
@OptIn(ExperimentalApi::class)
class ReaktorTelemetryInstallation internal constructor(
    val openTelemetry: OpenTelemetry,
    val processor: ReaktorSpanProcessor,
) {
    val health: SpanPipelineHealth get() = processor.health

    suspend fun flush(): OperationResultCode = processor.forceFlush()

    suspend fun shutdown(): OperationResultCode = processor.shutdown()
}

/**
 * D2 — replaces the noop default with a real SDK.
 *
 * `TelemetryAdapter` documents `NoopOpenTelemetry` as its default, which is why spans produced
 * elsewhere in this module currently go nowhere. This builds the instance that changes that.
 *
 * The resource carries `service.name`, the environment, and the stable snapshot identity, so
 * every span is attributable to a release without those becoming per-span attributes.
 */
@OptIn(ExperimentalApi::class)
fun createReaktorOpenTelemetry(
    serviceName: String,
    exporter: SpanExporter,
    scope: CoroutineScope,
    serviceVersion: String? = null,
    environment: String? = null,
    snapshotId: String? = null,
    activationId: String? = null,
    resourceAttributes: Map<String, Any> = emptyMap(),
    maxBatchSize: Int = 50,
    maxQueuedSpans: Int = 2048,
): ReaktorTelemetryInstallation {
    require(serviceName.isNotBlank()) { "A telemetry resource needs a service.name" }

    val resource = buildMap<String, Any> {
        put(ResourceKeys.ServiceName, serviceName)
        serviceVersion?.let { put(ResourceKeys.ServiceVersion, it) }
        environment?.let { put(ResourceKeys.DeploymentEnvironment, it) }
        snapshotId?.let { put(ResourceKeys.ReaktorSnapshotId, it) }
        activationId?.let { put(ResourceKeys.ReaktorActivationId, it) }
        putAll(resourceAttributes)
    }

    lateinit var processor: ReaktorSpanProcessor
    val otel = createOpenTelemetry {
        tracerProvider {
            resource(resource)
            export {
                ReaktorSpanProcessor(
                    exporter = exporter,
                    scope = scope,
                    maxBatchSize = maxBatchSize,
                    maxQueuedSpans = maxQueuedSpans,
                ).also { processor = it }
            }
        }
    }
    return ReaktorTelemetryInstallation(otel, processor)
}

/** The common case: OTLP over an injected transport. */
@OptIn(ExperimentalApi::class)
fun createReaktorOpenTelemetry(
    serviceName: String,
    transport: SpanBatchTransport,
    scope: CoroutineScope,
    serviceVersion: String? = null,
    environment: String? = null,
    snapshotId: String? = null,
    activationId: String? = null,
    resourceAttributes: Map<String, Any> = emptyMap(),
): ReaktorTelemetryInstallation = createReaktorOpenTelemetry(
    serviceName = serviceName,
    exporter = OtlpSpanExporter(transport),
    scope = scope,
    serviceVersion = serviceVersion,
    environment = environment,
    snapshotId = snapshotId,
    activationId = activationId,
    resourceAttributes = resourceAttributes,
)
