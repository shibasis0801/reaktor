#!/usr/bin/env node
// Standard Lighthouse runner for reaktor-performance.
//   config -> run (median of N) -> map LHR -> ReaktorPerformanceReport -> budgets -> assert
// The LHR->report mapping and the budget set are imported from the single source in
// ../ts/src/index.ts (Node strips the types), so this runner never re-states the contract.
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import {
  budgetViolations,
  lighthouseReport,
  recommendedLighthouseBudgets,
} from '../ts/src/index.ts';

function usage(message) {
  if (message) console.error(`error: ${message}`);
  console.error(
    [
      'usage: node tools/lighthouse-report.mjs --target <name> --url <url> [options]',
      '       node tools/lighthouse-report.mjs --self-test <fixture-lhr.json> [--assert]',
      '',
      '  --target <name>         report target label (required for a live run)',
      '  --url <url>             page to audit (required for a live run)',
      '  --runs <n>              Lighthouse runs; the median is reported (default 3)',
      '  --form-factor <ff>      mobile | desktop (default mobile); --preset is an alias',
      '  --out <report.json>     write the Reaktor report JSON (default: stdout)',
      '  --assert                exit non-zero if any Error-severity budget is breached',
      '  --self-test <lhr.json>  map a saved LHR fixture instead of launching Chrome',
    ].join('\n'),
  );
  process.exit(2);
}

function parseArgs(argv) {
  const args = { target: null, url: null, runs: 3, formFactor: 'mobile', out: null, assert: false, selfTest: null };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    switch (arg) {
      case '--target': args.target = argv[++i]; break;
      case '--url': args.url = argv[++i]; break;
      case '--runs': args.runs = Number(argv[++i]); break;
      case '--form-factor':
      case '--preset': args.formFactor = argv[++i]; break;
      case '--out': args.out = argv[++i]; break;
      case '--assert': args.assert = true; break;
      case '--self-test': args.selfTest = argv[++i]; break;
      case '--help': case '-h': usage(); break;
      default: usage(`unknown argument: ${arg}`);
    }
  }
  return args;
}

// Median by performance score, so we don't depend on Lighthouse internal module paths.
function medianLhr(lhrs) {
  if (lhrs.length === 1) return lhrs[0];
  const sorted = [...lhrs].sort(
    (a, b) => (a.categories?.performance?.score ?? 0) - (b.categories?.performance?.score ?? 0),
  );
  return sorted[Math.floor(sorted.length / 2)];
}

function emit(report, args) {
  const payload = `${JSON.stringify(report, null, 2)}\n`;
  if (args.out) {
    mkdirSync(dirname(resolve(args.out)), { recursive: true });
    writeFileSync(args.out, payload);
    console.error(`wrote ${args.out}`);
  } else {
    process.stdout.write(payload);
  }
  const violations = budgetViolations(report);
  if (violations.length === 0) {
    console.error('lighthouse budgets: all within budget');
  } else {
    console.error(`lighthouse budgets: ${violations.length} violation(s)`);
    for (const violation of violations) console.error(`  - ${violation.message}`);
  }
  const blocking = violations.filter((violation) => violation.severity === 'Error');
  if (args.assert && blocking.length > 0) process.exit(1);
}

async function runLive(args) {
  if (!args.target || !args.url) usage('--target and --url are required for a live run');
  let lighthouse;
  let chromeLauncher;
  let desktopConfig;
  try {
    ({ default: lighthouse } = await import('lighthouse'));
    chromeLauncher = await import('chrome-launcher');
    if (args.formFactor === 'desktop') {
      ({ default: desktopConfig } = await import('lighthouse/core/config/desktop-config.js'));
    }
  } catch {
    console.error('Lighthouse is not installed. Run: (cd reaktor-performance/tools && npm install)');
    process.exit(3);
  }

  const chrome = await chromeLauncher.launch({
    chromeFlags: ['--headless=new', '--no-sandbox', '--disable-gpu'],
  });
  try {
    const runs = Math.max(1, args.runs | 0);
    const startedAt = new Date().toISOString();
    const begin = Date.now();
    const lhrs = [];
    for (let i = 0; i < runs; i += 1) {
      const result = await lighthouse(
        args.url,
        { port: chrome.port, output: 'json', logLevel: 'error' },
        desktopConfig,
      );
      lhrs.push(result.lhr);
    }
    const report = lighthouseReport(args.target, medianLhr(lhrs), {
      url: args.url,
      reportPath: args.out ?? null,
      startedAt,
      durationMs: Date.now() - begin,
      budgets: recommendedLighthouseBudgets(),
    });
    emit(report, args);
  } finally {
    await chrome.kill();
  }
}

function runSelfTest(args) {
  const lhr = JSON.parse(readFileSync(resolve(args.selfTest), 'utf8'));
  const report = lighthouseReport(args.target ?? 'self-test', lhr, {
    url: args.url ?? undefined,
    budgets: recommendedLighthouseBudgets(),
  });
  emit(report, args);
}

const args = parseArgs(process.argv.slice(2));
if (args.selfTest) runSelfTest(args);
else await runLive(args);
