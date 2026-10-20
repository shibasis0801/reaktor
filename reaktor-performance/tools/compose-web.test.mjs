import { test } from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';
import { installComposeWebProbe, instrumentComposeWeb, composeWebSummary } from './compose-web.mjs';

const fixture = `class CanvasRenderer {}
class SkiaLayer$attachTo$1 extends CanvasRenderer {
  constructor($htmlCanvas, this$0, $box) {
    super($box);
  }
}
function CanvasRenderer$needRedraw$lambda(this$0) {
  return (timestamp) => {
    this$0.draw(timestamp);
    return Unit_instance;
  };
}`;

test('Skiko instrumentation measures successful and throwing renderer work and preserves exceptions', () => {
  const records = [];
  const scope = { Unit_instance: {}, globalThis: { __reaktorCompose: { begin: () => 'token', mark() {}, end: (...args) => records.push(args) } } };
  vm.createContext(scope);
  vm.runInContext(instrumentComposeWeb(fixture) + '\nthis.frame = CanvasRenderer$needRedraw$lambda;', scope);
  scope.frame({ __surfaceDisposed: true, draw() { throw new Error('disposed renderer drew'); } })(0);
  scope.frame({ draw() {} })(0);
  const failure = new Error('Skia failure');
  assert.throws(() => scope.frame({ draw() { throw failure; } })(0), error => error === failure);
  assert.deepEqual(records, [['token', false], ['token', true]]);
  assert.equal(instrumentComposeWeb('ordinary JS'), 'ordinary JS');
  assert.throws(() => instrumentComposeWeb('class CanvasRenderer {}'), /boundary changed/);
});

test('canvas inputs correlate only to their renderer, with bounded samples and explicit render completion', () => {
  let now = 0;
  class Canvas { width = 800; height = 600; clientWidth = 400; clientHeight = 300; }
  const handlers = {};
  const scope = { performance: { now: () => now }, document: { addEventListener: (name, callback) => { handlers[name] = callback; } }, HTMLCanvasElement: Canvas, devicePixelRatio: 2 };
  vm.createContext(scope);
  vm.runInContext(`(${installComposeWebProbe.toString()})({ maxSamples: 2 });`, scope);
  const probe = scope.__reaktorCompose, a = new Canvas(), b = new Canvas();
  const input = (type, canvas, timeStamp = now) => handlers[type]({ type, timeStamp, composedPath: () => [canvas] });
  now = 4; input('wheel', a, 1);
  now = 5; let token = probe.begin({}, b); now = 10; probe.end(token, false);
  assert.equal(probe.snapshot().inputs.length, 0);
  token = probe.begin({}, a); now = 14; probe.end(token, false);
  const result = composeWebSummary(probe.snapshot(), 4);
  assert.equal(result.inputToRender.p95Ms, 13);
  assert.equal(result.inputQueue.p95Ms, 9);
  assert.equal(result.inputDelivery.p95Ms, 3);
  assert.equal(result.inputRenderQueue.p95Ms, 6);
  assert.equal(result.renderCpu.overBudget, 1);
  assert.equal(result.canvases[0].dpr, 2);
  token = probe.begin({}, a); probe.end(token, true);
  assert.equal(probe.snapshot().droppedSamples, 1);
  handlers.webglcontextlost(); assert.equal(probe.snapshot().lostContexts, 1);
  probe.reset(); assert.equal(probe.snapshot().frames.length, 0);
  assert.equal(composeWebSummary(probe.snapshot()).renderCpu.p95Ms, null);
});

