import { existsSync, globSync, readFileSync } from 'node:fs';
import { isAbsolute, resolve } from 'node:path';
import { parseDocument } from 'yaml';

/** Parse actual YAML, with the same bounded membership contract as the Gradle kernel. */
export function workspaceDirectories(root: string): string[] {
  const source = readFileSync(resolve(root, 'pnpm-workspace.yaml'), 'utf8');
  if (Buffer.byteLength(source) > 1_048_576) throw new Error('Workspace definition is too large');
  const document = parseDocument(source);
  if (document.errors.length) throw new Error(document.errors.map(error => error.message).join('\n'));
  const packages: unknown = document.toJS({ maxAliasCount: 50 })?.packages;
  if (!Array.isArray(packages)) throw new Error('pnpm-workspace.yaml must declare packages');
  const included = new Set<string>(), excluded = new Set<string>();
  for (const pattern of packages) {
    if (typeof pattern !== 'string') throw new Error('pnpm workspace package patterns must be strings');
    const path = pattern.replace(/^!/, '');
    if (isAbsolute(path) || path.split('/').includes('..')) throw new Error(`Workspace must stay inside its root: ${pattern}`);
    for (const directory of globSync(path, { cwd: root }))
      if (existsSync(resolve(root, directory, 'package.json')))
        (pattern.startsWith('!') ? excluded : included).add(directory);
  }
  return [...included].filter(directory => !excluded.has(directory)).sort();
}
