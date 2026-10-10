import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pagefindTools } from '../../src/jsMain/typescript/pagefind.ts';
import { buildPagefind, documentationRecords, closePagefind } from '../scripts/pagefind.mjs';

const signal = () => new AbortController().signal;

test('search loads only the requested result window and retains provenance', async () => {
  const loaded = [];
  let initialized = 0, options;
  const tools = pagefindTools(async () => ({
    init() { initialized++; },
    async search(_query, value) {
      options = value;
      return { unfilteredResultCount: 42, results: Array.from({ length: 8 }, (_, id) => ({ id: String(id), async data() {
        loaded.push(id);
        return { url: `/docs/adapter#${id}`, plain_excerpt: 'Public excerpt', meta: { title: 'Adapter', document: 'adapter', revision: 'v1' } };
      } })) };
    },
    async filters() { return { kind: { guide: 8 } }; },
  }));
  const found = await tools[0].execute({ query: ' adapter ', offset: 2, limit: 2, filters: { kind: 'guide' } }, signal());
  assert.deepEqual(loaded, [2, 3]);
  assert.equal(found.total, 8);
  assert.equal(found.unfilteredTotal, 42);
  assert.equal(found.results[0].metadata.revision, 'v1');
  assert.deepEqual(options, { filters: { kind: 'guide' } });
  await tools[1].execute({}, signal());
  assert.equal(initialized, 1);
});

test('malformed arguments never load the index', async () => {
  let loads = 0;
  const tools = pagefindTools(async () => { loads++; throw new Error('should not load'); });
  for (const input of [{ query: '' }, { query: 'x', limit: 21 }, { query: 'x', offset: -1 }, { query: 'x', filters: [] }, { query: 'x', filters: { kind: [1] } }, { query: 'x', extra: true }]) {
    await assert.rejects(() => tools[0].execute(input, signal()));
  }
  await assert.rejects(() => tools[1].execute({ extra: true }, signal()));
  assert.equal(loads, 0);
});

test('cancellation discards a search completion and load failures can be retried', async () => {
  let loads = 0, finish;
  const tools = pagefindTools(async () => {
    if (++loads === 1) throw new Error('Index unavailable');
    return { async search() { return new Promise(resolve => { finish = resolve; }); }, async filters() { return {}; } };
  });
  await assert.rejects(() => tools[0].execute({ query: 'x' }, signal()), /Index unavailable/);
  const abort = new AbortController();
  const pending = tools[0].execute({ query: 'x' }, abort.signal);
  await new Promise(resolve => setImmediate(resolve));
  abort.abort();
  finish({ results: [] });
  await assert.rejects(() => pending, { name: 'AbortError' });
  assert.equal(loads, 2);
});

test('public documentation builds a real index with stable section URLs; private documents are excluded', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'reaktor-pagefind-'));
  const docs = [{ id: 'adapter', title: 'Adapters', url: 'https://example.com/docs/adapter', visibility: 'public', revision: 'v1', sections: [{ id: 'adapter:ownership', anchor: 'ownership', title: 'Ownership', text: 'Graph ownership manages cancellation.' }] },
    { id: 'private/secret', visibility: 'private', sections: [{ text: 'SECRET' }] }];
  try {
    const records = documentationRecords(docs, 'corpus-v1');
    assert.equal(records.length, 1);
    assert.equal(records[0].url, 'https://example.com/docs/adapter#ownership');
    assert.equal(records[0].meta.section, 'adapter:ownership');
    await buildPagefind(records, join(directory, 'index'));
    assert.match(await readFile(join(directory, 'index/pagefind.js'), 'utf8'), /search/);
    const manifest = JSON.parse(await readFile(join(directory, 'index/pagefind-entry.json'), 'utf8'));
    assert.equal(manifest.languages.en.page_count, 1);
  } finally { await closePagefind(); await rm(directory, { recursive: true, force: true }); }
});
