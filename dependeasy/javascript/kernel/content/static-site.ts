import { cp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import type { InlineConfig } from 'vite';
import { workspaceVite } from '../vite/tools.ts';

export interface StaticSiteOptions {
  root: string; destination: string; entry: string; renderer: string;
  pages: readonly { path?: string; title: string; data: Record<string, unknown>; content: string }[];
  staticDirectories: readonly string[]; aliases: Record<string, string>; head: string; base?: string;
}
interface ClientEntry { file: string; css?: string[]; isEntry?: boolean }
type Renderer = (data: Record<string, unknown>, content: string) => string;
const json = (value: unknown) => JSON.stringify(value).replaceAll('<', '\\u003c');
const escape = (value: string) => value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('"', '&quot;');

function configuration(options: StaticSiteOptions): InlineConfig {
  return { configFile: false, root: options.root, base: options.base ?? '/',
    resolve: { alias: options.aliases, dedupe: ['react', 'react-dom'] },
    oxc: { jsx: { runtime: 'automatic' } }, build: { target: 'es2022', sourcemap: false, minify: true } };
}

export async function prepareClient(options: StaticSiteOptions): Promise<ClientEntry> {
  const { build } = await workspaceVite(options.root);
  const common = configuration(options);
  await rm(options.destination, { recursive: true, force: true });
  await build({ ...common, build: { ...common.build, outDir: options.destination, manifest: true,
    rolldownOptions: { input: resolve(options.root, options.entry) } } });
  const manifest: Record<string, ClientEntry> = JSON.parse(await readFile(resolve(options.destination, '.vite/manifest.json'), 'utf8'));
  const asset = Object.values(manifest).find(value => value.isEntry);
  if (!asset) throw new Error('Vite did not emit the static site entry');
  return asset;
}

export async function withServerRenderer(options: StaticSiteOptions, operation: (render: Renderer) => Promise<void>): Promise<void> {
  const { build } = await workspaceVite(options.root);
  const common = configuration(options);
  const directory = resolve(options.root, '.cache-static-site');
  try {
    await build({ ...common, ssr: { noExternal: true }, build: { ...common.build, outDir: directory,
      ssr: resolve(options.root, options.renderer), rolldownOptions: { output: { entryFileNames: 'render.mjs' } } } });
    const { render } = await import(pathToFileURL(resolve(directory, 'render.mjs')).href + `?v=${Date.now()}`);
    await operation(render);
  } finally { await rm(directory, { recursive: true, force: true }); }
}

export async function renderPages(options: StaticSiteOptions, asset: ClientEntry, render: Renderer): Promise<void> {
  const base = options.base ?? '/';
  const styles = (asset.css ?? []).map(file => `<link rel="stylesheet" href="${base}${file}">`).join('');
  for (const page of options.pages) {
    const relative = page.path || 'index.html';
    const output = resolve(options.destination, relative);
    if (!output.startsWith(resolve(options.destination) + '/')) throw new Error(`Invalid page path: ${relative}`);
    const data = { ...page.data, title: page.title };
    const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>${escape(page.title)}</title>${options.head}${styles}</head><body><div id="root">${render(data, page.content)}</div><script id="site-page" type="application/json">${json(data)}</script><script type="module" src="${base}${asset.file}"></script></body></html>`;
    await mkdir(dirname(output), { recursive: true });
    await writeFile(output, html);
  }
}

export async function copyStaticDirectories(options: StaticSiteOptions): Promise<void> {
  for (const directory of options.staticDirectories) await cp(directory, options.destination, { recursive: true });
  await rm(resolve(options.destination, '.vite'), { recursive: true, force: true });
}
