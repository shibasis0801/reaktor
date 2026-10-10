import {dirname, join, resolve} from 'node:path';
import {mkdtemp, readFile, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import { workspaceVite } from '../vite/tools.ts';

/** Compile a source-owned TypeScript module for a Node generator using the workspace's Vite. */
export async function bundleNodeModule({entry, output, root = process.cwd()}: { entry: string; output: string; root?: string }) {
  const {build} = await workspaceVite(root);
  await build({configFile: false, root, logLevel: 'error', ssr: {noExternal: true},
    build: {ssr: resolve(entry), target: 'node24', outDir: dirname(resolve(output)),
      emptyOutDir: false, minify: false, sourcemap: false,
      rolldownOptions: {output: {entryFileNames: resolve(output).split('/').at(-1), codeSplitting: false}}}});
}

export async function bundleNodeSource(entry: string, root = process.cwd()) {
  const directory = await mkdtemp(join(tmpdir(), 'dependeasy-node-'));
  const output = join(directory, 'module.mjs');
  try {
    await bundleNodeModule({entry, output, root});
    return await readFile(output, 'utf8');
  } finally {
    await rm(directory, {recursive: true, force: true});
  }
}
