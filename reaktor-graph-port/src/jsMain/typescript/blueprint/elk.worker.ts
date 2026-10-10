import 'elkjs/lib/elk-worker.min.js';
import { compactElkResult, type ElkLaidGraph } from './engine';
import type { ElkGraphInput } from './types';

interface Reply {
  data?: unknown;
  error?: unknown;
}

interface WorkerScope {
  onmessage: ((event: MessageEvent) => void) | null;
  postMessage(message: unknown): void;
}

const scope = self as unknown as WorkerScope;
const dispatch = scope.onmessage;
const post = scope.postMessage.bind(scope);

function run(command: Record<string, unknown>): Reply {
  let reply: Reply = { error: 'elkjs did not answer' };
  scope.postMessage = message => { reply = message as Reply; };
  try {
    dispatch?.({ data: command } as MessageEvent);
  } finally {
    scope.postMessage = post;
  }
  return reply;
}

function describe(error: unknown): string {
  if (error instanceof Error) return error.message;
  if (error && typeof error === 'object' && 'message' in error) return String((error as { message: unknown }).message);
  return String(error);
}

run({ cmd: 'register', algorithms: ['layered'], id: 0 });

scope.onmessage = event => {
  const { id, graph } = event.data as { id: number; graph: ElkGraphInput };
  const started = performance.now();
  const reply = run({ cmd: 'layout', id, graph, layoutOptions: {}, options: {} });
  const ms = performance.now() - started;
  if (reply.error !== undefined || !reply.data) post({ id, error: describe(reply.error), ms });
  else post({ id, result: compactElkResult(reply.data as ElkLaidGraph), ms });
};

post({ ready: true });
