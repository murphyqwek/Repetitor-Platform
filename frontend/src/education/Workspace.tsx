'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useState, type FormEvent } from 'react';
import { ArrowLeft, ArrowRight, BookOpen, CalendarDays, ChevronRight, FolderOpen, LayoutGrid, LogOut,
  MessageSquare, Pencil, Plus, Trash2, UsersRound } from 'lucide-react';
import { api, ApiError, errorMessage, type Profile } from '../api';
import { Actions, Empty, Feedback, Field, Loading, Modal } from './controls';
import { type Course, type Page, request, useAction, useResource } from './client';
import { Schedule } from './Schedule';
import { Roster } from './Roster';
import { CourseMaterials, Library } from './Materials';
import { Chat } from './Chat';
import { ThemePicker } from '../components/ThemePicker';

type Section = 'courses' | 'calendar' | 'library';
type CourseTab = 'schedule' | 'roster' | 'materials' | 'chat';
type Scope = 'OWNED' | 'ENROLLED' | 'ALL';

export function Workspace() {
  const router = useRouter();
  const [user, setUser] = useState<Profile>();
  const [failure, setFailure] = useState('');
  const [section, setSection] = useState<Section>('courses');
  const [courseId, setCourseId] = useState<string>();
  const logout = useAction(() => router.replace('/login'));

  useEffect(() => {
    let active = true;
    void api.session().then(profile => {
      if (!active) return;
      if (!profile.nameRequested) router.replace('/profile/complete');
      else setUser(profile);
    }).catch(error => {
      if (!active) return;
      if (error instanceof ApiError && error.status === 401) router.replace('/login');
      else setFailure(errorMessage(error));
    });
    return () => { active = false; };
  }, [router]);

  if (!user) return <main className="edu-gate">{failure ? <Feedback message={failure} /> : <Loading />}</main>;
  const teacher = user.roles.includes('TEACHER');
  const admin = user.roles.includes('ADMIN');
  const student = user.roles.includes('STUDENT');
  if (!teacher && !admin && !student) return <main className="edu-gate">
    <Empty icon={<BookOpen />} title="Учебный кабинет для учеников и преподавателей">
      <Link href="/account" className="edu-button">Вернуться в аккаунт</Link>
    </Empty>
  </main>;

  const name = [user.firstName, user.surname].filter(Boolean).join(' ') || user.login;
  const initials = name.split(/\s+/).slice(0, 2).map(word => word[0]?.toUpperCase()).join('');
  function navigate(next: Section) { setSection(next); setCourseId(undefined); }
  const navigation = [
    { id: 'courses', label: 'Курсы', icon: LayoutGrid },
    { id: 'calendar', label: 'Расписание', icon: CalendarDays },
    ...((teacher || admin) ? [{ id: 'library' as const, label: 'Библиотека', icon: FolderOpen }] : []),
  ] as const;

  return <div className="education-shell">
    <aside className="edu-sidebar">
      <Link href="/learn" className="edu-brand"><BookOpen /><span>репетитор</span><i /></Link>
      <nav aria-label="Учебный кабинет">
        {navigation.map(({ id, label, icon: Icon }) => <button key={id} type="button"
          aria-current={section === id ? 'page' : undefined} onClick={() => navigate(id)}><Icon />{label}</button>)}
      </nav>
      <ThemePicker />
      <div className="edu-sidebar-foot"><Link href="/account" aria-label={`Аккаунт: ${name}`} className="edu-avatar">{initials}</Link>
        <div><Link href="/account">{name}</Link><small>{admin ? 'Администратор' : teacher ? 'Преподаватель' : 'Ученик'}</small></div>
        <button className="edu-icon" aria-label="Выйти" title="Выйти" disabled={logout.busy}
          onClick={() => void logout.run(api.logout)}><LogOut /></button>
      </div>
    </aside>
    <main className="edu-main">
      <Feedback message={logout.error} />
      {section === 'courses' && (courseId ? <CourseRoom key={courseId} id={courseId} user={user} back={() => setCourseId(undefined)} />
        : <Courses user={user} open={setCourseId} />)}
      {section === 'calendar' && <><PageHeading title="Расписание" description="Ваши занятия по московскому времени" /><Schedule /></>}
      {section === 'library' && <><PageHeading title="Библиотека" description="Учебные файлы и доступ к ним из курсов" /><Library user={user} /></>}
    </main>
  </div>;
}

function PageHeading({ title, description, children }: { title: string; description?: string; children?: React.ReactNode }) {
  return <header className="edu-heading"><div><h1>{title}</h1>{description && <p>{description}</p>}</div>{children}</header>;
}

