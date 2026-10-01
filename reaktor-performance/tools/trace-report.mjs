#!/usr/bin/env node
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { gunzipSync } from 'node:zlib';
import { analyzeTrace, collapsedStacks, cpuProfileJson, firstPresentedAfter, markTime, quietAfter, threadProfiles, traceModel } from '../ts/src/trace.ts';
import { flameFrames, profileCapture, profilingReport, traceMetrics } from '../ts/src/profiling.ts';
import { flameSvg } from '../ts/src/flame.ts';
import { symbolicator } from '../ts/src/sourcemap.ts';
import { budgetViolations } from '../ts/src/index.ts';

function usage(message) {
  if (message) console.error(`error: ${message}`);
  console.error([
    'usage: node tools/trace-report.mjs --trace <trace.json[.gz]> --target <name> [options]',
    '',
    '  --url <origin>          page origin, picks the renderer that committed it',
    '  --maps <dir>            directory holding <chunk>.js.map files for symbolication',
    '  --from-mark <name>      start the analysis window at this user-timing mark',
    '  --to-mark <name>        end the analysis window at this user-timing mark',
    '  --budget-ms <ms>        frame budget used for main-thread frame counts (default 8.33)',
    '  --out <report.json>     write the ReaktorPerformanceReport (default: stdout)',
    '  --flame-dir <dir>       write a flame SVG, collapsed stacks and .cpuprofile files',
    '  --name <label>          capture name (default: the trace file name)',
  ].join('\n'));
  process.exit(2);
}

function parseArgs(argv) {
  const args = { trace: null, target: null, url: undefined, maps: null, fromMark: null, toMark: null, budgetMs: 8.33, out: null, flameDir: null, name: null };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--trace') args.trace = argv[++index];
    else if (arg === '--target') args.target = argv[++index];
    else if (arg === '--url') args.url = argv[++index];
    else if (arg === '--maps') args.maps = argv[++index];
    else if (arg === '--from-mark') args.fromMark = argv[++index];
    else if (arg === '--to-mark') args.toMark = argv[++index];
    else if (arg === '--budget-ms') args.budgetMs = Number(argv[++index]);
    else if (arg === '--out') args.out = argv[++index];
    else if (arg === '--flame-dir') args.flameDir = argv[++index];
    else if (arg === '--name') args.name = argv[++index];
    else if (arg === '--help' || arg === '-h') usage();
    else usage(`unknown argument: ${arg}`);
  }
  if (!args.trace || !args.target) usage('--trace and --target are required');
  return args;
}

export function readTrace(path) {
  const buffer = readFileSync(path);
  const text = path.endsWith('.gz') ? gunzipSync(buffer).toString('utf8') : buffer.toString('utf8');
  return JSON.parse(text);
}

export function mapLoader(directory) {
  if (!directory) return undefined;
  const root = resolve(directory);
  return symbolicator(url => {
    const name = basename(url.split('?')[0]);
    for (const candidate of [join(root, `${name}.map`), join(root, 'assets', `${name}.map`)]) {
      if (existsSync(candidate)) return JSON.parse(readFileSync(candidate, 'utf8'));
    }
    return null;
  });
}

export function reportFromTrace(trace, options) {
  const model = traceModel(trace, { url: options.url });
  const from = options.fromMark ? markTime(model, options.fromMark) : null;
  const to = options.toMark ? markTime(model, options.toMark) : null;
  const window = from !== null || to !== null ? { start: from ?? model.start, end: to ?? model.end } : undefined;
  const analysis = analyzeTrace(model, { window, budgetMs: options.budgetMs, symbolicate: options.symbolicate });
  const name = options.name ?? options.target;
  const scope = { route: options.url ?? null, operation: name, attributes: options.attributes ?? {} };
  const metrics = traceMetrics(`trace.${name}`, analysis, scope);
  if (options.fromMark) {
    const painted = from === null ? null : firstPresentedAfter(model, from);
    if (painted !== null && from !== null) metrics.push({ name: `trace.${name}.firstPresentedAfterMark`, value: Math.round((painted - from) / 100) / 10, unit: 'ms', domain: 'Runtime', scope });
  }
  const top = analysis.flame ? [flameFrames([toFrame(analysis.flame)])[0]] : [];
  const report = profilingReport(options.target, {
    metrics,
    flamegraph: flameFrames(analysis.longTaskFlames),
    profiles: [profileCapture(name, analysis, { platform: options.platform ?? 'chrome', startedAt: new Date().toISOString(), outputPath: options.tracePath ?? null, top: trimDepth(top, 7), scope })],
    toolRuns: [{ name, tool: 'ChromeDevTools', status: 'Passed', startedAt: new Date().toISOString(), durationMs: analysis.windowMs, reportPath: options.tracePath ?? null, scope }],
  });
  return { report, analysis, model };
}

