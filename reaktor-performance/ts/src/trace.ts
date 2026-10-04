export interface TraceEvent {
  name: string;
  cat: string;
  ph: string;
  ts: number;
  dur?: number;
  tdur?: number;
  pid: number;
  tid: number;
  id?: string | number;
  id2?: { local?: string; global?: string };
  s?: string;
  args?: Record<string, any>;
}

export interface ThreadRef {
  pid: number;
  tid: number;
}

export interface TraceModel {
  events: TraceEvent[];
  threads: Map<string, string>;
  processes: Map<number, string>;
  start: number;
  end: number;
  renderer: number | null;
  main: ThreadRef | null;
}

export interface TraceWindow {
  start: number;
  end: number;
}

export interface Slice {
  event: TraceEvent;
  start: number;
  end: number;
  self: number;
  depth: number;
  children: Slice[];
  parent: Slice | null;
}

export type WorkCategory = 'scripting' | 'gc' | 'style' | 'layout' | 'paint' | 'composite' | 'loading' | 'other';

const scripting = new Set([
  'EvaluateScript', 'v8.compile', 'v8.compileModule', 'v8.evaluateModule', 'v8.produceModuleCache', 'v8.produceCache', 'CacheScript',
  'CompileScript', 'CompileCode', 'CompileModule', 'OptimizeCode', 'FunctionCall', 'TimerFire', 'TimerInstall', 'TimerRemove',
  'EventDispatch', 'FireAnimationFrame', 'RequestAnimationFrame', 'CancelAnimationFrame', 'FireIdleCallback', 'RunMicrotasks',
  'V8.Execute', 'v8.run', 'v8.callFunction', 'v8.parseOnBackground', 'v8.deserializeOnBackground', 'XHRReadyStateChange', 'XHRLoad',
  'V8.ParseFunction', 'V8.ScriptCompiler', 'V8.CompileFullCode', 'V8.DeoptimizeCode', 'v8.wasm.compileLazy', 'ResizeObserver',
  'EmbedderCallback', 'HandlePostMessage', 'SchedulePostMessage', 'WebSocketCreate', 'WebSocketSendHandshakeRequest',
  'WebSocketReceiveHandshakeResponse', 'WebSocketDestroy', 'IntersectionObserverCallback', 'PerformanceObserverCallback',
]);
const garbage = new Set([
  'MinorGC', 'MajorGC', 'GCEvent', 'BlinkGC.AtomicPhase', 'ThreadState::performIdleLazySweep', 'ThreadState::completeSweep',
  'BlinkGCMarking', 'V8.GC_SCAVENGER', 'V8.GCScavenger', 'V8.GCCompactor', 'V8.GCFinalizeMC', 'V8.GCFinalizeMCReduceMemory',
  'V8.GCIncrementalMarking', 'V8.GCIncrementalMarkingFinalize', 'V8.GCIncrementalMarkingStart', 'V8.GCMarkCompactor', 'V8.GC_MC_BACKGROUND_MARKING',
  'CppGC.AtomicMark', 'CppGC.AtomicSweep', 'CppGC.IncrementalMark', 'CppGC.IncrementalSweep', 'CppGC.ConcurrentMark', 'CppGC.ConcurrentSweep',
]);
const style = new Set(['UpdateLayoutTree', 'RecalculateStyles', 'ScheduleStyleRecalculation', 'StyleRecalcInvalidationTracking', 'StyleInvalidatorInvalidationTracking', 'ParseAuthorStyleSheet']);
const layout = new Set(['Layout', 'InvalidateLayout', 'LayoutInvalidationTracking', 'HitTest', 'ComputeIntersections', 'IntersectionObserverController::computeIntersections', 'UpdateLayoutObjects', 'LayoutShift', 'ScrollLayer']);
const paint = new Set(['Paint', 'PaintSetup', 'PaintImage', 'PrePaint', 'Decode Image', 'ImageDecodeTask', 'Decode LazyPixelRef', 'Draw LazyPixelRef', 'RasterTask', 'Rasterize', 'PaintArtifactCompositor::Update']);
const composite = new Set(['Layerize', 'UpdateLayer', 'UpdateLayerTree', 'CompositeLayers', 'Commit', 'ActivateLayerTree', 'DrawFrame', 'BeginFrame', 'GPUTask', 'Screenshot', 'NeedsBeginFrameChanged', 'RequestMainThreadFrame', 'BeginMainThreadFrame']);
const loading = new Set(['ParseHTML', 'ResourceSendRequest', 'ResourceReceiveResponse', 'ResourceReceivedData', 'ResourceFinish', 'ResourceWillSendRequest', 'ResourceChangePriority', 'PreloadScanner']);

const prefixes: Array<[RegExp, WorkCategory]> = [
  [/^(V8\.GC|V8\.ConcurrentMarking|CppGC|BlinkGC|ThreadState::)/, 'gc'],
  [/^(V8\.(Execute|Compile|Parse|Deserialize|StackGuard|HandleInterrupts|BytecodeBudgetInterrupt|DeoptimizeCode|OptimizeConcurrent|Builtin)|v8\.|BlinkScheduler_PerformMicrotaskCheckpoint|ScriptCatchup)/, 'scripting'],
  [/^(Document::UpdateStyleAndLayoutTree|Document::RecalcStyle|StyleEngine::|Document::updateStyle|ComputedStyle)/, 'style'],
  [/^(LocalFrameView::performLayout|LayoutView::|Document::UpdateStyleAndLayout$|LocalFrameView::UpdateLayout|PaintLayer::UpdateLayerPositions|LayoutBlockFlow|ResizeObserverController)/, 'layout'],
  [/^(LocalFrameView::RunPrePaintLifecyclePhase|LocalFrameView::RunPaintLifecyclePhase|PaintController|PaintArtifact|RasterInvalidation|cc::DisplayItemList|DisplayItemList)/, 'paint'],
  [/^(LocalFrameView::RunCompositingInputsLifecyclePhase|PaintArtifactCompositor|LayerTreeHost|ProxyMain::BeginMainFrame|cc::|Compositor)/, 'composite'],
];

