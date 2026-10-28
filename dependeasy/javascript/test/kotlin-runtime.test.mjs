import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm, realpath, utimes } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { kotlinRuntime } from '../api/vite.ts';

const files = {
  'runtime.mjs': "import './net/net/Engine.mjs';\nexport * from './App.mjs';\nexport * from './net.mjs';\n",
  'App.mjs': "import './net.mjs';\nexport * from './App/app/Screen.export.mjs';\n",
  'App/app/Screen.export.mjs': "import { Screen } from './Screen.mjs';\nexport { Screen };\n",
  'App/app/Screen.mjs': "import { createElement } from 'react';\nimport { awaitSkiko } from './skiko.mjs';\nexport class Screen { name() { return 'screen'; } draw() { return [createElement, awaitSkiko]; } }\n",
  'net.mjs': "import './net/net/Engine.mjs';\nexport * from './net/net/Engine.export.mjs';\n",
  'net/net/Engine.export.mjs': "export const engine = 'js';\n",
  'net/net/Engine.mjs': "globalThis.engineRegistered = true;\n",
  'skiko.mjs': "export function awaitSkiko() { return 'skiko-root'; }\n",
};

async function library(action) {
  const root = await realpath(await mkdtemp(join(tmpdir(), 'dependeasy-kotlin-runtime-')));
  try {
    for (const [name, text] of Object.entries(files)) {
      await mkdir(dirname(join(root, name)), { recursive: true });
      await writeFile(join(root, name), text);
    }
    await action(root, kotlinRuntime({ aliases: [{ replacement: join(root, 'runtime.mjs') }, { replacement: '/elsewhere/index.ts' }] },
      { adapters: [{ name: 'adapter', transform: (code, id) => (id.endsWith('Screen.mjs') ? code.replace("'screen'", "'adapted'") : null) }] }));
  } finally { await rm(root, { recursive: true, force: true }); }
}

const context = { resolve: async (source, importer) => ({ id: resolve(dirname(importer), source) }) };

test('a production build keeps only what its consumers use: export files and barrels have no side effects', () => library(async (root, plugin) => {
  plugin.configResolved({ command: 'build', root: import.meta.dirname });
  const runtime = join(root, 'runtime.mjs');
  assert.deepEqual(await plugin.resolveId.call(context, './App.mjs', runtime, {}), { id: join(root, 'App.mjs'), moduleSideEffects: false });
  assert.deepEqual(await plugin.resolveId.call(context, './App/app/Screen.export.mjs', join(root, 'App.mjs'), {}),
    { id: join(root, 'App/app/Screen.export.mjs'), moduleSideEffects: false });
  assert.deepEqual(await plugin.resolveId.call(context, './net/net/Engine.mjs', runtime, {}), { id: join(root, 'net/net/Engine.mjs') }, 'an eager initializer keeps its side effects');
  assert.equal(await plugin.resolveId.call(context, './Screen.mjs', join(root, 'App/app/Screen.export.mjs'), {}), null);
  assert.equal(await plugin.resolveId.call(context, './skiko.mjs', join(root, 'App/app/Screen.mjs'), {}), join(root, 'skiko.mjs'));
  assert.equal(await plugin.resolveId.call(context, './App.mjs', '/elsewhere/index.ts', {}), null);
  assert.equal(await plugin.transform('const value = 1;', join(root, 'App/app/Screen.mjs')), null);
  assert.equal(await plugin.transform("name() { return 'screen'; }", join(root, 'App/app/Screen.mjs')), "name() { return 'adapted'; }");
  const conflict = ids => ({ code: 'NAMESPACE_CONFLICT', exporter: runtime, ids: ids.map(id => join(root, id)) });
  assert.equal(plugin.onLog('warn', conflict(['net/net/Engine.export.mjs'])), false, "an eager initializer's hook is the runtime's own business");
  assert.equal(plugin.onLog('warn', conflict(['net/net/Engine.export.mjs', 'App/app/Screen.export.mjs'])), true, 'any other conflict still reaches the build log');
}));

test('the development server gets the runtime as one adapted module, rebuilt when its files change', () => library(async (root, plugin) => {
  plugin.configResolved({ command: 'serve', root: import.meta.dirname });
  const runtime = join(root, 'runtime.mjs');
  const first = await plugin.load(runtime);
  assert.match(first.code, /engineRegistered = true/);
  assert.match(first.code, /["']adapted["']/);
  assert.match(first.code, /from ['"]react['"]/, 'bare imports stay for the server to resolve');
  assert.match(first.code, /["']skiko-root["']/, 'nested Skiko imports reach the library root');
  assert.doesNotMatch(first.code, /sourceMappingURL/, 'the map travels with the module, not as a file');
  assert.equal(await plugin.load(runtime), first);
  assert.equal(await plugin.transform(first.code, runtime), null, 'the bundled runtime is not adapted twice');
  assert.equal(await plugin.load(join(root, 'App.mjs')), null);
  await writeFile(join(root, 'net/net/Engine.mjs'), 'globalThis.engineRegistered = "again";\n');
  const later = new Date(Date.now() + 5000);
  await utimes(join(root, 'net/net/Engine.mjs'), later, later);
  assert.match((await plugin.load(runtime)).code, /engineRegistered = ["']again["']/);
}));
