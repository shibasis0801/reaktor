export interface ProbeEvent {
  name: string;
  interactionId: number;
  startTime: number;
  processingStart: number;
  processingEnd: number;
  duration: number;
  target: string;
}

export interface ProbeScript {
  invoker: string;
  invokerType: string;
  sourceURL: string;
  sourceFunctionName: string;
  sourceCharPosition: number;
  duration: number;
  forcedStyleAndLayoutDuration: number;
}

export interface ProbeAnimationFrame {
  startTime: number;
  duration: number;
  blockingDuration: number;
  renderStart: number;
  styleAndLayoutStart: number;
  scripts: ProbeScript[];
}

export interface ProbeState {
  events: ProbeEvent[];
  longTasks: Array<{ startTime: number; duration: number }>;
  animationFrames: ProbeAnimationFrame[];
  paints: Record<string, number>;
  lcp: number;
  cls: number;
  frames: number[];
}

export interface ProbeSnapshot {
  now: number;
  events: ProbeEvent[];
  longTasks: Array<{ startTime: number; duration: number }>;
  animationFrames: ProbeAnimationFrame[];
  frames: number[];
  paints: Record<string, number>;
  lcp: number;
  cls: number;
  memory: { usedBytes: number; totalBytes: number } | null;
}

export function installProbe(): void {
  const scope = globalThis as unknown as { __reaktorProbe?: unknown };
  if (scope.__reaktorProbe) return;
  const state = { events: [] as ProbeEvent[], longTasks: [] as Array<{ startTime: number; duration: number }>, animationFrames: [] as ProbeAnimationFrame[], paints: {} as Record<string, number>, lcp: 0, cls: 0, frames: [] as number[] };
  let sampling = false;
  const describe = (node: unknown): string => {
    const element = node as { tagName?: string; getAttribute?: (name: string) => string | null; className?: unknown } | null;
    if (!element || !element.tagName) return '';
    const testId = element.getAttribute ? element.getAttribute('data-testid') : null;
    const classes = typeof element.className === 'string' ? element.className.split(/\s+/).filter(Boolean).slice(0, 2).join('.') : '';
    return `${element.tagName.toLowerCase()}${testId ? `[data-testid=${testId}]` : ''}${classes ? `.${classes}` : ''}`;
  };
  const observe = (type: string, options: Record<string, unknown>, callback: (entry: any) => void) => {
    try {
      const observer = new PerformanceObserver(list => list.getEntries().forEach(callback));
      observer.observe({ type, buffered: true, ...options } as PerformanceObserverInit);
    } catch {
      return;
    }
  };
  observe('event', { durationThreshold: 16 }, entry => {
    state.events.push({ name: entry.name, interactionId: entry.interactionId ?? 0, startTime: entry.startTime, processingStart: entry.processingStart, processingEnd: entry.processingEnd, duration: entry.duration, target: describe(entry.target) });
  });
  observe('first-input', {}, entry => {
    state.events.push({ name: entry.name, interactionId: entry.interactionId ?? 0, startTime: entry.startTime, processingStart: entry.processingStart, processingEnd: entry.processingEnd, duration: entry.duration, target: describe(entry.target) });
  });
  observe('longtask', {}, entry => { state.longTasks.push({ startTime: entry.startTime, duration: entry.duration }); });
  observe('long-animation-frame', {}, entry => {
    state.animationFrames.push({
      startTime: entry.startTime, duration: entry.duration, blockingDuration: entry.blockingDuration ?? 0, renderStart: entry.renderStart ?? 0, styleAndLayoutStart: entry.styleAndLayoutStart ?? 0,
      scripts: (entry.scripts ?? []).map((script: any) => ({
        invoker: script.invoker ?? '', invokerType: script.invokerType ?? '', sourceURL: script.sourceURL ?? '', sourceFunctionName: script.sourceFunctionName ?? '',
        sourceCharPosition: script.sourceCharPosition ?? -1, duration: script.duration ?? 0, forcedStyleAndLayoutDuration: script.forcedStyleAndLayoutDuration ?? 0,
      })),
    });
  });
  observe('paint', {}, entry => { state.paints[entry.name] = entry.startTime; });
  observe('largest-contentful-paint', {}, entry => { state.lcp = entry.startTime; });
  observe('layout-shift', {}, entry => { if (!entry.hadRecentInput) state.cls += entry.value ?? 0; });
  const tick = (time: number) => {
    if (!sampling) return;
    state.frames.push(time);
    requestAnimationFrame(tick);
  };
  scope.__reaktorProbe = {
    state,
    startFrames() {
      state.frames = [];
      if (sampling) return;
      sampling = true;
      requestAnimationFrame(tick);
    },
    stopFrames() {
      sampling = false;
      return state.frames.slice();
    },
    snapshot(since: number) {
      const memory = (performance as unknown as { memory?: { usedJSHeapSize: number; totalJSHeapSize: number } }).memory;
      return {
        now: performance.now(),
        events: state.events.filter(item => item.startTime >= since),
        longTasks: state.longTasks.filter(item => item.startTime >= since),
        animationFrames: state.animationFrames.filter(item => item.startTime >= since),
        frames: state.frames.slice(),
        paints: { ...state.paints },
        lcp: state.lcp,
        cls: state.cls,
        memory: memory ? { usedBytes: memory.usedJSHeapSize, totalBytes: memory.totalJSHeapSize } : null,
      };
    },
  };
}