export function categoryOf(name: string): WorkCategory {
  const exact = exactCategory(name);
  if (exact !== 'other') return exact;
  for (const [pattern, category] of prefixes) if (pattern.test(name)) return category;
  return 'other';
}

function exactCategory(name: string): WorkCategory {
  if (garbage.has(name)) return 'gc';
  if (scripting.has(name)) return 'scripting';
  if (style.has(name)) return 'style';
  if (layout.has(name)) return 'layout';
  if (paint.has(name)) return 'paint';
  if (composite.has(name)) return 'composite';
  if (loading.has(name)) return 'loading';
  return 'other';
}

export function traceEvents(input: unknown): TraceEvent[] {
  if (Array.isArray(input)) return input as TraceEvent[];
  if (input && typeof input === 'object' && Array.isArray((input as { traceEvents?: unknown }).traceEvents)) return (input as { traceEvents: TraceEvent[] }).traceEvents;
  throw new Error('Not a Chrome trace: expected an array or { traceEvents }');
}

export const threadKey = (pid: number, tid: number): string => `${pid}:${tid}`;

function urlMatches(candidate: unknown, url: string | undefined): boolean {
  if (typeof candidate !== 'string' || !candidate) return false;
  if (!url) return candidate.startsWith('http');
  try {
    return new URL(candidate).origin === new URL(url).origin;
  } catch {
    return candidate.startsWith(url);
  }
}

export function traceModel(input: unknown, options: { url?: string } = {}): TraceModel {
  const events = traceEvents(input).filter(event => event && typeof event.ts === 'number');
  const threads = new Map<string, string>();
  const processes = new Map<number, string>();
  let start = Infinity;
  let end = -Infinity;
  for (const event of events) {
    if (event.ph === 'M') {
      if (event.name === 'thread_name') threads.set(threadKey(event.pid, event.tid), String(event.args?.name ?? ''));
      if (event.name === 'process_name') processes.set(event.pid, String(event.args?.name ?? ''));
      continue;
    }
    if (event.ts <= 0) continue;
    start = Math.min(start, event.ts);
    end = Math.max(end, event.ts + (event.dur ?? 0));
  }
  let renderer: number | null = null;
  const committed = events.filter(event => event.name === 'FrameCommittedInBrowser' && urlMatches(event.args?.data?.url, options.url));
  if (committed.length > 0) renderer = Number(committed[committed.length - 1].args?.data?.processId) || null;
  if (renderer === null) {
    for (const event of events) {
      if (event.name !== 'TracingStartedInBrowser') continue;
      const frames = (event.args?.data?.frames ?? []) as Array<{ url?: string; processId?: number; parent?: string; isOutermostMainFrame?: boolean }>;
      const frame = frames.find(item => !item.parent && urlMatches(item.url, options.url)) ?? frames.find(item => !item.parent);
      if (frame?.processId) renderer = frame.processId;
    }
  }
  if (renderer === null || ![...threads].some(([key, name]) => name === 'CrRendererMain' && key.startsWith(`${renderer}:`))) {
    const busy = new Map<string, number>();
    for (const event of events) {
      if (event.ph !== 'X' || event.name !== 'RunTask') continue;
      const key = threadKey(event.pid, event.tid);
      if (threads.get(key) !== 'CrRendererMain') continue;
      busy.set(key, (busy.get(key) ?? 0) + (event.dur ?? 0));
    }
    const best = [...busy].sort((a, b) => b[1] - a[1])[0];
    if (best) renderer = Number(best[0].split(':')[0]);
  }
  let main: ThreadRef | null = null;
  if (renderer !== null) {
    for (const [key, name] of threads) {
      const [pid, tid] = key.split(':').map(Number);
      if (pid === renderer && name === 'CrRendererMain') main = { pid, tid };
    }
  }
  return { events, threads, processes, start: Number.isFinite(start) ? start : 0, end: Number.isFinite(end) ? end : 0, renderer, main };
}

export function threadsNamed(model: TraceModel, pattern: RegExp, pid?: number | null): ThreadRef[] {
  const found: ThreadRef[] = [];
  for (const [key, name] of model.threads) {
    const [threadPid, tid] = key.split(':').map(Number);
    if (pattern.test(name) && (pid === undefined || pid === null || threadPid === pid)) found.push({ pid: threadPid, tid });
  }
  return found;
}

export function gpuThreads(model: TraceModel): ThreadRef[] {
  const gpu = [...model.processes].filter(([, name]) => /GPU/i.test(name)).map(([pid]) => pid);
  return gpu.flatMap(pid => threadsNamed(model, /^(CrGpuMain|VizCompositorThread)$/, pid));
}

function within(event: TraceEvent, window?: TraceWindow): boolean {
  if (!window) return true;
  const end = event.ts + (event.dur ?? 0);
  return end > window.start && event.ts < window.end;
}

export function slices(model: TraceModel, thread: ThreadRef, window?: TraceWindow): Slice[] {
  const complete: TraceEvent[] = [];
  const open = new Map<string, TraceEvent[]>();
  for (const event of model.events) {
    if (event.pid !== thread.pid || event.tid !== thread.tid) continue;
    if (event.ph === 'X' && typeof event.dur === 'number') complete.push(event);
    else if (event.ph === 'B') {
      const stack = open.get(event.name) ?? [];
      stack.push(event);
      open.set(event.name, stack);
    } else if (event.ph === 'E') {
      const begin = open.get(event.name)?.pop();
      if (begin) complete.push({ ...begin, ph: 'X', dur: event.ts - begin.ts, args: { ...(begin.args ?? {}), ...(event.args ?? {}) } });
    }
  }
  const chosen = complete.filter(event => within(event, window)).sort((a, b) => a.ts - b.ts || (b.dur ?? 0) - (a.dur ?? 0));
  const roots: Slice[] = [];
  const stack: Slice[] = [];
  for (const event of chosen) {
    const slice: Slice = { event, start: event.ts, end: event.ts + (event.dur ?? 0), self: event.dur ?? 0, depth: 0, children: [], parent: null };
    while (stack.length > 0 && stack[stack.length - 1].end <= slice.start) stack.pop();
    const parent = stack.length > 0 && slice.end <= stack[stack.length - 1].end + 0.5 ? stack[stack.length - 1] : null;
    if (parent) {
      slice.parent = parent;
      slice.depth = parent.depth + 1;
      parent.children.push(slice);
      parent.self = Math.max(0, parent.self - (slice.end - slice.start));
    } else {
      while (stack.length > 0) stack.pop();
      roots.push(slice);
    }
    stack.push(slice);
  }
  return roots;
}

