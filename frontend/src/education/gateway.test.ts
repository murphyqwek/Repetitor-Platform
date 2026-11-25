// @vitest-environment node

import { NextRequest } from 'next/server';
import { expect, it } from 'vitest';
import { sessionToken } from './gateway';

function request(headers: Record<string, string>, method = 'POST') {
  return new NextRequest('http://localhost:3000/education-api/data/courses', {
    method, headers: {
      Host: '127.0.0.1:3000', Origin: 'http://127.0.0.1:3000',
      Cookie: 'access_token=example-token; XSRF-TOKEN=csrf-token',
      'X-XSRF-TOKEN': 'csrf-token', ...headers,
    },
  });
}

it('uses the browser origin and matching CSRF cookie even when Next normalizes its URL', () => {
  expect(sessionToken(request({}))).toBe('example-token');
});

it('rejects cross-origin writes and writes without the matching CSRF header', () => {
  expect(sessionToken(request({ Origin: 'http://localhost:3001' }))).toHaveProperty('status', 403);
  expect(sessionToken(request({ 'X-XSRF-TOKEN': '' }))).toHaveProperty('status', 403);
});

it('requires the session cookie and allows authenticated reads without an Origin header', () => {
  expect(sessionToken(request({ Cookie: '' }, 'GET'))).toHaveProperty('status', 401);
  expect(sessionToken(request({ Origin: '', 'X-XSRF-TOKEN': '' }, 'GET'))).toBe('example-token');
});
