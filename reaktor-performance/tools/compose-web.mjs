/** Opt-in Skiko CPU and canvas-input measurements. Install before Compose mounts. */
export function installComposeWebProbe({ maxSamples = 10000, textureUploads = false } = {}) {
  if (globalThis.__reaktorCompose) return;
  const limit = Math.max(1, Math.floor(maxSamples));
  let frames = [], inputs = [], phases = [], pending = [], dropped = 0, lostContexts = 0;
  const renderers = new WeakMap();
  let nextId = 1;
  let uploads = new Map(), uploadCount = 0, uploadCpuMs = 0, untrackedUploads = 0;
  const shapeLimit = Math.min(limit, 128);
  if (textureUploads) for (const name of ['WebGLRenderingContext', 'WebGL2RenderingContext']) {
    const prototype = globalThis[name]?.prototype;
    if (!prototype) continue;
    for (const operation of ['texSubImage2D', 'texImage2D']) {
    if (!Object.hasOwn(prototype, operation)) continue;
    const original = prototype[operation];
    prototype[operation] = function (...args) {
      const source = args.at(-1);
      const widthIndex = operation === 'texImage2D' ? 3 : 4;
      const width = args.length >= 9 ? args[widthIndex] : source?.width;
      const height = args.length >= 9 ? args[widthIndex + 1] : source?.height;
      const key = `${operation}:${width ?? '?'}x${height ?? '?'}:${source?.constructor?.name ?? typeof source}`;
      const start = performance.now();
      try { return original.apply(this, args); }
      finally {
        const elapsed = performance.now() - start;
        uploadCount++; uploadCpuMs += elapsed;
        const entry = uploads.get(key);
        if (entry) { entry.count++; entry.cpuMs += elapsed; }
        else if (uploads.size < shapeLimit) uploads.set(key, { shape: key, count: 1, cpuMs: elapsed });
        else untrackedUploads++;
      }
    };
    }
  }
  const append = (list, value) => { if (list.length < limit) list.push(value); else dropped++; };
  const input = event => {
    const canvas = event.composedPath().find(node => node instanceof HTMLCanvasElement);
    if (!canvas) return;
    const now = performance.now();
    const at = event.timeStamp > 0 && event.timeStamp <= now ? event.timeStamp : now;
    const sample = { type: event.type, at, eventAt: event.timeStamp, receivedAt: now, canvas };
    if (pending.length < limit) pending.push(sample); else dropped++;
  };
  for (const type of ['pointerdown', 'pointermove', 'pointerup', 'wheel', 'keydown']) document.addEventListener(type, input, { capture: true, passive: true });
  document.addEventListener('webglcontextlost', () => { lostContexts++; }, true);
  globalThis.__reaktorCompose = {
    begin(renderer, canvas) {
      let id = renderers.get(renderer);
      if (!id) { id = nextId++; renderers.set(renderer, id); }
      return { id, canvas, start: performance.now() };
    },
    mark(token, phase) { token[phase] = performance.now(); },
    beginPhase(name) { return { name, at: performance.now() }; },
    endPhase(token, failed) { const end = performance.now(); append(phases, { ...token, end, cpuMs: end - token.at, failed }); },
    end(token, failed) {
      const end = performance.now();
      const canvas = token.canvas;
      append(frames, { renderer: token.id, at: token.start, end, cpuMs: end - token.start, failed,
        prepareMs: token.sceneAt == null ? null : token.sceneAt - token.start,
        sceneMs: token.submitAt == null ? null : token.submitAt - token.sceneAt,
        submitMs: token.submitAt == null ? null : end - token.submitAt,
        width: canvas?.width ?? null, height: canvas?.height ?? null,
        cssWidth: canvas?.clientWidth ?? null, cssHeight: canvas?.clientHeight ?? null, dpr: globalThis.devicePixelRatio });
      if (failed) return;
      pending = pending.filter(sample => {
        if (sample.canvas !== canvas || sample.at > token.start) return true;
        append(inputs, { type: sample.type, at: sample.at, eventAt: sample.eventAt, renderer: token.id,
          inputToRenderMs: end - sample.at, queueMs: token.start - sample.at, deliveryMs: sample.receivedAt - sample.at, renderQueueMs: token.start - sample.receivedAt });
        return false;
      });
    },
    reset() { frames = []; inputs = []; phases = []; pending = []; dropped = 0; lostContexts = 0; uploads = new Map(); uploadCount = 0; uploadCpuMs = 0; untrackedUploads = 0; },
    snapshot() { return { frames: frames.slice(), inputs: inputs.slice(), phases: phases.slice(), pendingInputs: pending.length, droppedSamples: dropped, lostContexts, textureUploads: { count: uploadCount, cpuMs: uploadCpuMs, untrackedUploads, shapes: [...uploads.values()].sort((a,b) => b.cpuMs-a.cpuMs).slice(0,32) } }; },
  };
}