test('render phases partition CPU time; recomposer work outside a render is bounded and resettable', () => {
  let now = 0;
  const scope = { performance: { now: () => now }, document: { addEventListener() {} } };
  vm.createContext(scope);
  vm.runInContext(`(${installComposeWebProbe.toString()})({ maxSamples: 2 });`, scope);
  const probe = scope.__reaktorCompose;
  const token = probe.begin({}, null);
  now = 1; probe.mark(token, 'sceneAt');
  now = 4; probe.mark(token, 'submitAt');
  now = 9; probe.end(token, false);
  const phase = probe.beginPhase('recomposerTasks'); now = 11; probe.endPhase(phase, false);
  probe.endPhase(probe.beginPhase('recomposerTasks'), true);
  probe.endPhase(probe.beginPhase('recomposerTasks'), false);
  const summary = composeWebSummary(probe.snapshot());
  assert.equal(summary.renderPhases.prepareMs.p95Ms, 1);
  assert.equal(summary.renderPhases.sceneMs.p95Ms, 3);
  assert.equal(summary.renderPhases.submitMs.p95Ms, 5);
  assert.equal(summary.recomposerTasks.p95Ms, 2);
  assert.equal(summary.failedPhases, 1);
  assert.equal(summary.droppedSamples, 1);
  probe.reset(); assert.equal(probe.snapshot().phases.length, 0);
});

test('recomposer instrumentation discovers compiler names and preserves thrown work', () => {
  const source = `class FrameRecomposer {
  dispatch() { return new FrameRecomposer$dispatch$$inlined$Runnable$1(); }
  xyz() {
    // Inline function 'androidx.compose.ui.util.trace' call
    _set_composeThreadId__abc(this, getCurrentThreadId());
    this.work();
    return Unit_instance;
  }
}`;
  const records = [];
  const scope = { Unit_instance: {}, getCurrentThreadId() {}, _set_composeThreadId__abc() {},
    globalThis: { __reaktorCompose: { beginPhase: name => name, endPhase: (...args) => records.push(args) } } };
  vm.createContext(scope);
  assert.ok(instrumentComposeWeb(source).includes('new FrameRecomposer$dispatch$$inlined$Runnable$1()'));
  vm.runInContext(instrumentComposeWeb(source) + '\nthis.recomposer = new FrameRecomposer();', scope);
  scope.recomposer.work = () => {};
  scope.recomposer.xyz();
  const failure = new Error('compose failed'); scope.recomposer.work = () => { throw failure; };
  assert.throws(() => scope.recomposer.xyz(), error => error === failure);
  assert.deepEqual(records, [['recomposerTasks', false], ['recomposerTasks', true]]);
  assert.throws(() => instrumentComposeWeb('class FrameRecomposer {}'), /boundary changed/);
});

test('pinned linked Skiko boundary still instruments', () => {
  const file = process.env.COMPOSE_LINKED_MODULE;
  if (!file) return;
  const source = readFileSync(file, 'utf8');
  assert.ok(instrumentComposeWeb(source).includes('probe.end(sample, failed)'));
});

test('optional GPU measurements retain totals while bounding shape samples and preserving failures', () => {
  let now = 0;
  class GL { texImage2D() { now += 7; } texSubImage2D(...args) { now += 2; if(args[0] === 'fail') throw new Error('upload failed'); } }
  const scope = { performance: { now: () => now }, document: { addEventListener() {} }, WebGLRenderingContext: GL };
  vm.createContext(scope);
  vm.runInContext(`(${installComposeWebProbe.toString()})({ maxSamples: 2, textureUploads: true });`, scope);
  const gl = new GL();
  gl.texImage2D(0,0,0,10,20,0,0,0,1234);
  for(let width=1;width<=10;width++) gl.texSubImage2D(0,0,0,0,width,1,0,0,1234);
  assert.throws(() => gl.texSubImage2D('fail',0,0,0,1,1,0,0,1234), /upload failed/);
  const uploads = scope.__reaktorCompose.snapshot().textureUploads;
  assert.equal(uploads.count, 12);
  assert.equal(uploads.cpuMs, 29);
  assert.equal(uploads.shapes.length, 2);
  assert.ok(uploads.shapes.some(shape => shape.shape === 'texImage2D:10x20:Number'));
  assert.equal(uploads.untrackedUploads, 9);
  scope.__reaktorCompose.reset();
  assert.equal(scope.__reaktorCompose.snapshot().textureUploads.count, 0);
});
