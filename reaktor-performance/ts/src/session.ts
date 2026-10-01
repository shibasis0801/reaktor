import type { ReaktorPerformanceBudget, ReaktorPerformanceMetric, ReaktorPerformanceToolRun } from './index.ts';
import type { ReaktorFlamegraphFrame, ReaktorProfileCapture, ReaktorProfilingReport, ReaktorPerformanceSample } from './profiling.ts';
import type { ReactComponentStat, ReactFlameNode, ReactProbeSnapshot } from './react.ts';
import type { MutationCounts } from './mutations.ts';
import type { LayerSummary } from './cdp.ts';
import { spread, spreadText, change, type Spread } from './stats.ts';

export interface SessionViewport {
  name: string;
  width: number;
  height: number;
  dsf: number;
}

export interface SessionLoad {
  load1: number;
  load5: number;
  load15: number;
  cpus: number;
}

export interface GestureSample {
  name: string;
  load: SessionLoad;
  windowMs: number;
  raf: { p50Ms: number; p95Ms: number; p99Ms: number; maxMs: number; dropped: number; droppedShare: number; fps: number } | null;
  fps: { mean: number; p5: number; median: number } | null;
  trace: {
    busyMs: number;
    categories: Record<string, number>;
    frames: { presented: number; dropped: number; partial: number; p95Ms: number; maxMs: number };
    longTasks: Array<{ startMs: number; durationMs: number }>;
    style: { recalcs: number; elements: number; maxElements: number };
    layout: { layouts: number; dirtyObjects: number };
    raster: { tiles: number; paintImages: number; paints: number; gpuBusyMs: number };
    interactions: Array<{ type: string; durationMs: number }>;
    paints: number;
    js: Array<{ name: string; location: string; selfMs: number; totalMs: number }>;
  } | null;
  metrics: Record<string, number>;
  react: { mid: ReactProbeSnapshot | null; settled: ReactProbeSnapshot | null } | null;
  mutations: { mid: MutationCounts | null; settled: MutationCounts | null } | null;
  flicker: { flickerFrames: number; cellEvents: number; worstDip: number; frameCount: number; capturedFps: number; strip: string | null } | null;
  layers: LayerSummary | null;
  paintStrip: string | null;
  extra: Record<string, unknown>;
  error?: string;
}

export interface OpenSample {
  name: string;
  load: SessionLoad;
  timings: Record<string, number | null>;
  busyMs: number;
  categories: Record<string, number>;
  longTasks: Array<{ durationMs: number }>;
  tbtMs: number;
  scripts: Array<{ url: string; compileMs: number; evaluateMs: number }>;
  workerBusyMs: number | null;
  heapMB: number;
  dom: { map: number; document: number };
  transfer: { scripts: number; total: number; requests: number };
  error?: string;
}

export interface SessionRun {
  label: string;
  viewport: SessionViewport;
  run: number;
  pass: string;
  load: SessionLoad;
  refreshMs: number;
  opens: OpenSample[];
  steps: GestureSample[];
  memory: Array<{ at: string; heapMB: number; domNodes: number; listeners: number; mapElements: number }>;
}

export interface SessionComponentRow {
  scope: string;
  name: string;
  renders: number;
  wasted: number;
  selfMs: number;
  reasons: Record<string, number>;
}

export interface ReaktorSessionReport extends ReaktorProfilingReport {
  label: string;
  load: Spread;
  spreads: Record<string, Spread>;
  components: SessionComponentRow[];
  reactFlame: ReactFlameNode[];
  layers: Record<string, LayerSummary>;
  captures: { traces: string[]; strips: string[]; paintStrips: string[] };
}

const finite = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value);

function collect(table: Map<string, number[]>, name: string, value: unknown) {
  if (!finite(value)) return;
  const list = table.get(name);
  if (list) list.push(value);
  else table.set(name, [value]);
}

