export type ReaktorPerformanceDomain =
  | 'Build'
  | 'Runtime'
  | 'WebVitals'
  | 'AppVitals'
  | 'ServerVitals'
  | 'Profiling'
  | 'Telemetry'
  | 'Tooling';

export interface ReaktorPerformanceScope {
  graphId?: string | null;
  nodeId?: string | null;
  route?: string | null;
  service?: string | null;
  operation?: string | null;
  module?: string | null;
  artifactPath?: string | null;
  attributes?: Record<string, string>;
}

export interface ReaktorPerformanceMetric {
  name: string;
  value: number;
  unit: string;
  domain?: ReaktorPerformanceDomain;
  scope?: ReaktorPerformanceScope;
  budgetLimit?: number | null;
}

export interface ReaktorBuildArtifactMetric {
  name: string;
  type: string;
  bytes: number;
  compressedBytes?: number | null;
  budgetBytes?: number | null;
  compressedBudgetBytes?: number | null;
  path?: string | null;
  scope?: ReaktorPerformanceScope;
}

export interface ReaktorPerformanceReport {
  target: string;
  generatedAt: string;
  metrics?: ReaktorPerformanceMetric[];
  buildArtifacts?: ReaktorBuildArtifactMetric[];
  webVitals?: Record<string, number | null>;
  appVitals?: Record<string, unknown>;
  toolRuns?: Record<string, unknown>[];
}

export function createReport(
  target: string,
  partial: Partial<Omit<ReaktorPerformanceReport, 'target' | 'generatedAt'>> = {},
): ReaktorPerformanceReport {
  return {
    target,
    generatedAt: new Date().toISOString(),
    metrics: [],
    buildArtifacts: [],
    toolRuns: [],
    ...partial,
  };
}

export function browserVitalsReport(target: string, perf: unknown = globalThis.ReaktorPerf): ReaktorPerformanceReport {
  const api = perf as { report?: () => Record<string, unknown> } | undefined;
  const snapshot = api?.report?.() ?? {};
  return createReport(target, {
    webVitals: snapshot.vitals as Record<string, number | null> | undefined,
    appVitals: snapshot.appVitals as Record<string, unknown> | undefined,
    metrics: [
      metric('resources.totalTransferSize', nestedNumber(snapshot, 'resources', 'totalTransferSize'), 'bytes', 'Build'),
      metric('resources.scriptTransferSize', nestedNumber(snapshot, 'resources', 'scriptTransferSize'), 'bytes', 'Build'),
      metric('longTasks.totalDuration', nestedNumber(snapshot, 'longTasks', 'totalDuration'), 'ms', 'Runtime'),
    ].filter((item): item is ReaktorPerformanceMetric => item.value >= 0),
  });
}

export function metric(
  name: string,
  value: number,
  unit: string,
  domain: ReaktorPerformanceDomain = 'Runtime',
  scope: ReaktorPerformanceScope = {},
  budgetLimit?: number,
): ReaktorPerformanceMetric {
  return {
    name,
    value: Number.isFinite(value) ? Math.round(value * 10) / 10 : -1,
    unit,
    domain,
    scope,
    budgetLimit,
  };
}

function nestedNumber(source: Record<string, unknown>, ...path: string[]): number {
  let cursor: unknown = source;
  for (const key of path) {
    if (cursor == null || typeof cursor !== 'object') return -1;
    cursor = (cursor as Record<string, unknown>)[key];
  }
  return typeof cursor === 'number' && Number.isFinite(cursor) ? cursor : -1;
}

declare global {
  // Installed by src/jsMain/resources/reaktor-web-vitals.js when used directly.
  // Kept intentionally loose so Playwright/Lighthouse adapters can pass snapshots too.
  // eslint-disable-next-line no-var
  var ReaktorPerf: unknown;
}
