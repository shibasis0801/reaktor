export interface ReadConfigCommandArgs {
  config?: string;
  env?: string;
  script?: string;
}

export interface ReadConfigOptions {
  hideWarnings?: boolean;
}

export interface D1Binding {
  binding: string;
  database_name?: string;
  database_id?: string;
}

export interface KvBinding {
  binding: string;
  id?: string;
}

export interface R2Binding {
  binding: string;
  bucket_name?: string;
}

export interface DurableObjectBinding {
  name: string;
  class_name: string;
  script_name?: string;
}

export interface DurableObjects {
  bindings: DurableObjectBinding[];
}

export interface ServiceBinding {
  binding: string;
  service: string;
  entrypoint?: string;
}

export interface QueueProducer {
  binding: string;
  queue: string;
}

export interface Queues {
  producers?: QueueProducer[];
}

export interface HyperdriveBinding {
  binding: string;
  id: string;
}

export interface WorkflowBinding {
  binding: string;
  name: string;
  class_name: string;
}

export interface VpcServiceBinding {
  binding: string;
  service_id: string;
}

export interface RateLimitBinding {
  name: string;
  namespace_id: string;
}

export interface Config {
  configPath?: string;
  name?: string;
  main?: string;
  account_id?: string;
  compatibility_date?: string;
  compatibility_flags: string[];
  d1_databases: D1Binding[];
  kv_namespaces: KvBinding[];
  r2_buckets: R2Binding[];
  durable_objects: DurableObjects;
  services?: ServiceBinding[];
  queues: Queues;
  hyperdrive: HyperdriveBinding[];
  workflows: WorkflowBinding[];
  vpc_services: VpcServiceBinding[];
  ratelimits: RateLimitBinding[];
}

export interface WranglerBin {
  wrangler: string;
}

export interface WranglerPackage {
  version: string;
  bin: WranglerBin;
}

export interface Wrangler {
  unstable_readConfig(args: ReadConfigCommandArgs, options?: ReadConfigOptions): Config;
}
