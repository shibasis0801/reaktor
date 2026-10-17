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

export type ReaktorPerformanceSeverity = 'Info' | 'Warning' | 'Error';

// Mirror of ReaktorPerformanceBudgetDirection in commonMain: Max caps a lower-is-better metric,
// Min floors a higher-is-better one (e.g. a Lighthouse 0..100 category score).
export type ReaktorPerformanceBudgetDirection = 'Max' | 'Min';

export interface ReaktorPerformanceBudget {
  metric: string;
  limit: number;
  unit: string;
  severity?: ReaktorPerformanceSeverity;
  direction?: ReaktorPerformanceBudgetDirection;
}

export interface ReaktorPerformanceToolRun {
  name: string;
  tool: string;
  status: 'Passed' | 'Failed' | 'Skipped';
  startedAt: string;
  durationMs: number;
  reportPath?: string | null;
  errorMessage?: string | null;
  metrics?: ReaktorPerformanceMetric[];
  scope?: ReaktorPerformanceScope;
}

export interface ReaktorPerformanceReport {
  target: string;
  generatedAt: string;
  metrics?: ReaktorPerformanceMetric[];
  buildArtifacts?: ReaktorBuildArtifactMetric[];
  webVitals?: Record<string, number | null>;
  appVitals?: Record<string, unknown>;
  toolRuns?: ReaktorPerformanceToolRun[];
  budgets?: ReaktorPerformanceBudget[];
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

// ---------------------------------------------------------------------------
// Lighthouse — the standard runner-facing surface. Keep names and budgets in
// sync with ReaktorLighthouse.kt (the cross-target contract); tools/lighthouse-
// report.mjs is the only Node entry point that should consume these.
// ---------------------------------------------------------------------------

export const lighthouseMetrics = {
  performance: 'lighthouse.performance',
  accessibility: 'lighthouse.accessibility',
  bestPractices: 'lighthouse.best-practices',
  seo: 'lighthouse.seo',
  pwa: 'lighthouse.pwa',
  fcp: 'lighthouse.fcp',
  lcp: 'lighthouse.lcp',
  speedIndex: 'lighthouse.speed-index',
  tbt: 'lighthouse.tbt',
  tti: 'lighthouse.tti',
  cls: 'lighthouse.cls',
  ttfb: 'lighthouse.ttfb',
} as const;

/** Lower-is-better lab / Core Web Vitals caps at the web.dev "good" thresholds. */
export function lighthouseWebVitalsBudgets(): ReaktorPerformanceBudget[] {
  return [
    { metric: lighthouseMetrics.lcp, limit: 2500, unit: 'ms', direction: 'Max' },
    { metric: lighthouseMetrics.tbt, limit: 200, unit: 'ms', direction: 'Max' },
    { metric: lighthouseMetrics.cls, limit: 0.1, unit: '', direction: 'Max' },
    { metric: lighthouseMetrics.speedIndex, limit: 3400, unit: 'ms', direction: 'Max' },
    { metric: lighthouseMetrics.fcp, limit: 1800, unit: 'ms', direction: 'Max' },
  ];
}

/** Higher-is-better category-score floors. */
export function lighthouseCategoryBudgets(min = 90): ReaktorPerformanceBudget[] {
  return [
    lighthouseMetrics.performance,
    lighthouseMetrics.accessibility,
    lighthouseMetrics.bestPractices,
    lighthouseMetrics.seo,
  ].map((metricName) => ({ metric: metricName, limit: min, unit: 'score', direction: 'Min' as const }));
}

export function recommendedLighthouseBudgets(): ReaktorPerformanceBudget[] {
  return [...lighthouseWebVitalsBudgets(), ...lighthouseCategoryBudgets()];
}

export interface LighthouseAudit {
  numericValue?: number;
}

export interface LighthouseCategory {
  score?: number | null;
}

/** The slice of a Lighthouse Result (LHR) the mapper reads. */
export interface LighthouseResult {
  requestedUrl?: string;
  finalUrl?: string;
  finalDisplayedUrl?: string;
  categories?: Record<string, LighthouseCategory | undefined>;
  audits?: Record<string, LighthouseAudit | undefined>;
}

export interface LighthouseReportOptions {
  url?: string;
  budgets?: ReaktorPerformanceBudget[];
  reportPath?: string | null;
  generatedAt?: string;
  startedAt?: string;
  durationMs?: number;
}

/**
 * Map a Lighthouse Result into the standard `ReaktorPerformanceReport`. Category scores (0..100)
 * and lab metrics become `WebVitals` metrics under the `lighthouseMetrics` names; limits live only
 * in `budgets` (matched by name in `budgetViolations`) so a metric is never double-budgeted.
 */
export function lighthouseReport(
  target: string,
  lhr: LighthouseResult,
  options: LighthouseReportOptions = {},
): ReaktorPerformanceReport {
  const url = options.url ?? lhr.finalDisplayedUrl ?? lhr.finalUrl ?? lhr.requestedUrl ?? null;
  const scope: ReaktorPerformanceScope = { route: url };
  const score = (id: string): number | undefined => {
    const value = lhr.categories?.[id]?.score;
    return typeof value === 'number' ? value * 100 : undefined;
  };
  const audit = (id: string): number | undefined => {
    const value = lhr.audits?.[id]?.numericValue;
    return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
  };

  const metrics: ReaktorPerformanceMetric[] = [];
  const add = (name: string, value: number | undefined, unit: string): void => {
    if (value === undefined) return;
    // CLS (unitless) is small and budgeted at 0.1, so it needs more than the 1-decimal
    // precision that suits ms/score metrics — otherwise 0.12 would round to 0.1 and pass.
    const decimals = unit === '' ? 3 : 1;
    const factor = 10 ** decimals;
    metrics.push({ name, value: Math.round(value * factor) / factor, unit, domain: 'WebVitals', scope });
  };
  add(lighthouseMetrics.performance, score('performance'), 'score');
  add(lighthouseMetrics.accessibility, score('accessibility'), 'score');
  add(lighthouseMetrics.bestPractices, score('best-practices'), 'score');
  add(lighthouseMetrics.seo, score('seo'), 'score');
  add(lighthouseMetrics.pwa, score('pwa'), 'score');
  add(lighthouseMetrics.fcp, audit('first-contentful-paint'), 'ms');
  add(lighthouseMetrics.lcp, audit('largest-contentful-paint'), 'ms');
  add(lighthouseMetrics.speedIndex, audit('speed-index'), 'ms');
  add(lighthouseMetrics.tbt, audit('total-blocking-time'), 'ms');
  add(lighthouseMetrics.tti, audit('interactive'), 'ms');
  add(lighthouseMetrics.cls, audit('cumulative-layout-shift'), '');
  add(lighthouseMetrics.ttfb, audit('server-response-time'), 'ms');

  const generatedAt = options.generatedAt ?? new Date().toISOString();
  return {
    target,
    generatedAt,
    metrics,
    buildArtifacts: [],
    webVitals: {
      firstContentfulPaintMs: audit('first-contentful-paint') ?? null,
      largestContentfulPaintMs: audit('largest-contentful-paint') ?? null,
      cumulativeLayoutShift: audit('cumulative-layout-shift') ?? 0,
      timeToFirstByteMs: audit('server-response-time') ?? null,
    },
    toolRuns: [
      {
        name: url ?? target,
        tool: 'Lighthouse',
        status: 'Passed',
        startedAt: options.startedAt ?? generatedAt,
        durationMs: options.durationMs ?? 0,
        reportPath: options.reportPath ?? null,
        scope,
      },
    ],
    budgets: options.budgets ?? recommendedLighthouseBudgets(),
  };
}

export interface ReaktorPerformanceBudgetViolation {
  metric: string;
  actual: number;
  limit: number;
  unit: string;
  severity: ReaktorPerformanceSeverity;
  direction: ReaktorPerformanceBudgetDirection;
  message: string;
}

/**
 * Mirrors the `budgets` path of `ReaktorPerformanceReport.budgetViolations()` in commonMain: match
 * each budget to the last metric of the same name and compare by direction (Max cap / Min floor).
 */
export function budgetViolations(report: ReaktorPerformanceReport): ReaktorPerformanceBudgetViolation[] {
  const metrics = report.metrics ?? [];
  const violations: ReaktorPerformanceBudgetViolation[] = [];
  for (const budget of report.budgets ?? []) {
    let found: ReaktorPerformanceMetric | undefined;
    for (const candidate of metrics) {
      if (candidate.name === budget.metric) found = candidate;
    }
    if (!found) continue;
    const direction = budget.direction ?? 'Max';
    const violated = direction === 'Max' ? found.value > budget.limit : found.value < budget.limit;
    if (!violated) continue;
    violations.push({
      metric: budget.metric,
      actual: found.value,
      limit: budget.limit,
      unit: budget.unit,
      severity: budget.severity ?? 'Error',
      direction,
      message: `${budget.metric} ${found.value} ${direction === 'Max' ? 'exceeded' : 'fell below'} ${budget.limit} budget`,
    });
  }
  return violations;
}

declare global {
  // Installed by src/jsMain/resources/reaktor-web-vitals.js when used directly.
  // Kept intentionally loose so Playwright/Lighthouse adapters can pass snapshots too.
  // eslint-disable-next-line no-var
  var ReaktorPerf: unknown;
}
