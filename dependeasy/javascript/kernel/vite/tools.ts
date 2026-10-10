import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export async function workspaceVite(root: string): Promise<typeof import('vite')> {
  const require = createRequire(resolve(root, 'package.json'));
  return import(pathToFileURL(require.resolve('vite')).href);
}
