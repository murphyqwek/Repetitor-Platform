'use client';

import { useCallback, useEffect, useState } from 'react';
import { api, csrfToken } from '../api';

export type Page<T> = { items: T[]; nextCursor: string | null; hasMore: boolean };
export type Course = { id: string; title: string; teacherId: string; meetingUrl: string | null; chatMode: 'NORMAL' | 'ANNOUNCEMENTS' };
export type Student = { studentId: string; displayName: string; blocked: boolean; canSendMessages: boolean };
export type Lesson = {
  courseId: string; kind: 'SINGLE' | 'RECURRING'; singleLessonId: string | null; seriesId: string | null;
  originalDate: string | null; startsAt: string; endsAt: string;
};
export type Calendar = { month: string; lessons: Lesson[] };
export type Series = { id: string; startDate: string; weekdays: number[]; startTime: string; durationMinutes: number; stopFromDate: string | null };
export type Material = { id: string; name: string; folderId: string | null; contentType: string; sizeBytes: number; downloadUrl?: string };
export type Folder = { id: string; name: string; parentId: string | null };
export type LibraryContents = { folders: Folder[]; files: Material[] };
export type ChatAttachment = { id: string; fileName: string; contentType: string; sizeBytes: number; downloadUrl: string };
export type ChatMessage = { id: string; text: string | null; author: { id: string; displayName: string; role: string }; createdAt: string; attachments: ChatAttachment[] };
export type ChatHistory = { messages: ChatMessage[]; nextCursor: string | null; hasMore: boolean };
export type Conflict = { subjectType: string; startsAt: string; endsAt: string };

const rest = '/education-api/data';

export class EducationError extends Error {
  constructor(message: string, readonly status: number, readonly code?: string, readonly conflicts: Conflict[] = []) { super(message); }
}

export async function request<T>(path: string, method = 'GET', value?: unknown, signal?: AbortSignal): Promise<T> {
  const url = path.startsWith('/education-api/chat/') || path.startsWith('/api/v1/directory/') ? path : rest + path;
  async function send() {
    const headers: Record<string, string> = {};
    if (method !== 'GET') headers['X-XSRF-TOKEN'] = await csrfToken();
    const multipart = value instanceof FormData;
    if (!multipart && value !== undefined) headers['Content-Type'] = 'application/json';
    return fetch(url, { method, headers, signal, cache: 'no-store', body: multipart ? value : JSON.stringify(value) });
  }
  let result = await send();
  if (result.status === 401) {
    await api.session();
    result = await send();
  }
  if (!result.ok) {
    const body = await result.json().catch(() => ({}));
    const translations: Record<string, string> = {
      MATERIAL_IN_USE: 'Файл используется в задании и пока недоступен для удаления.',
      ALREADY_ENROLLED: 'Ученик уже зачислен на курс.',
      STUDENT_BLOCKED: 'Этот ученик заблокирован.',
      CHAT_SEND_FORBIDDEN: 'Отправка сообщений в этом курсе ограничена.',
      INVALID_ATTACHMENT: 'Вложение недоступно. Прикрепите файл заново.',
      INVALID_MESSAGE: 'Проверьте текст сообщения и вложения.',
      FILE_TOO_LARGE: 'Файл превышает допустимый размер.',
      UNSUPPORTED_MEDIA_TYPE: 'Этот формат файла не поддерживается.',
    };
    throw new EducationError(translations[body.code] || (result.status === 404 ? 'Данные больше недоступны.'
      : result.status === 403 ? 'У вас нет доступа к этому действию.' : body.detail || body.error || 'Не удалось выполнить запрос.'),
      result.status, body.code, body.conflicts);
  }
  return (result.status === 204 || result.headers.get('Content-Length') === '0' ? undefined : await result.json()) as T;
}

export function download(path: string) { return rest + path.replace(/^\/api\/v1/, ''); }

export function describe(error: unknown) { return error instanceof Error ? error.message : 'Не удалось выполнить запрос.'; }

export function useResource<T>(path: string | null) {
  const [version, setVersion] = useState(0);
  const [state, setState] = useState<{ path: string; data?: T; error?: string }>();
  const reload = useCallback(() => setVersion(value => value + 1), []);
  useEffect(() => {
    if (!path) return;
    const controller = new AbortController();
    void request<T>(path, 'GET', undefined, controller.signal).then(
      data => { if (!controller.signal.aborted) setState({ path, data }); },
      error => { if (!controller.signal.aborted) setState({ path, error: describe(error) }); },
    );
    return () => controller.abort();
  }, [path, version]);
  const current = state?.path === path ? state : undefined;
  return { data: current?.data, error: current?.error, loading: !!path && !current, reload };
}

export function useAction(done?: () => void) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    setError('');
    try { await action(); done?.(); return true; }
    catch (failure) { setError(describe(failure)); return false; }
    finally { setBusy(false); }
  }
  return { run, busy, error };
}

export const moscow = 'Europe/Moscow';
const localDate = new Intl.DateTimeFormat('sv-SE', { timeZone: moscow, year: 'numeric', month: '2-digit', day: '2-digit' });
export function dayOf(value: string | Date) { return localDate.format(new Date(value)); }
export function clock(value: string | Date) {
  return new Intl.DateTimeFormat('ru-RU', { timeZone: moscow, hour: '2-digit', minute: '2-digit' }).format(new Date(value));
}
export function displayDay(value: string | Date) {
  return new Intl.DateTimeFormat('ru-RU', { timeZone: moscow, day: 'numeric', month: 'long' }).format(new Date(value));
}
export function monthName(month: string) {
  return new Intl.DateTimeFormat('ru-RU', { timeZone: 'UTC', month: 'long', year: 'numeric' })
    .format(new Date(`${month}-01T00:00:00Z`)).replace(/\sг\.$/, '');
}
export function bytes(size: number) {
  return size < 1000 ? `${size} Б` : size < 1000000 ? `${Math.round(size / 1000)} КБ` : `${(size / 1000000).toFixed(1)} МБ`;
}
