import test from "node:test";
import assert from "node:assert/strict";
import { ReaktorWebClient } from "../dist/index.js";

const connection = {
  protocol: 1, session: "session", epoch: 7,
  operations: [{ operation: "document.open", contract: "document", version: 1, request: { name: "Open", fingerprint: "request" }, response: { name: "Opened", fingerprint: "response" } }],
  events: [{ operation: "document.changed", contract: "document", version: 1, schema: "changed" }]
};
test("typed request uses host schema and matches correlation/epoch", async () => {
  const sent = []; const client = new ReaktorWebClient(body => sent.push(JSON.parse(body))); client.connect(connection);
  const result = client.invoke("document.open", { id: "one" });
  const request = sent[0];
  assert.equal(request.schema, "request");
  assert.deepEqual(Object.keys(request).sort(), ["contract", "epoch", "id", "kind", "operation", "payload", "protocol", "schema", "session", "version"]);
  client.receive({ ...request, kind: "reply", replyTo: request.id, epoch: 6, payload: "stale" });
  client.receive({ ...request, kind: "reply", replyTo: request.id, payload: "opened" });
  assert.equal(await result, "opened"); client.close();
});
test("cancellation sends correlation and rejects the pending call", async () => {
  const sent = []; const client = new ReaktorWebClient(body => sent.push(JSON.parse(body))); client.connect(connection);
  const abort = new AbortController(); const result = client.invoke("document.open", {}, { signal: abort.signal }); abort.abort();
  await assert.rejects(result, /cancelled/); assert.equal(sent[1].replyTo, sent[0].id); client.close();
});
test("subscription releases on unsubscribe and document replacement", async () => {
  const sent = []; const events = []; const client = new ReaktorWebClient(body => sent.push(JSON.parse(body))); client.connect(connection);
  const subscription = client.subscribe("document.changed", value => events.push(value));
  const request = sent[0]; client.receive({ ...request, kind: "reply", replyTo: request.id });
  const stop = await subscription;
  client.receive({ ...request, kind: "event", replyTo: request.id, payload: 1 });
  stop(); client.receive({ ...request, kind: "event", replyTo: request.id, payload: 2 });
  assert.deepEqual(events, [1]); assert.equal(sent[1].kind, "unsubscribe");
  const pending = client.invoke("document.open", {}); client.connect({ ...connection, epoch: 8 });
  await assert.rejects(pending, /session changed/); client.close();
});
test("oversized payload and unknown operations never reach the native transport", async () => {
  const sent = []; const client = new ReaktorWebClient(body => sent.push(body)); client.connect(connection);
  await assert.rejects(client.invoke("shell.execute", {}), /Unknown/);
  await assert.rejects(client.invoke("document.open", "a".repeat(65_536)), /64 KiB/);
  assert.equal(sent.length, 0); client.close();
});
test("host revocation immediately rejects pending calls", async () => {
  const client = new ReaktorWebClient(() => {}); client.connect(connection);
  const pending = client.invoke("document.open", {});
  client.receive({ protocol: 1, session: "session", epoch: 7, id: "closed", kind: "closed" });
  await assert.rejects(pending, /session changed/);
  await assert.rejects(client.invoke("document.open", {}), /unavailable/);
});
