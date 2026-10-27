import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

test('the sample Lighthouse result maps to a report within the recommended budgets', () => {
  const runner = fileURLToPath(new URL('./lighthouse-report.mjs', import.meta.url));
  const sample = fileURLToPath(new URL('./fixtures/lighthouse-sample.json', import.meta.url));
  const report = JSON.parse(execFileSync(process.execPath, [runner, '--self-test', sample, '--assert'],
    { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }));
  assert.equal(report.target, 'self-test');
  assert.ok(report.metrics.length > 0);
  assert.ok(report.budgets.length > 0);
});
