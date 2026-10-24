import { resolve, dirname } from 'node:path';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import type { Plugin, UserConfig } from 'vite';
import { kotlinResources } from '../kernel/vite/kotlin-resources.ts';
import { staticAssetPlugin, type StaticAssetOptions } from '../kernel/vite/static-assets.ts';
import { reactDevtoolsPlugin } from '../kernel/vite/react-devtools.ts';
export { linkedKotlinDependencies } from '../kernel/vite/kotlin-dependencies.ts';

export interface WebContract {
  schema: number;
  component: string;
  directory: string;
  aliases: Record<string, string>;
  assets: Record<string, string>;
  workspaces?: string[]
  packages?: Record<string, string>;
  dataSources?: Record<string, string>;
  kotlinDirectory?: string | null;
  resources?: string | null;
  moduleName?: string | null;
  target: string;
  assetFileNames: string;
  tests?: { include: string[]; exclude: string[]; setupFiles: string[]; maxWorkers?: number };
}

export function webContract(source: string | URL): Omit<WebContract, 'aliases'> & { aliases: { find: RegExp; replacement: string }[] } {
  const file = source instanceof URL ? createRequire(source).resolve('#dependeasy/web') : source;
  const value: WebContract = JSON.parse(readFileSync(file, 'utf8'));
  if (value.schema !== 1) throw new Error(`Unsupported web contract: ${file}`);
  const base = dirname(file);
  const paths = (values: Record<string, string>): Record<string, string> => Object.fromEntries(Object.entries(values).map(([key, path]) => [key, resolve(base, path)]));
  return { ...value, directory: resolve(base, value.directory), aliases: Object.entries(paths(value.aliases)).map(([name, path]) => ({
    find: new RegExp('^' + name.split('*').map(part => part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('(.+)') + '$'),
    replacement: path.replaceAll('*', '$1'),
  })), assets: paths(value.assets), packages: paths(value.packages ?? {}),
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
  const contract = webContract(contractFile ?? pathToFileURL(resolve(root, 'package.json')));
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
      outDir: resolve(root, output), emptyOutDir: true, target: contract.target, sourcemap: false,
      assetsInlineLimit: 0,
      rolldownOptions: { input: resolve(root, 'index.html'), output: { assetFileNames: contract.assetFileNames } },
    },
  };
}

/** Hermes consumes the same Vite pipeline as browser TypeScript. */
export function hermesLibrary(contract: Pick<WebContract, 'target'>, entry: string, fileName: string, output = 'dist'): UserConfig {
  return { build: {
    target: contract.target, minify: false, sourcemap: false, outDir: output, emptyOutDir: true,
    lib: { entry, name: 'ReaktorFfi', formats: ['iife'], fileName: () => fileName },
  } };
}
