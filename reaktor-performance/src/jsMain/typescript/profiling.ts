import type {
  ReaktorPerformanceBudget,
  ReaktorPerformanceDomain,
  ReaktorPerformanceMetric,
  ReaktorPerformanceReport,
  ReaktorPerformanceScope,
  ReaktorPerformanceToolRun,
} from './index';
import type { FlamegraphFrame, TraceAnalysis } from './trace';
import type { FrameSummary, InteractionSummary } from './probes';

export interface ReaktorPerformanceMark {
  name: string;
  startMs: number;
}

export interface ReaktorPerformanceSample {
  name: string;
  iterations: number;
  medianMs: number;
  bestMs: number;
  worstMs: number;
  budgetMs?: number | null;
  unit?: string;
  scope?: ReaktorPerformanceScope;
}

export interface ReaktorFlamegraphFrame {
  name: string;
  startMs: number;
  durationMs: number;
  children?: ReaktorFlamegraphFrame[];
}

export type ReaktorProfilerKind = 'AsyncProfiler' | 'Jfr' | 'SimplePerf' | 'Perfetto' | 'Instruments' | 'ChromeDevTools' | 'WorkersAnalytics' | 'Custom';

export interface ReaktorProfileCapture {
  name: string;
  profiler: ReaktorProfilerKind;
  platform: string;
  startedAt: string;
  durationMs: number;
  outputPath?: string | null;
  sampleCount?: number | null;
  topFrames?: ReaktorFlamegraphFrame[];
  scope?: ReaktorPerformanceScope;
}

export interface ReaktorProfilingReport extends ReaktorPerformanceReport {
  marks?: ReaktorPerformanceMark[];
  samples?: ReaktorPerformanceSample[];
  flamegraph?: ReaktorFlamegraphFrame[];
  profiles?: ReaktorProfileCapture[];
}

const tenth = (value: number): number => (Number.isFinite(value) ? Math.round(value * 10) / 10 : -1);

export function measure(name: string, value: number, unit: string, domain: ReaktorPerformanceDomain = 'Runtime', scope: ReaktorPerformanceScope = {}): ReaktorPerformanceMetric {
  return { name, value: tenth(value), unit, domain, scope };
}

export function middle(values: number[]): number {
  if (values.length === 0) return Number.NaN;
  const sorted = [...values].sort((a, b) => a - b);
  const half = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[half] : (sorted[half - 1] + sorted[half]) / 2;
}

export function sampleOf(name: string, values: number[], options: { unit?: string; scope?: ReaktorPerformanceScope; budget?: number | null; higherIsBetter?: boolean } = {}): ReaktorPerformanceSample {
  const finite = values.filter(value => Number.isFinite(value));
  const low = finite.length ? Math.min(...finite) : Number.NaN;
  const high = finite.length ? Math.max(...finite) : Number.NaN;
  return {
    name,
    iterations: finite.length,
    medianMs: tenth(middle(finite)),
    bestMs: tenth(options.higherIsBetter ? high : low),
    worstMs: tenth(options.higherIsBetter ? low : high),
    budgetMs: options.budget ?? null,
    unit: options.unit ?? 'ms',
    scope: options.scope ?? {},
  };
}

export function sampleMetrics(sample: ReaktorPerformanceSample, domain: ReaktorPerformanceDomain = 'Runtime'): ReaktorPerformanceMetric[] {
  const unit = sample.unit ?? 'ms';
  const scope = { ...(sample.scope ?? {}), attributes: { ...(sample.scope?.attributes ?? {}), iterations: String(sample.iterations), best: String(sample.bestMs), worst: String(sample.worstMs) } };
  return [{ name: sample.name, value: sample.medianMs, unit, domain, scope }];
}

export function traceMetrics(prefix: string, analysis: TraceAnalysis, scope: ReaktorPerformanceScope = {}): ReaktorPerformanceMetric[] {
  const metrics: ReaktorPerformanceMetric[] = [];
  const add = (name: string, value: number, unit = 'ms', domain: ReaktorPerformanceDomain = 'Runtime') => metrics.push(measure(`${prefix}.${name}`, value, unit, domain, scope));
  add('window', analysis.windowMs);
  add('main.busy', analysis.main.busyMs);
  for (const [category, value] of Object.entries(analysis.main.categories)) add(`main.${category}`, value);
  add('main.longTasks.count', analysis.longTasks.length, 'count');
  add('main.longTasks.total', analysis.longTasks.reduce((sum, task) => sum + task.durationMs, 0));
  add('main.longTasks.max', analysis.longTasks.reduce((most, task) => Math.max(most, task.durationMs), 0));
  add('main.frames.p95', analysis.mainFrames.p95Ms);
  add('main.frames.over', analysis.mainFrames.over, 'count');
  add('style.recalcs', analysis.style.recalcs, 'count');
  add('style.elements', analysis.style.elements, 'count');
  add('style.maxElements', analysis.style.maxElements, 'count');
  add('layout.count', analysis.layout.layouts, 'count');
  add('layout.dirtyObjects', analysis.layout.dirtyObjects, 'count');
  add('raster.tiles', analysis.raster.tiles, 'count', 'Profiling');
  add('raster.paintImages', analysis.raster.paintImages, 'count', 'Profiling');
  add('gpu.busy', analysis.raster.gpuBusyMs, 'ms', 'Profiling');
  add('gpu.raster', analysis.raster.gpuFlushMs, 'ms', 'Profiling');
  add('compositor.busy', analysis.raster.compositorMs, 'ms', 'Profiling');
  add('frames.presented', analysis.frames.presented, 'count');
  add('frames.dropped', analysis.frames.dropped, 'count');
  add('frames.p95', analysis.frames.p95Ms);
  add('frames.max', analysis.frames.maxMs);
  if (analysis.interactions.length > 0) add('inp', analysis.inpMs);
  if (analysis.worker) add('worker.busy', analysis.worker.busyMs);
  return metrics;
}

