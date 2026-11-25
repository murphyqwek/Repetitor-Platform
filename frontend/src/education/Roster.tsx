'use client';

import { useState, type FormEvent } from 'react';
import { Search, UserPlus, UsersRound } from 'lucide-react';
import { Actions, Empty, Feedback, Field, Loading, Modal } from './controls';
import { type Page, type Student, type Conflict, EducationError, describe, displayDay, clock, request, useAction, useResource } from './client';

type DirectoryStudent = { id: string; firstName: string | null; surname: string | null; patronymic: string | null; blocked: boolean; grade: number | null };

export function Roster({ courseId }: { courseId: string }) {
  const [add, setAdd] = useState(false);
  const [exclude, setExclude] = useState<Student>();
  const path = `/courses/${courseId}/students`;
  const students = useResource<Page<Student>>(`${path}?limit=100`);
  const change = useAction(() => { students.reload(); setExclude(undefined); });
  return <>
    <div className="edu-section-bar"><h2>Ученики курса</h2><button className="edu-primary" onClick={() => setAdd(true)}><UserPlus />Добавить ученика</button></div>
    <Feedback message={students.error || change.error} />
    {students.loading && <Loading />}
    {students.data?.items.length === 0 && <Empty icon={<UsersRound />} title="Учеников пока нет"><p>Найдите ученика по имени и добавьте в курс.</p></Empty>}
    {students.data?.items.map(student => <div key={student.studentId} className="edu-row">
      <span className="edu-symbol"><UsersRound /></span>
      <div className="edu-grow"><strong>{student.displayName}</strong><small>{student.blocked ? 'Аккаунт заблокирован' : student.canSendMessages ? 'Может писать в чат' : 'Чат только для чтения'}</small></div>
      <button className="edu-button" disabled={change.busy} onClick={() => void change.run(() =>
        request(`${path}/${student.studentId}/chat-permissions`, 'PATCH', { canSendMessages: !student.canSendMessages }))}>
        {student.canSendMessages ? 'Ограничить чат' : 'Разрешить чат'}</button>
      <button className="edu-text-button edu-danger-text" onClick={() => setExclude(student)}>Исключить</button>
    </div>)}
    {add && <AddStudent path={path} close={() => setAdd(false)} saved={() => { setAdd(false); students.reload(); }} />}
    {exclude && <Modal title="Исключить ученика?" close={() => setExclude(undefined)}>
      <p>{exclude.displayName} потеряет доступ к этому курсу.</p><Feedback message={change.error} />
      <Actions><button className="edu-button" onClick={() => setExclude(undefined)}>Отмена</button>
        <button className="edu-destructive" disabled={change.busy} onClick={() => void change.run(() =>
          request(`${path}/${exclude.studentId}`, 'DELETE'))}>Исключить</button></Actions>
    </Modal>}
  </>;
}

function AddStudent({ path, close, saved }: { path: string; close: () => void; saved: () => void }) {
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState<DirectoryStudent>();
  const [conflicts, setConflicts] = useState<Conflict[]>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const result = useResource<{ content: DirectoryStudent[] }>(query ?
    `/api/v1/directory/users?type=STUDENT&fio=${encodeURIComponent(query)}` : null);

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSelected(undefined);
    setConflicts(undefined);
    setError('');
    const name = String(new FormData(event.currentTarget).get('name') || '').trim();
    if (name === query) result.reload(); else setQuery(name);
  }

  async function enroll(student: DirectoryStudent, confirmed = false) {
    setSelected(student);
    setBusy(true);
    setError('');
    setConflicts(undefined);
    try {
      await request(path, 'POST', { studentId: student.id, confirmConflicts: confirmed });
      saved();
    } catch (failure) {
      if (failure instanceof EducationError && failure.code === 'SCHEDULE_CONFLICTS') setConflicts(failure.conflicts);
      else setError(describe(failure));
    } finally { setBusy(false); }
  }

  return <Modal title="Добавить ученика" close={close}>
    <form className="edu-search" onSubmit={search}><Field label="Имя или фамилия"><input name="name" required autoFocus /></Field>
      <button className="edu-button" disabled={busy}><Search />Найти</button></form>
    <Feedback message={error || result.error} />
    {result.loading && <Loading />}
    {result.data?.content.length === 0 && <p className="edu-muted">Ученики не найдены.</p>}
    {result.data?.content.map(student => <div className="edu-row" key={student.id}>
      <div className="edu-grow"><strong>{[student.surname, student.firstName, student.patronymic].filter(Boolean).join(' ') || 'Ученик'}</strong>
        <small>{student.blocked ? 'Аккаунт заблокирован' : student.grade ? `${student.grade} класс` : 'Ученик'}</small></div>
      <button className="edu-button" disabled={busy || student.blocked} onClick={() => void enroll(student)}>Зачислить</button>
    </div>)}
    {conflicts && selected && <div className="edu-conflicts"><strong>Есть пересечения в расписании</strong>
      <ul>{conflicts.map((item, index) => <li key={index}>{displayDay(item.startsAt)} · {clock(item.startsAt)}–{clock(item.endsAt)}</li>)}</ul>
      <button className="edu-primary" disabled={busy} onClick={() => void enroll(selected, true)}>Всё равно зачислить</button>
    </div>}
  </Modal>;
}
