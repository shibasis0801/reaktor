import { BrowserToolHost } from 'reaktor-reaktor-mcp';
export { createBrowserToolGraph } from 'reaktor-reaktor-mcp';

export interface ToolDefinition {
  name: string;
  description: string;
  inputSchema?: Record<string, unknown>;
  title?: string;
  outputSchema?: Record<string, unknown>;
  readOnly?: boolean;
  idempotent?: boolean;
  destructive?: boolean;
  openWorld?: boolean;
  untrustedContent?: boolean;
  consequential?: boolean;
}

export interface BrowserTool extends ToolDefinition {
  execute(input: Record<string, unknown>, signal: AbortSignal): Promise<unknown> | unknown;
}

export interface ModelContext {
  registerTool(tool: {
    name: string;
    title?: string;
    description: string;
    inputSchema: Record<string, unknown>;
    annotations: { readOnlyHint: boolean; untrustedContentHint: boolean; consequentialHint: boolean };
    execute(input: Record<string, unknown>, options: { signal: AbortSignal }): Promise<unknown>;
  }, options: { signal: AbortSignal }): Promise<void>;
}

export class WebMcpAdapter {
  private readonly host: BrowserToolHost;

  constructor(graph: ConstructorParameters<typeof BrowserToolHost>[0], tools: readonly BrowserTool[]) {
    const handlers = new Map(tools.map(tool => [tool.name, tool]));
    this.host = new BrowserToolHost(graph, JSON.stringify(tools.map(({ execute, ...definition }) => definition)), async (name, input, signal) => {
      const handler = handlers.get(name);
      if (!handler) throw new Error(`Unknown tool: ${name}`);
      const value = await handler.execute(JSON.parse(input), signal);
      const serialized = JSON.stringify(value);
      if (serialized === undefined) throw new Error('Tool results must be JSON serializable');
      return serialized;
    });
  }

  register(context?: ModelContext): Promise<boolean> { return this.host.register(context); }
  get isClosed(): boolean { return this.host.isClosed; }
  async handleMcp(body: string): Promise<string | null> { return await this.host.handleMcp(body) ?? null; }
  close(): void { this.host.close(); }
}