export function frameMetrics(prefix: string, frames: FrameSummary, scope: ReaktorPerformanceScope = {}): ReaktorPerformanceMetric[] {
  return [
    measure(`${prefix}.raf.p50`, frames.p50Ms, 'ms', 'AppVitals', scope),
    measure(`${prefix}.raf.p95`, frames.p95Ms, 'ms', 'AppVitals', scope),
    measure(`${prefix}.raf.p99`, frames.p99Ms, 'ms', 'AppVitals', scope),
    measure(`${prefix}.raf.max`, frames.maxMs, 'ms', 'AppVitals', scope),
    measure(`${prefix}.raf.dropped`, frames.dropped, 'count', 'AppVitals', scope),
    measure(`${prefix}.raf.droppedShare`, frames.droppedShare, 'ratio', 'AppVitals', scope),
    measure(`${prefix}.raf.fps`, frames.fps, 'fps', 'AppVitals', scope),
  ];
}

export function interactionMetrics(prefix: string, list: InteractionSummary[], scope: ReaktorPerformanceScope = {}): ReaktorPerformanceMetric[] {
  if (list.length === 0) return [];
  const worst = list.reduce((most, item) => (item.durationMs > most.durationMs ? item : most), list[0]);
  return [
    measure(`${prefix}.interaction`, worst.durationMs, 'ms', 'WebVitals', { ...scope, attributes: { ...(scope.attributes ?? {}), event: worst.name, target: worst.target } }),
    measure(`${prefix}.interaction.inputDelay`, worst.inputDelayMs, 'ms', 'WebVitals', scope),
    measure(`${prefix}.interaction.processing`, worst.processingMs, 'ms', 'WebVitals', scope),
    measure(`${prefix}.interaction.presentation`, worst.presentationMs, 'ms', 'WebVitals', scope),
  ];
}

export function flameFrames(frames: FlamegraphFrame[]): ReaktorFlamegraphFrame[] {
  return frames.map(frame => ({ name: frame.name, startMs: frame.startMs, durationMs: frame.durationMs, children: flameFrames(frame.children ?? []) }));
}

export function profileCapture(name: string, analysis: TraceAnalysis, options: { platform: string; startedAt: string; outputPath?: string | null; top?: FlamegraphFrame[]; scope?: ReaktorPerformanceScope }): ReaktorProfileCapture {
  return {
    name,
    profiler: 'ChromeDevTools',
    platform: options.platform,
    startedAt: options.startedAt,
    durationMs: analysis.windowMs,
    outputPath: options.outputPath ?? null,
    sampleCount: null,
    topFrames: flameFrames(options.top ?? []),
    scope: options.scope ?? {},
  };
}

export function profilingReport(target: string, parts: {
  metrics?: ReaktorPerformanceMetric[];
  samples?: ReaktorPerformanceSample[];
  marks?: ReaktorPerformanceMark[];
  flamegraph?: ReaktorFlamegraphFrame[];
  profiles?: ReaktorProfileCapture[];
  toolRuns?: ReaktorPerformanceToolRun[];
  budgets?: ReaktorPerformanceBudget[];
  appVitals?: Record<string, unknown>;
  webVitals?: Record<string, number | null>;
  generatedAt?: string;
}): ReaktorProfilingReport {
  const samples = parts.samples ?? [];
  return {
    target,
    generatedAt: parts.generatedAt ?? new Date().toISOString(),
    marks: parts.marks ?? [],
    webVitals: parts.webVitals,
    appVitals: parts.appVitals,
    samples,
    metrics: [...(parts.metrics ?? []), ...samples.flatMap(sample => sampleMetrics(sample))],
    buildArtifacts: [],
    flamegraph: parts.flamegraph ?? [],
    profiles: parts.profiles ?? [],
    toolRuns: parts.toolRuns ?? [],
    budgets: parts.budgets ?? [],
  };
}
