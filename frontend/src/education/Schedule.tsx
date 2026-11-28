'use client';

import { useState, type FormEvent } from 'react';
import { CalendarDays, ChevronLeft, ChevronRight, Plus, Repeat2 } from 'lucide-react';
import { Actions, Empty, Feedback, Field, Loading, Modal } from './controls';
import { type Calendar, type Course, type Lesson, type Page, type Series, type Conflict, EducationError,
  clock, dayOf, describe, displayDay, monthName, request, useAction, useResource } from './client';

const week = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'];
type Plan = { kind: 'lesson' | 'series'; date?: string; lesson?: Lesson } | { kind: 'cancel'; lesson: Lesson } | { kind: 'stop'; series: Series };
type Command = { path: string; method: string; body: Record<string, unknown> };

function monthAt(month: string, difference: number) {
  const date = new Date(`${month}-01T00:00:00Z`);
  date.setUTCMonth(date.getUTCMonth() + difference);
  return date.toISOString().slice(0, 7);
}

export function Schedule({ courseId, manage = false }: { courseId?: string; manage?: boolean }) {
  const today = dayOf(new Date());
  const [month, setMonth] = useState(today.slice(0, 7));
  const [selected, setSelected] = useState(today);
  const [plan, setPlan] = useState<Plan>();
  const coursePath = `/courses/${courseId}`;
  const calendar = useResource<Calendar>(`${courseId ? coursePath : '/me'}/schedule?month=${month}`);
  const series = useResource<Page<Series>>(manage ? `${coursePath}/lesson-series?limit=100` : null);
  const first = new Date(`${month}-01T00:00:00Z`);
  const offset = (first.getUTCDay() + 6) % 7;
  const count = new Date(Date.UTC(first.getUTCFullYear(), first.getUTCMonth() + 1, 0)).getUTCDate();
  const lessons = calendar.data?.lessons || [];
  const agenda = lessons.filter(lesson => dayOf(lesson.startsAt) === selected);
  function changeMonth(direction: number) {
    const next = monthAt(month, direction);
    setMonth(next);
    setSelected(next === today.slice(0, 7) ? today : `${next}-01`);
  }
  function saved() { setPlan(undefined); calendar.reload(); series.reload(); }

  return <>
    <div className="edu-section-bar"><div><h2>{monthName(month)}</h2><span className="edu-muted">Время московское</span></div>
      <div className="edu-inline">
        <button className="edu-icon" aria-label="Предыдущий месяц" onClick={() => changeMonth(-1)}><ChevronLeft /></button>
        <button className="edu-button" onClick={() => { setMonth(today.slice(0, 7)); setSelected(today); }}>Сегодня</button>
        <button className="edu-icon" aria-label="Следующий месяц" onClick={() => changeMonth(1)}><ChevronRight /></button>
      </div>
    </div>
    <Feedback message={calendar.error || series.error} />
    <div className="edu-calendar-layout">
      <div className="edu-calendar" aria-label="Календарь месяца" aria-busy={calendar.loading}>
        {week.map(day => <span className="edu-weekday" key={day}>{day}</span>)}
        {Array.from({ length: offset }, (_, index) => <span className="edu-calendar-gap" key={`gap-${index}`} />)}
        {Array.from({ length: count }, (_, index) => {
          const day = index + 1;
          const date = `${month}-${String(day).padStart(2, '0')}`;
          const entries = lessons.filter(lesson => dayOf(lesson.startsAt) === date);
          return <button key={day} className="edu-calendar-day" data-today={date === today || undefined}
            aria-pressed={date === selected} aria-label={`${day}: занятий — ${entries.length}`} onClick={() => setSelected(date)}>
            <span>{day}</span>{entries.slice(0, 2).map(lesson =>
              <small key={lessonKey(lesson)} className="edu-calendar-slot">{clock(lesson.startsAt)}</small>)}
            {entries.length > 2 && <small className="edu-muted">+{entries.length - 2}</small>}
          </button>;
        })}
      </div>
      <aside className="edu-agenda">
        <h3>{displayDay(`${selected}T12:00:00+03:00`)}</h3>
        {calendar.loading ? <Loading /> : agenda.length === 0 ? <p className="edu-muted">Занятий на этот день нет.</p> : agenda.map(lesson =>
          <div className="edu-agenda-slot" key={lessonKey(lesson)}>
            <strong>{clock(lesson.startsAt)}–{clock(lesson.endsAt)}</strong>
            <span>{courseId ? (lesson.kind === 'RECURRING' ? 'Регулярное занятие' : 'Разовое занятие') : <LessonCourse id={lesson.courseId} />}</span>
            {manage && <div className="edu-inline">
              <button className="edu-text-button" onClick={() => setPlan({ kind: 'lesson', lesson })}>Перенести</button>
              <button className="edu-text-button edu-danger-text" onClick={() => setPlan({ kind: 'cancel', lesson })}>Отменить</button>
            </div>}
          </div>)}
        {manage && <button className="edu-primary" onClick={() => setPlan({ kind: 'lesson', date: selected })}><Plus />Добавить занятие</button>}
      </aside>
    </div>
    {manage && <div className="edu-series">
      <div className="edu-section-bar"><h2>Регулярные занятия</h2>
        <button className="edu-button" onClick={() => setPlan({ kind: 'series', date: selected })}><Repeat2 />Добавить серию</button></div>
      {series.loading ? <Loading /> : series.data?.items.length === 0 ? <p className="edu-muted">Регулярных занятий пока нет.</p> :
        series.data?.items.map(item => <div className="edu-row" key={item.id}><Repeat2 />
          <div className="edu-grow"><strong>{item.weekdays.map(day => week[day - 1]).join(', ')} · {item.startTime.slice(0, 5)}</strong>
            <small>{item.durationMinutes} мин · с {displayDay(item.startDate + 'T12:00:00+03:00')}
              {item.stopFromDate && ` · остановлена с ${displayDay(item.stopFromDate + 'T12:00:00+03:00')}`}</small></div>
          {!item.stopFromDate && <button className="edu-button" onClick={() => setPlan({ kind: 'stop', series: item })}>Остановить</button>}
        </div>)}
    </div>}
    {plan && <ScheduleForm coursePath={coursePath} plan={plan} selected={selected} close={() => setPlan(undefined)} saved={saved} />}
  </>;
}

