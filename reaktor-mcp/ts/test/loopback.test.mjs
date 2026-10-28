import { test } from 'node:test';
import assert from 'node:assert/strict';
import { discoverReaktorPlus } from '../../src/jsMain/typescript/loopback.ts';

const endpoint = 'http://127.0.0.1:9317/browser/mcp';
const discovery = 'http://127.0.0.1:9317/reaktor-plus/v1';
const token = 'origin-specific-credential-1234567890';
const admission = { service: 'reaktor-plus', version: 1, endpoint, token, capabilities: ['inference.ask'] };
const json = value => new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } });

test('discovers the fixed service and keeps request identity across an uncertain reply', async t => {
  const calls = [];
  let fail = true;
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    assert.equal(init.credentials, 'omit');
    assert.equal(init.redirect, 'error');
    assert.equal(init.cache, 'no-store');
    assert.equal(init.referrerPolicy, 'no-referrer');
    if (url === discovery) return json(admission);
    assert.equal(url, endpoint);
    assert.equal(init.headers.Authorization, `Bearer ${token}`);
    const call = JSON.parse(init.body);
    calls.push(call);
    if (call.method === 'notifications/initialized') return new Response(null, { status: 202 });
    if (call.method === 'tools/call' && fail) { fail = false; throw new TypeError('lost reply'); }
    const result = call.method === 'initialize' ? { protocolVersion: '2025-11-25' }
      : call.method === 'tools/list' ? { tools: [{ name: 'inference.ask' }] }
      : { structuredContent: { requestId: call.params.arguments.requestId, provider: 'Antigravity', model: 'gemini-4-argon-high', reply: '391', elapsedMillis: 20 } };
    return json({ jsonrpc: '2.0', id: call.id, result });
  });
  const client = await discoverReaktorPlus();
  const requestId = crypto.randomUUID();
  await assert.rejects(client.ask('17 times 23?', requestId), /Cannot reach local Reaktor/);
  assert.equal((await client.ask('17 times 23?', requestId)).reply, '391');
  const asks = calls.filter(call => call.method === 'tools/call');
  assert.deepEqual(asks.map(call => call.params.arguments), Array(2).fill({ question: '17 times 23?', requestId }));
});

test('refuses discovery that redirects inference outside the fixed loopback endpoint', async t => {
  let requests = 0;
  t.mock.method(globalThis, 'fetch', async () => { requests++; return json({ ...admission, endpoint: 'https://example.test/browser/mcp' }); });
  await assert.rejects(discoverReaktorPlus(), /compatible Reaktor\+ service/);
  assert.equal(requests, 1);
});

test('refuses a workspace tool surface in place of isolated inference', async t => {
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    if (url === discovery) return json(admission);
    const call = JSON.parse(init.body);
    if (call.method === 'notifications/initialized') return new Response(null, { status: 202 });
    return json({ jsonrpc: '2.0', id: call.id, result: call.method === 'initialize' ? {} : { tools: [{ name: 'inference.ask' }, { name: 'kernel' }] } });
  });
  await assert.rejects(discoverReaktorPlus(), /inference tool/);
});

test('reports an absent service without attempting inference', async t => {
  let requests = 0;
  t.mock.method(globalThis, 'fetch', async url => { requests++; assert.equal(url, discovery); throw new TypeError('refused'); });
  await assert.rejects(discoverReaktorPlus(), /Reaktor\+ is unavailable on this computer/);
  assert.equal(requests, 1);
});