function Courses({ user, open }: { user: Profile; open: (id: string) => void }) {
  const scopes: { id: Scope; title: string }[] = [];
  if (user.roles.includes('ADMIN')) scopes.push({ id: 'ALL', title: 'Все курсы' });
  if (user.roles.includes('TEACHER')) scopes.push({ id: 'OWNED', title: 'Мои курсы' });
  if (user.roles.includes('STUDENT')) scopes.push({ id: 'ENROLLED', title: 'Мои занятия' });
  const [scope, setScope] = useState(scopes[0].id);
  const [showCreate, setShowCreate] = useState(false);
  const [extra, setExtra] = useState<Page<Course>>();
  const path = `/courses?scope=${scope}`;
  const courses = useResource<Page<Course>>(path);
  const more = useAction();
  const isManager = scopes.some(value => value.id !== 'ENROLLED');
  const items = [...(courses.data?.items || []), ...(extra?.items || [])];
  const cursor = (extra || courses.data)?.nextCursor;
  function select(value: Scope) { setScope(value); setExtra(undefined); }

  return <>
    <PageHeading title="Курсы" description="Откройте курс, чтобы посмотреть занятия, материалы и чат">
      {isManager && <button className="edu-primary" onClick={() => setShowCreate(true)}><Plus />Создать курс</button>}
    </PageHeading>
    {scopes.length > 1 && <div className="edu-tabs" aria-label="Тип курсов">
      {scopes.map(view => <button key={view.id} aria-pressed={scope === view.id} onClick={() => select(view.id)}>{view.title}</button>)}
    </div>}
    <Feedback message={courses.error || more.error} />
    {courses.loading && <Loading />}
    {courses.data && items.length === 0 && <Empty icon={<BookOpen />} title="Здесь пока нет курсов">
      <p>{isManager ? 'Создайте курс и добавьте в него учеников.' : 'Курсы появятся после зачисления преподавателем.'}</p>
      {isManager && <button className="edu-button" onClick={() => setShowCreate(true)}><Plus />Создать курс</button>}
    </Empty>}
    <div className="edu-course-grid">
      {items.map((course, index) => <button key={course.id} className="edu-course-card" data-color={index % 3} onClick={() => open(course.id)}>
        <span className="edu-card-icon"><BookOpen /></span><strong>{course.title}</strong>
        <span className="edu-card-action">Открыть курс <ArrowRight /></span>
      </button>)}
    </div>
    {cursor && <button className="edu-button edu-more" disabled={more.busy} onClick={() => void more.run(async () => {
      const page = await request<Page<Course>>(`${path}&cursor=${encodeURIComponent(cursor)}`);
      setExtra({ ...page, items: [...(extra?.items || []), ...page.items] });
    })}>Показать ещё</button>}
    {showCreate && <CourseForm user={user} close={() => setShowCreate(false)} saved={open} />}
  </>;
}

function CourseForm({ user, course, saved, close }: { user: Profile; course?: Course; saved: (id: string) => void; close: () => void }) {
  const action = useAction();
  const chooseTeacher = !course && user.roles.includes('ADMIN') && !user.roles.includes('TEACHER');
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const title = String(form.get('title') || '').trim();
    const teacherId = String(form.get('teacherId') || '').trim();
    if (!title) return;
    void action.run(async () => {
      const result = await request<Course>(course ? `/courses/${course.id}` : '/courses', course ? 'PATCH' : 'POST',
        { title, ...(teacherId && { teacherId }) });
      close();
      saved(result.id);
    });
  }
  return <Modal title={course ? 'Название курса' : 'Новый курс'} close={close}><form onSubmit={submit}>
    <Field label="Название"><input name="title" defaultValue={course?.title} placeholder="Например, Математика · 9 класс" required maxLength={200} autoFocus /></Field>
    {chooseTeacher && <Field label="ID преподавателя"><input name="teacherId" required /></Field>}
    <Feedback message={action.error} />
    <Actions><button className="edu-button" type="button" onClick={close}>Отмена</button>
      <button className="edu-primary" disabled={action.busy}>{course ? 'Сохранить' : 'Создать курс'}</button></Actions>
  </form></Modal>;
}

function CourseRoom({ id, user, back }: { id: string; user: Profile; back: () => void }) {
  const course = useResource<Course>(`/courses/${id}`);
  const [tab, setTab] = useState<CourseTab>('schedule');
  const [dialog, setDialog] = useState<'rename' | 'delete'>();
  const deletion = useAction(back);
  const current = course.data;
  if (!current) return <><button className="edu-back" onClick={back}><ArrowLeft />Курсы</button>
    {course.loading ? <Loading /> : <Feedback message={course.error} />}</>;
  const manage = user.roles.includes('ADMIN') || (user.roles.includes('TEACHER') && current.teacherId === user.id);
  const tabs = [
    { id: 'schedule', title: 'Расписание', icon: CalendarDays },
    ...(manage ? [{ id: 'roster' as const, title: 'Участники', icon: UsersRound }] : []),
    { id: 'materials', title: 'Материалы', icon: FolderOpen }, { id: 'chat', title: 'Чат', icon: MessageSquare },
  ] as const;
  return <>
    <div className="edu-crumb"><button onClick={back}>Курсы</button><ChevronRight /><span>{current.title}</span></div>
    <PageHeading title={current.title}>
      {manage && <div className="edu-inline">
        <button className="edu-button" onClick={() => setDialog('rename')}><Pencil />Переименовать</button>
        <button className="edu-icon edu-danger-text" aria-label="Удалить курс" title="Удалить курс" onClick={() => setDialog('delete')}><Trash2 /></button>
      </div>}
    </PageHeading>
    <div className="edu-tabs" aria-label="Разделы курса">
      {tabs.map(({ id, title, icon: Icon }) => <button key={id} aria-pressed={tab === id} onClick={() => setTab(id)}><Icon />{title}</button>)}
    </div>
    {tab === 'schedule' && <Schedule courseId={id} manage={manage} />}
    {tab === 'roster' && <Roster courseId={id} />}
    {tab === 'materials' && <CourseMaterials courseId={id} />}
    {tab === 'chat' && <Chat course={current} user={user} manage={manage} />}
    {dialog === 'rename' && <CourseForm user={user} course={current} close={() => setDialog(undefined)} saved={() => course.reload()} />}
    {dialog === 'delete' && <Modal title="Удалить курс?" close={() => setDialog(undefined)}>
      <p>Будут удалены расписание, участники и чат курса. Файлы библиотеки сохранятся.</p>
      <Feedback message={deletion.error} />
      <Actions><button className="edu-button" onClick={() => setDialog(undefined)}>Отмена</button>
        <button className="edu-destructive" disabled={deletion.busy} onClick={() => void deletion.run(() => request(`/courses/${id}`, 'DELETE'))}>Удалить курс</button></Actions>
    </Modal>}
  </>;
}