export function* walkSlices(roots: Slice[]): Generator<Slice> {
  const pending = [...roots].reverse();
  while (pending.length > 0) {
    const slice = pending.pop()!;
    yield slice;
    for (let index = slice.children.length - 1; index >= 0; index -= 1) pending.push(slice.children[index]);
  }
}

export interface Breakdown {
  busyMs: number;
  categories: Record<WorkCategory, number>;
  events: Array<{ name: string; category: WorkCategory; selfMs: number; count: number }>;
}

const clip = (slice: Slice, window?: TraceWindow): number => {
  if (!window) return slice.self;
  const span = slice.end - slice.start;
  if (span <= 0) return 0;
  const overlap = Math.max(0, Math.min(slice.end, window.end) - Math.max(slice.start, window.start));
  return slice.self * (overlap / span);
};

export const tracingArtifacts = new Set(['CpuProfiler::StartProfiling', 'CpuProfiler::StopProfiling', 'TracingStartedInBrowser']);

function artifact(slice: Slice): boolean {
  for (let cursor: Slice | null = slice; cursor; cursor = cursor.parent) if (tracingArtifacts.has(cursor.event.name)) return true;
  return false;
}

function artifactTime(root: Slice, window?: TraceWindow): number {
  let total = 0;
  for (const slice of walkSlices([root])) {
    if (!tracingArtifacts.has(slice.event.name)) continue;
    total += window ? Math.max(0, Math.min(slice.end, window.end) - Math.max(slice.start, window.start)) : slice.end - slice.start;
  }
  return total;
}

export function breakdown(model: TraceModel, thread: ThreadRef | null, window?: TraceWindow): Breakdown {
  const categories: Record<WorkCategory, number> = { scripting: 0, gc: 0, style: 0, layout: 0, paint: 0, composite: 0, loading: 0, other: 0 };
  const byName = new Map<string, { selfMs: number; count: number }>();
  let busy = 0;
  if (!thread) return { busyMs: 0, categories, events: [] };
  const roots = slices(model, thread, window);
  for (const root of roots) {
    const span = window ? Math.max(0, Math.min(root.end, window.end) - Math.max(root.start, window.start)) : root.end - root.start;
    busy += Math.max(0, span - artifactTime(root, window));
  }
  for (const slice of walkSlices(roots)) {
    if (artifact(slice)) continue;
    const self = clip(slice, window) / 1000;
    if (self <= 0) continue;
    const name = slice.event.name;
    const category = categoryOf(name);
    const inherited = category === 'other' && slice.parent ? inheritCategory(slice) : category;
    categories[inherited] += self;
    const entry = byName.get(name) ?? { selfMs: 0, count: 0 };
    entry.selfMs += self;
    entry.count += 1;
    byName.set(name, entry);
  }
  const events = [...byName].map(([name, entry]) => ({ name, category: categoryOf(name), selfMs: round(entry.selfMs), count: entry.count })).sort((a, b) => b.selfMs - a.selfMs);
  for (const key of Object.keys(categories) as WorkCategory[]) categories[key] = round(categories[key]);
  return { busyMs: round(busy / 1000), categories, events };
}

function inheritCategory(slice: Slice): WorkCategory {
  for (let cursor = slice.parent; cursor; cursor = cursor.parent) {
    const category = categoryOf(cursor.event.name);
    if (category !== 'other') return category;
  }
  return 'other';
}

export interface LongTask {
  startMs: number;
  durationMs: number;
  categories: Record<WorkCategory, number>;
  top: Array<{ name: string; selfMs: number }>;
}

export function longTasks(model: TraceModel, threshold = 50, window?: TraceWindow): LongTask[] {
  if (!model.main) return [];
  const tasks: LongTask[] = [];
  for (const root of slices(model, model.main, window)) {
    const duration = (root.end - root.start - artifactTime(root)) / 1000;
    if (duration < threshold) continue;
    const categories: Record<WorkCategory, number> = { scripting: 0, gc: 0, style: 0, layout: 0, paint: 0, composite: 0, loading: 0, other: 0 };
    const names = new Map<string, number>();
    for (const slice of walkSlices([root])) {
      if (artifact(slice)) continue;
      const self = slice.self / 1000;
      const category = categoryOf(slice.event.name);
      categories[category === 'other' && slice.parent ? inheritCategory(slice) : category] += self;
      names.set(slice.event.name, (names.get(slice.event.name) ?? 0) + self);
    }
    for (const key of Object.keys(categories) as WorkCategory[]) categories[key] = round(categories[key]);
    tasks.push({
      startMs: round((root.start - model.start) / 1000),
      durationMs: round(duration),
      categories,
      top: [...names].sort((a, b) => b[1] - a[1]).slice(0, 6).map(([name, selfMs]) => ({ name, selfMs: round(selfMs) })),
    });
  }
  return tasks;
}

export interface FrameStats {
  frames: number;
  presented: number;
  dropped: number;
  partial: number;
  noUpdate: number;
  missingContent: number;
  highLatency: number;
  p50Ms: number;
  p95Ms: number;
  p99Ms: number;
  maxMs: number;
  source: 'pipeline-reporter' | 'draw-frame' | 'none';
}

export function percentile(values: number[], p: number): number {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1));
  return sorted[index];
}

export function median(values: number[]): number {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
}

export const round = (value: number, digits = 1): number => {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
};

