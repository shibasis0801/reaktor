import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { createRequire } from 'node:module';
import { basename, dirname, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import type { Plugin, ResolvedConfig } from 'vite';

/** The entry dependeasy writes beside a library that Kotlin linked one module per file. */
const RuntimeEntry = 'runtime.mjs';
/** Kotlin's per-file output leaves these Skiko imports relative to each consumer; they live at the library root. */
const SkikoRoots = new Set(['./skiko.mjs', './js-reexport-symbols.mjs']);

type Transform = (this: unknown, code: string, id: string) => unknown;
/** Only an adapter's transform runs, so a plugin built against the consumer's own copy of Vite fits. */
type Adapter = { name: string; transform?: unknown };

function fingerprint(directory: string): string {
  let files = 0;
  let newest = 0;
  const visit = (at: string) => {
    for (const entry of readdirSync(at, { withFileTypes: true })) {
      const path = join(at, entry.name);
      if (entry.isDirectory()) visit(path);
      else if (entry.name.endsWith('.mjs')) { files += 1; newest = Math.max(newest, statSync(path).mtimeMs); }
    }
  };
  visit(directory);
  return `${files}:${newest}`;
}

/** Loads the Kotlin runtime a web contract aliases and applies its dependency adapters exactly once.
 * A runtime Kotlin linked per file is tree-shaken in production, where its export files and barrels have
 * no side effects, and reaches the development server and tests as one module, where its thousands of
 * files would each cost a request. */
export function kotlinRuntime(contract: { aliases: { replacement: string }[] }, { adapters = [] }: { adapters?: Adapter[] } = {}): Plugin {
  const runtimes = new Set(contract.aliases.map(alias => alias.replacement).filter(path => basename(path) === RuntimeEntry && existsSync(path)));
  const roots = [...runtimes].map(dirname);
  const root = (id: string) => roots.find(directory => id.startsWith(directory + '/'));
  const barrel = (id: string) => root(id) === dirname(id) && !runtimes.has(id) && existsSync(id.slice(0, -'.mjs'.length));
  /** Kotlin exports every eager initializer's hook under one name only so the linker keeps it; the runtime runs those initializers itself. */
  const initializerConflict = ({ code, exporter, ids }: { code?: string; exporter?: string; ids?: string[] }) => {
    if (code !== 'NAMESPACE_CONFLICT' || !exporter || !runtimes.has(exporter) || !ids?.length) return false;
    const eager = [...readFileSync(exporter, 'utf8').matchAll(/^import '(\.\/[^']+)';$/gm)].map(match => join(dirname(exporter), match[1]));
    return ids.every(id => eager.includes(id.replace(/\.export\.mjs$/, '.mjs')));
  };
  const transforms = adapters.map(adapter => adapter.transform as Transform | { handler: Transform } | undefined)
    .map(hook => (typeof hook === 'function' ? hook : hook?.handler)).filter(hook => hook !== undefined);
  async function adapt(context: unknown, code: string, id: string): Promise<string | null> {
    let current = code;
    for (const transform of transforms) {
      const result = await transform.call(context, current, id) as string | { code: string } | null | undefined;
      if (result) current = typeof result === 'string' ? result : result.code;
    }
    return current === code ? null : current;
  }
  let config: ResolvedConfig;
  const bundles = new Map<string, { key: string; code: string; map: unknown }>();
  async function bundle(entry: string) {
    const key = fingerprint(dirname(entry));
    const cached = bundles.get(entry);
    if (cached?.key === key) return cached;
    const vite = createRequire(join(config.root, 'package.json')).resolve('vite');
    const { rolldown } = await import(pathToFileURL(createRequire(vite).resolve('rolldown')).href);
    const build = await rolldown({
      input: entry,
      platform: 'browser',
      external: (source: string) => !/^(\.|\/|\0)/.test(source),
      plugins: [{
        name: 'dependeasy-kotlin-runtime-files',
        resolveId: (source: string, importer?: string) => (importer && SkikoRoots.has(source) && root(importer) ? join(root(importer)!, source.slice(2)) : null),
        transform(code: string, id: string) { return adapt(this, code, id); },
        onLog: (_level: string, log: { code?: string }) => !initializerConflict(log),
      }],
    });
    const { output: [chunk] } = await build.generate({ format: 'esm', sourcemap: 'hidden', codeSplitting: false });
    await build.close();
    const value = { key, code: chunk.code, map: chunk.map };
    bundles.set(entry, value);
    return value;
  }
  return {
    name: 'dependeasy-kotlin-runtime',
    enforce: 'pre',
    configResolved(resolved) { config = resolved; },
    async resolveId(source, importer, options) {
      if (!importer || !root(importer)) return null;
      if (SkikoRoots.has(source) && dirname(importer) !== root(importer)) return join(root(importer)!, source.slice(2));
      if (config.command !== 'build' || !(runtimes.has(importer) || barrel(importer))) return null;
      const resolved = await this.resolve(source, importer, { ...options, skipSelf: true });
      return resolved && (resolved.id.endsWith('.export.mjs') || barrel(resolved.id)) ? { ...resolved, moduleSideEffects: false } : resolved;
    },
    load(id) {
      return config.command === 'serve' && runtimes.has(id) ? bundle(id) : null;
    },
    onLog: (_level, log) => !initializerConflict(log),
    transform(code, id) {
      return config.command === 'serve' && runtimes.has(id) ? null : adapt(this, code, id);
    },
  };
}