function repackMoved(extra: Record<string, unknown>): number | null {
  const pick = (value: unknown) => (value && typeof value === 'object' && finite((value as { moved?: unknown }).moved) ? (value as { moved: number }).moved : null);
  const values = [pick(extra.repack), pick(extra.opened), pick(extra.closed), pick(extra.shrunk), pick(extra.restored)].filter((value): value is number => value !== null);
  return values.length ? values.reduce((sum, value) => sum + value, 0) : null;
}

export function gestureValues(prefix: string, step: GestureSample, table: Map<string, number[]>) {
  const add = (name: string, value: unknown) => collect(table, `${prefix}.${name}`, value);
  if (step.fps) {
    add('fps.median', step.fps.median);
    add('fps.p5', step.fps.p5);
    add('fps.mean', step.fps.mean);
  }
  if (step.raf) {
    add('raf.p95', step.raf.p95Ms);
    add('raf.max', step.raf.maxMs);
    add('raf.droppedShare', step.raf.droppedShare);
  }
  if (step.trace) {
    const frames = step.trace.frames;
    const total = frames.presented + frames.dropped + frames.partial;
    add('frames.presented', frames.presented);
    add('frames.dropped', frames.dropped + frames.partial);
    add('frames.droppedShare', total ? Math.round(((frames.dropped + frames.partial) / total) * 1000) / 1000 : null);
    add('main.busy', step.trace.busyMs);
    for (const category of ['scripting', 'style', 'layout', 'paint', 'composite', 'gc']) add(`main.${category}`, step.trace.categories[category]);
    add('style.recalcs', step.trace.style.recalcs);
    add('style.elements', step.trace.style.elements);
    add('layout.count', step.trace.layout.layouts);
    add('paint.count', step.trace.paints);
    add('raster.tiles', step.trace.raster.tiles);
    add('gpu.busy', step.trace.raster.gpuBusyMs);
    add('longTasks.count', step.trace.longTasks.length);
    add('longTasks.max', step.trace.longTasks.reduce((most, task) => Math.max(most, task.durationMs), 0));
    if (step.trace.interactions.length) add('interaction.worst', step.trace.interactions.reduce((most, item) => Math.max(most, item.durationMs), 0));
  }
  add('cdp.layouts', step.metrics.LayoutCount);
  add('cdp.styleRecalcs', step.metrics.RecalcStyleCount);
  add('cdp.layoutMs', finite(step.metrics.LayoutDuration) ? step.metrics.LayoutDuration * 1000 : null);
  add('cdp.styleMs', finite(step.metrics.RecalcStyleDuration) ? step.metrics.RecalcStyleDuration * 1000 : null);
  add('cdp.scriptMs', finite(step.metrics.ScriptDuration) ? step.metrics.ScriptDuration * 1000 : null);
  if (step.flicker) {
    add('flicker.frames', step.flicker.flickerFrames);
    add('flicker.cells', step.flicker.cellEvents);
    add('flicker.worstDip', step.flicker.worstDip);
  }
  const mid = step.mutations?.mid;
  const settled = step.mutations?.settled;
  if (mid) {
    add('mutations.gesture', mid.total);
    add('mutations.structural', mid.structural);
    add('mutations.attributes', mid.attributes);
  }
  if (mid && settled) add('mutations.settle', settled.total - mid.total);
  const reactMid = step.react?.mid;
  const reactSettled = step.react?.settled;
  if (reactMid) {
    add('react.commits', reactMid.commits);
    add('react.rendered', reactMid.rendered);
    add('react.wasted', reactMid.wasted);
  }
  if (reactMid && reactSettled) {
    add('react.settleCommits', reactSettled.commits - reactMid.commits);
    add('react.settleRendered', reactSettled.rendered - reactMid.rendered);
  }
  add('repack.moved', repackMoved(step.extra));
}

