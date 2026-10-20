import { instrumentComposeWeb } from '../../reaktor-performance/tools/compose-web.mjs';

/** Skiko's generated dependency eagerly starts WASM at import time. Defer only that
 * dependency initialization until Compose calls awaitSkiko.then(). The ordinary
 * React workspace shares the Kotlin runtime, and must not fetch a renderer it isn't using. */
export function lazyComposeDependencies() {
  return {
    name: 'surface-compose-dependencies',
    enforce: 'pre',
    transform(source, id) {
      const file = id.replaceAll('\\', '/');
      if (file.endsWith('/js-reexport-symbols.mjs')) return source.replaceAll("window[", "globalThis[");
      // Compose 1.12.1 leaves its resolution MediaQueryList outside the window's
      // existing AbortController. It retains a disposed viewport on every visit.
      // Reuse that owner's signal; compiler-private field names are discovered
      // from the linked constructor rather than pinned to generated spellings.
      let patched = source;
      if (source.includes('class DefaultWindowState {')) {
        const owner = source.match(/class DefaultWindowState \{[\s\S]*?this\.([\w$]+) = new EventTargetListener\(window\)/)?.[1];
        const listener = /tmp\.addEventListener\('change', tmp_0, tmp\$ret\$0\);/;
        if (!owner || !listener.test(source)) throw new Error('Compose viewport listener changed; requalify renderer disposal.');
        patched = patched.replace(listener, `tmp.addEventListener('change', tmp_0, { ...tmp$ret$0, signal: Object.values($this.${owner}).find(value => value instanceof AbortController).signal });`);
      }
      // Skiko 0.150.1's web SkiaLayer.detach is empty. Closing Compose alone
      // leaves its GL buffers and context in Skiko's global handle tables.
      if (source.includes('class SkiaLayer {')) {
        const layer = source.match(/class SkiaLayer \{[\s\S]*?\n\}/)?.[0];
        const state = layer?.match(/this\.([\w$]+) = null;/)?.[1];
        const detachMatch = layer?.match(/\n  ([\w$]+)\(\) \{\n  \}/);
        const detach = detachMatch?.[0];
        const attach = 'function attachTo($this, htmlCanvas) {';
        const attachedCanvas = source.match(/function attachTo\(\$this, htmlCanvas\) \{\n  \$this\.([\w$]+) = htmlCanvas;/)?.[1];
        const renderer = source.match(/class CanvasRenderer \{[\s\S]*?\n\}/)?.[0];
        const width = renderer?.match(/this\.([\w$]+) = width;/)?.[1];
        const height = renderer?.match(/this\.([\w$]+) = height;/)?.[1];
        if (!state || !detach || !attachedCanvas || !width || !height) throw new Error('Skiko layer disposal/resize changed; requalify the Compose dependency adapter.');
        const replacement = detach.replace(/\{\n  \}/, `{
    const renderer = this.${state};
    renderer?.__surfaceDispose();
    this.${state} = null;
  }`);
        // Compose calls attachTo on every resize, sometimes from inside a frame. Keep the
        // DirectContext and its caches; swap the framebuffer-sized surface when the next
        // frame starts, never under a frame that is still drawing into it.
        patched = patched.replace(layer, () => layer.replace(detach, replacement))
          .replace(attach, `${attach}
  const renderer = $this.${state};
  if ($this.${attachedCanvas} === htmlCanvas && renderer && !renderer.__surfaceDisposed) {
    renderer.${width} = htmlCanvas.width;
    renderer.${height} = htmlCanvas.height;
    renderer.__resized = true;
    return;
  }
  $this.${detachMatch[1]}();`);
      }
      if (source.includes('class CanvasRenderer {')) {
        const renderer = source.match(/class CanvasRenderer \{[\s\S]*?\n\}/)?.[0];
        const pointer = renderer?.match(/this\.([\w$]+) = contextPointer;/)?.[1];
        const context = renderer?.match(/this\.([\w$]+) = Companion_getInstance[\w$]*\(\)\.[\w$]+\(\);/)?.[1];
        const close = source.match(/function disposeCanvas\(\$this\) \{[\s\S]*?tmp0_safe_receiver\.([\w$]+)\(\);/)?.[1];
        const resize = renderer?.match(/\n    this\.([\w$]+)\(\);/)?.[1];
        const frame = /function CanvasRenderer\$needRedraw\$lambda\(this\$0\) \{\n  return \(timestamp\) => \{/;
        if (!pointer || !context || !close || !resize || !frame.test(source)) throw new Error('Skiko renderer disposal changed; requalify the Compose dependency adapter.');
        const dispose = `
  __surfaceDispose() {
    if (this.__surfaceDisposed) return;
    this.__surfaceDisposed = true;
    GL.makeContextCurrent(this.${pointer});
    disposeCanvas(this);
    this.${context}.${close}();
    GL.deleteContext(this.${pointer});
    GL.makeContextCurrent(0);
  }
}`;
        patched = patched.replace(renderer, renderer.slice(0, -1) + dispose)
          .replace(frame, `$&\n    if (this$0.__surfaceDisposed) return;\n    if (this$0.__resized) { this$0.__resized = false; GL.makeContextCurrent(this$0.${pointer}); this$0.${resize}(); }`);
      }
      patched = instrumentComposeWeb(patched);
      if (!file.endsWith('/skiko.mjs')) return patched === source ? undefined : patched;
      const eager = 'export const awaitSkiko = loadSkikoWASM().then((module) => {';
      const end = '    return module\n});';
      if (!source.includes(eager) || !source.includes(end)) throw new Error('Skiko initialization changed; requalify the Compose dependency adapter.');
      return source.replace(eager, 'let readiness;\nexport const awaitSkiko = { then(resolve, reject) {\n    readiness ??= loadSkikoWASM().then((module) => {')
        .replace(end, '    return module\n});\n    return readiness.then(resolve, reject);\n} };');
    },
  };
}
