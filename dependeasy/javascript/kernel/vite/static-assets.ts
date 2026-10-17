import { readFileSync, readdirSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { extname, join, resolve } from 'node:path';
import type { Plugin } from 'vite';

export interface StaticAssetOptions {
  root: string;
  files?: Record<string, string>;
  directories?: Array<{ from: string; to: string; extension: string }>;
  developmentOnly?: boolean;
}

const contentTypes: Record<string, string> = {
  '.mjs': 'text/javascript', '.js': 'text/javascript', '.wasm': 'application/wasm',
  '.json': 'application/json', '.css': 'text/css', '.woff2': 'font/woff2',
};

function outputName(name: string): string {
  const value = name.replaceAll('\\', '/');
  if (!value || value.startsWith('/') || value.split('/').some(part => !part || part === '..' || part === '.'))
    throw new Error(`Static asset output must stay inside the bundle: ${name}`);
  return value;
}

function* directoryFiles(directory: string, prefix = ''): Generator<[string, string]> {
  for (const file of readdirSync(directory, { withFileTypes: true })) {
    const name = join(prefix, file.name);
    if (file.isDirectory()) yield* directoryFiles(join(directory, file.name), name);
    else if (file.isFile()) yield [name, join(directory, file.name)];
  }
}

/** One declaration serves development files and emits the same paths into the bundle. */
export function staticAssetPlugin(options: StaticAssetOptions): Plugin {
  const declared = Object.entries(options.files ?? {}).map(([name, source]) => [outputName(name), resolve(options.root, source)] as const);
  const directories = (options.directories ?? []).map(item => ({ ...item, to: outputName(item.to), from: resolve(options.root, item.from) }));
  function assets(): Map<string, string> {
    const result = new Map(declared);
    for (const directory of directories) for (const [name, file] of directoryFiles(directory.from)) {
      if (!name.endsWith(directory.extension)) continue;
      const output = outputName(join(directory.to, name));
      if (result.has(output)) throw new Error(`Static asset output is declared twice: ${output}`);
      result.set(output, file);
    }
    return result;
  }
  return {
    name: 'dependeasy-static-assets',
    configureServer(server) {
      const files = assets();
      server.middlewares.use(async (request, response, next) => {
        const file = files.get((request.url ?? '').split('?')[0].replace(/^\//, ''));
        if (!file) return next();
        try {
          const source = await readFile(file);
          response.setHeader('Content-Type', contentTypes[extname(file)] ?? 'application/octet-stream');
          if (options.developmentOnly) response.setHeader('Cache-Control', 'no-store');
          response.end(source);
        } catch { response.statusCode = 503; response.end('The declared asset is unavailable. Build its producer first.'); }
      });
    },
    generateBundle() {
      if (options.developmentOnly) return;
      for (const [fileName, file] of assets()) this.emitFile({ type: 'asset', fileName, source: readFileSync(file) });
    },
  };
}
