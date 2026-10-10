#!/usr/bin/env node
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { budgetViolations, lighthouseReport, recommendedLighthouseBudgets } from '../ts/src/index.ts';

function usage(message) {
  if (message) console.error(`error: ${message}`);
  console.error([
    'usage: node tools/lighthouse-flow.mjs --target <name> --url <url> [options]',
    '',
    'A Lighthouse navigation run inside a user flow, so a signed-in page can be audited:',
    'local storage entries are written before any page script runs, after Lighthouse resets storage.',
    '',
    '  --target <name>         report target label',
    '  --url <url>             page to audit',
    '  --storage <file.json>   {"key": "value"} entries written to localStorage on every new document',
    '  --runs <n>              runs; the median by performance score is reported (default 3)',
    '  --preset <ff>           mobile | desktop (default desktop)',
    '  --lhr-dir <dir>         also save each Lighthouse result JSON there',
    '  --out <report.json>     write the Reaktor report JSON (default: stdout)',
    '  --assert                exit non-zero if any Error-severity budget is breached',
  ].join('\n'));
  process.exit(2);
}

function parseArgs(argv) {
  const args = { target: null, url: null, storage: null, runs: 3, preset: 'desktop', lhrDir: null, out: null, assert: false };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--target') args.target = argv[++index];
    else if (arg === '--url') args.url = argv[++index];
    else if (arg === '--storage') args.storage = argv[++index];
    else if (arg === '--runs') args.runs = Number(argv[++index]);
    else if (arg === '--preset' || arg === '--form-factor') args.preset = argv[++index];
    else if (arg === '--lhr-dir') args.lhrDir = argv[++index];
    else if (arg === '--out') args.out = argv[++index];
    else if (arg === '--assert') args.assert = true;
    else if (arg === '--help' || arg === '-h') usage();
    else usage(`unknown argument: ${arg}`);
  }
  if (!args.target || !args.url) usage('--target and --url are required');
  return args;
}

function medianLhr(lhrs) {
  const sorted = [...lhrs].sort((a, b) => (a.categories?.performance?.score ?? 0) - (b.categories?.performance?.score ?? 0));
  return sorted[Math.floor(sorted.length / 2)];
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  let lighthouse;
  let chromeLauncher;
  let puppeteer;
  try {
    lighthouse = await import('lighthouse');
    chromeLauncher = await import('chrome-launcher');
    ({ default: puppeteer } = await import('puppeteer-core'));
  } catch {
    console.error('Lighthouse is not installed. Run: ./gradlew pnpmInstall from the Reaktor root');
    process.exit(3);
  }
  const config = args.preset === 'desktop' ? (await import('lighthouse/core/config/desktop-config.js')).default : undefined;
  const storage = args.storage ? JSON.parse(readFileSync(args.storage, 'utf8')) : null;
  const startedAt = new Date().toISOString();
  const begin = Date.now();
  const lhrs = [];
  for (let run = 0; run < Math.max(1, args.runs | 0); run += 1) {
    const chrome = await chromeLauncher.launch({ chromeFlags: ['--headless=new', '--no-sandbox', '--disable-gpu'] });
    try {
      const browser = await puppeteer.connect({ browserURL: `http://127.0.0.1:${chrome.port}`, defaultViewport: null });
      const page = await browser.newPage();
      if (storage) {
        await page.evaluateOnNewDocument(entries => {
          for (const [key, value] of Object.entries(entries)) if (typeof value === 'string') localStorage.setItem(key, value);
        }, storage);
      }
      const flow = await lighthouse.startFlow(page, { config, name: args.target });
      await flow.navigate(args.url, { name: `${args.target} navigation` });
      const result = await flow.createFlowResult();
      const lhr = result.steps[0].lhr;
      lhrs.push(lhr);
      if (args.lhrDir) {
        mkdirSync(args.lhrDir, { recursive: true });
        writeFileSync(join(args.lhrDir, `${args.target}-run${run}.lhr.json`), JSON.stringify(lhr));
      }
      await browser.disconnect();
    } finally {
      await chrome.kill();
    }
  }
  const report = lighthouseReport(args.target, medianLhr(lhrs), {
    url: args.url,
    reportPath: args.out ?? null,
    startedAt,
    durationMs: Date.now() - begin,
    budgets: recommendedLighthouseBudgets(),
  });
  const payload = `${JSON.stringify(report, null, 2)}\n`;
  if (args.out) {
    mkdirSync(dirname(resolve(args.out)), { recursive: true });
    writeFileSync(args.out, payload);
    console.error(`wrote ${args.out}`);
  } else process.stdout.write(payload);
  const scores = lhrs.map(lhr => Math.round((lhr.categories?.performance?.score ?? 0) * 100));
  console.error(`performance scores per run: ${scores.join(', ')}`);
  const violations = budgetViolations(report);
  if (violations.length === 0) console.error('lighthouse budgets: all within budget');
  else {
    console.error(`lighthouse budgets: ${violations.length} violation(s)`);
    for (const violation of violations) console.error(`  - ${violation.message}`);
  }
  if (args.assert && violations.some(violation => violation.severity === 'Error')) process.exit(1);
}

await main();
