export interface LoopbackMcpPairing { endpoint: string; token: string }
export interface LoopbackAnswer {
  requestId: string;
  provider: 'Antigravity';
  model: string;
  reply: string;
  elapsedMillis: number;
}

/** Browser client for the explicitly paired inference surface, never the workspace owner API. */
export class LoopbackMcpClient {
  private sequence = 0;
  private readonly endpoint: string;
  constructor(private readonly pairing: LoopbackMcpPairing) {
    const url = new URL(pairing.endpoint);
    if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname) ||
      url.pathname !== '/browser/mcp' || url.username || url.password || url.search || url.hash || pairing.token.length < 32) {
      throw new Error('Enter the loopback endpoint and pairing code from reaktor-agent workspace browser.');
    }
    this.endpoint = url.href;
  }

  private async rpc(method: string, params?: object, notification = false): Promise<any> {
    const id = notification ? undefined : ++this.sequence;
    let response: Response;
    try {
      response = await fetch(this.endpoint, {
        method: 'POST', credentials: 'omit', redirect: 'error', cache: 'no-store', referrerPolicy: 'no-referrer',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${this.pairing.token}`, 'MCP-Protocol-Version': '2025-11-25' },
        body: JSON.stringify({ jsonrpc: '2.0', id, method, params }), signal: AbortSignal.timeout(100_000),
      });
    } catch {
      throw new Error('Cannot reach local Reaktor. Start the workspace owner and allow local network access in your browser.');
    }
    if (!response.ok) throw new Error(response.status === 401 || response.status === 403
      ? 'Pairing was refused. Create a new pairing for this website origin.' : `Local Reaktor returned HTTP ${response.status}.`);
    if (notification) return;
    const message = await response.json() as { jsonrpc?: string; id?: number; error?: { message?: string }; result?: unknown };
    if (message.id !== id || message.jsonrpc !== '2.0') throw new Error('Invalid response from local Reaktor.');
    if (message.error) throw new Error(message.error.message || 'Local Reaktor refused the request.');
    return message.result;
  }

  async connect(): Promise<void> {
    await this.rpc('initialize', { protocolVersion: '2025-11-25', capabilities: {}, clientInfo: { name: 'browser-inference', version: '1.0.0' } });
    await this.rpc('notifications/initialized', {}, true);
    const result = await this.rpc('tools/list');
    if (!Array.isArray(result.tools) || result.tools.length !== 1 || result.tools[0].name !== 'inference.ask') {
      throw new Error('This endpoint does not expose the paired inference tool. Upgrade reaktor-mcp.');
    }
  }

  async ask(question: string): Promise<LoopbackAnswer> {
    const requestId = crypto.randomUUID();
    const result = await this.rpc('tools/call', { name: 'inference.ask', arguments: { question, requestId } });
    const value = result.structuredContent;
    if (result.isError) throw new Error(value?.error || result.content?.[0]?.text || 'Antigravity could not answer.');
    if (value?.requestId !== requestId || value.provider !== 'Antigravity' || typeof value.reply !== 'string' ||
      typeof value.model !== 'string' || typeof value.elapsedMillis !== 'number') throw new Error('Invalid answer from local Reaktor.');
    return value;
  }
}