export function openValues(prefix: string, open: OpenSample, table: Map<string, number[]>) {
  const add = (name: string, value: unknown) => collect(table, `${prefix}.${name}`, value);
  for (const [name, value] of Object.entries(open.timings)) add(name, value);
  add('tbt', open.tbtMs);
  add('longTasks.count', open.longTasks.length);
  add('longTasks.max', open.longTasks.reduce((most, task) => Math.max(most, task.durationMs), 0));
  add('main.busy', open.busyMs);
  add('main.scripting', open.categories.scripting);
  add('main.style', open.categories.style);
  add('main.layout', open.categories.layout);
  add('worker.busy', open.workerBusyMs);
  add('heapMB', open.heapMB);
  add('dom.map', open.dom.map);
  add('dom.document', open.dom.document);
  add('transfer.scripts', open.transfer.scripts);
}

function mergeFlame(into: Map<string, ReactFlameNode>, node: ReactFlameNode) {
  const known = into.get(node.name);
  if (!known) { into.set(node.name, JSON.parse(JSON.stringify(node)) as ReactFlameNode); return; }
  known.totalMs += node.totalMs;
  known.selfMs += node.selfMs;
  known.renders += node.renders;
  const children = new Map(known.children.map(child => [child.name, child]));
  for (const child of node.children) mergeFlame(children, child);
  known.children = [...children.values()];
}

function flameFrames(node: ReactFlameNode, start = 0, depth = 0): ReaktorFlamegraphFrame {
  let offset = start;
  const children: ReaktorFlamegraphFrame[] = [];
  if (depth < 12) for (const child of [...node.children].sort((a, b) => b.totalMs - a.totalMs || b.renders - a.renders)) {
    children.push(flameFrames(child, offset, depth + 1));
    offset += child.totalMs || child.renders * 0.01;
  }
  return { name: `${node.name} ×${node.renders}`, startMs: Math.round(start * 100) / 100, durationMs: Math.round((node.totalMs || node.renders * 0.01) * 100) / 100, children };
}

export interface SessionReportOptions {
  target: string;
  label: string;
  budgets?: ReaktorPerformanceBudget[];
  traces?: string[];
  generatedAt?: string;
}

