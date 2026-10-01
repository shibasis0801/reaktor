#!/usr/bin/env node
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { brotliCompressSync, constants, gzipSync } from 'node:zlib';
import { dynamicImports, htmlEntry, packageShares, sourceBytes, staticImports } from '../ts/src/bundle.ts';
import { budgetViolations } from '../ts/src/index.ts';

function usage(message) {
  if (message) console.error(`error: ${message}`);
  console.error([
    'usage: node tools/bundle-report.mjs --dist <dir> --target <name> [options]',
    '',
    'Sizes every chunk of a Vite or Rollup web build, splits what the first page load fetches from what loads',
    'later, and breaks the first-load JavaScript down by package with the build\'s source maps.',
    '',
    '  --dist <dir>             build output that holds index.html and assets/',
    '  --html <file>            entry document inside --dist (default index.html)',
    '  --target <name>          report target label',
    '  --budget <metric=limit>  add a Max budget, repeatable (e.g. bundle.initial.js.gzip=250000)',
    '  --top <n>                packages listed per chunk (default 20)',
    '  --out <report.json>      write the ReaktorPerformanceReport (default: stdout)',
  ].join('\n'));
  process.exit(2);
}

function parseArgs(argv) {
  const args = { dist: null, html: 'index.html', target: null, budgets: [], top: 20, out: null };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--dist') args.dist = argv[++index];
    else if (arg === '--html') args.html = argv[++index];
    else if (arg === '--target') args.target = argv[++index];
    else if (arg === '--budget') {
      const [metric, limit] = String(argv[++index]).split('=');
      args.budgets.push({ metric, limit: Number(limit), unit: 'bytes', direction: 'Max', severity: 'Error' });
    } else if (arg === '--top') args.top = Number(argv[++index]);
    else if (arg === '--out') args.out = argv[++index];
    else if (arg === '--help' || arg === '-h') usage();
    else usage(`unknown argument: ${arg}`);
  }
  if (!args.dist || !args.target) usage('--dist and --target are required');
  return args;
}

const sizes = buffer => ({
  bytes: buffer.byteLength,
  gzip: gzipSync(buffer, { level: 9 }).byteLength,
  brotli: brotliCompressSync(buffer, { params: { [constants.BROTLI_PARAM_QUALITY]: 11 } }).byteLength,
});

function walk(root, prefix = '') {
  const files = [];
  for (const entry of readdirSync(join(root, prefix), { withFileTypes: true })) {
    const path = prefix ? `${prefix}/${entry.name}` : entry.name;
    if (entry.isDirectory()) files.push(...walk(root, path));
    else files.push(path);
  }
  return files;
}

