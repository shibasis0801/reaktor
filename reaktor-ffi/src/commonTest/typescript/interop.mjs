import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { runInNewContext } from 'node:vm';

test('the Vite bundle supports typed imports and exports without dropping wire values', () => {
  const calls = [];
  const context = { ReaktorNative: { invoke(module, operation, payload) {
    calls.push({ module, operation, payload });
    return payload;
  } } };
  runInNewContext(readFileSync(new URL('../../../dist/reaktor-ffi.js', import.meta.url), 'utf8'), context);
  const { exportModule, native } = context.ReaktorFfi;
  const echo = native.module('host').function('echo');
  exportModule('roundtrip', { echo, broken: () => Promise.resolve(1), invalid: () => ({ missing: undefined }) });
  const request = '{"text":"unicode λ 🎉","nested":{"count":42},"nullable":null}';
  assert.equal(context.ReaktorTypeScript.invoke('roundtrip', 'echo', request), request);
  assert.deepEqual(calls, [{ module: 'host', operation: 'echo', payload: request }]);
  assert.throws(() => context.ReaktorTypeScript.invoke('roundtrip', 'toString', '{}'), /No TypeScript operation/);
  assert.throws(() => context.ReaktorTypeScript.invoke('roundtrip', 'broken', '{}'), /Promise/);
  assert.throws(() => context.ReaktorTypeScript.invoke('roundtrip', 'invalid', '{}'), /JSON data/);
  assert.throws(() => context.ReaktorTypeScript.invoke('roundtrip', 'echo', '9007199254740993'), /integers safe/);
  assert.throws(() => exportModule('roundtrip', {}), /unique/);
});
