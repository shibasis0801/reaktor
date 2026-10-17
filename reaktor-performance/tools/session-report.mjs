#!/usr/bin/env node
import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { compareMarkdown, sessionReport } from '../ts/src/session.ts';
import { budgetViolations } from '../ts/src/index.ts';

function usage(message) {
  if (message) console.error(`error: ${message}`);
  console.error([
    'usage: node tools/session-report.mjs --runs <dir> --target <name> --label <label> [options]',
    '',
    'Folds the per-run JSON files a browser harness wrote (opens, gestures, React commits, DOM mutations,',
    'flicker strips, layer trees) into ONE ReaktorPerformanceReport with medians and spreads.',
    '',
    '  --runs <dir>             directory searched recursively for run JSON files',
    '  --target <name>          report target',
    '  --label <label>          label of this run set (before, after, ...)',
    '  --traces <dir>           directory searched for saved DevTools traces (*.json, *.json.gz)',
    '  --budgets <file.json>    ReaktorPerformanceBudget[] to attach and check',
    '  --compare <report.json>  an earlier report; writes a markdown before/after table next to --out',
    '  --rows <file.json>       metric names (or {metric,label}) for the comparison table',
    '  --out <report.json>      output path (default: stdout)',
  ].join('\n'));
  process.exit(2);
}

function parseArgs(argv) {
  const args = { runs: null, target: null, label: null, traces: null, budgets: null, compare: null, rows: null, out: null };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    const value = () => argv[++index];
    if (arg === '--runs') args.runs = value();
    else if (arg === '--target') args.target = value();
    else if (arg === '--label') args.label = value();
    else if (arg === '--traces') args.traces = value();
    else if (arg === '--budgets') args.budgets = value();
    else if (arg === '--compare') args.compare = value();
    else if (arg === '--rows') args.rows = value();
    else if (arg === '--out') args.out = value();
    else if (arg === '--help' || arg === '-h') usage();
    else usage(`unknown argument: ${arg}`);
  }
  if (!args.runs || !args.target || !args.label) usage('--runs, --target and --label are required');
  return args;
}

function files(root, pattern) {
  if (!existsSync(root)) return [];
  const found = [];
  for (const name of readdirSync(root)) {
    const path = join(root, name);
    if (statSync(path).isDirectory()) found.push(...files(path, pattern));
    else if (pattern.test(name)) found.push(path);
  }
  return found.sort();
}

const args = parseArgs(process.argv.slice(2));
const runs = files(resolve(args.runs), /\.json$/).map(path => JSON.parse(readFileSync(path, 'utf8'))).filter(run => run && Array.isArray(run.steps) && run.viewport);
const traces = args.traces ? files(resolve(args.traces), /\.json(\.gz)?$/) : [];
const budgets = args.budgets ? JSON.parse(readFileSync(args.budgets, 'utf8')) : [];
const report = sessionReport(runs, { target: args.target, label: args.label, traces, budgets });
const payload = `${JSON.stringify(report, null, 1)}\n`;
if (args.out) {
  mkdirSync(dirname(resolve(args.out)), { recursive: true });
  writeFileSync(args.out, payload);
  console.error(`wrote ${args.out} from ${runs.length} runs (${Object.keys(report.spreads).length} metrics, load ${report.load.median})`);
} else process.stdout.write(payload);
if (args.compare) {
  const before = JSON.parse(readFileSync(args.compare, 'utf8'));
  const rows = args.rows ? JSON.parse(readFileSync(args.rows, 'utf8')) : Object.keys(report.spreads);
  const table = compareMarkdown(before, report, rows);
  const path = args.out ? args.out.replace(/\.json$/, '') + `.vs-${before.label}.md` : null;
  if (path) { writeFileSync(path, `${table}\n`); console.error(`wrote ${path}`); } else console.log(table);
}
const violations = budgetViolations(report);
for (const violation of violations) console.error(`budget: ${violation.message}`);
if (violations.some(violation => violation.severity === 'Error')) process.exitCode = 1;