function lessonKey(lesson: Lesson) { return lesson.singleLessonId || `${lesson.seriesId}:${lesson.originalDate}`; }

function LessonCourse({ id }: { id: string }) {
  const course = useResource<Course>(`/courses/${id}`);
  return course.data?.title || 'Занятие';
}

function ScheduleForm({ coursePath, plan, selected, close, saved }: {
  coursePath: string; plan: Plan; selected: string; close: () => void; saved: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [pending, setPending] = useState<{ command: Command; conflicts: Conflict[] }>();
  const lesson = 'lesson' in plan ? plan.lesson : undefined;
  const once = lesson?.kind === 'SINGLE';
  const lessonPath = lesson ? once ? `${coursePath}/lessons/${lesson.singleLessonId}`
    : `${coursePath}/lesson-series/${lesson.seriesId}/occurrences/${lesson.originalDate}` : `${coursePath}/lessons`;

  async function run({ path, method, body }: Command, confirmed = false) {
    setBusy(true);
    setError('');
    try {
      await request(path, method, confirmed ? { ...body, confirmConflicts: true } : body);
      saved();
    } catch (failure) {
      if (failure instanceof EducationError && failure.code === 'SCHEDULE_CONFLICTS' && !confirmed) {
        setPending({ command: { path, method, body }, conflicts: failure.conflicts });
      } else setError(describe(failure));
    } finally { setBusy(false); }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPending(undefined);
    const data = new FormData(event.currentTarget);
    const value = (key: string) => String(data.get(key) || '');
    if (plan.kind === 'series') {
      const weekdays = data.getAll('weekdays').map(Number);
      if (!weekdays.length) { setError('Выберите хотя бы один день недели.'); return; }
      void run({ path: `${coursePath}/lesson-series`, method: 'POST', body: {
        startDate: value('date'), startTime: value('time'), durationMinutes: Number(value('duration')), weekdays,
      } });
    } else if (plan.kind === 'stop') {
      void run({ path: `${coursePath}/lesson-series/${plan.series.id}/stop`, method: 'POST', body: { stopFromDate: value('date') } });
    } else if (plan.kind === 'cancel') {
      void run({ path: `${lessonPath}/cancel`, method: 'POST', body: { reason: value('reason') || null } });
    } else {
      const startsAt = `${value('start')}:00+03:00`;
      const endsAt = `${value('end')}:00+03:00`;
      if (Date.parse(endsAt) <= Date.parse(startsAt)) { setError('Окончание должно быть позже начала.'); return; }
      void run({ path: lesson && !once ? `${lessonPath}/reschedule` : lessonPath, method: once ? 'PATCH' : 'POST',
        body: { startsAt, endsAt, ...(lesson && { reason: value('reason') || null }) } });
    }
  }

  const title = plan.kind === 'series' ? 'Новая серия' : plan.kind === 'stop' ? 'Остановить серию'
    : plan.kind === 'cancel' ? 'Отменить занятие' : lesson ? 'Перенести занятие' : 'Новое занятие';
  const start = lesson ? `${dayOf(lesson.startsAt)}T${clock(lesson.startsAt)}` : `${selected}T16:00`;
  const end = lesson ? `${dayOf(lesson.endsAt)}T${clock(lesson.endsAt)}` : `${selected}T17:00`;
  return <Modal title={title} close={close}><form onSubmit={submit}>
    {plan.kind === 'lesson' && <div className="edu-field-grid">
      <Field label="Начало, МСК"><input type="datetime-local" name="start" defaultValue={start} required /></Field>
      <Field label="Окончание, МСК"><input type="datetime-local" name="end" defaultValue={end} required /></Field>
    </div>}
    {plan.kind === 'series' && <>
      <div className="edu-field-grid">
        <Field label="Дата начала"><input name="date" type="date" defaultValue={plan.date} required /></Field>
        <Field label="Время, МСК"><input name="time" type="time" defaultValue="16:00" required /></Field>
      </div>
      <Field label="Длительность, мин"><input name="duration" type="number" min="5" max="300" defaultValue="60" required /></Field>
      <fieldset className="edu-week-options"><legend>Дни недели</legend>
        {week.map((name, index) => <label key={name}><input type="checkbox" name="weekdays" value={index + 1} />{name}</label>)}
      </fieldset>
    </>}
    {plan.kind === 'stop' && <Field label="Остановить начиная с"><input name="date" type="date" defaultValue={selected} required /></Field>}
    {lesson && <Field label="Причина, если нужно"><input name="reason" /></Field>}
    {pending && <div className="edu-conflicts"><strong>Есть пересечения в расписании</strong>
      <ul>{pending.conflicts.map((item, index) => <li key={index}>
        {item.subjectType === 'TEACHER' ? 'Преподаватель' : 'Ученик'} · {displayDay(item.startsAt)} · {clock(item.startsAt)}–{clock(item.endsAt)}
      </li>)}</ul>
      <button type="button" className="edu-button" disabled={busy} onClick={() => void run(pending.command, true)}>Всё равно сохранить</button>
    </div>}
    <Feedback message={error} />
    <Actions><button type="button" className="edu-button" onClick={close}>Закрыть</button>
      <button className={plan.kind === 'cancel' ? 'edu-destructive' : 'edu-primary'} disabled={busy}>{plan.kind === 'cancel' ? 'Отменить занятие' : 'Сохранить'}</button></Actions>
  </form></Modal>;
}
