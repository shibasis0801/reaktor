import { test } from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { lazyComposeDependencies } from './compose-dependencies.mjs';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

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

// Per-file output links each class as the module's first-use factory and puts CanvasRenderer in its own file.
const perFile = {
  'CanvasRenderer.mjs': `function disposeCanvas($this) {
  var tmp0_safe_receiver = $this.surface;
  tmp0_safe_receiver.close();
}
function CanvasRenderer$needRedraw$lambda(this$0) {
  return (timestamp) => {
    this$0.draw(timestamp);
    return Unit_instance;
  };
}
var CanvasRendererClass;
function CanvasRenderer() {
  if (CanvasRendererClass === VOID) {
    class $ {
      constructor(contextPointer, width, height, $box) {
        this.pointer = contextPointer;
        this.width = width;
        this.height = height;
        this.context = Companion_getInstance_fake().create();
        this.resize();
      }
      resize() { counts.surfaces++; }
      draw(timestamp) { this.onDraw?.(); }
    }
    CanvasRendererClass = $;
  }
  return CanvasRendererClass;
}`,
  'SkiaLayer.web.mjs': `function attachTo($this, htmlCanvas) {
  $this.canvas = htmlCanvas;
  $this.renderer = new (SkiaLayer$attachTo$1())(htmlCanvas, $this);
}
var SkiaLayer$attachTo$1Class;
function SkiaLayer$attachTo$1() {
  if (SkiaLayer$attachTo$1Class === VOID) {
    class $ extends CanvasRenderer() {
      constructor($htmlCanvas, this$0, $box) {
        super(createWebGLContext($htmlCanvas), $htmlCanvas.width, $htmlCanvas.height, $box);
      }
    }
    SkiaLayer$attachTo$1Class = $;
  }
  return SkiaLayer$attachTo$1Class;
}
var SkiaLayerClass;
function SkiaLayer() {
  if (SkiaLayerClass === VOID) {
    class $ {
      constructor() {
        this.renderer = null;
        this.canvas = null;
      }
      detach() {
      }
    }
    SkiaLayerClass = $;
  }
  return SkiaLayerClass;
}`,
};

function linked(files, action) {
  const directory = mkdtempSync(join(tmpdir(), 'compose-per-file-'));
  try {
    for (const [name, text] of Object.entries(files)) writeFileSync(join(directory, name), text);
    return action(name => lazyComposeDependencies().transform(files[name], join(directory, name)));
  } finally { rmSync(directory, { recursive: true, force: true }); }
}

function exercise(transformed, layer) {
  const counts = { contexts: 0, closed: 0, deleted: 0, surfaces: 0 };
  const scope = {
    counts, Unit_instance: {}, VOID: undefined,
    createWebGLContext() { return ++counts.contexts; },
    Companion_getInstance_fake() { return { create: () => ({ close() { counts.closed++; } }) }; },
    GL: { makeContextCurrent() {}, deleteContext() { counts.deleted++; } },
  };
  vm.createContext(scope);
  // disposeCanvas's real counterpart closes nullable native surface/target objects.
  vm.runInContext(transformed + `\nthis.layer = new ${layer}(); this.attach = attachTo; this.frame = CanvasRenderer$needRedraw$lambda;`, scope);
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
}

test('resize retains one GL context, swaps the surface between frames, and retirement closes it once', () => {
  exercise(lazyComposeDependencies().transform(fixture, '/linked.mjs'), 'SkiaLayer');
});

test('per-file output gets the same renderer lifetime from its two linked modules', () => linked(perFile, transform => {
  exercise(transform('CanvasRenderer.mjs') + '\n' + transform('SkiaLayer.web.mjs'), '(SkiaLayer())');
}));

test('dependency adapter fails on resize boundary drift and accepts the pinned producer', () => {
  assert.throws(() => lazyComposeDependencies().transform(fixture.replace('this.width = width;', ''), '/linked.mjs'), /resize changed/);
  const drifted = { ...perFile, 'CanvasRenderer.mjs': perFile['CanvasRenderer.mjs'].replace('this.width = width;', '') };
  assert.throws(() => linked(drifted, transform => transform('SkiaLayer.web.mjs')), /resize changed/);
  const file = process.env.COMPOSE_LINKED_MODULE;
  if (file) {
    const transformed = lazyComposeDependencies().transform(readFileSync(file, 'utf8'), file);
    assert.ok(transformed.includes('=== htmlCanvas && renderer && !renderer.__surfaceDisposed'));
    assert.ok(transformed.includes('probe.end(sample, failed)'));
  }
});
