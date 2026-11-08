import { test } from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { installProbe } from '../src/jsMain/typescript/probes.ts';

function runtime() {
  let next = 0;
  const pending = new Map();
  const scope = vm.createContext({
    performance: { now: () => 0 },
    requestAnimationFrame(callback) { const id = ++next; pending.set(id, callback); return id; },
    cancelAnimationFrame(id) { pending.delete(id); },
  });
  vm.runInContext(`(${installProbe})();`, scope);
  return {
    probe: scope.__reaktorProbe,
    pending,
    frame(time) {
      const callbacks = [...pending.values()];
      pending.clear();
      callbacks.forEach(callback => callback(time));
    },
  };
}

test('rapidly restarted sampling records each display frame once and retires pending work', () => {
  const { probe, pending, frame } = runtime();
  for (let repeat = 0; repeat < 5; repeat++) {
    probe.startFrames();
    probe.stopFrames();
    assert.equal(pending.size, 0, 'stopped probes leave no scheduled callback');
  }
  probe.startFrames();
  assert.equal(pending.size, 1);
  frame(16);
  frame(32);
  assert.deepEqual(Array.from(probe.stopFrames()), [16, 32]);
  assert.equal(pending.size, 0);
  frame(48);
  assert.deepEqual(Array.from(probe.snapshot(0).frames), [16, 32]);
  probe.startFrames();
  frame(64);
  assert.deepEqual(Array.from(probe.stopFrames()), [64], 'a new window has no samples from earlier windows');
});

test('starting an active sampling window preserves a single animation-frame loop', () => {
  const { probe, pending, frame } = runtime();
  probe.startFrames();
  frame(16);
  probe.startFrames();
  assert.equal(pending.size, 1);
  frame(32);
  assert.deepEqual(Array.from(probe.stopFrames()), [32]);
  assert.equal(pending.size, 0);
});
