#!/usr/bin/env node
import { createServer } from 'node:http';
import { resolve } from 'node:path';
import { staticFiles } from './route-host.mjs';

function usage(message) {
  if (message) console.error(`error: ${message}`);
  console.error([
    'usage: node tools/static-server.mjs --dist <dir> [--port <n>] [--host <h>] [--immutable] [--latency <ms>]',
    '',
    'Serves a production web build the way a static-assets CDN does: brotli or gzip by Accept-Encoding,',
    'ETags with 304 revalidation, and index.html for any extensionless path (single-page app).',
    'API paths (/api/, /auth/) answer 404; use route-host.mjs to serve a fake API from the same origin.',
    '',
    '  --dist <dir>     build output directory',
    '  --port <n>       port (default 4380)',
    '  --host <h>       interface (default 127.0.0.1)',
    '  --immutable      hashed /assets/ files get max-age=31536000, immutable (default: max-age=0, must-revalidate)',
    '  --latency <ms>   delay every response by this many milliseconds',
  ].join('\n'));
  process.exit(2);
}

function parseArgs(argv) {
  const args = { dist: null, port: 4380, host: '127.0.0.1', immutable: false, latency: 0 };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--dist') args.dist = argv[++index];
    else if (arg === '--port') args.port = Number(argv[++index]);
    else if (arg === '--host') args.host = argv[++index];
    else if (arg === '--immutable') args.immutable = true;
    else if (arg === '--latency') args.latency = Number(argv[++index]);
    else if (arg === '--help' || arg === '-h') usage();
    else usage(`unknown argument: ${arg}`);
  }
  if (!args.dist) usage('--dist is required');
  return args;
}

const args = parseArgs(process.argv.slice(2));
const serve = staticFiles(args.dist, { immutable: args.immutable });
createServer((request, response) => {
  const pathname = new URL(request.url ?? '/', 'http://local').pathname;
  const answer = () => {
    if (pathname.startsWith('/api/') || pathname.startsWith('/auth/')) {
      response.writeHead(404, { 'content-type': 'application/json', 'cache-control': 'no-store' });
      response.end('{"error":"not served by the static perf server"}');
      return;
    }
    serve(request, response);
  };
  if (args.latency > 0) setTimeout(answer, args.latency);
  else answer();
}).listen(args.port, args.host, () => {
  process.stdout.write(`static perf server ${resolve(args.dist)} on http://${args.host}:${args.port}${args.immutable ? ' (immutable assets)' : ''}\n`);
});
