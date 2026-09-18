#!/usr/bin/env node

import {createReadStream} from 'node:fs';
import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import process from 'node:process';

const host = process.env.CLAW_IN_ONE_WEB_HOST;
const requestedPort = Number(process.env.CLAW_IN_ONE_WEB_PORT);
if (host !== '127.0.0.1' || !Number.isSafeInteger(requestedPort) || requestedPort < 0 || requestedPort > 65535) {
  throw new Error('server requires a bounded loopback host and port');
}

const root = path.resolve('dist');
const contentTypes = new Map([
  ['.css', 'text/css; charset=utf-8'],
  ['.html', 'text/html; charset=utf-8'],
  ['.js', 'text/javascript; charset=utf-8'],
  ['.json', 'application/json; charset=utf-8'],
  ['.svg', 'image/svg+xml'],
]);

const server = http.createServer(async (request, response) => {
  if (request.method === 'GET' && request.url === '/api/hello') {
    response.writeHead(200, {'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store'});
    response.end(JSON.stringify({message: 'Frontend and backend are running together.'}));
    return;
  }
  if (request.method === 'GET' && request.url === '/.claw-in-one/health') {
    response.writeHead(200, {'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store'});
    response.end(JSON.stringify({status: 'ready'}));
    return;
  }
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    response.writeHead(405, {'content-type': 'text/plain; charset=utf-8'});
    response.end('Method not allowed');
    return;
  }
  const pathname = new URL(request.url ?? '/', 'http://localhost').pathname;
  const requested = pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, '');
  let file = path.resolve(root, requested);
  if (!file.startsWith(`${root}${path.sep}`) && file !== root) {
    response.writeHead(404).end();
    return;
  }
  let stat = await fs.stat(file).catch(() => null);
  if (!stat?.isFile()) {
    file = path.join(root, 'index.html');
    stat = await fs.stat(file).catch(() => null);
  }
  if (!stat?.isFile()) {
    response.writeHead(404).end();
    return;
  }
  response.writeHead(200, {
    'content-type': contentTypes.get(path.extname(file)) ?? 'application/octet-stream',
    'cache-control': file.endsWith('index.html') ? 'no-store' : 'public, max-age=31536000, immutable',
  });
  if (request.method === 'HEAD') response.end();
  else createReadStream(file).pipe(response);
});

server.listen(requestedPort, host, () => {
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('server address is unavailable');
  process.stdout.write(`${JSON.stringify({status: 'listening', host, port: address.port})}\n`);
});

const shutdown = () => server.close(() => process.exit(0));
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