export function frameStats(model: TraceModel, window?: TraceWindow): FrameStats {
  const renderer = model.renderer;
  const begins = new Map<string, TraceEvent>();
  const durations: number[] = [];
  const states = { presented: 0, dropped: 0, partial: 0, noUpdate: 0, missingContent: 0, highLatency: 0 };
  let seen = 0;
  for (const event of model.events) {
    if (event.name !== 'PipelineReporter' || (renderer !== null && event.pid !== renderer)) continue;
    const id = String(event.id2?.local ?? event.id2?.global ?? event.id ?? '');
    if (event.ph === 'b') begins.set(id, event);
    else if (event.ph === 'e') {
      const begin = begins.get(id);
      if (!begin) continue;
      begins.delete(id);
      if (window && (begin.ts < window.start || begin.ts > window.end)) continue;
      const reporter = begin.args?.frame_reporter ?? begin.args?.chrome_frame_reporter ?? {};
      const state = String(reporter.state ?? '');
      seen += 1;
      if (reporter.has_missing_content || reporter.checkerboarded_needs_raster || reporter.checkerboarded_needs_record) states.missingContent += 1;
      if (reporter.has_high_latency) states.highLatency += 1;
      if (state === 'STATE_DROPPED') states.dropped += 1;
      else if (state === 'STATE_PRESENTED_PARTIAL') states.partial += 1;
      else if (state === 'STATE_NO_UPDATE_DESIRED') { states.noUpdate += 1; continue; }
      else states.presented += 1;
      durations.push((event.ts - begin.ts) / 1000);
    }
  }
  if (seen > 0) {
    return {
      frames: durations.length, ...states,
      p50Ms: round(percentile(durations, 50)), p95Ms: round(percentile(durations, 95)), p99Ms: round(percentile(durations, 99)), maxMs: round(Math.max(0, ...durations)),
      source: 'pipeline-reporter',
    };
  }
  const draws = model.events.filter(event => event.name === 'DrawFrame' && (renderer === null || event.pid === renderer) && (!window || (event.ts >= window.start && event.ts <= window.end))).map(event => event.ts).sort((a, b) => a - b);
  const dropped = model.events.filter(event => event.name === 'DroppedFrame' && (renderer === null || event.pid === renderer) && (!window || (event.ts >= window.start && event.ts <= window.end))).length;
  const gaps = draws.slice(1).map((ts, index) => (ts - draws[index]) / 1000);
  return {
    frames: draws.length, presented: draws.length, dropped, partial: 0, noUpdate: 0, missingContent: 0, highLatency: 0,
    p50Ms: round(percentile(gaps, 50)), p95Ms: round(percentile(gaps, 95)), p99Ms: round(percentile(gaps, 99)), maxMs: round(Math.max(0, ...gaps)),
    source: draws.length ? 'draw-frame' : 'none',
  };
}

export interface MainFrameStats {
  count: number;
  p50Ms: number;
  p95Ms: number;
  maxMs: number;
  over: number;
}

export function mainFrameWork(model: TraceModel, budgetMs: number, window?: TraceWindow): MainFrameStats {
  if (!model.main) return { count: 0, p50Ms: 0, p95Ms: 0, maxMs: 0, over: 0 };
  const durations: number[] = [];
  for (const root of slices(model, model.main, window)) {
    let frame = false;
    for (const slice of walkSlices([root])) {
      const name = slice.event.name;
      if (name === 'BeginMainThreadFrame' || name === 'FireAnimationFrame' || name === 'UpdateLayoutTree' || name === 'Paint' || name === 'Layerize' || name === 'PrePaint') { frame = true; break; }
    }
    if (frame) durations.push((root.end - root.start) / 1000);
  }
  return {
    count: durations.length,
    p50Ms: round(percentile(durations, 50)),
    p95Ms: round(percentile(durations, 95)),
    maxMs: round(Math.max(0, ...durations)),
    over: durations.filter(value => value > budgetMs).length,
  };
}

export interface Interaction {
  interactionId: number;
  type: string;
  startMs: number;
  durationMs: number;
  inputDelayMs: number;
  processingMs: number;
  presentationMs: number;
}

export function interactions(model: TraceModel, window?: TraceWindow): Interaction[] {
  const byId = new Map<number, Interaction>();
  for (const event of model.events) {
    if (event.name !== 'EventTiming' || event.ph !== 'b') continue;
    if (model.renderer !== null && event.pid !== model.renderer) continue;
    if (window && (event.ts < window.start || event.ts > window.end)) continue;
    const data = event.args?.data ?? {};
    const interactionId = Number(data.interactionId ?? 0);
    if (!interactionId) continue;
    const duration = Number(data.duration ?? 0);
    const timeStamp = Number(data.timeStamp ?? 0);
    const processingStart = Number(data.processingStart ?? timeStamp);
    const processingEnd = Number(data.processingEnd ?? processingStart);
    const candidate: Interaction = {
      interactionId,
      type: String(data.type ?? ''),
      startMs: round((event.ts - model.start) / 1000),
      durationMs: duration,
      inputDelayMs: round(Math.max(0, processingStart - timeStamp)),
      processingMs: round(Math.max(0, processingEnd - processingStart)),
      presentationMs: round(Math.max(0, timeStamp + duration - processingEnd)),
    };
    const known = byId.get(interactionId);
    if (!known || candidate.durationMs > known.durationMs) byId.set(interactionId, candidate);
  }
  return [...byId.values()].sort((a, b) => a.startMs - b.startMs);
}

export function inp(list: Interaction[]): number {
  if (list.length === 0) return 0;
  const sorted = list.map(item => item.durationMs).sort((a, b) => b - a);
  return sorted[Math.min(sorted.length - 1, Math.floor(list.length / 50))];
}

export interface UserTiming {
  name: string;
  startMs: number;
  durationMs: number;
}

