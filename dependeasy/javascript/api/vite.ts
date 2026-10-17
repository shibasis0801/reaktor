import { resolve, dirname } from 'node:path';
import { readFileSync } from 'node:fs';
import type { Plugin, UserConfig } from 'vite';
import { kotlinResources } from '../kernel/vite/kotlin-resources.ts';
import { staticAssetPlugin, type StaticAssetOptions } from '../kernel/vite/static-assets.ts';
import { reactDevtoolsPlugin } from '../kernel/vite/react-devtools.ts';
export { linkedKotlinDependencies } from '../kernel/vite/kotlin-dependencies.ts';

interface WebContract {
  schema: number;
  component: string;
  directory: string;
  aliases: Record<string, string>;
  assets: Record<string, string>;
  kotlinDirectory?: string | null;
  resources?: string | null;
  moduleName?: string | null;
}

export function webContract(file: string): WebContract {
  const value: WebContract = JSON.parse(readFileSync(file, 'utf8'));
  if (value.schema !== 1) throw new Error(`Unsupported web contract: ${file}`);
  const base = dirname(file);
  const paths = (values: Record<string, string>): Record<string, string> => Object.fromEntries(Object.entries(values).map(([key, path]) => [key, resolve(base, path)]));
  return { ...value, directory: resolve(base, value.directory), aliases: paths(value.aliases), assets: paths(value.assets),
    kotlinDirectory: value.kotlinDirectory && resolve(base, value.kotlinDirectory), resources: value.resources && resolve(base, value.resources) };
}

export function staticAssets(options: StaticAssetOptions): Plugin { return staticAssetPlugin(options); }

export function reactDevtools<T>(root: string, connect: (backend: T, options: { port: number }) => unknown,
  { enabled = false, port = 8097 }: { enabled?: boolean; port?: number } = {}): Plugin | false {
  return enabled && reactDevtoolsPlugin(root, connect.toString(), port);
}

export interface KotlinBrowserOptions {
  directory: string;
  contract?: string;
  output?: string;
  sqlite?: boolean;
  aliases?: Record<string, string>;
}

/** Kotlin emits modules; Vite bundles them and the kernel serves their resources. */
export function kotlinBrowser({ directory, contract: contractFile, output = 'dist/client', sqlite = false,
  aliases = {} }: KotlinBrowserOptions): UserConfig {
  const root = resolve(directory);
  const contract = webContract(contractFile ?? resolve(root, 'build/dependeasy/web/browser.json'));
  const { kotlinDirectory: kotlin, resources, moduleName } = contract;
  if (!kotlin || !resources || !moduleName) throw new Error('The browser contract has no Kotlin executable');
  return {
    root, publicDir: false,
    resolve: { alias: {
      'compose-flow': '@xyflow/react',
      './skiko.mjs': resolve(resources, 'skiko.mjs'),
      './js-reexport-symbols.mjs': resolve(resources, 'js-reexport-symbols.mjs'),
      ...aliases,
    } },
    plugins: [kotlinResources({ kotlin, resources, moduleName, sqlite })],
    build: {
      outDir: resolve(root, output), emptyOutDir: true, target: 'es2022', sourcemap: false,
      assetsInlineLimit: 0,
      rolldownOptions: { input: resolve(root, 'index.html'), output: { assetFileNames: '[name]-[hash][extname]' } },
    },
  };
}

/** Hermes consumes the same Vite pipeline as browser TypeScript. */
export function hermesLibrary(entry: string, fileName: string, output = 'dist'): UserConfig {
  return { build: {
    target: 'es2018', minify: false, sourcemap: false, outDir: output, emptyOutDir: true,
    lib: { entry, name: 'ReaktorFfi', formats: ['iife'], fileName: () => fileName },
  } };
}
