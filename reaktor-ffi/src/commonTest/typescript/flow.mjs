import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { runInNewContext } from 'node:vm';

test('Hermes bundle installs Flow and preserves queued delivery', () => {
  const printed = [];
  const context = { print: value => printed.push(value) };
  runInNewContext(readFileSync(new URL('../../../dist/reaktor-ffi.js', import.meta.url), 'utf8'), context);
  assert.deepEqual(printed, []);
  const flow = new context.Flow();
  const values = [];
  flow.emit(42);
  const observer = flow.collect(value => values.push(value));
  assert.deepEqual(values, [42]);
  assert.throws(() => flow.collect(() => {}), /multiple observers/);
  flow.stopCollecting(observer);
  flow.emit(43);
  flow.collect(value => values.push(value));
  assert.deepEqual(values, [42,43]);
});

test('reentrant delivery stays FIFO, and cancellation retains the remaining queue', () => {
  const context = {};
  runInNewContext(readFileSync(new URL('../../../dist/reaktor-ffi.js', import.meta.url), 'utf8'), context);
  const flow = new context.Flow();
  const values = [];
  flow.emit(1); flow.emit(2);
  const first = value => { values.push(value); flow.emit(3); flow.stopCollecting(first); };
  flow.collect(first);
  flow.collect(value => values.push(value));
  flow.stopCollecting(first); // A stale subscription must not detach the current collector.
  flow.emit(4);
  assert.deepEqual(values, [1, 2, 3, 4]);
});

test('large buffered streams do not recurse during reentrant emission', () => {
  const context = {};
  runInNewContext(readFileSync(new URL('../../../dist/reaktor-ffi.js', import.meta.url), 'utf8'), context);
  const flow = new context.Flow();
  let count = 0;
  flow.collect(value => { count++; if (value < 20000) flow.emit(value + 1); });
  flow.emit(0);
  assert.equal(count, 20001);
});