export function userTimings(model: TraceModel): UserTiming[] {
  const found: UserTiming[] = [];
  const begins = new Map<string, TraceEvent>();
  for (const event of model.events) {
    if (!String(event.cat).includes('blink.user_timing')) continue;
    if (model.renderer !== null && event.pid !== model.renderer) continue;
    if (event.ph === 'R' || event.ph === 'I' || event.ph === 'i' || event.ph === 'n') found.push({ name: event.name, startMs: round((event.ts - model.start) / 1000), durationMs: 0 });
    else if (event.ph === 'b') begins.set(`${event.name}:${event.id ?? event.id2?.local ?? ''}`, event);
    else if (event.ph === 'e') {
      const key = `${event.name}:${event.id ?? event.id2?.local ?? ''}`;
      const begin = begins.get(key);
      if (begin) { found.push({ name: event.name, startMs: round((begin.ts - model.start) / 1000), durationMs: round((event.ts - begin.ts) / 1000) }); begins.delete(key); }
    }
  }
  return found.sort((a, b) => a.startMs - b.startMs);
}

export function firstEvent(model: TraceModel, name: string, predicate: (event: TraceEvent) => boolean = () => true): TraceEvent | undefined {
  return model.events.find(event => event.name === name && (model.renderer === null || event.pid === model.renderer) && predicate(event));
}

export interface ScriptCost {
  url: string;
  compileMs: number;
  evaluateMs: number;
}

export function scriptCosts(model: TraceModel, window?: TraceWindow): ScriptCost[] {
  const costs = new Map<string, ScriptCost>();
  const threads = model.renderer === null ? [] : threadsNamed(model, /^(CrRendererMain|DedicatedWorker thread)$/, model.renderer);
  for (const thread of threads) {
    for (const slice of walkSlices(slices(model, thread, window))) {
      const name = slice.event.name;
      const data = slice.event.args?.data ?? slice.event.args ?? {};
      const url = String(data.url ?? data.fileName ?? data.scriptName ?? '');
      if (!url) continue;
      const entry = costs.get(url) ?? { url, compileMs: 0, evaluateMs: 0 };
      const total = (slice.end - slice.start) / 1000;
      if (name === 'v8.compile' || name === 'v8.compileModule' || name === 'CompileScript' || name === 'v8.parseOnBackground') entry.compileMs += total;
      else if (name === 'EvaluateScript' || name === 'v8.evaluateModule') entry.evaluateMs += total;
      else continue;
      costs.set(url, entry);
    }
  }
  return [...costs.values()].map(item => ({ url: item.url, compileMs: round(item.compileMs), evaluateMs: round(item.evaluateMs) })).sort((a, b) => b.compileMs + b.evaluateMs - (a.compileMs + a.evaluateMs));
}

export interface Counters {
  jsHeapSizeUsed: number | null;
  nodes: number | null;
  listeners: number | null;
}

export function lastCounters(model: TraceModel): Counters {
  let found: TraceEvent | undefined;
  for (const event of model.events) if (event.name === 'UpdateCounters' && (model.renderer === null || event.pid === model.renderer)) found = event;
  const data = found?.args?.data ?? {};
  return { jsHeapSizeUsed: data.jsHeapSizeUsed ?? null, nodes: data.nodes ?? null, listeners: data.jsEventListeners ?? null };
}

export function invalidations(model: TraceModel, window?: TraceWindow): Array<{ reason: string; count: number; nodeName: string }> {
  const counted = new Map<string, { reason: string; count: number; nodeName: string }>();
  for (const event of model.events) {
    if (!/InvalidationTracking$/.test(event.name)) continue;
    if (model.renderer !== null && event.pid !== model.renderer) continue;
    if (window && (event.ts < window.start || event.ts > window.end)) continue;
    const data = event.args?.data ?? {};
    const reason = `${event.name}: ${data.reason ?? data.changedAttribute ?? data.changedPseudo ?? data.changedClass ?? data.changedId ?? 'unknown'}`;
    const nodeName = String(data.nodeName ?? '');
    const key = `${reason}|${nodeName}`;
    const entry = counted.get(key) ?? { reason, count: 0, nodeName };
    entry.count += 1;
    counted.set(key, entry);
  }
  return [...counted.values()].sort((a, b) => b.count - a.count);
}

export function styleRecalcElements(model: TraceModel, window?: TraceWindow): { recalcs: number; elements: number; maxElements: number } {
  let recalcs = 0;
  let elements = 0;
  let maxElements = 0;
  for (const event of model.events) {
    if (event.name !== 'UpdateLayoutTree' || event.ph !== 'X') continue;
    if (!model.main || event.pid !== model.main.pid || event.tid !== model.main.tid) continue;
    if (window && (event.ts < window.start || event.ts > window.end)) continue;
    const count = Number(event.args?.elementCount ?? event.args?.data?.elementCount ?? 0);
    recalcs += 1;
    elements += count;
    maxElements = Math.max(maxElements, count);
  }
  return { recalcs, elements, maxElements };
}

export function layoutObjects(model: TraceModel, window?: TraceWindow): { layouts: number; dirtyObjects: number; maxDirty: number } {
  let layouts = 0;
  let dirtyObjects = 0;
  let maxDirty = 0;
  for (const event of model.events) {
    if (event.name !== 'Layout' || event.ph !== 'X') continue;
    if (!model.main || event.pid !== model.main.pid || event.tid !== model.main.tid) continue;
    if (window && (event.ts < window.start || event.ts > window.end)) continue;
    const dirty = Number(event.args?.beginData?.dirtyObjects ?? 0);
    layouts += 1;
    dirtyObjects += dirty;
    maxDirty = Math.max(maxDirty, dirty);
  }
  return { layouts, dirtyObjects, maxDirty };
}


export interface CallFrame {
  functionName: string;
  url: string;
  scriptId?: number | string;
  lineNumber: number;
  columnNumber: number;
  codeType?: string;
}

export interface ProfileNode {
  id: number;
  callFrame: CallFrame;
  parent?: number;
}

export interface ThreadProfile {
  pid: number;
  tid: number;
  thread: string;
  nodes: Map<number, ProfileNode>;
  samples: number[];
  times: number[];
}

export interface SourcePosition {
  name: string;
  source: string | null;
  line: number | null;
}

export type Symbolicate = (frame: CallFrame) => SourcePosition | null;

export interface FunctionCost {
  key: string;
  name: string;
  location: string;
  url: string;
  selfMs: number;
  totalMs: number;
}

export interface FlameNode {
  name: string;
  location: string;
  selfMs: number;
  totalMs: number;
  children: FlameNode[];
}