function resolveChunk(from, specifier) {
  if (!specifier.startsWith('.') && !specifier.startsWith('/')) return null;
  const path = specifier.startsWith('/') ? specifier.slice(1) : join(dirname(from), specifier).replace(/\\/g, '/');
  return path.replace(/^\.\//, '');
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const dist = resolve(args.dist);
  const html = readFileSync(join(dist, args.html), 'utf8');
  const entry = htmlEntry(html);
  const files = walk(dist).filter(path => /\.(js|mjs|css)$/.test(path));
  const code = new Map(files.map(path => [path, readFileSync(join(dist, path))]));
  const clean = url => url.replace(/^\//, '').split('?')[0];
  const initial = new Set();
  const pending = [...entry.scripts.map(clean), ...entry.preloads.map(clean)];
  while (pending.length > 0) {
    const path = pending.pop();
    if (initial.has(path) || !code.has(path)) continue;
    initial.add(path);
    for (const specifier of staticImports(code.get(path).toString('utf8'))) {
      const target = resolveChunk(path, specifier);
      if (target && code.has(target)) pending.push(target);
    }
  }
  const lazyFrom = new Map();
  const workers = new Set();
  for (const [path, buffer] of code) {
    const text = buffer.toString('utf8');
    for (const specifier of dynamicImports(text)) {
      const target = resolveChunk(path, specifier);
      if (target) lazyFrom.set(target, [...(lazyFrom.get(target) ?? []), path]);
    }
    for (const match of text.matchAll(/["'`]((?:\/?assets\/)?[\w.-]+\.worker[\w.-]*\.js)["'`]/g)) workers.add(clean(match[1].includes('/') ? match[1] : `${dirname(path)}/${match[1]}`));
  }
  const artifacts = [];
  const totals = { js: { bytes: 0, gzip: 0, brotli: 0 }, css: { bytes: 0, gzip: 0, brotli: 0 }, lazyJs: { bytes: 0, gzip: 0, brotli: 0 }, worker: { bytes: 0, gzip: 0, brotli: 0 } };
  const composition = {};
  for (const [path, buffer] of code) {
    const size = sizes(buffer);
    const css = path.endsWith('.css');
    const isInitial = initial.has(path) || entry.styles.map(clean).includes(path);
    const worker = workers.has(path) || /\.worker/.test(basename(path));
    const type = worker ? 'web-worker' : css ? (isInitial ? 'web-css-initial' : 'web-css-lazy') : isInitial ? 'web-js-initial' : 'web-js-lazy';
    const bucket = worker ? totals.worker : css ? (isInitial ? totals.css : null) : isInitial ? totals.js : totals.lazyJs;
    if (bucket) { bucket.bytes += size.bytes; bucket.gzip += size.gzip; bucket.brotli += size.brotli; }
    artifacts.push({
      name: path, type, bytes: size.bytes, compressedBytes: size.gzip, path: join(dist, path),
      scope: { artifactPath: join(dist, path), attributes: { brotli: String(size.brotli), loadedBy: isInitial ? 'entry' : (lazyFrom.get(path) ?? []).join(',') || (worker ? 'new Worker' : 'unknown') } },
    });
    const mapPath = join(dist, `${path}.map`);
    if (!css && existsSync(mapPath) && (isInitial || worker || size.bytes > 100_000)) {
      const shares = packageShares(sourceBytes(buffer.toString('utf8'), JSON.parse(readFileSync(mapPath, 'utf8'))));
      composition[path] = shares.slice(0, args.top);
    }
  }
  artifacts.sort((a, b) => b.bytes - a.bytes);
  const metric = (name, value, unit = 'bytes') => ({ name, value, unit, domain: 'Build', scope: { module: args.target } });
  const metrics = [
    metric('bundle.initial.js.raw', totals.js.bytes), metric('bundle.initial.js.gzip', totals.js.gzip), metric('bundle.initial.js.brotli', totals.js.brotli),
    metric('bundle.initial.css.raw', totals.css.bytes), metric('bundle.initial.css.gzip', totals.css.gzip),
    metric('bundle.initial.chunks', initial.size, 'count'),
    metric('bundle.lazy.js.raw', totals.lazyJs.bytes), metric('bundle.lazy.js.gzip', totals.lazyJs.gzip),
    metric('bundle.worker.raw', totals.worker.bytes), metric('bundle.worker.gzip', totals.worker.gzip),
  ];
  const report = {
    target: args.target,
    generatedAt: new Date().toISOString(),
    metrics,
    buildArtifacts: artifacts,
    appVitals: { initialChunks: [...initial], entry, lazyFrom: Object.fromEntries(lazyFrom), composition },
    toolRuns: [{ name: `bundle ${args.target}`, tool: 'bundle-report', status: 'Passed', startedAt: new Date().toISOString(), durationMs: 0, reportPath: args.out ?? null, scope: { artifactPath: dist } }],
    budgets: args.budgets,
  };
  const payload = `${JSON.stringify(report, null, 2)}\n`;
  if (args.out) {
    mkdirSync(dirname(resolve(args.out)), { recursive: true });
    writeFileSync(args.out, payload);
    console.error(`wrote ${args.out}`);
  } else process.stdout.write(payload);
  const kb = value => `${(value / 1024).toFixed(1)} KiB`;
  console.error(`initial JS ${kb(totals.js.bytes)} raw · ${kb(totals.js.gzip)} gzip · ${kb(totals.js.brotli)} br in ${initial.size} chunks; initial CSS ${kb(totals.css.bytes)} raw · ${kb(totals.css.gzip)} gzip`);
  console.error(`lazy JS ${kb(totals.lazyJs.bytes)} raw · ${kb(totals.lazyJs.gzip)} gzip; workers ${kb(totals.worker.bytes)} raw · ${kb(totals.worker.gzip)} gzip`);
  for (const path of initial) {
    console.error(`  initial ${path}`);
    for (const item of (composition[path] ?? []).slice(0, 12)) console.error(`    ${kb(item.bytes).padStart(12)}  ${(item.share * 100).toFixed(1).padStart(5)}%  ${item.name}`);
  }
  const violations = budgetViolations(report);
  for (const violation of violations) console.error(`budget: ${violation.message}`);
  if (violations.some(violation => violation.severity === 'Error')) process.exitCode = 1;
}

main();
