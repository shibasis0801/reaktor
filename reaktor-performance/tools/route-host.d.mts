import type { IncomingMessage, ServerResponse } from 'node:http';

export interface HostedRequest {
  url(): string;
  method(): string;
  headers(): Record<string, string>;
  postData(): string | null;
  postDataJSON(): any;
}

export interface HostedRoute {
  request(): HostedRequest;
  fulfill(options?: { status?: number; contentType?: string; body?: string | Buffer; json?: unknown; headers?: Record<string, string> }): Promise<void>;
  abort(reason?: string): Promise<void>;
  continue(): Promise<void>;
  fallback(): Promise<void>;
}

export interface HostedSocket {
  send(message: string | Buffer): void;
  onMessage(listener: (message: string) => void): void;
  onClose(listener: () => void): void;
  close(): Promise<void>;
}

export interface HostedPage {
  route(pattern: string | RegExp, handler: (route: HostedRoute, request: HostedRequest) => unknown): Promise<void>;
  unroute(pattern: string | RegExp): Promise<void>;
  routeWebSocket(pattern: string | RegExp, handler: (socket: HostedSocket) => unknown): Promise<void>;
}

export interface RouteHost {
  url: string;
  page: HostedPage;
  clear(): void;
  stop(): Promise<void>;
}

export function staticFiles(dist: string, options?: { immutable?: boolean }): (request: IncomingMessage, response: ServerResponse) => void;
export function globPattern(glob: string): RegExp;
export function startRouteHost(options: { dist?: string; port?: number; host?: string; immutable?: boolean; latencyMs?: number }): Promise<RouteHost>;
