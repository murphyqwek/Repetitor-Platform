import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { api, ApiError } from './api';

const fetchMock = vi.fn();
const response = (value?: unknown, status = 200) => new Response(value === undefined ? null : JSON.stringify(value), { status });

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  document.cookie = 'XSRF-TOKEN=plain-cookie-token; Path=/';
});
afterEach(() => vi.unstubAllGlobals());

it('uses the plain CSRF cookie rather than the masked JSON token, sending only server registration fields', async () => {
  fetchMock.mockResolvedValueOnce(response({ headerName: 'X-XSRF-TOKEN', token: 'masked-token' })).mockResolvedValueOnce(response(undefined, 201));
  await api.parent({ login: 'parent1', password: 'password123', invitationCode: 'ABCD1234EF56', policyVersion: 'draft' });
  expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/v1/auth/register/parent', expect.objectContaining({
    credentials: 'include', method: 'POST', headers: { 'X-XSRF-TOKEN': 'plain-cookie-token', 'Content-Type': 'application/json' },
    body: JSON.stringify({ login: 'parent1', password: 'password123', invitationCode: 'ABCD1234EF56', policyVersion: 'draft' }),
  }));
});

it('does not send credentials without a CSRF cookie', async () => {
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/';
  fetchMock.mockResolvedValueOnce(response({ headerName: 'X-XSRF-TOKEN', token: 'masked-token' }));
  await expect(api.login('parent1', 'password123')).rejects.toThrow('Разрешите cookies');
  expect(fetchMock).toHaveBeenCalledTimes(1);
});

it('refreshes an expired session once before reading profile again', async () => {
  fetchMock.mockResolvedValueOnce(response({}, 401)).mockResolvedValueOnce(response({ headerName: 'X-XSRF-TOKEN', token: 'csrf' }))
    .mockResolvedValueOnce(response()).mockResolvedValueOnce(response({ id: 'student' }));
  expect(await api.session()).toEqual({ id: 'student' });
  expect(fetchMock.mock.calls.map(call => call[0])).toEqual(['/api/v1/users/me', '/api/v1/auth/csrf', '/api/v1/auth/refresh', '/api/v1/users/me']);
});

it('does not retry blocked accounts or reveal raw HTML server errors', async () => {
  fetchMock.mockResolvedValueOnce(response({}, 403));
  await expect(api.session()).rejects.toBeInstanceOf(ApiError);
  expect(fetchMock).toHaveBeenCalledTimes(1);
  fetchMock.mockResolvedValueOnce(new Response('<html>stack trace</html>', { status: 502 }));
  await expect(api.me()).rejects.toThrow('Сервис временно недоступен');
});
