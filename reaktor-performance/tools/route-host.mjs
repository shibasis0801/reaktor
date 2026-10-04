import { createHash } from 'node:crypto';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { extname, join, normalize, resolve } from 'node:path';
import { brotliCompressSync, constants, gzipSync } from 'node:zlib';

const types = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.json': 'application/json',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.webp': 'image/webp', '.ico': 'image/x-icon', '.woff2': 'font/woff2',
  '.wasm': 'application/wasm', '.map': 'application/json', '.txt': 'text/plain',
};

export function staticFiles(dist, options = {}) {
  const root = resolve(dist);
  const cache = new Map();
  const load = path => {
    const stat = statSync(path);
    const known = cache.get(path);
    if (known && known.mtime === stat.mtimeMs) return known;
    const body = readFileSync(path);
    const compressible = /\.(html|js|mjs|css|json|svg|map|txt)$/.test(path);
    const entry = {
      mtime: stat.mtimeMs,
      body,
      br: compressible ? brotliCompressSync(body, { params: { [constants.BROTLI_PARAM_QUALITY]: 9 } }) : null,
      gzip: compressible ? gzipSync(body, { level: 9 }) : null,
      etag: `"${createHash('sha1').update(body).digest('hex')}"`,
      type: types[extname(path)] ?? 'application/octet-stream',
    };
    cache.set(path, entry);
    return entry;
  };
  return (request, response) => {
    const url = new URL(request.url ?? '/', 'http://local');
    let file = normalize(join(root, decodeURIComponent(url.pathname)));
    if (!file.startsWith(root) || !existsSync(file) || statSync(file).isDirectory()) {
      if (extname(url.pathname) && !url.pathname.endsWith('.html')) {
        response.writeHead(404, { 'content-type': 'text/plain', 'cache-control': 'no-store' });
        response.end('missing');
        return;
      }
      file = join(root, 'index.html');
    }
    const entry = load(file);
    const hashed = url.pathname.startsWith('/assets/');
    const headers = {
      'content-type': entry.type,
      'cache-control': options.immutable && hashed ? 'public, max-age=31536000, immutable' : 'public, max-age=0, must-revalidate',
      etag: entry.etag,
      vary: 'accept-encoding',
    };
    if (request.headers['if-none-match'] === entry.etag) {
      response.writeHead(304, headers);
      response.end();
      return;
    }
    const accepts = String(request.headers['accept-encoding'] ?? '');
    let body = entry.body;
    if (entry.br && /\bbr\b/.test(accepts)) { body = entry.br; headers['content-encoding'] = 'br'; }
    else if (entry.gzip && /\bgzip\b/.test(accepts)) { body = entry.gzip; headers['content-encoding'] = 'gzip'; }
    headers['content-length'] = String(body.length);
    response.writeHead(200, headers);
    response.end(request.method === 'HEAD' ? undefined : body);
  };
}

export function globPattern(glob) {
  let source = '';
  for (let index = 0; index < glob.length; index += 1) {
    const char = glob[index];
    if (char === '*' && glob[index + 1] === '*') { source += '.*'; index += 1; }
    else if (char === '*') source += '[^/]*';
    else if (char === '{') source += '(';
    else if (char === '}') source += ')';
    else if (char === ',' && source.includes('(')) source += '|';
    else source += char.replace(/[.+^$()|[\]\\?]/g, '\\$&');
  }
  return new RegExp(`^${source}$`);
}

const matcher = pattern => (typeof pattern === 'string' ? globPattern(pattern) : pattern);

function readBody(request) {
  return new Promise(done => {
    const parts = [];
    request.on('data', part => parts.push(part));
    request.on('end', () => done(Buffer.concat(parts)));
    request.on('error', () => done(Buffer.alloc(0)));
  });
}

function routeShim(request, response, url, body, next) {
  let handled = false;
  const headers = Object.fromEntries(Object.entries(request.headers).map(([name, value]) => [name.toLowerCase(), Array.isArray(value) ? value.join(', ') : String(value ?? '')]));
  return {
    handled: () => handled,
    request: () => ({
      url: () => url,
      method: () => request.method ?? 'GET',
      headers: () => headers,
      postData: () => (body.length ? body.toString('utf8') : null),
      postDataJSON: () => (body.length ? JSON.parse(body.toString('utf8')) : null),
    }),
    async fulfill(options = {}) {
      handled = true;
      const payload = options.json !== undefined ? JSON.stringify(options.json) : options.body ?? '';
      const extra = Object.fromEntries(Object.entries(options.headers ?? {}).map(([name, value]) => [name.toLowerCase(), value]));
      const type = options.contentType ?? extra['content-type'] ?? (options.json !== undefined ? 'application/json' : 'text/plain');
      const buffer = Buffer.isBuffer(payload) ? payload : Buffer.from(payload);
      response.writeHead(options.status ?? 200, { 'cache-control': 'no-store', ...extra, 'content-type': type, 'content-length': String(buffer.length) });
      response.end(buffer);
    },
    async abort() {
      handled = true;
      request.socket.destroy();
    },
    async continue() { handled = true; await next(); },
    async fallback() { await next(); handled = true; },
  };
}

function acceptKey(key) {
  return createHash('sha1').update(`${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`).digest('base64');
}