export interface FlamegraphFrame {
  name: string;
  startMs: number;
  durationMs: number;
  children: FlamegraphFrame[];
}

const special = new Set(['(root)', '(program)', '(idle)', '(garbage collector)']);

export function threadProfiles(model: TraceModel): ThreadProfile[] {
  const profiles = new Map<string, ThreadProfile>();
  const starts = new Map<string, number>();
  const chunks: TraceEvent[] = [];
  for (const event of model.events) {
    if (event.ph !== 'P') continue;
    const key = `${event.pid}:${event.id}`;
    if (event.name === 'Profile') {
      starts.set(key, Number(event.args?.data?.startTime ?? event.ts));
      profiles.set(key, { pid: event.pid, tid: event.tid, thread: model.threads.get(`${event.pid}:${event.tid}`) ?? `tid ${event.tid}`, nodes: new Map(), samples: [], times: [] });
    } else if (event.name === 'ProfileChunk') chunks.push(event);
  }
  const clocks = new Map<string, number>();
  for (const event of chunks) {
    const key = `${event.pid}:${event.id}`;
    const profile = profiles.get(key);
    if (!profile) continue;
    const data = event.args?.data ?? {};
    const cpu = data.cpuProfile ?? {};
    for (const node of (cpu.nodes ?? []) as ProfileNode[]) profile.nodes.set(node.id, node);
    const samples = (cpu.samples ?? []) as number[];
    const deltas = (data.timeDeltas ?? []) as number[];
    let clock = clocks.get(key) ?? starts.get(key) ?? event.ts;
    for (let index = 0; index < samples.length; index += 1) {
      clock += deltas[index] ?? 0;
      profile.samples.push(samples[index]);
      profile.times.push(clock);
    }
    clocks.set(key, clock);
  }
  return [...profiles.values()].filter(profile => profile.samples.length > 0);
}

function durations(profile: ThreadProfile): number[] {
  const out: number[] = new Array(profile.times.length).fill(0);
  for (let index = 0; index + 1 < profile.times.length; index += 1) out[index] = Math.max(0, profile.times[index + 1] - profile.times[index]);
  if (out.length > 1) out[out.length - 1] = out[out.length - 2];
  return out;
}

