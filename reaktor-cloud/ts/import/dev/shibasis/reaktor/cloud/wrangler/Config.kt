// Automatically generated - do not modify!

package dev.shibasis.reaktor.cloud.wrangler

external interface Config {
var configPath: String?
var name: String?
var main: String?
var account_id: String?
var compatibility_date: String?
var compatibility_flags: js.array.ReadonlyArray<String>
var d1_databases: js.array.ReadonlyArray<D1Binding>
var kv_namespaces: js.array.ReadonlyArray<KvBinding>
var r2_buckets: js.array.ReadonlyArray<R2Binding>
var durable_objects: DurableObjects
var services: js.array.ReadonlyArray<ServiceBinding>?
var queues: Queues
var hyperdrive: js.array.ReadonlyArray<HyperdriveBinding>
var workflows: js.array.ReadonlyArray<WorkflowBinding>
var vpc_services: js.array.ReadonlyArray<VpcServiceBinding>
var ratelimits: js.array.ReadonlyArray<RateLimitBinding>
}
