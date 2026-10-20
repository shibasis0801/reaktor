import { test } from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { installWorkerWebProbe } from './worker-web.mjs';

function runtime(options) {
  let time = 0;
  class Worker {
    listeners = {};
    constructor(url, options) { this.url = url; this.options = options; }
    addEventListener(type, listener) { (this.listeners[type] ??= []).push(listener); }
    emit(type, data) { this.listeners[type]?.forEach(listener => listener({ data })); }
    postMessage(data) { time += 2; if (data.fail) throw new Error('clone failed'); }
    terminate() { this.closed = true; }
  }
  const scope = vm.createContext({ Worker, performance: { now: () => time } });
  vm.runInContext(`(${installWorkerWebProbe})(${JSON.stringify(options)});`, scope);
  return { scope, advance: value => { time += value; }, worker: () => new scope.Worker('elk.js', { name: 'blueprint-elk' }) };
}

test('worker probe correlates out-of-order replies and separates wall, post and compute time', () => {
  const { scope, advance, worker } = runtime();
  const one = worker(), two = worker();
  one.postMessage({ id: 1, secret: 'not retained' }); two.postMessage({ id: 1 });
  advance(10); two.emit('message', { id: 1, ms: 4 }); one.emit('message', { ready: true });
  advance(5); one.emit('message', { id: 1, ms: 7 });
  const result = scope.__reaktorWorkers.snapshot();
  assert.equal(result.pending, 0);
  assert.equal(result.jobs[0].worker, 2); assert.equal(result.jobs[0].elapsedMs, 12);
  assert.equal(result.jobs[1].elapsedMs, 19); assert.equal(result.jobs[1].postCpuMs, 2);
  assert.equal(result.jobs[1].computeMs, 7); assert.ok(!JSON.stringify(result).includes('secret'));
  assert.equal(result.active, 2);
});

test('worker probe bounds retention and preserves failures and termination', () => {
  const { scope, worker } = runtime({ maxSamples: 2 });
  const one = worker();
  assert.throws(() => one.postMessage({ id: 0, fail: true }), /clone failed/);
  scope.__reaktorWorkers.reset();
  one.postMessage({ id: 1 }); one.postMessage({ id: 2 }); one.postMessage({ id: 3 });
  one.terminate(); one.terminate();
  let result = scope.__reaktorWorkers.snapshot();
  assert.equal(result.jobs.length, 2); assert.ok(result.jobs.every(job => job.canceled));
  assert.equal(result.dropped, 1); assert.equal(result.active, 0); assert.equal(result.terminated, 1);
  scope.__reaktorWorkers.reset();
  const two = worker(); two.postMessage({ id: 1 }); two.emit('error');
  result = scope.__reaktorWorkers.snapshot(); assert.equal(result.jobs[0].failed, true);
  const unrelated = new scope.Worker('db.js', { name: 'sqlite' }); unrelated.postMessage({ id: 2 });
  assert.equal(scope.__reaktorWorkers.snapshot().created, 2);
});