export function sessionReport(runs: SessionRun[], options: SessionReportOptions): ReaktorSessionReport {
  const table = new Map<string, number[]>();
  const loads: number[] = [];
  const componentTable = new Map<string, SessionComponentRow>();
  const flames = new Map<string, Map<string, ReactFlameNode>>();
  const layers: Record<string, LayerSummary> = {};
  const strips: string[] = [];
  const paintStrips: string[] = [];
  for (const run of runs) {
    loads.push(run.load.load1);
    const base = `${run.viewport.name}`;
    for (const open of run.opens) if (!open.error) openValues(`${base}.${open.name}`, open, table);
    for (const step of run.steps) {
      if (step.error) continue;
      loads.push(step.load.load1);
      gestureValues(`${base}.${step.name}`, step, table);
      const scope = `${base}.${step.name}`;
      const react = step.react?.mid;
      if (react) {
        for (const component of react.components as ReactComponentStat[]) {
          const key = `${scope}|${component.name}`;
          const row = componentTable.get(key) ?? { scope, name: component.name, renders: 0, wasted: 0, selfMs: 0, reasons: {} };
          row.renders += component.renders;
          row.wasted += component.wasted;
          row.selfMs += component.selfMs;
          for (const [reason, count] of Object.entries(component.reasons)) row.reasons[reason] = (row.reasons[reason] ?? 0) + (count ?? 0);
          componentTable.set(key, row);
        }
        const forScope = flames.get(scope) ?? new Map<string, ReactFlameNode>();
        mergeFlame(forScope, { ...react.flame, name: scope });
        flames.set(scope, forScope);
      }
      if (step.layers) layers[scope] = step.layers;
      if (step.flicker?.strip) strips.push(step.flicker.strip);
      if (step.paintStrip) paintStrips.push(step.paintStrip);
    }
    for (const memory of run.memory) {
      collect(table, `${base}.memory.${memory.at}.heapMB`, memory.heapMB);
      collect(table, `${base}.memory.${memory.at}.domNodes`, memory.domNodes);
    }
  }
  const spreads: Record<string, Spread> = {};
  const samples: ReaktorPerformanceSample[] = [];
  const metrics: ReaktorPerformanceMetric[] = [];
  for (const [name, values] of [...table].sort((a, b) => a[0].localeCompare(b[0]))) {
    const value = spread(values);
    spreads[name] = value;
    const unit = /fps/.test(name) ? 'fps' : /Share|worstDip/.test(name) ? 'ratio' : /MB$/.test(name) ? 'MB' : /(count|frames|recalcs|elements|tiles|commits|rendered|wasted|mutations|repack|cells|dom|presented|dropped|layouts|styleRecalcs)/.test(name) ? 'count' : /transfer/.test(name) ? 'bytes' : 'ms';
    const lowerIsBetter = !/fps|presented/.test(name);
    samples.push({ name, iterations: value.n, medianMs: value.median, bestMs: lowerIsBetter ? value.min : value.max, worstMs: lowerIsBetter ? value.max : value.min, unit, scope: { operation: name.split('.').slice(1, 2).join(''), attributes: { viewport: name.split('.')[0], spread: spreadText(value, unit), p25: String(value.p25), p75: String(value.p75) } } });
    metrics.push({ name, value: value.median, unit, domain: /^.*\.(react|mutations)\./.test(name) ? 'AppVitals' : 'Runtime', scope: { attributes: { iterations: String(value.n), min: String(value.min), max: String(value.max) } } });
  }
  const components = [...componentTable.values()].sort((a, b) => b.renders - a.renders).slice(0, 200);
  const reactFlame = [...flames.values()].flatMap(scope => [...scope.values()]);
  const generatedAt = options.generatedAt ?? new Date().toISOString();
  const profiles: ReaktorProfileCapture[] = (options.traces ?? []).map(path => ({ name: path.split('/').slice(-2).join('/'), profiler: 'ChromeDevTools', platform: 'chrome', startedAt: generatedAt, durationMs: 0, outputPath: path, sampleCount: null, topFrames: [], scope: {} }));
  const toolRuns: ReaktorPerformanceToolRun[] = [{ name: options.label, tool: 'Playwright', status: 'Passed', startedAt: generatedAt, durationMs: 0, reportPath: null, scope: { attributes: { runs: String(runs.length) } } }];
  return {
    target: options.target,
    label: options.label,
    generatedAt,
    marks: [],
    samples,
    metrics,
    buildArtifacts: [],
    flamegraph: reactFlame.map(node => flameFrames(node)),
    profiles,
    toolRuns,
    budgets: options.budgets ?? [],
    load: spread(loads),
    spreads,
    components,
    reactFlame,
    layers,
    captures: { traces: options.traces ?? [], strips, paintStrips },
  };
}

export interface CompareRow {
  metric: string;
  label?: string;
  lowerIsBetter?: boolean;
}

export function compareMarkdown(before: ReaktorSessionReport, after: ReaktorSessionReport, rows: Array<string | CompareRow>): string {
  const lines = [
    `| Metric | ${before.label} median [min–max] | ${after.label} median [min–max] | Change |`,
    '|---|---|---|---|',
  ];
  for (const item of rows) {
    const row = typeof item === 'string' ? { metric: item } : item;
    const a = before.spreads[row.metric];
    const b = after.spreads[row.metric];
    if (!a && !b) continue;
    const delta = a && b ? change(a, b) : Number.NaN;
    lines.push(`| ${row.label ?? row.metric} | ${a ? spreadText(a) : 'n/a'} | ${b ? spreadText(b) : 'n/a'} | ${Number.isFinite(delta) ? `${delta > 0 ? '+' : ''}${delta}%` : ''} |`);
  }
  lines.push('', `Load average (1 min) during the runs: ${before.label} ${spreadText(before.load)}, ${after.label} ${spreadText(after.load)}.`);
  return lines.join('\n');
}
