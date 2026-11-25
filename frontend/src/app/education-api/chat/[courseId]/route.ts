import type { NextRequest } from 'next/server';
import { connectChat, frame, frames } from '../../../../education/stomp';
import { problem, sessionToken, uuid } from '../../../../education/gateway';

type Params = { params: Promise<{ courseId: string }> };

export async function GET(request: NextRequest, { params }: Params) {
  const { courseId } = await params;
  if (!uuid.test(courseId)) return problem(404, 'Курс не найден.');
  const token = sessionToken(request);
  if (typeof token !== 'string') return token;
  try {
    const socket = await connectChat(token, request.signal);
    const encoder = new TextEncoder();
    let heartbeat: ReturnType<typeof setInterval>;
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        const emit = (event: string) => controller.enqueue(encoder.encode(event));
        socket.once('close', () => {
          clearInterval(heartbeat);
          try { controller.close(); } catch { /* Stream was canceled. */ }
        });
        frames(socket, event => {
          if (event.command === 'MESSAGE') emit(`data: ${event.body.replaceAll('\n', '\ndata: ')}\n\n`);
        });
        frame(socket, 'SUBSCRIBE', { id: 'course', destination: `/topic/courses/${courseId}/chat` });
        emit(': connected\n\n');
        heartbeat = setInterval(() => emit(': heartbeat\n\n'), 15000);
      },
      cancel() {
        clearInterval(heartbeat);
        socket.close();
      },
    });
    return new Response(stream, { headers: {
      'Content-Type': 'text/event-stream', 'Cache-Control': 'private, no-store, no-transform', 'X-Accel-Buffering': 'no',
    } });
  } catch {
    return problem(502, 'Чат недоступен.');
  }
}

export async function POST(request: NextRequest, { params }: Params) {
  const { courseId } = await params;
  if (!uuid.test(courseId)) return problem(404, 'Курс не найден.');
  const token = sessionToken(request);
  if (typeof token !== 'string') return token;
  let payload: string;
  try {
    payload = JSON.stringify(await request.json());
    if (Buffer.byteLength(payload) > 24 * 1024) return problem(413, 'Сообщение слишком длинное.');
  } catch {
    return problem(400, 'Проверьте текст сообщения.');
  }
  try {
    const socket = await connectChat(token, request.signal);
    return await new Promise<Response>(resolve => {
      const finish = (response: Response) => {
        clearTimeout(timer);
        resolve(response);
        socket.close();
      };
      const timer = setTimeout(() => finish(problem(504, 'Подтверждение отправки не пришло.')), 8000);
      socket.once('close', () => finish(problem(502, 'Соединение с чатом закрыто.')));
      frames(socket, event => {
        if (event.command !== 'MESSAGE') return;
        try {
          const result = JSON.parse(event.body);
          finish(result.type === 'MESSAGE_ACCEPTED' ? Response.json(result, { headers: { 'Cache-Control': 'no-store' } })
            : Response.json({ code: result.code, detail: result.message || 'Сообщение не отправлено.' },
              { status: 409, headers: { 'Cache-Control': 'no-store' } }));
        } catch {
          finish(problem(502, 'Чат вернул некорректный ответ.'));
        }
      });
      frame(socket, 'SUBSCRIBE', { id: 'response', destination: '/user/queue/chat.responses' });
      frame(socket, 'SEND', { destination: `/app/courses/${courseId}/chat.send`, 'content-type': 'application/json',
        'content-length': String(Buffer.byteLength(payload)) }, payload);
    });
  } catch {
    return problem(502, 'Чат недоступен.');
  }
}
