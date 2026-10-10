import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { staticAssets, reactDevtools } from '../api/vite.ts';

async function fixture(action) {
  const root = await mkdtemp(join(tmpdir(), 'dependeasy-assets-'));
  try {
    await mkdir(join(root, 'fonts', 'nested'), { recursive: true });
    await writeFile(join(root, 'fonts', 'nested', 'one.woff2'), 'font');
    await writeFile(join(root, 'fonts', 'ignored.css'), 'ignored');
    await writeFile(join(root, 'worker.mjs'), 'worker');
    await symlink(join(root, 'fonts'), join(root, 'fonts', 'cycle'));
    await action(root);
  } finally { await rm(root, { recursive: true, force: true }); }
}

const options = root => ({ root, files: { 'worker.mjs': 'worker.mjs' },
  directories: [{ from: 'fonts', to: 'fonts', extension: '.woff2' }] });

test('development and bundle share filtered nested assets without following dependency links', () => fixture(async root => {
  const plugin = staticAssets(options(root));
  let middleware;
  plugin.configureServer({ middlewares: { use(value) { middleware = value; } } });
  const headers = {}, response = { setHeader(key, value) { headers[key] = value; }, end(body) { this.body = body; } };
  await middleware({ url: '/fonts/nested/one.woff2?version=1' }, response, () => assert.fail('declared asset did not resolve'));
  assert.equal(headers['Content-Type'], 'font/woff2');
  assert.equal(String(response.body), 'font');
  let forwarded = false;
  await middleware({ url: '/fonts/ignored.css' }, response, () => { forwarded = true; });
  assert.equal(forwarded, true);
  const emitted = [];
  plugin.generateBundle.call({ emitFile(asset) { emitted.push(asset); } });
  assert.deepEqual(emitted.map(asset => asset.fileName).sort(), ['fonts/nested/one.woff2', 'worker.mjs']);
  assert.equal(String(emitted.find(asset => asset.fileName === 'fonts/nested/one.woff2').source), 'font');
}));

test('development fixtures are never emitted and missing declared producers fail explicitly', () => fixture(async root => {
  const plugin = staticAssets({ root, files: { 'api/example': 'worker.mjs', 'missing.mjs': 'missing.mjs' }, developmentOnly: true });
  let middleware;
  plugin.configureServer({ middlewares: { use(value) { middleware = value; } } });
  const headers = {}, response = { setHeader(key, value) { headers[key] = value; }, end(body) { this.body = body; } };
  await middleware({ url: '/api/example' }, response, () => assert.fail());
  assert.equal(headers['Cache-Control'], 'no-store');
  await middleware({ url: '/missing.mjs' }, response, () => assert.fail());
  assert.equal(response.statusCode, 503);
  plugin.generateBundle.call({ emitFile() { assert.fail('development fixture leaked into the bundle'); } });
  assert.throws(() => staticAssets({ root, files: { '../outside': 'worker.mjs' } }), /inside the bundle/);
  const duplicate = staticAssets({ ...options(root), files: { 'fonts/nested/one.woff2': 'worker.mjs' } });
  assert.throws(() => duplicate.generateBundle.call({ emitFile() {} }), /declared twice/);
}));

test('DevTools is opt-in and adds the declared backend before its connection bootstrap', () => {
  const connect = (backend, options) => backend.connect(options);
  assert.equal(reactDevtools('/unused', connect), false);
  const plugin = reactDevtools('/unused', connect, { enabled: true, port: 8098 });
  const tags = plugin.transformIndexHtml.handler();
  assert.equal(tags[0].attrs.src, '/react-devtools-backend.js');
  assert.equal(tags[0].injectTo, 'head-prepend');
  assert.match(tags[1].children, /port: 8098/);
});