function frame(opcode, payload) {
  const length = payload.length;
  const head = length < 126 ? Buffer.from([0x80 | opcode, length]) : length < 65536 ? Buffer.from([0x80 | opcode, 126, length >> 8, length & 255]) : Buffer.concat([Buffer.from([0x80 | opcode, 127]), (() => { const size = Buffer.alloc(8); size.writeBigUInt64BE(BigInt(length)); return size; })()]);
  return Buffer.concat([head, payload]);
}

function socketShim(socket) {
  const messages = [];
  const closes = [];
  let buffered = Buffer.alloc(0);
  let fragments = [];
  let open = true;
  const finish = () => {
    if (!open) return;
    open = false;
    closes.forEach(listener => listener());
  };
  socket.on('data', chunk => {
    buffered = Buffer.concat([buffered, chunk]);
    for (;;) {
      if (buffered.length < 2) return;
      const fin = (buffered[0] & 0x80) !== 0;
      const opcode = buffered[0] & 0x0f;
      const masked = (buffered[1] & 0x80) !== 0;
      let length = buffered[1] & 0x7f;
      let offset = 2;
      if (length === 126) { if (buffered.length < 4) return; length = buffered.readUInt16BE(2); offset = 4; }
      else if (length === 127) { if (buffered.length < 10) return; length = Number(buffered.readBigUInt64BE(2)); offset = 10; }
      const maskAt = offset;
      if (masked) offset += 4;
      if (buffered.length < offset + length) return;
      const payload = Buffer.from(buffered.subarray(offset, offset + length));
      if (masked) for (let index = 0; index < payload.length; index += 1) payload[index] ^= buffered[maskAt + (index % 4)];
      buffered = buffered.subarray(offset + length);
      if (opcode === 0x8) { if (open) socket.write(frame(0x8, Buffer.alloc(0))); socket.end(); finish(); return; }
      if (opcode === 0x9) { socket.write(frame(0xa, payload)); continue; }
      if (opcode === 0xa) continue;
      fragments.push(payload);
      if (!fin) continue;
      const text = Buffer.concat(fragments).toString('utf8');
      fragments = [];
      messages.forEach(listener => listener(text));
    }
  });
  socket.on('close', finish);
  socket.on('error', finish);
  return {
    send(message) { if (open) socket.write(frame(typeof message === 'string' ? 0x1 : 0x2, Buffer.from(message))); },
    onMessage(listener) { messages.push(listener); },
    onClose(listener) { closes.push(listener); },
    async close() { if (open) { socket.write(frame(0x8, Buffer.alloc(0))); socket.end(); } finish(); },
  };
}

export function startRouteHost(options) {
  const serveStatic = options.dist ? staticFiles(options.dist, { immutable: options.immutable }) : null;
  let routes = [];
  let sockets = [];
  const live = new Set();
  const latency = Math.max(0, Number(options.latencyMs ?? 0));
  const server = createServer(async (request, response) => {
    if (latency) await new Promise(done => setTimeout(done, latency));
    const base = `http://${request.headers.host ?? 'local'}`;
    const url = new URL(request.url ?? '/', base).toString();
    const candidates = routes.filter(route => route.pattern.test(url)).reverse();
    const body = candidates.length ? await readBody(request) : Buffer.alloc(0);
    let index = 0;
    const next = async () => {
      const route = candidates[index++];
      if (route) {
        const shim = routeShim(request, response, url, body, next);
        await route.handler(shim, shim.request());
        if (!shim.handled() && !response.writableEnded) await next();
        return;
      }
      if (new URL(url).pathname.startsWith('/api/') || new URL(url).pathname.startsWith('/auth/') || !serveStatic) {
        response.writeHead(404, { 'content-type': 'application/json', 'cache-control': 'no-store' });
        response.end('{"error":"no route"}');
        return;
      }
      serveStatic(request, response);
    };
    try {
      await next();
    } catch (error) {
      if (!response.headersSent) response.writeHead(500, { 'content-type': 'text/plain' });
      response.end(String(error instanceof Error ? error.stack ?? error.message : error));
    }
  });
  server.on('upgrade', (request, socket) => {
    const url = new URL(request.url ?? '/', `http://${request.headers.host ?? 'local'}`).toString();
    const route = [...sockets].reverse().find(item => item.pattern.test(url));
    const key = request.headers['sec-websocket-key'];
    if (!route || typeof key !== 'string') { socket.end('HTTP/1.1 404 Not Found\r\n\r\n'); return; }
    socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${acceptKey(key)}\r\n\r\n`);
    socket.setNoDelay(true);
    live.add(socket);
    socket.on('close', () => live.delete(socket));
    route.handler(socketShim(socket));
  });
  server.on('connection', socket => socket.setNoDelay(true));
  const page = {
    async route(pattern, handler) { routes.push({ pattern: matcher(pattern), handler }); },
    async unroute(pattern) { const source = String(matcher(pattern)); routes = routes.filter(route => String(route.pattern) !== source); },
    async routeWebSocket(pattern, handler) { sockets.push({ pattern: matcher(pattern), handler }); },
  };
  return new Promise((done, fail) => {
    server.once('error', fail);
    server.listen(options.port ?? 0, options.host ?? '127.0.0.1', () => {
      const address = server.address();
      done({
        url: `http://${options.host ?? '127.0.0.1'}:${address.port}`,
        page,
        clear() {
          routes = [];
          sockets = [];
          live.forEach(socket => socket.destroy());
          live.clear();
        },
        stop() {
          live.forEach(socket => socket.destroy());
          return new Promise(closed => server.close(() => closed()));
        },
      });
    });
  });
}
