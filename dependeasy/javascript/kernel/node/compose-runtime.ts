import { readFile } from 'node:fs/promises';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';

// Kotlin's per-file output leaves these Skiko imports relative to each consumer.
// Resolve the real shared runtime from the generated package, including Coil users.
const runtimeModules = new Set(['./skiko.mjs', './js-reexport-symbols.mjs']);
registerHooks({
  resolve(specifier, context, nextResolve) {
    const boundary = context.parentURL?.lastIndexOf('/kotlin/') ?? -1;
    if (runtimeModules.has(specifier) && boundary >= 0) {
      const root = context.parentURL!.slice(0, boundary + '/kotlin/'.length);
      return { url: new URL(specifier, root).href, shortCircuit: true };
    }
    return nextResolve(specifier, context);
  },
});

// Skiko's web runtime fetches a file: Wasm asset even in model-only Node tests.
// Keep real WebAssembly execution and let every other request use Node's fetch.
const fetch = globalThis.fetch;
const browser: { window?: unknown; XMLHttpRequest?: unknown } = globalThis;
browser.window ??= globalThis;
browser.XMLHttpRequest ??= class FileRequest {
  url!: URL;
  response?: ArrayBuffer;
  status?: number;
  onload?: () => void;
  onerror?: (error: unknown) => void;
  open(method: string, url: string) {
    if (method !== 'GET' || !String(url).startsWith('file:')) throw new Error('Node Compose runtime only supports local asset requests');
    this.url = new URL(url);
  }
  send() {
    try {
      const bytes = readFileSync(this.url);
      this.response = Uint8Array.from(bytes).buffer;
      this.status = 200;
      this.onload?.();
    } catch (error) { this.onerror?.(error); }
  }
};
globalThis.fetch = async (input, options) => {
  const url = input instanceof Request ? input.url : String(input);
  if (url.startsWith('file:')) {
    const bytes = await readFile(new URL(url));
    return new Response(bytes, { headers: { 'Content-Type': url.endsWith('.wasm') ? 'application/wasm' : 'application/octet-stream' } });
  }
  return fetch(input, options);
};
