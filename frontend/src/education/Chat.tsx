'use client';

import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Download, MessageSquare, Paperclip, Send, Trash2, X } from 'lucide-react';
import type { Profile } from '../api';
import { Actions, Empty, Feedback, Loading, Modal } from './controls';
import { type Course, type ChatHistory, type ChatMessage, bytes, clock, describe, download, request, useAction } from './client';

type Upload = { attachmentId: string; fileName: string; contentType: string; sizeBytes: number };
type Command = { clientMessageId: string; text: string | null; attachmentIds: string[] };
type ChatEvent = { type: 'MESSAGE_CREATED'; message: ChatMessage } | { type: 'MESSAGE_DELETED'; messageId: string };
const fileTypes = '.jpg,.jpeg,.png,.webp,.pdf,.docx,.xlsx,.txt';

function update(messages: ChatMessage[], event: ChatEvent) {
  return event.type === 'MESSAGE_DELETED' ? messages.filter(message => message.id !== event.messageId) :
    [...messages.filter(message => message.id !== event.message.id), event.message];
}

export function Chat({ course, user, manage }: { course: Course; user: Profile; manage: boolean }) {
  const path = `/courses/${course.id}/chat`;
  const stream = `/education-api/chat/${course.id}`;
  const [mode, setMode] = useState(course.chatMode);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [connected, setConnected] = useState(false);
  const [historyError, setHistoryError] = useState('');
  const [text, setText] = useState('');
  const [files, setFiles] = useState<Upload[]>([]);
  const [remove, setRemove] = useState<ChatMessage>();
  const [sync, setSync] = useState(0);
  const pending = useRef<Command | null>(null);
  const tombstones = useRef(new Set<string>());
  const viewport = useRef<HTMLDivElement>(null);
  const picker = useRef<HTMLInputElement>(null);
  const send = useAction();
  const upload = useAction();
  const older = useAction();
  const moderation = useAction();
  const settings = useAction();
  const canWrite = manage || mode === 'NORMAL';

  useEffect(() => {
    let current = true;
    let sequence = 0;
    let buffer: ChatEvent[] | undefined;
    const abort = new AbortController();
    const source = new EventSource(stream);

    async function latest() {
      const version = ++sequence;
      buffer = [];
      try {
        const page = await request<ChatHistory>(`${path}/messages`, 'GET', undefined, abort.signal);
        if (!current || version !== sequence) return;
        setMessages((buffer || []).reduce(update, page.messages).filter(message => !tombstones.current.has(message.id)));
        setCursor(page.nextCursor);
        setHistoryError('');
        buffer = undefined;
        requestAnimationFrame(() => viewport.current?.scrollTo(0, viewport.current.scrollHeight));
      } catch (failure) {
        if (current && version === sequence) setHistoryError(describe(failure));
        buffer = undefined;
      } finally {
        if (current && version === sequence) setLoading(false);
      }
    }
    source.onopen = () => {
      setConnected(true);
      void latest();
      void request<{ mode: Course['chatMode'] }>(`${path}/settings`, 'GET', undefined, abort.signal)
        .then(value => { if (current) setMode(value.mode); }).catch(() => {});
    };
    source.onerror = () => setConnected(false);
    source.onmessage = message => {
      const event = JSON.parse(message.data) as ChatEvent;
      if (event.type === 'MESSAGE_DELETED') tombstones.current.add(event.messageId);
      if (event.type === 'MESSAGE_CREATED' && tombstones.current.has(event.message.id)) return;
      if (buffer) buffer.push(event);
      else {
        const node = viewport.current;
        const atBottom = node && node.scrollHeight - node.scrollTop - node.clientHeight < 100;
        setMessages(values => update(values, event));
        if (atBottom) requestAnimationFrame(() => node.scrollTo(0, node.scrollHeight));
      }
    };
    void latest();
    return () => { current = false; abort.abort(); source.close(); };
  }, [path, stream, sync]);

  function loadOlder() {
    if (!cursor) return;
    void older.run(async () => {
      const page = await request<ChatHistory>(`${path}/messages?before=${encodeURIComponent(cursor)}`);
      const view = viewport.current;
      const previousHeight = view?.scrollHeight || 0;
      setMessages(current => [...current, ...page.messages.filter(message => !tombstones.current.has(message.id) && !current.some(item => item.id === message.id))]);
      setCursor(page.nextCursor);
      if (view) requestAnimationFrame(() => { view.scrollTop += view.scrollHeight - previousHeight; });
    });
  }

  function addFiles(selected: FileList | null) {
    if (!selected?.length) return;
    pending.current = null;
    void upload.run(async () => {
      if (selected.length + files.length > 5) throw new Error('К одному сообщению можно прикрепить до пяти файлов.');
      if (Array.from(selected).some(file => file.size > 20000000)) throw new Error('Каждый файл должен быть не больше 20 МБ.');
      if (Array.from(selected).reduce((sum, file) => sum + file.size, files.reduce((sum, file) => sum + file.sizeBytes, 0)) > 50000000) {
        throw new Error('Общий размер вложений должен быть не больше 50 МБ.');
      }
      for (const file of Array.from(selected)) {
        const form = new FormData();
        form.set('file', file);
        const result = await request<Upload>(`${path}/attachments`, 'POST', form);
        setFiles(current => [...current, result]);
      }
    }).finally(() => { if (picker.current) picker.current.value = ''; });
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (send.busy || upload.busy || !canWrite || (!text.trim() && !files.length)) return;
    const command = pending.current || {
      clientMessageId: crypto.randomUUID(), text: text.trim() || null, attachmentIds: files.map(file => file.attachmentId),
    };
    pending.current = command;
    void send.run(async () => {
      await request<{ messageId: string }>(stream, 'POST', command);
      pending.current = null;
      setText('');
      setFiles([]);
      if (!connected) setSync(value => value + 1);
    });
  }

  const sorted = [...messages].sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt) || a.id.localeCompare(b.id));
  return <div className="edu-chat">
    <div className="edu-section-bar"><div><h2>Чат курса</h2>
      <span className="edu-chat-status"><i data-connected={connected} />{connected ? 'На связи' : 'Подключение…'}</span></div>
      {manage && <label className="edu-chat-mode"><span>Режим чата</span><select value={mode} disabled={settings.busy} onChange={event => {
        const value = event.currentTarget.value as Course['chatMode'];
        void settings.run(async () => { await request(`${path}/settings`, 'PATCH', { mode: value }); setMode(value); });
      }}><option value="NORMAL">Общий чат</option><option value="ANNOUNCEMENTS">Только объявления</option></select></label>}
    </div>
    <Feedback message={historyError || older.error || settings.error || moderation.error} />
    <div className="edu-chat-messages" ref={viewport} aria-label="Сообщения курса">
      {cursor && <button className="edu-button edu-more" disabled={older.busy} onClick={loadOlder}>Более ранние сообщения</button>}
      {loading && <Loading />}
      {!loading && !historyError && !messages.length && <Empty icon={<MessageSquare />} title="Сообщений пока нет">
        {canWrite && <p>Напишите первое сообщение в чат курса.</p>}
      </Empty>}
      {sorted.map(message => <div key={message.id} className="edu-message" data-own={message.author.id === user.id || undefined}>
        <div className="edu-message-meta"><strong>{message.author.id === user.id ? 'Вы' : message.author.displayName}</strong><small>{clock(message.createdAt)}</small>
          {manage && (user.roles.includes('ADMIN') || message.author.role === 'STUDENT' || message.author.id === user.id) &&
            <button className="edu-icon" aria-label="Удалить сообщение" title="Удалить сообщение" onClick={() => setRemove(message)}><Trash2 /></button>}
        </div>
        {message.text && <p>{message.text}</p>}
        {message.attachments.map(file => <a className="edu-message-file" key={file.id} href={download(file.downloadUrl)}><Download />
          <span>{file.fileName}<small>{bytes(file.sizeBytes)}</small></span></a>)}
      </div>)}
    </div>
    {canWrite ? <form className="edu-compose" onSubmit={submit}>
      {files.length > 0 && <div className="edu-chips">{files.map(file => <span key={file.attachmentId}>{file.fileName}
        <button type="button" className="edu-icon" aria-label={`Убрать ${file.fileName}`} disabled={send.busy} onClick={() => {
          pending.current = null; setFiles(current => current.filter(item => item.attachmentId !== file.attachmentId));
        }}><X /></button></span>)}</div>}
      <Feedback message={send.error || upload.error} />
      <div className="edu-compose-row">
        <input ref={picker} type="file" multiple accept={fileTypes} hidden onChange={event => addFiles(event.target.files)} />
        <button type="button" className="edu-icon" aria-label="Прикрепить файл" title="Прикрепить файл" disabled={upload.busy || send.busy || files.length >= 5}
          onClick={() => picker.current?.click()}><Paperclip /></button>
        <textarea aria-label="Сообщение" placeholder="Напишите сообщение…" rows={2} maxLength={4000} value={text} disabled={send.busy}
          onChange={event => { pending.current = null; setText(event.target.value); }} />
        <button className="edu-primary" aria-label="Отправить сообщение" disabled={send.busy || upload.busy || (!text.trim() && !files.length)}><Send /><span>Отправить</span></button>
      </div>
      {upload.busy && <small className="edu-muted" role="status">Загрузка вложений…</small>}
    </form> : <p className="edu-compose edu-muted">В этом чате преподаватель публикует объявления.</p>}
    {remove && <Modal title="Удалить сообщение?" close={() => setRemove(undefined)}>
      <p>Сообщение и его вложения пропадут из чата.</p><Feedback message={moderation.error} />
      <Actions><button className="edu-button" onClick={() => setRemove(undefined)}>Отмена</button>
        <button className="edu-destructive" disabled={moderation.busy} onClick={() => void moderation.run(async () => {
          await request(`${path}/messages/${remove.id}`, 'DELETE');
          tombstones.current.add(remove.id);
          setMessages(values => values.filter(message => message.id !== remove.id));
          setRemove(undefined);
        })}>Удалить сообщение</button></Actions>
    </Modal>}
  </div>;
}
