import { test } from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { lazyComposeDependencies } from './compose-dependencies.mjs';
import { readFileSync } from 'node:fs';

const fixture = `class CanvasRenderer {
  constructor(contextPointer, width, height) {
    this.pointer = contextPointer;
    this.width = width;
    this.height = height;
    this.context = Companion_getInstance_fake().create();
    this.resize();
  }
  resize() { counts.surfaces++; }
  draw(timestamp) { this.onDraw?.(); }
}
class SkiaLayer$attachTo$1 extends CanvasRenderer {
  constructor($htmlCanvas, this$0, $box) {
    super(createWebGLContext($htmlCanvas), $htmlCanvas.width, $htmlCanvas.height);
  }
}
class SkiaLayer {
  constructor() { this.renderer = null; this.canvas = null; }
  detach() {
  }
}
function attachTo($this, htmlCanvas) {
  $this.canvas = htmlCanvas;
  $this.renderer = new SkiaLayer$attachTo$1(htmlCanvas, $this);
}
function disposeCanvas($this) {
  var tmp0_safe_receiver = $this.surface;
  tmp0_safe_receiver.close();
}
function CanvasRenderer$needRedraw$lambda(this$0) {
  return (timestamp) => {
    this$0.draw(timestamp);
    return Unit_instance;
  };
}`;

test('resize retains one GL context, swaps the surface between frames, and retirement closes it once', () => {
  const counts = { contexts: 0, closed: 0, deleted: 0, surfaces: 0 };
  const scope = {
    counts, Unit_instance: {},
    createWebGLContext() { return ++counts.contexts; },
    Companion_getInstance_fake() { return { create: () => ({ close() { counts.closed++; } }) }; },
    GL: { makeContextCurrent() {}, deleteContext() { counts.deleted++; } },
  };
  vm.createContext(scope);
  const transformed = lazyComposeDependencies().transform(fixture, '/linked.mjs');
  // disposeCanvas's real counterpart closes nullable native surface/target objects.
  vm.runInContext(transformed + '\nthis.layer = new SkiaLayer(); this.attach = attachTo; this.frame = CanvasRenderer$needRedraw$lambda;', scope);
  scope.layer.surface = null;
  const first = { width: 800, height: 600 };
  scope.attach(scope.layer, first);
  const original = scope.layer.renderer;
  original.surface = { close() {} };
  first.width = 1200;
  scope.attach(scope.layer, first);
  assert.equal(scope.layer.renderer, original);
  assert.equal(original.width, 1200);
  assert.equal(counts.contexts, 1);
  assert.equal(counts.surfaces, 1);
  scope.frame(original)(0);
  assert.equal(counts.surfaces, 2);
  original.onDraw = () => { first.width = 1600; scope.attach(scope.layer, first); assert.equal(counts.surfaces, 2); };
  scope.frame(original)(0);
  original.onDraw = null;
  assert.equal(counts.surfaces, 2);
  scope.frame(original)(0);
  assert.equal(counts.surfaces, 3);
  assert.equal(original.width, 1600);
  assert.equal(counts.deleted, 0);
  scope.attach(scope.layer, { width: 400, height: 300 });
  assert.equal(counts.deleted, 1);
  scope.layer.renderer.surface = { close() {} };
  scope.layer.detach(); scope.layer.detach();
  assert.equal(counts.contexts, 2);
  assert.equal(counts.closed, 2);
  assert.equal(counts.deleted, 2);
  scope.frame(original)(0); // A scheduled callback from a retired renderer does no work.
});

test('dependency adapter fails on resize boundary drift and accepts the pinned producer', () => {
  assert.throws(() => lazyComposeDependencies().transform(fixture.replace('this.width = width;', ''), '/linked.mjs'), /resize changed/);
  const file = process.env.COMPOSE_LINKED_MODULE;
  if (file) {
    const transformed = lazyComposeDependencies().transform(readFileSync(file, 'utf8'), file);
    assert.ok(transformed.includes('=== htmlCanvas && renderer && !renderer.__surfaceDisposed'));
    assert.ok(transformed.includes('probe.end(sample, failed)'));
  }
});
