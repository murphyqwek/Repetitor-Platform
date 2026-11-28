import WebSocket from 'ws';
import { educationOrigin } from './gateway';

type Frame = { command: string; body: string };

export function frames(socket: WebSocket, receive: (frame: Frame) => void) {
  let buffer = '';
  socket.on('message', data => {
    buffer += data.toString();
    let end: number;
    while ((end = buffer.indexOf('\0')) !== -1) {
      const raw = buffer.slice(0, end).trimStart();
      buffer = buffer.slice(end + 1);
      const separator = raw.indexOf('\n\n');
      const command = raw.split('\n', 1)[0]?.trim();
      if (command) receive({ command, body: separator === -1 ? '' : raw.slice(separator + 2) });
    }
  });
}

export function frame(socket: WebSocket, command: string, headers: Record<string, string>, body = '') {
  const lines = Object.entries(headers).map(([name, value]) => `${name}:${value}`);
  socket.send([command, ...lines, '', body + '\0'].join('\n'));
}

export function connectChat(token: string, signal: AbortSignal) {
  const url = new URL('/ws', educationOrigin);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  return new Promise<WebSocket>((resolve, reject) => {
    const socket = new WebSocket(url, 'v12.stomp', { handshakeTimeout: 5000, maxPayload: 64 * 1024 });
    const abort = () => socket.terminate();
    signal.addEventListener('abort', abort, { once: true });
    if (signal.aborted) abort();
    const timeout = setTimeout(() => socket.terminate(), 6000);
    socket.once('close', () => {
      clearTimeout(timeout);
      signal.removeEventListener('abort', abort);
      reject(new Error('Chat connection closed'));
    });
    socket.on('error', reject);
    socket.once('open', () => frame(socket, 'CONNECT', {
      'accept-version': '1.2', host: url.host, Authorization: `Bearer ${token}`,
    }));
    frames(socket, event => {
      if (event.command === 'CONNECTED') {
        clearTimeout(timeout);
        resolve(socket);
      } else if (event.command === 'ERROR') socket.close();
    });
  });
}