function label(frame: CallFrame, symbolicate?: Symbolicate): { name: string; location: string } {
  const mapped = symbolicate ? symbolicate(frame) : null;
  const name = frame.functionName || '(anonymous)';
  if (special.has(name) || (!frame.url && name.startsWith('('))) return { name, location: '' };
  const file = frame.url ? frame.url.replace(/^https?:\/\/[^/]+/, '') : '(native)';
  const raw = `${file}:${frame.lineNumber + 1}:${frame.columnNumber + 1}`;
  if (!mapped || !mapped.source) return { name, location: raw };
  const source = mapped.source.replace(/^.*?\/(src|ts|node_modules)\//, '$1/');
  return { name: mapped.name && name.length <= 2 ? mapped.name : name, location: `${source}:${mapped.line ?? 0}` };
}

function inWindow(time: number, window?: TraceWindow): boolean {
  return !window || (time >= window.start && time <= window.end);
}

export function functionCosts(profile: ThreadProfile, options: { window?: TraceWindow; symbolicate?: Symbolicate } = {}): FunctionCost[] {
  const spans = durations(profile);
  const labels = new Map<number, { key: string; name: string; location: string; url: string }>();
  const describe = (id: number) => {
    let known = labels.get(id);
    if (!known) {
      const node = profile.nodes.get(id);
      const frame = node?.callFrame ?? { functionName: '(unknown)', url: '', lineNumber: 0, columnNumber: 0 };
      const { name, location } = label(frame, options.symbolicate);
      known = { key: `${name}@${location}`, name, location, url: frame.url };
      labels.set(id, known);
    }
    return known;
  };
  const costs = new Map<string, FunctionCost>();
  for (let index = 0; index < profile.samples.length; index += 1) {
    if (!inWindow(profile.times[index], options.window)) continue;
    const span = spans[index] / 1000;
    let id: number | undefined = profile.samples[index];
    const seen = new Set<string>();
    let first = true;
    while (id !== undefined) {
      const node = profile.nodes.get(id);
      if (!node) break;
      const info = describe(id);
      if (info.name !== '(root)') {
        const entry = costs.get(info.key) ?? { key: info.key, name: info.name, location: info.location, url: info.url, selfMs: 0, totalMs: 0 };
        if (first) entry.selfMs += span;
        if (!seen.has(info.key)) { entry.totalMs += span; seen.add(info.key); }
        costs.set(info.key, entry);
      }
      first = false;
      id = node.parent;
    }
  }
  return [...costs.values()].map(item => ({ ...item, selfMs: Math.round(item.selfMs * 10) / 10, totalMs: Math.round(item.totalMs * 10) / 10 })).sort((a, b) => b.selfMs - a.selfMs);
}

export function scriptSelfTime(costs: FunctionCost[]): Array<{ url: string; selfMs: number }> {
  const byUrl = new Map<string, number>();
  for (const cost of costs) byUrl.set(cost.url || cost.name, (byUrl.get(cost.url || cost.name) ?? 0) + cost.selfMs);
  return [...byUrl].map(([url, selfMs]) => ({ url, selfMs: Math.round(selfMs * 10) / 10 })).sort((a, b) => b.selfMs - a.selfMs);
}

export function topDown(profile: ThreadProfile, options: { window?: TraceWindow; symbolicate?: Symbolicate; dropIdle?: boolean } = {}): FlameNode {
  const spans = durations(profile);
  const root: FlameNode = { name: 'all', location: '', selfMs: 0, totalMs: 0, children: [] };
  const index = new Map<FlameNode, Map<string, FlameNode>>();
  const childOf = (parent: FlameNode, name: string, location: string): FlameNode => {
    let table = index.get(parent);
    if (!table) { table = new Map(); index.set(parent, table); }
    const key = `${name}@${location}`;
    let child = table.get(key);
    if (!child) {
      child = { name, location, selfMs: 0, totalMs: 0, children: [] };
      table.set(key, child);
      parent.children.push(child);
    }
    return child;
  };
  const labels = new Map<number, { name: string; location: string }>();
  for (let sample = 0; sample < profile.samples.length; sample += 1) {
    if (!inWindow(profile.times[sample], options.window)) continue;
    const span = spans[sample] / 1000;
    const stack: number[] = [];
    for (let id: number | undefined = profile.samples[sample]; id !== undefined; id = profile.nodes.get(id)?.parent) stack.push(id);
    stack.reverse();
    const leaf = profile.nodes.get(profile.samples[sample])?.callFrame.functionName ?? '';
    if (options.dropIdle !== false && (leaf === '(idle)' || leaf === '(program)')) continue;
    let cursor = root;
    root.totalMs += span;
    for (const id of stack) {
      let info = labels.get(id);
      if (!info) {
        const frame = profile.nodes.get(id)?.callFrame ?? { functionName: '(unknown)', url: '', lineNumber: 0, columnNumber: 0 };
        info = label(frame, options.symbolicate);
        labels.set(id, info);
      }
      if (info.name === '(root)') continue;
      cursor = childOf(cursor, info.name, info.location);
      cursor.totalMs += span;
    }
    cursor.selfMs += span;
  }
  return root;
}

export function pruneFlame(node: FlameNode, minimumMs: number): FlameNode {
  const children = node.children.filter(child => child.totalMs >= minimumMs).sort((a, b) => b.totalMs - a.totalMs).map(child => pruneFlame(child, minimumMs));
  return { ...node, totalMs: Math.round(node.totalMs * 10) / 10, selfMs: Math.round(node.selfMs * 10) / 10, children };
}

export function flamegraphFrames(node: FlameNode, startMs = 0): FlamegraphFrame {
  let offset = startMs;
  const children: FlamegraphFrame[] = [];
  for (const child of node.children) {
    children.push(flamegraphFrames(child, offset));
    offset += child.totalMs;
  }
  const name = node.location ? `${node.name} ${node.location}` : node.name;
  return { name, startMs: Math.round(startMs * 10) / 10, durationMs: Math.round(node.totalMs * 10) / 10, children };
}

export function collapsedStacks(node: FlameNode, prefix: string[] = [], out: string[] = []): string[] {
  const frame = node.location ? `${node.name} ${node.location}` : node.name;
  const path = node.name === 'all' && prefix.length === 0 ? [] : [...prefix, frame.replace(/;/g, ',')];
  const microseconds = Math.round(node.selfMs * 1000);
  if (path.length > 0 && microseconds > 0) out.push(`${path.join(';')} ${microseconds}`);
  for (const child of node.children) collapsedStacks(child, path, out);
  return out;
}

export function cpuProfileJson(profile: ThreadProfile): Record<string, unknown> {
  const children = new Map<number, number[]>();
  for (const node of profile.nodes.values()) {
    if (node.parent === undefined) continue;
    const list = children.get(node.parent) ?? [];
    list.push(node.id);
    children.set(node.parent, list);
  }
  const hits = new Map<number, number>();
  for (const id of profile.samples) hits.set(id, (hits.get(id) ?? 0) + 1);
  const nodes = [...profile.nodes.values()].map(node => ({ id: node.id, callFrame: { ...node.callFrame, scriptId: String(node.callFrame.scriptId ?? 0) }, hitCount: hits.get(node.id) ?? 0, children: children.get(node.id) ?? [] }));
  const startTime = profile.times[0] ?? 0;
  const timeDeltas = profile.times.map((time, index) => (index === 0 ? 0 : time - profile.times[index - 1]));
  return { nodes, startTime, endTime: profile.times[profile.times.length - 1] ?? startTime, samples: profile.samples, timeDeltas };
}

export interface RasterStats {
  tiles: number;
  paintImages: number;
  paints: number;
  gpuFlushMs: number;
  gpuBusyMs: number;
  rasterWorkerMs: number;
  compositorMs: number;
  vizMs: number;
}

export function rasterStats(model: TraceModel, window?: TraceWindow): RasterStats {
  let tiles = 0;
  let paintImages = 0;
  let paints = 0;
  for (const event of model.events) {
    if (event.ph !== 'X') continue;
    if (window && (event.ts < window.start || event.ts > window.end)) continue;
    if (event.name === 'RasterTask' && event.pid === model.renderer) tiles += 1;
    else if (event.name === 'PaintImage' && event.pid === model.renderer) paintImages += 1;
    else if (event.name === 'Paint' && model.main && event.pid === model.main.pid && event.tid === model.main.tid) paints += 1;
  }
  let gpuFlushMs = 0;
  let gpuBusyMs = 0;
  let vizMs = 0;
  for (const thread of gpuThreads(model)) {
    const name = model.threads.get(threadKey(thread.pid, thread.tid));
    const part = breakdown(model, thread, window);
    if (name === 'VizCompositorThread') vizMs += part.busyMs;
    else {
      gpuBusyMs += part.busyMs;
      gpuFlushMs += part.events.filter(item => /DoEndRasterCHROMIUM|DoRasterCHROMIUM|WaitForCommandsToBeScheduled/.test(item.name)).reduce((sum, item) => sum + item.selfMs, 0);
    }
  }
  const workers = threadsNamed(model, /^(CompositorTileWorker|ThreadPoolForegroundWorker)/, model.renderer);
  let rasterWorkerMs = 0;
  for (const thread of workers) rasterWorkerMs += breakdown(model, thread, window).events.filter(item => /Raster|Playback|PaintImage|ImageDecode/.test(item.name)).reduce((sum, item) => sum + item.selfMs, 0);
  const compositor = threadsNamed(model, /^Compositor$/, model.renderer)[0];
  return {
    tiles, paintImages, paints,
    gpuFlushMs: round(gpuFlushMs), gpuBusyMs: round(gpuBusyMs), rasterWorkerMs: round(rasterWorkerMs),
    compositorMs: compositor ? breakdown(model, compositor, window).busyMs : 0, vizMs: round(vizMs),
  };
}

function sliceName(slice: Slice): string {
  const data = slice.event.args?.data ?? {};
  if (slice.event.name === 'FunctionCall' || slice.event.name === 'EventDispatch' || slice.event.name === 'TimerFire') {
    const what = data.functionName || data.type || '';
    const where = data.url ? `${String(data.url).replace(/^https?:\/\/[^/]+/, '')}:${(data.lineNumber ?? 0) + 1}` : '';
    return [slice.event.name, what, where].filter(Boolean).join(' ');
  }
  if (slice.event.name === 'UpdateLayoutTree') return `UpdateLayoutTree ${slice.event.args?.elementCount ?? ''} elements`;
  if (slice.event.name === 'Layout') return `Layout ${slice.event.args?.beginData?.dirtyObjects ?? ''} dirty`;
  return slice.event.name;
}

export function sliceFlame(model: TraceModel, root: Slice, minimumMs = 0.5): FlamegraphFrame {
  const children = root.children
    .filter(child => (child.end - child.start) / 1000 >= minimumMs && !tracingArtifacts.has(child.event.name))
    .map(child => sliceFlame(model, child, minimumMs));
  return { name: sliceName(root), startMs: round((root.start - model.start) / 1000, 2), durationMs: round((root.end - root.start) / 1000, 2), children };
}

export function longTaskFlames(model: TraceModel, threshold = 50, window?: TraceWindow, limit = 8): FlamegraphFrame[] {
  if (!model.main) return [];
  return slices(model, model.main, window)
    .filter(root => (root.end - root.start - artifactTime(root)) / 1000 >= threshold)
    .sort((a, b) => (b.end - b.start) - (a.end - a.start))
    .slice(0, limit)
    .map(root => sliceFlame(model, root))
    .sort((a, b) => a.startMs - b.startMs);
}

export interface TraceAnalysis {
  spanMs: number;
  windowMs: number;
  main: Breakdown;
  worker: Breakdown | null;
  raster: RasterStats;
  frames: FrameStats;
  mainFrames: MainFrameStats;
  longTasks: LongTask[];
  interactions: Interaction[];
  inpMs: number;
  style: { recalcs: number; elements: number; maxElements: number };
  layout: { layouts: number; dirtyObjects: number; maxDirty: number };
  scripts: ScriptCost[];
  js: FunctionCost[];
  workerJs: FunctionCost[];
  flame: FlameNode | null;
  longTaskFlames: FlamegraphFrame[];
  timings: UserTiming[];
}

export function analyzeTrace(model: TraceModel, options: { window?: TraceWindow; budgetMs?: number; symbolicate?: Symbolicate; flameMinimumMs?: number } = {}): TraceAnalysis {
  const window = options.window;
  const workerThread = model.renderer === null ? undefined : threadsNamed(model, /^DedicatedWorker thread$/, model.renderer)[0];
  const profiles = threadProfiles(model);
  const mainProfile = profiles.find(item => model.main && item.pid === model.main.pid && item.tid === model.main.tid);
  const workerProfile = workerThread ? profiles.find(item => item.pid === workerThread.pid && item.tid === workerThread.tid) : undefined;
  const list = interactions(model, window);
  const flame = mainProfile ? pruneFlame(topDown(mainProfile, { window, symbolicate: options.symbolicate }), options.flameMinimumMs ?? 0.5) : null;
  return {
    spanMs: round((model.end - model.start) / 1000),
    windowMs: round(window ? (window.end - window.start) / 1000 : (model.end - model.start) / 1000),
    main: breakdown(model, model.main, window),
    worker: workerThread ? breakdown(model, workerThread, window) : null,
    raster: rasterStats(model, window),
    frames: frameStats(model, window),
    mainFrames: mainFrameWork(model, options.budgetMs ?? 16.7, window),
    longTasks: longTasks(model, 50, window),
    interactions: list,
    inpMs: inp(list),
    style: styleRecalcElements(model, window),
    layout: layoutObjects(model, window),
    scripts: scriptCosts(model, window),
    js: mainProfile ? functionCosts(mainProfile, { window, symbolicate: options.symbolicate }).filter(item => !item.name.startsWith('(') || item.name === '(garbage collector)') : [],
    workerJs: workerProfile ? functionCosts(workerProfile, { window, symbolicate: options.symbolicate }).filter(item => !item.name.startsWith('(') || item.name === '(garbage collector)') : [],
    flame,
    longTaskFlames: longTaskFlames(model, 50, window),
    timings: userTimings(model),
  };
}

export function markTime(model: TraceModel, name: string): number | null {
  let found: number | null = null;
  for (const event of model.events) {
    if (event.name !== name || !String(event.cat).includes('blink.user_timing')) continue;
    if (model.renderer !== null && event.pid !== model.renderer) continue;
    found = event.ts;
  }
  return found;
}

export function firstPresentedAfter(model: TraceModel, ts: number): number | null {
  const begins = new Map<string, TraceEvent>();
  let best: number | null = null;
  for (const event of model.events) {
    if (event.name !== 'PipelineReporter' || (model.renderer !== null && event.pid !== model.renderer)) continue;
    const id = String(event.id2?.local ?? event.id2?.global ?? event.id ?? '');
    if (event.ph === 'b') begins.set(id, event);
    else if (event.ph === 'e') {
      const begin = begins.get(id);
      if (!begin) continue;
      begins.delete(id);
      const state = String((begin.args?.frame_reporter ?? begin.args?.chrome_frame_reporter)?.state ?? '');
      if (begin.ts < ts || (state !== 'STATE_PRESENTED_ALL' && state !== 'STATE_PRESENTED_PARTIAL')) continue;
      if (best === null || event.ts < best) best = event.ts;
    }
  }
  return best;
}

export function quietAfter(model: TraceModel, from: number, quietMs = 500, threshold = 50): number {
  if (!model.main) return from;
  let cursor = from;
  for (const root of slices(model, model.main)) {
    if (root.end < cursor) continue;
    if ((root.end - root.start - artifactTime(root)) / 1000 < threshold) continue;
    if (root.start - cursor >= quietMs * 1000) break;
    cursor = Math.max(cursor, root.end);
  }
  return cursor;
}
