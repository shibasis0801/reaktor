import { readdirSync, readFileSync } from 'node:fs';
import { resolve, join, extname } from 'node:path';
import { createRequire } from 'node:module';

const runtimeFiles = new Set(['index.html', 'skiko.mjs', 'skikod8.mjs', 'js-reexport-symbols.mjs', 'skiko.wasm', 'META-INF']);
const contentTypes: Record<string, string> = {
  '.wasm': 'application/wasm', '.mjs': 'text/javascript', '.js': 'text/javascript',
  '.json': 'application/json', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.webp': 'image/webp', '.png': 'image/png', '.jpg': 'image/jpeg',
  '.ttf': 'font/ttf', '.woff2': 'font/woff2', '.xml': 'application/xml',
};
function* files(directory: string, prefix = ''): Generator<[string, string]> {
  for (const file of readdirSync(directory, { withFileTypes: true })) {
    if (!prefix && runtimeFiles.has(file.name)) continue;
    const name = join(prefix, file.name);
    if (file.isDirectory()) yield* files(join(directory, file.name), name);
    else yield [name, join(directory, file.name)];
  }
}

import type { Plugin } from 'vite';

export function kotlinResources({ kotlin, resources, moduleName, sqlite }: { kotlin: string; resources: string; moduleName: string; sqlite: boolean }): Plugin {
  const entry = resolve(kotlin, `${moduleName}.mjs`);
  const entryId = '/@dependeasy/kotlin-entry';
  return {
      name: 'dependeasy-kotlin-browser',
      resolveId(id) {
        if (id === entryId) return entry;
        if (id === 'postgres' || id.startsWith('cloudflare:')) return '\0dependeasy-browser-empty';
      },
      load(id) {
        if (id === '\0dependeasy-browser-empty') return { code: 'module.exports = {};', moduleType: 'cjs' };
      },
      configureServer(server) {
        const assets = new Map([...files(resources)].map(([name, file]) => [`/${name.replaceAll('\\', '/')}`, file]));
        server.middlewares.use((request, response, next) => {
          const url = request.url?.split('?')[0];
          const asset = url ? assets.get(url) : undefined;
          if (asset) {
            response.setHeader('Content-Type', contentTypes[extname(asset)] ?? 'application/octet-stream');
            response.end(readFileSync(asset));
          }
          else next();
        });
      },
      transformIndexHtml: { order: 'pre', handler(html) {
        return html.replace(/<link[^>]*rel="modulepreload"[^>]*\/?>/g, '')
          .replace(/<script type="module" src="[^"]+"[^>]*><\/script>/, `<script type="module" src="${entryId}" fetchpriority="high"></script>`);
      } },
      generateBundle(_, bundle) {
        for (const [name, file] of files(resources)) this.emitFile({ type: 'asset', fileName: name.replaceAll('\\', '/'), source: readFileSync(file) });
        if (sqlite) {
          const require = createRequire(resolve(kotlin, '../package.json'));
          const sqliteRoot = resolve(require.resolve('@sqlite.org/sqlite-wasm/package.json'), '../dist');
          for (const [source, name] of [['index.mjs', 'sqlite3.mjs'], ['sqlite3.wasm', 'sqlite3.wasm']])
            this.emitFile({ type: 'asset', fileName: `sqlite/${name}`, source: readFileSync(resolve(sqliteRoot, source)) });
        }
        const wasm = Object.keys(bundle).find(name => /skiko.*\.wasm$/.test(name));
        const html = bundle['index.html'];
        if (wasm && html && html.type === 'asset') html.source = String(html.source).replace('<!-- rendering-runtime -->',
          `<link rel="preload" href="/${wasm}" as="fetch" type="application/wasm" crossorigin fetchpriority="low" />`);
      },
    };
}
