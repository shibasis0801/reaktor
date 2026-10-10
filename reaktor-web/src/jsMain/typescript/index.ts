export interface SchemaRef { name: string; fingerprint: string }
export interface OperationDescriptor {
  contract: string; version: number; operation: string;
  request: SchemaRef; response: SchemaRef;
}
export interface EventDescriptor { contract: string; version: number; operation: string; schema: string }
export interface Connection {
  protocol: 1; session: string; epoch: number;
  operations: OperationDescriptor[]; events: EventDescriptor[];
}
export interface Envelope {
  protocol: 1; id: string; kind: "invoke" | "subscribe" | "cancel" | "unsubscribe" | "reply" | "event" | "closed";
  session: string; epoch: number; operation?: string; contract?: string; version?: number;
  schema?: string; payload?: unknown; replyTo?: string; traceId?: string; error?: string | null;
}
type Transport = (body: string) => void;
type Pending = { resolve: (value: unknown) => void; reject: (error: Error) => void; timer: ReturnType<typeof setTimeout> };
export class ReaktorWebClient {
  private connection?: Connection;
  private pending = new Map<string, Pending>();
  private subscriptions = new Map<string, (payload: unknown) => void>();
  private serial = 0;
  constructor(private readonly transport: Transport) {}
  connect(connection: Connection): void {
    this.close();
    if (connection.protocol !== 1) throw new Error("Unsupported Reaktor protocol");
    this.connection = connection;
  }
  receive(message: Envelope): void {
    const connection = this.connection;
    if (!connection || message.protocol !== 1 || message.session !== connection.session || message.epoch !== connection.epoch) return;
    if (message.kind === "closed") { this.close(); return; }
    if (message.kind === "event") {
      if (message.replyTo) this.subscriptions.get(message.replyTo)?.(message.payload);
      return;
    }
    if (message.kind !== "reply" || !message.replyTo) return;
    const request = this.pending.get(message.replyTo);
    if (!request) return;
    clearTimeout(request.timer);
    this.pending.delete(message.replyTo);
    if (message.error) { this.subscriptions.delete(message.replyTo); request.reject(new Error(message.error)); }
    else request.resolve(message.payload);
  }
  async invoke<T>(operation: string, payload: unknown, options: { signal?: AbortSignal; traceId?: string } = {}): Promise<T> {
    const definition = this.requireConnection().operations.find(it => it.operation === operation);
    if (!definition) throw new Error("Unknown Reaktor operation");
    return this.request("invoke", { operation: definition.operation, contract: definition.contract,
      version: definition.version, schema: definition.request.fingerprint, payload, traceId: options.traceId }, options.signal) as Promise<T>;
  }
  async subscribe<T>(operation: string, listener: (payload: T) => void, signal?: AbortSignal): Promise<() => void> {
    const definition = this.requireConnection().events.find(it => it.operation === operation);
    if (!definition) throw new Error("Unknown Reaktor event");
    const id = this.nextId();
    this.subscriptions.set(id, listener as (payload: unknown) => void);
    try { await this.request("subscribe", definition, signal, id); }
    catch (error) { this.subscriptions.delete(id); throw error; }
    const unsubscribe = () => {
      if (!this.subscriptions.delete(id)) return;
      if (this.connection) this.send({ kind: "unsubscribe", id: this.nextId(), replyTo: id });
      signal?.removeEventListener("abort", unsubscribe);
    };
    if (signal?.aborted) unsubscribe(); else signal?.addEventListener("abort", unsubscribe, { once: true });
    return unsubscribe;
  }
  close(): void {
    for (const item of this.pending.values()) { clearTimeout(item.timer); item.reject(new Error("Reaktor session changed")); }
    this.pending.clear();
    this.subscriptions.clear();
    this.connection = undefined;
  }
  private requireConnection(): Connection {
    if (!this.connection) throw new Error("Reaktor bridge is unavailable in this document");
    return this.connection;
  }
  private nextId(): string { return "r_" + (++this.serial).toString(36); }
  private request(kind: "invoke" | "subscribe", data: Partial<Envelope>, signal?: AbortSignal, id = this.nextId()): Promise<unknown> {
    if (signal?.aborted) return Promise.reject(new Error("Request cancelled"));
    if (this.pending.size >= 32) return Promise.reject(new Error("Too many Reaktor requests"));
    const result = new Promise<unknown>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id); this.subscriptions.delete(id); reject(new Error("Reaktor request timed out"));
        if (this.connection) this.send({ kind: "cancel", id: this.nextId(), replyTo: id });
      }, 15_000);
      this.pending.set(id, { resolve, reject, timer });
      try { this.send({ ...data, kind, id }); } catch (error) {
        clearTimeout(timer); this.pending.delete(id); reject(error instanceof Error ? error : new Error(String(error)));
      }
    });
    const cancel = () => {
      const pending = this.pending.get(id);
      if (!pending) return;
      clearTimeout(pending.timer); this.pending.delete(id); this.subscriptions.delete(id);
      pending.reject(new Error("Request cancelled"));
      if (this.connection) this.send({ kind: "cancel", id: this.nextId(), replyTo: id });
    };
    signal?.addEventListener("abort", cancel, { once: true });
    return result.finally(() => signal?.removeEventListener("abort", cancel));
  }
  private send(message: Partial<Envelope> & Pick<Envelope, "id" | "kind">): void {
    const connection = this.requireConnection();
    const body = JSON.stringify({ ...message, protocol: 1, session: connection.session, epoch: connection.epoch, version: message.version ?? 1 });
    if (new TextEncoder().encode(body).byteLength > 65_536) throw new Error("Reaktor message exceeds 64 KiB");
    this.transport(body);
  }
}

