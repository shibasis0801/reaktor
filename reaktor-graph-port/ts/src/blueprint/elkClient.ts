import { assemble, plan, readFrame, type BlueprintPlan } from './engine';
import type { BlueprintEdge, BlueprintGroup, BlueprintLayout, ElkFrameResult, ElkGraphInput, FlatFrame, LayoutOptions } from './types';

export interface LayoutCache {
  get(hash: string): Promise<ElkFrameResult | undefined>;
  put(hash: string, result: ElkFrameResult): Promise<void>;
}

export type FrameSource = 'memory' | 'cache' | 'worker';

export interface FrameTiming {
  key: string;
  source: FrameSource;
  ms: number;
}

export interface FrameSet {
  plan: BlueprintPlan;
  flats: Map<string, FlatFrame>;
  timings: FrameTiming[];
  elapsed: number;
}

interface Pending {
  resolve: (value: { result: ElkFrameResult; ms: number }) => void;
  reject: (error: Error) => void;
}

export function createElkWorker(): Worker {
  return new Worker(new URL('./elk.worker.ts', import.meta.url), { type: 'module', name: 'blueprint-elk' });
}

export function indexedDbCache(name = 'reaktor-blueprint', store = 'frames'): LayoutCache {
  let opening: Promise<IDBDatabase | null> | null = null;
  const open = () => opening ??= new Promise<IDBDatabase | null>(resolve => {
    try {
      if (typeof indexedDB === 'undefined') { resolve(null); return; }
      const request = indexedDB.open(name, 1);
      request.onupgradeneeded = () => request.result.createObjectStore(store);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => resolve(null);
      request.onblocked = () => resolve(null);
    } catch {
      resolve(null);
    }
  });
  const run = <T>(mode: IDBTransactionMode, work: (objects: IDBObjectStore) => IDBRequest<T>) => open().then(database => new Promise<T | undefined>(resolve => {
    if (!database) { resolve(undefined); return; }
    try {
      const request = work(database.transaction(store, mode).objectStore(store));
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => resolve(undefined);
    } catch {
      resolve(undefined);
    }
  }));
  return {
    get: hash => run<ElkFrameResult>('readonly', objects => objects.get(hash) as IDBRequest<ElkFrameResult>),
    put: async (hash, result) => { await run('readwrite', objects => objects.put(result, hash)); },
  };
}

export class ElkClient {
  private worker: Worker | null = null;
  private next = 1;
  private readonly pending = new Map<number, Pending>();
  private readonly memory = new Map<string, ElkFrameResult>();
  bootedAt: number | null = null;
  startedAt: number | null = null;

  constructor(private readonly options: { worker?: () => Worker; cache?: LayoutCache | null } = {}) {}

  private ensure(): Worker {
    if (this.worker) return this.worker;
    this.startedAt = performance.now();
    const worker = (this.options.worker ?? createElkWorker)();
    worker.onmessage = (event: MessageEvent<{ id?: number; ready?: boolean; result?: ElkFrameResult; error?: string; ms?: number }>) => {
      const data = event.data;
      if (data.ready) { this.bootedAt = performance.now(); return; }
      const waiting = data.id === undefined ? undefined : this.pending.get(data.id);
      if (!waiting || data.id === undefined) return;
      this.pending.delete(data.id);
      if (data.result) waiting.resolve({ result: data.result, ms: data.ms ?? 0 });
      else waiting.reject(new Error(data.error ?? 'Layout failed'));
    };
    worker.onerror = event => {
      const error = new Error(event.message || 'Layout worker failed');
      this.pending.forEach(waiting => waiting.reject(error));
      this.pending.clear();
      this.worker?.terminate();
      this.worker = null;
    };
    this.worker = worker;
    return worker;
  }

  compute(graph: ElkGraphInput): Promise<{ result: ElkFrameResult; ms: number }> {
    const worker = this.ensure();
    const id = this.next++;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      worker.postMessage({ id, graph });
    });
  }

  async frame(hash: string, graph: ElkGraphInput): Promise<{ result: ElkFrameResult; source: FrameSource; ms: number }> {
    const started = performance.now();
    const remembered = this.memory.get(hash);
    if (remembered) return { result: remembered, source: 'memory', ms: performance.now() - started };
    const stored = this.options.cache ? await this.options.cache.get(hash) : undefined;
    if (stored) {
      this.memory.set(hash, stored);
      return { result: stored, source: 'cache', ms: performance.now() - started };
    }
    const { result, ms } = await this.compute(graph);
    this.memory.set(hash, result);
    if (this.options.cache) void this.options.cache.put(hash, result);
    return { result, source: 'worker', ms };
  }

  forget(): void {
    this.memory.clear();
  }

  dispose(): void {
    this.pending.forEach(waiting => waiting.reject(new Error('Layout client disposed')));
    this.pending.clear();
    this.worker?.terminate();
    this.worker = null;
  }
}

export async function layoutFrames(client: ElkClient, key: string, groups: BlueprintGroup[], edges: BlueprintEdge[], options: LayoutOptions = {}): Promise<FrameSet> {
  const started = performance.now();
  const blueprint = plan(key, groups, edges, options);
  const timings: FrameTiming[] = [];
  const results = await Promise.all(blueprint.requests.map(async request => {
    const { result, source, ms } = await client.frame(request.hash, request.graph);
    timings.push({ key: request.key, source, ms });
    return [request.key, readFrame(blueprint, request, result)] as const;
  }));
  return { plan: blueprint, flats: new Map(results), timings, elapsed: performance.now() - started };
}

export function layoutFromFrames(frames: FrameSet, aspect: number): BlueprintLayout {
  return assemble(frames.plan, frames.flats, aspect);
}
