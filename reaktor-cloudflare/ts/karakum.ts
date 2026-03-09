export interface CloudflareEnv {}

export interface WorkerExecutionContext {
  waitUntil(promise: Promise<unknown>): void;
  passThroughOnException(): void;
}

export interface D1RawOptions {
  columnNames?: boolean;
}

export interface D1ExecResult {
  count?: number;
  duration?: number;
}

export interface D1Result {
  success?: boolean;
  results?: unknown[];
  meta?: unknown;
}

export interface D1PreparedStatement {
  bind(...values: unknown[]): D1PreparedStatement;
  first(columnName?: string): Promise<unknown>;
  run(): Promise<D1Result>;
  all(): Promise<D1Result>;
  raw(options?: D1RawOptions): Promise<unknown[]>;
}

export interface D1Database {
  prepare(query: string): D1PreparedStatement;
  batch(statements: D1PreparedStatement[]): Promise<D1Result[]>;
  exec(query: string): Promise<D1ExecResult>;
}

export interface DurableObjectId {}

export interface DurableObjectGetOptions {
  locationHint?: string;
}

export interface DurableObjectStub {
  fetch(input: unknown, init?: unknown): Promise<unknown>;
}

export interface DurableObjectNamespace {
  newUniqueId(options?: unknown): DurableObjectId;
  idFromName(name: string): DurableObjectId;
  idFromString(id: string): DurableObjectId;
  get(id: DurableObjectId, options?: DurableObjectGetOptions): DurableObjectStub;
  getByName(name: string): DurableObjectStub;
}

export interface DurableObjectListOptions {
  start?: string;
  end?: string;
  prefix?: string;
  reverse?: boolean;
  limit?: number;
}

export interface DurableObjectStorage {
  get(key: string): Promise<unknown>;
  put(key: string, value: unknown): Promise<void>;
  delete(key: string): Promise<boolean>;
  deleteAll(): Promise<void>;
  list(options?: DurableObjectListOptions): Promise<unknown>;
}

export interface DurableObjectState {
  readonly id: DurableObjectId;
  readonly storage: DurableObjectStorage;
  waitUntil(promise: Promise<unknown>): void;
  blockConcurrencyWhile(callback: () => Promise<unknown>): Promise<unknown>;
}

export interface R2Object {
  key: string;
  size: number;
  etag: string;
}

export interface R2Bucket {
  head(key: string): Promise<R2Object | null>;
  get(key: string, options?: unknown): Promise<unknown>;
  put(key: string, value: unknown, options?: unknown): Promise<R2Object | null>;
  delete(keys: string | string[]): Promise<void>;
  list(options?: unknown): Promise<unknown>;
}

export interface VectorizeVector {
  id: string;
  values: number[];
  namespace?: string;
  metadata?: unknown;
}

export interface VectorizeMatch {
  id: string;
  score: number;
  values?: number[];
  metadata?: unknown;
}

export interface VectorizeMatches {
  matches: VectorizeMatch[];
  count?: number;
}

export interface VectorizeMutationResult {
  ids?: string[];
  count?: number;
}

export interface VectorizeQueryOptions {
  topK?: number;
  namespace?: string;
  returnValues?: boolean;
  returnMetadata?: boolean;
  filter?: unknown;
}

export interface VectorizeIndex {
  describe(): Promise<unknown>;
  insert(vectors: VectorizeVector[]): Promise<VectorizeMutationResult>;
  upsert(vectors: VectorizeVector[]): Promise<VectorizeMutationResult>;
  query(vector: number[], options?: VectorizeQueryOptions): Promise<VectorizeMatches>;
  getByIds(ids: string[]): Promise<VectorizeVector[]>;
  deleteByIds(ids: string[]): Promise<VectorizeMutationResult>;
}