declare global {
  interface Window {
    reaktor?: ReaktorWebClient;
    __reaktorConfig?: Connection;
    __reaktorConnect?: (connection: Connection) => void;
    __reaktorReceive?: (message: Envelope) => void;
    __reaktorNativePost?: (body: string) => void;
    ReaktorNative?: { postMessage: (body: string) => void };
    webkit?: { messageHandlers?: { reaktor?: { postMessage: (body: string) => void } } };
  }
}
export function installWebBridge(target: Window = window): ReaktorWebClient {
  const token = target.document.querySelector<HTMLMetaElement>('meta[name="reaktor-browser-token"]')?.content;
  const parentOrigin = target.document.querySelector<HTMLMetaElement>('meta[name="reaktor-parent-origin"]')?.content;
  const parameters = new URLSearchParams(target.location.hash.substring(1));
  const browserToken = token ?? parameters.get("reaktor_token");
  const browserParent = parentOrigin ?? parameters.get("reaktor_parent");
  let browserPort: MessagePort | undefined;
  const transport = (body: string) => {
    const post = target.__reaktorNativePost;
    if (browserPort) browserPort.postMessage({ kind: "request", body });
    else if (post) post(body);
    else if (target.ReaktorNative) target.ReaktorNative.postMessage(body);
    else if (target.webkit?.messageHandlers?.reaktor) target.webkit.messageHandlers.reaktor.postMessage(body);
    else throw new Error("No Reaktor native transport");
  };
  const client = new ReaktorWebClient(transport);
  target.reaktor = client;
  target.__reaktorConnect = connection => { target.__reaktorConfig = connection; client.connect(connection); target.dispatchEvent(new Event("reaktor-ready")); };
  target.__reaktorReceive = message => client.receive(message);
  if (browserToken && browserParent) {
    const channel = new MessageChannel();
    browserPort = channel.port1;
    browserPort.onmessage = event => {
      const message = event.data;
      if (message?.kind === "configure") target.__reaktorConnect?.(message.configuration);
      else if (message?.kind === "deliver") {
        client.receive(message.envelope);
        browserPort?.postMessage({ kind: "ack", id: message.id });
      } else if (message?.kind === "close") { client.close(); browserPort?.close(); }
    };
    target.parent.postMessage({ kind: "reaktor-bundle-ready", token: browserToken }, browserParent, [channel.port2]);
  }
  if (target.__reaktorConfig) target.__reaktorConnect(target.__reaktorConfig);
  target.addEventListener("pagehide", () => client.close(), { once: true });
  return client;
}