function toFrame(node, start = 0) {
  let offset = start;
  const children = [];
  for (const child of node.children) {
    children.push(toFrame(child, offset));
    offset += child.totalMs;
  }
  return { name: node.location ? `${node.name} ${node.location}` : node.name, startMs: Math.round(start * 10) / 10, durationMs: Math.round(node.totalMs * 10) / 10, children };
}

function trimDepth(frames, depth) {
  if (depth <= 0) return [];
  return frames.map(frame => ({ ...frame, children: trimDepth(frame.children ?? [], depth - 1) }));
}

export function writeFlames(directory, label, analysis, model, symbolicate) {
  mkdirSync(directory, { recursive: true });
  const files = [];
  if (analysis.flame) {
    const svg = join(directory, `${label}.main.svg`);
    writeFileSync(svg, flameSvg(analysis.flame, { title: `${label} · main thread CPU`, subtitle: `${analysis.flame.totalMs.toFixed(1)} ms of JS and runtime samples on CrRendererMain; idle removed` }));
    writeFileSync(join(directory, `${label}.main.folded.txt`), `${collapsedStacks(analysis.flame).join('\n')}\n`);
    files.push(svg);
  }
  for (const profile of threadProfiles(model)) {
    if (profile.pid !== model.renderer) continue;
    const kind = profile.thread === 'CrRendererMain' ? 'main' : profile.thread === 'DedicatedWorker thread' ? 'worker' : null;
    if (!kind) continue;
    const path = join(directory, `${label}.${kind}.cpuprofile`);
    writeFileSync(path, JSON.stringify(cpuProfileJson(profile)));
    files.push(path);
  }
  writeFileSync(join(directory, `${label}.longtasks.json`), `${JSON.stringify(analysis.longTaskFlames, null, 1)}\n`);
  return files;
}

function summary(analysis) {
  const lines = [];
  lines.push(`window ${analysis.windowMs} ms · main busy ${analysis.main.busyMs} ms ${JSON.stringify(analysis.main.categories)}`);
  lines.push(`frames ${JSON.stringify(analysis.frames)}`);
  lines.push(`raster ${JSON.stringify(analysis.raster)}`);
  lines.push(`style ${JSON.stringify(analysis.style)} layout ${JSON.stringify(analysis.layout)}`);
  lines.push(`long tasks ${analysis.longTasks.length}: ${analysis.longTasks.map(task => `${task.durationMs}ms@${task.startMs}`).join(', ')}`);
  if (analysis.interactions.length) lines.push(`interactions ${analysis.interactions.map(item => `${item.type} ${item.durationMs}ms`).join(', ')}`);
  lines.push('top JS self time (main):');
  for (const item of analysis.js.slice(0, 15)) lines.push(`  ${String(item.selfMs).padStart(7)} ms self ${String(item.totalMs).padStart(7)} ms total  ${item.name} ${item.location}`);
  return lines.join('\n');
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const symbolicate = mapLoader(args.maps);
  const trace = readTrace(args.trace);
  const label = args.name ?? basename(args.trace).replace(/\.json(\.gz)?$/, '');
  const { report, analysis, model } = reportFromTrace(trace, { ...args, name: label, symbolicate, tracePath: resolve(args.trace) });
  if (args.flameDir) writeFlames(args.flameDir, label, analysis, model, symbolicate);
  const payload = `${JSON.stringify(report, null, 2)}\n`;
  if (args.out) {
    mkdirSync(dirname(resolve(args.out)), { recursive: true });
    writeFileSync(args.out, payload);
    console.error(`wrote ${args.out}`);
  } else process.stdout.write(payload);
  console.error(summary(analysis));
  const violations = budgetViolations(report);
  if (violations.length) console.error(`budgets: ${violations.length} violation(s)`);
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(new URL(import.meta.url).pathname)) await main();