export function measureRefresh(): Promise<number> {
  return new Promise(resolve => {
    const times: number[] = [];
    const tick = (time: number) => {
      times.push(time);
      if (times.length < 61) requestAnimationFrame(tick);
      else {
        const gaps = times.slice(1).map((value, index) => value - times[index]).sort((a, b) => a - b);
        resolve(gaps[Math.floor(gaps.length / 2)]);
      }
    };
    requestAnimationFrame(tick);
  });
}

export interface FrameSummary {
  count: number;
  refreshMs: number;
  p50Ms: number;
  p95Ms: number;
  p99Ms: number;
  maxMs: number;
  meanMs: number;
  dropped: number;
  droppedShare: number;
  fps: number;
}

function quantile(sorted: number[], p: number): number {
  if (sorted.length === 0) return 0;
  return sorted[Math.min(sorted.length - 1, Math.max(0, Math.ceil(p * sorted.length) - 1))];
}

export function frameSummary(timestamps: number[], refreshMs: number): FrameSummary {
  const gaps = timestamps.slice(1).map((value, index) => value - timestamps[index]);
  const sorted = [...gaps].sort((a, b) => a - b);
  const dropped = gaps.reduce((count, gap) => count + Math.max(0, Math.round(gap / refreshMs) - 1), 0);
  const span = timestamps.length > 1 ? timestamps[timestamps.length - 1] - timestamps[0] : 0;
  const round = (value: number) => Math.round(value * 10) / 10;
  const expected = refreshMs > 0 ? Math.round(span / refreshMs) : 0;
  return {
    count: gaps.length,
    refreshMs: round(refreshMs),
    p50Ms: round(quantile(sorted, 0.5)),
    p95Ms: round(quantile(sorted, 0.95)),
    p99Ms: round(quantile(sorted, 0.99)),
    maxMs: round(sorted[sorted.length - 1] ?? 0),
    meanMs: round(gaps.length ? span / gaps.length : 0),
    dropped,
    droppedShare: expected > 0 ? Math.round((dropped / expected) * 1000) / 1000 : 0,
    fps: round(span > 0 ? (gaps.length / span) * 1000 : 0),
  };
}

export interface InteractionSummary {
  interactionId: number;
  name: string;
  target: string;
  durationMs: number;
  inputDelayMs: number;
  processingMs: number;
  presentationMs: number;
}

export function interactionSummary(events: ProbeEvent[]): InteractionSummary[] {
  const byId = new Map<number, InteractionSummary>();
  for (const event of events) {
    if (!event.interactionId) continue;
    const candidate: InteractionSummary = {
      interactionId: event.interactionId,
      name: event.name,
      target: event.target,
      durationMs: event.duration,
      inputDelayMs: Math.round(Math.max(0, event.processingStart - event.startTime) * 10) / 10,
      processingMs: Math.round(Math.max(0, event.processingEnd - event.processingStart) * 10) / 10,
      presentationMs: Math.round(Math.max(0, event.startTime + event.duration - event.processingEnd) * 10) / 10,
    };
    const known = byId.get(event.interactionId);
    if (!known || candidate.durationMs > known.durationMs) byId.set(event.interactionId, candidate);
  }
  return [...byId.values()];
}

export function animationFrameSummary(frames: ProbeAnimationFrame[]): { count: number; totalBlockingMs: number; worstMs: number; scripts: Array<{ source: string; durationMs: number; forcedLayoutMs: number; count: number }> } {
  const scripts = new Map<string, { source: string; durationMs: number; forcedLayoutMs: number; count: number }>();
  for (const frame of frames) {
    for (const script of frame.scripts) {
      const source = `${script.invoker || script.invokerType} ${script.sourceFunctionName || ''} ${script.sourceURL.replace(/^https?:\/\/[^/]+/, '')}:${script.sourceCharPosition}`.trim();
      const entry = scripts.get(source) ?? { source, durationMs: 0, forcedLayoutMs: 0, count: 0 };
      entry.durationMs += script.duration;
      entry.forcedLayoutMs += script.forcedStyleAndLayoutDuration;
      entry.count += 1;
      scripts.set(source, entry);
    }
  }
  return {
    count: frames.length,
    totalBlockingMs: Math.round(frames.reduce((sum, frame) => sum + frame.blockingDuration, 0)),
    worstMs: Math.round(frames.reduce((most, frame) => Math.max(most, frame.duration), 0)),
    scripts: [...scripts.values()].map(item => ({ ...item, durationMs: Math.round(item.durationMs), forcedLayoutMs: Math.round(item.forcedLayoutMs) })).sort((a, b) => b.durationMs - a.durationMs).slice(0, 12),
  };
}
