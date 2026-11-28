export interface Profile {
  id: string;
  login: string;
  firstName: string | null;
  surname: string | null;
  patronymic: string | null;
  roles: ('PARENT' | 'TEACHER' | 'STUDENT' | 'ADMIN')[];
  nameRequested: boolean;
  blocked: boolean;
}

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

export async function csrfToken() {
  // Spring Security spa() expects the plain cookie token in the header, not the masked JSON token.
  const csrf = await fetch('/api/v1/auth/csrf', { credentials: 'include', cache: 'no-store', signal: AbortSignal.timeout(12000) });
  if (!csrf.ok) throw new ApiError(csrf.status, 'Не удалось подготовить запрос. Попробуйте ещё раз.');
  const token = document.cookie.split('; ').find(cookie => cookie.startsWith('XSRF-TOKEN='))?.slice('XSRF-TOKEN='.length);
  if (!token) throw new ApiError(403, 'Не удалось получить защитный cookie. Разрешите cookies и попробуйте снова.');
  return decodeURIComponent(token);
}

async function send<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  if (method !== 'GET') {
    headers['X-XSRF-TOKEN'] = await csrfToken();
    if (body !== undefined) headers['Content-Type'] = 'application/json';
  }
  const response = await fetch(`/api/v1${path}`, {
    method, headers, credentials: 'include', cache: 'no-store', signal: AbortSignal.timeout(12000),
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    const fallback = response.status === 401 ? 'Проверьте логин и пароль.'
      : response.status === 403 ? 'Доступ ограничен. Обратитесь к преподавателю.'
      : response.status >= 500 ? 'Сервис временно недоступен. Попробуйте позже.'
      : 'Не удалось выполнить запрос. Проверьте данные.';
    const error = await response.json().catch(() => null) as { error?: unknown } | null;
    throw new ApiError(response.status, typeof error?.error === 'string' ? error.error : fallback);
  }
  if (response.status === 204 || response.headers.get('Content-Length') === '0') return undefined as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const api = {
  session: async (): Promise<Profile> => {
    try { return await send<Profile>('/users/me'); }
    catch (error) {
      if (!(error instanceof ApiError) || error.status !== 401) throw error;
      await send<void>('/auth/refresh', 'POST');
      return send<Profile>('/users/me');
    }
  },
  me: () => send<Profile>('/users/me'),
  login: (login: string, password: string) => send<void>('/auth/login', 'POST', { login, password }),
  parent: (body: { login: string; password: string; invitationCode: string; policyVersion: string }) =>
    send<void>('/auth/register/parent', 'POST', body),
  teacher: (code: string, body: { login: string; password: string; policyVersion: string }) =>
    send<void>(`/auth/register/teacher/${encodeURIComponent(code)}`, 'POST', body),
  invitation: (code: string) => send<void>(`/auth/register/teacher/${encodeURIComponent(code)}`),
  profile: (body: { firstName: string; surname: string; patronymic: string }) => send<Profile>('/users/me', 'PATCH', body),
  logout: () => send<void>('/auth/logout', 'POST'),
};

export function errorMessage(error: unknown): string {
  return error instanceof ApiError ? error.message : 'Нет связи с сервером. Проверьте подключение и попробуйте снова.';
}
