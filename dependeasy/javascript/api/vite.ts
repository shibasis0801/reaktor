import { resolve } from 'node:path';
import type { Plugin, UserConfig } from 'vite';
import { kotlinResources } from '../kernel/vite/kotlin-resources.ts';
import { staticAssetPlugin, type StaticAssetOptions } from '../kernel/vite/static-assets.ts';
import { reactDevtoolsPlugin } from '../kernel/vite/react-devtools.ts';
export { linkedKotlinDependencies } from '../kernel/vite/kotlin-dependencies.ts';

export function staticAssets(options: StaticAssetOptions): Plugin { return staticAssetPlugin(options); }

export function reactDevtools<T>(root: string, connect: (backend: T, options: { port: number }) => unknown,
  { enabled = false, port = 8097 }: { enabled?: boolean; port?: number } = {}): Plugin | false {
  return enabled && reactDevtoolsPlugin(root, connect.toString(), port);
}

export interface KotlinBrowserOptions {
  directory: string;
  moduleName: string;
  output?: string;
  sqlite?: boolean;
  aliases?: Record<string, string>;
}

/** Kotlin emits modules; Vite bundles them and the kernel serves their resources. */
export function kotlinBrowser({ directory, moduleName, output = 'dist/client', sqlite = false,
  aliases = {} }: KotlinBrowserOptions): UserConfig {
  const root = resolve(directory);
  const kotlin = resolve(root, '../../build/js/packages', moduleName, 'kotlin');
  const resources = resolve(root, 'build/processedResources/js/main');
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
