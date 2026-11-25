import type { NextRequest } from 'next/server';
import { educationOrigin, problem, sessionToken } from '../../../../education/gateway';

type Params = { params: Promise<{ path: string[] }> };

async function proxy(request: NextRequest, { params }: Params) {
  const { path } = await params;
  const root = path[0];
  if (!(root === 'courses' || root === 'materials' || path.join('/') === 'me/schedule')
      || path.some(part => !/^[a-z\d-]+$/i.test(part))) {
    return problem(404, 'Раздел не найден.');
  }
  const token = sessionToken(request);
  if (typeof token !== 'string') return token;

  const url = new URL('/api/v1/' + path.join('/'), educationOrigin);
  url.search = request.nextUrl.search;
  const headers = new Headers({ Authorization: `Bearer ${token}` });
  const type = request.headers.get('Content-Type');
  if (type) headers.set('Content-Type', type);
  const options: RequestInit & { duplex?: 'half' } = {
    method: request.method, headers, cache: 'no-store', redirect: 'manual', signal: request.signal,
  };
  if (request.body) {
    options.body = request.body;
    options.duplex = 'half';
  }
  try {
    const upstream = await fetch(url, options);
    const responseHeaders = new Headers({ 'Cache-Control': 'private, no-store', 'X-Content-Type-Options': 'nosniff' });
    for (const name of ['Content-Type', 'Content-Disposition']) {
      const value = upstream.headers.get(name);
      if (value) responseHeaders.set(name, value);
    }
    return new Response(upstream.body, { status: upstream.status, headers: responseHeaders });
  } catch {
    return problem(502, 'Учебный сервис недоступен.');
  }
}

export { proxy as GET, proxy as POST, proxy as PATCH, proxy as PUT, proxy as DELETE };
