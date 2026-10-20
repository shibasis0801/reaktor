/** Opt-in request/response timing. Payloads are never retained or serialized. */
export function installWorkerWebProbe({ name = 'blueprint-elk', maxSamples = 10000 } = {}) {
  if (globalThis.__reaktorWorkers || !globalThis.Worker) return;
  const Original = globalThis.Worker;
  const limit = Math.max(1, Math.floor(maxSamples));
  let samples = [], pending = new Map(), next = 0, created = 0, terminated = 0, dropped = 0;
  const append = sample => { if (samples.length < limit) samples.push(sample); else dropped++; };
  const finish = (key, extra) => {
    const job = pending.get(key);
    if (!job) return;
    pending.delete(key);
    append({ ...job, elapsedMs: performance.now() - job.at, ...extra });
  };
  globalThis.Worker = class extends Original {
    constructor(url, options) {
      super(url, options);
      if (options?.name !== name) return;
      const worker = ++next;
      created++;
      let retired = false;
      this.addEventListener('message', ({ data }) => {
        if (typeof data?.id !== 'number') return;
        finish(`${worker}:${data.id}`, { computeMs: Number.isFinite(data.ms) ? data.ms : null, failed: !!data.error });
      });
      this.addEventListener('error', () => {
        for (const [key, job] of pending) if (job.worker === worker) finish(key, { failed: true });
      });
      const post = this.postMessage, terminate = this.terminate;
      this.postMessage = function(data, ...rest) {
        const key = typeof data?.id === 'number' ? `${worker}:${data.id}` : null;
        const at = performance.now();
        if (key && pending.size < limit) pending.set(key, { worker, request: data.id, at });
        else if (key) dropped++;
        try { return post.call(this, data, ...rest); }
        catch (error) { if (key) finish(key, { failed: true }); throw error; }
        finally { const job = pending.get(key); if (job) job.postCpuMs = performance.now() - at; }
      };
      this.terminate = function(...args) {
        try { return terminate.apply(this, args); }
        finally {
          if (!retired) { retired = true; terminated++; }
          for (const [key, job] of pending) if (job.worker === worker) finish(key, { canceled: true });
        }
      };
    }
  };
  globalThis.__reaktorWorkers = {
    reset() { samples = []; pending.clear(); dropped = 0; },
    snapshot() { return { jobs: samples.slice(), pending: pending.size, created, terminated, active: created - terminated, dropped }; },
  };
}