/** Dependency injection only; no Compose private field names are pinned. */
export function instrumentComposeWeb(source) {
  if (source.includes('class FrameRecomposer {')) {
    // Locate the synchronous task flush by its source-level inline marker, not
    // compiler-generated method/field spellings. This also runs outside RAF.
    const scheduler = /\n  ([\w$]+)\(\) \{\n    \/\/ Inline function 'androidx.compose.ui.util.trace' call\n    _set_composeThreadId__[\w$]+\(this, getCurrentThreadId\(\)\);[\s\S]*?\n  \}/;
    const linked = source.match(/class FrameRecomposer \{[\s\S]*?\n\}/)?.[0];
    if (!linked || !scheduler.test(linked)) throw new Error('Compose recomposer boundary changed; requalify performance instrumentation.');
    source = source.replace(linked, () => linked.replace(scheduler, method => method.replace('{', `{
    const probe = globalThis.__reaktorCompose;
    const phase = probe?.beginPhase('recomposerTasks');
    let failed = true;
    try {`).replace(/\n  \}$/, `
    } finally { if (phase) probe.endPhase(phase, failed); }
  }`).replace('return Unit_instance;', 'failed = false;\n    return Unit_instance;')));
  }
  if (!source.includes('class CanvasRenderer {')) return source;
  const subclass = /class SkiaLayer\$attachTo\$1 extends CanvasRenderer \{\n  constructor\(\$htmlCanvas, this\$0, \$box\) \{[\s\S]*?super\([^\n]+\);/;
  const frame = /function CanvasRenderer\$needRedraw\$lambda\(this\$0\) \{\n  return \(timestamp\) => \{([\s\S]*?)\n  \};\n\}/;
  if (!subclass.test(source) || !frame.test(source)) throw new Error('Compose performance boundary changed; requalify Skiko instrumentation.');
  return source.replace(subclass, '$&\n    this.__reaktorCanvas = $htmlCanvas;')
    .replace(frame, (match, body) => {
      const draw = /this\$0\.[\w$]+\(timestamp\);/g;
      if ([...body.matchAll(draw)].length !== 1) throw new Error('Skiko scene boundary changed; requalify performance instrumentation.');
      const measured = body.replace(draw, 'if (sample) probe.mark(sample, "sceneAt");\n    $&\n    if (sample) probe.mark(sample, "submitAt");');
      return match.replace(body, `
    if (this$0.__surfaceDisposed) return;
    const probe = globalThis.__reaktorCompose;
    const sample = probe?.begin(this$0, this$0.__reaktorCanvas);
    let failed = true;
    try {${measured.replace('return Unit_instance;', 'failed = false;\n    return Unit_instance;')}
    } finally { if (sample) probe.end(sample, failed); }`);
    });
}

export function composeWebSummary(snapshot, budgetMs = 1000 / 60) {
  const quantiles = values => {
    const sorted = values.filter(Number.isFinite).sort((a, b) => a - b);
    const at = p => sorted.length ? sorted[Math.ceil(p * sorted.length) - 1] : null;
    return { count: sorted.length, p50Ms: at(.5), p95Ms: at(.95), maxMs: sorted.at(-1) ?? null };
  };
  return {
    renderCpu: { ...quantiles(snapshot.frames.map(frame => frame.cpuMs)), overBudget: snapshot.frames.filter(frame => frame.cpuMs > budgetMs).length },
    renderPhases: Object.fromEntries(['prepareMs', 'sceneMs', 'submitMs'].map(name => [name, quantiles(snapshot.frames.map(frame => frame[name]))])),
    recomposerTasks: quantiles((snapshot.phases ?? []).filter(phase => phase.name === 'recomposerTasks').map(phase => phase.cpuMs)),
    failedPhases: (snapshot.phases ?? []).filter(phase => phase.failed).length,
    inputToRender: quantiles(snapshot.inputs.map(input => input.inputToRenderMs)),
    inputQueue: quantiles(snapshot.inputs.map(input => input.queueMs)),
    inputDelivery: quantiles(snapshot.inputs.map(input => input.deliveryMs)),
    inputRenderQueue: quantiles(snapshot.inputs.map(input => input.renderQueueMs)),
    failedFrames: snapshot.frames.filter(frame => frame.failed).length,
    droppedSamples: snapshot.droppedSamples, pendingInputs: snapshot.pendingInputs, lostContexts: snapshot.lostContexts,
    textureUploads: snapshot.textureUploads ?? { count: 0, cpuMs: 0, untrackedUploads: 0, shapes: [] },
    canvases: [...new Map(snapshot.frames.map(frame => [frame.renderer, { renderer: frame.renderer, width: frame.width, height: frame.height, cssWidth: frame.cssWidth, cssHeight: frame.cssHeight, dpr: frame.dpr }])).values()],
  };
}

export function composeWebMetrics(summary, prefix = 'compose', scope = {}) {
  const values = {
    'render.cpu.p50': summary.renderCpu.p50Ms, 'render.cpu.p95': summary.renderCpu.p95Ms,
    'render.cpu.max': summary.renderCpu.maxMs, 'input.render.p95': summary.inputToRender.p95Ms,
    'render.prepare.p95': summary.renderPhases.prepareMs.p95Ms, 'render.scene.p95': summary.renderPhases.sceneMs.p95Ms,
    'render.submit.p95': summary.renderPhases.submitMs.p95Ms, 'recomposer.tasks.p95': summary.recomposerTasks.p95Ms,
    'input.queue.p95': summary.inputQueue.p95Ms,
    'input.delivery.p95': summary.inputDelivery.p95Ms, 'input.renderQueue.p95': summary.inputRenderQueue.p95Ms,
  };
  const counts = { 'render.count': summary.renderCpu.count, 'render.overBudget': summary.renderCpu.overBudget,
    'render.failed': summary.failedFrames, 'context.lost': summary.lostContexts, 'samples.dropped': summary.droppedSamples,
    'input.pending': summary.pendingInputs };
  return [...Object.entries(values).filter(([, value]) => value !== null).map(([name, value]) => ({ name: `${prefix}.${name}`, value, unit: 'ms', domain: 'Runtime', scope })),
    ...Object.entries(counts).map(([name, value]) => ({ name: `${prefix}.${name}`, value, unit: 'count', domain: 'Runtime', scope }))];
}
