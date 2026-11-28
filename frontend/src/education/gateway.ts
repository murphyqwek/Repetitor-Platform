import { timingSafeEqual } from 'node:crypto';
import type { NextRequest } from 'next/server';

export const educationOrigin = new URL(process.env.EDUCATION_API_URL || 'http://127.0.0.1:8081');

export function problem(status: number, detail: string) {
  return Response.json({ detail }, { status, headers: { 'Cache-Control': 'no-store' } });
}

export function sessionToken(request: NextRequest): string | Response {
  const token = request.cookies.get('access_token')?.value;
  if (!token) return problem(401, 'Войдите в аккаунт.');
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    const header = Buffer.from(request.headers.get('X-XSRF-TOKEN') || '');
    const cookie = Buffer.from(request.cookies.get('XSRF-TOKEN')?.value || '');
    const origin = request.headers.get('Origin');
    const host = request.headers.get('Host');
    const expectedOrigin = host ? `${request.nextUrl.protocol}//${host}` : request.nextUrl.origin;
    if (origin !== expectedOrigin || !header.length || header.length !== cookie.length || !timingSafeEqual(header, cookie)) {
      return problem(403, 'Не удалось подтвердить запрос. Обновите страницу.');
    }
  }
  return token;
}

export const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
