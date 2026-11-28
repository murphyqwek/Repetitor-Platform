'use client';

import { useState, type FormEvent } from 'react';
import { ArrowLeft, ChevronRight, Download, FileText, FolderOpen, FolderPlus, MoreHorizontal, Upload } from 'lucide-react';
import type { Profile } from '../api';
import { Actions, Empty, Feedback, Field, Loading, Modal } from './controls';
import { type Page, type Course, type Material, type Folder, type LibraryContents, bytes, download, request, useAction, useResource } from './client';

type Entry = { kind: 'files' | 'folders'; id: string; name: string };
type Edit = { type: 'create' | 'upload' } | { type: 'rename' | 'move' | 'remove' | 'access'; entry: Entry };

export function CourseMaterials({ courseId }: { courseId: string }) {
  const path = `/courses/${courseId}/materials`;
  const first = useResource<Page<Material>>(path);
  const [extra, setExtra] = useState<Page<Material>>();
  const more = useAction();
  const page = extra || first.data;
  const cursor = page?.nextCursor;
  const files = [...(first.data?.items || []), ...(extra?.items || [])];
  return <>
    <div className="edu-section-bar"><h2>Доступные материалы</h2></div>
    {first.loading && <Loading />}
    <Feedback message={first.error || more.error} />
    {first.data && files.length === 0 && <Empty icon={<FolderOpen />} title="Материалов пока нет"><p>Файлы появятся, когда преподаватель откроет доступ для курса.</p></Empty>}
    {files.map(file => <FileRow key={file.id} item={file} url={download(file.downloadUrl || `${path}/files/${file.id}/content`)} />)}
    {cursor && <button className="edu-button edu-more" disabled={more.busy} onClick={() => void more.run(async () => {
      const next = await request<Page<Material>>(`${path}?cursor=${encodeURIComponent(cursor)}`);
      setExtra({ ...next, items: [...(extra?.items || []), ...next.items] });
    })}>Показать ещё</button>}
  </>;
}

export function Library({ user }: { user: Profile }) {
  const admin = user.roles.includes('ADMIN');
  const teacher = user.roles.includes('TEACHER');
  const [owner, setOwner] = useState('');
  const [crumbs, setCrumbs] = useState<Folder[]>([]);
  const [edit, setEdit] = useState<Edit>();
  const current = crumbs.at(-1);
  const available = teacher || !!owner;
  const content = useResource<LibraryContents>(available ? libraryPath(current?.id, owner) : null);
  function selectOwner(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const teacherId = String(new FormData(event.currentTarget).get('owner') || '').trim();
    setCrumbs([]);
    setOwner(teacherId);
  }

  return <>
    {admin && <form className="edu-search edu-admin-library" onSubmit={selectOwner}>
      <Field label="ID преподавателя"><input name="owner" placeholder={teacher ? 'По умолчанию — ваша библиотека' : 'UUID владельца библиотеки'} /></Field>
      <button className="edu-button">Открыть</button>
    </form>}
    {available && <>
      <div className="edu-section-bar">
        <nav className="edu-crumb" aria-label="Путь к папке"><button onClick={() => setCrumbs([])}>Корень</button>
          {crumbs.map((folder, index) => <span key={folder.id}><ChevronRight /><button onClick={() => setCrumbs(crumbs.slice(0, index + 1))}>{folder.name}</button></span>)}
        </nav>
        <div className="edu-inline">
          <button className="edu-button" onClick={() => setEdit({ type: 'create' })}><FolderPlus />Новая папка</button>
          <button className="edu-primary" onClick={() => setEdit({ type: 'upload' })}><Upload />Загрузить файл</button>
        </div>
      </div>
      <Feedback message={content.error} />
      {content.loading && <Loading />}
      {content.data && !content.data.folders.length && !content.data.files.length && <Empty icon={<FolderOpen />} title="В папке пусто">
        <p>Загрузите файл или создайте вложенную папку.</p>
      </Empty>}
      {content.data?.folders.map(folder => <div key={folder.id} className="edu-row">
        <span className="edu-symbol"><FolderOpen /></span>
        <button className="edu-row-title edu-grow" onClick={() => setCrumbs([...crumbs, folder])}>{folder.name}</button>
        <FileMenu item={{ ...folder, kind: 'folders' }} select={setEdit} />
      </div>)}
      {content.data?.files.map(file => <FileRow key={file.id} item={file} url={download(`/materials/files/${file.id}/content`)}>
        <FileMenu item={{ ...file, kind: 'files' }} select={setEdit} />
      </FileRow>)}
    </>}
    {edit && (edit.type === 'access' ?
      <AccessForm item={edit.entry} admin={admin} close={() => setEdit(undefined)} /> :
      <LibraryForm edit={edit} folderId={current?.id || null} owner={owner} close={() => setEdit(undefined)}
        saved={() => { setEdit(undefined); content.reload(); }} />)}
  </>;
}

function libraryPath(folder?: string, owner?: string) {
  const params = new URLSearchParams({ parentId: folder || 'root' });
  if (owner) params.set('teacherId', owner);
  return '/materials?' + params;
}

function FileRow({ item, url, children }: { item: Material; url: string; children?: React.ReactNode }) {
  return <div className="edu-row">
    <span className="edu-symbol"><FileText /></span>
    <div className="edu-grow"><strong>{item.name}</strong><small>{bytes(item.sizeBytes)}</small></div>
    <a className="edu-icon" href={url} title="Скачать" aria-label={`Скачать ${item.name}`}><Download /></a>{children}
  </div>;
}

function FileMenu({ item, select }: { item: Entry; select: (edit: Edit) => void }) {
  return <details className="edu-menu">
    <summary className="edu-icon" aria-label={`Действия с ${item.name}`}><MoreHorizontal /></summary>
    <div>
      <button onClick={event => { event.currentTarget.closest('details')?.removeAttribute('open'); select({ type: 'rename', entry: item }); }}>Переименовать</button>
      {item.kind === 'files' && <button onClick={event => { event.currentTarget.closest('details')?.removeAttribute('open'); select({ type: 'move', entry: item }); }}>Переместить</button>}
      <button onClick={event => { event.currentTarget.closest('details')?.removeAttribute('open'); select({ type: 'access', entry: item }); }}>Настроить доступ</button>
      <button className="edu-danger-text" onClick={event => { event.currentTarget.closest('details')?.removeAttribute('open'); select({ type: 'remove', entry: item }); }}>Удалить</button>
    </div>
  </details>;
}

function LibraryForm({ edit, folderId, owner, close, saved }: {
  edit: Edit; folderId: string | null; owner: string; close: () => void; saved: () => void;
}) {
  const action = useAction(saved);
  const [folders, setFolders] = useState<Folder[]>([]);
  const destination = folders.at(-1);
  const chooser = useResource<LibraryContents>(edit.type === 'move' ? libraryPath(destination?.id, owner) : null);
  const title = { create: 'Новая папка', upload: 'Загрузить файл', rename: 'Переименовать', move: 'Переместить файл',
    remove: 'Удалить материал?', access: 'Доступ к материалу' }[edit.type];

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const name = String(form.get('name') || '').trim();
    void action.run(() => {
      if (edit.type === 'create') return request('/materials/folders', 'POST', { name, parentId: folderId, ...(owner && { teacherId: owner }) });
      if (edit.type === 'upload') {
        if (folderId) form.set('folderId', folderId);
        if (owner) form.set('teacherId', owner);
        return request('/materials/files', 'POST', form);
      }
      if (!('entry' in edit)) return Promise.resolve();
      const path = `/materials/${edit.entry.kind}/${edit.entry.id}`;
      return edit.type === 'remove' ? request(path, 'DELETE') :
        request(path, 'PATCH', edit.type === 'move' ? { folderId: destination?.id || null } : { name });
    });
  }

  return <Modal title={title} close={close}><form onSubmit={submit}>
    {(edit.type === 'create' || edit.type === 'rename') && <Field label="Название">
      <input name="name" required maxLength={255} defaultValue={edit.type === 'rename' ? edit.entry.name : ''} autoFocus />
    </Field>}
    {edit.type === 'upload' && <Field label="Файл"><input name="file" type="file" required /></Field>}
    {edit.type === 'remove' && <p>{edit.entry.name} будет удалён из библиотеки и станет недоступен в курсах.</p>}
    {edit.type === 'move' && <>
      <p>Папка назначения: <strong>{destination?.name || 'Корень'}</strong></p>
      {destination && <button className="edu-button" type="button" onClick={() => setFolders(folders.slice(0, -1))}><ArrowLeft />На уровень выше</button>}
      {chooser.loading && <Loading />}
      {chooser.data?.folders.map(item => <button className="edu-picker-row" key={item.id} type="button" onClick={() => setFolders([...folders, item])}>
        <FolderOpen />{item.name}<ChevronRight /></button>)}
      <Feedback message={chooser.error} />
    </>}
    <Feedback message={action.error} />
    <Actions><button type="button" className="edu-button" onClick={close}>Отмена</button>
      <button disabled={action.busy} className={edit.type === 'remove' ? 'edu-destructive' : 'edu-primary'}>
        {edit.type === 'remove' ? 'Удалить' : edit.type === 'upload' ? 'Загрузить' : edit.type === 'move' ? 'Переместить сюда' : 'Сохранить'}
      </button></Actions>
  </form></Modal>;
}

function AccessForm({ item, admin, close }: { item: Entry; admin: boolean; close: () => void }) {
  const path = `/materials/${item.kind}/${item.id}/course-access`;
  const courses = useResource<Page<Course>>(`/courses?scope=${admin ? 'ALL' : 'OWNED'}&limit=100`);
  const access = useResource<Array<string | { courseId: string; allowRead: boolean }>>(path);
  const [saved, setSaved] = useState(false);
  const action = useAction(() => { access.reload(); setSaved(true); });
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const course = String(data.get('course') || '');
    const rule = data.get('rule');
    const target = `${path}/${course}`;
    setSaved(false);
    void action.run(() => rule === 'inherit' ? request(target, 'DELETE') :
      request(target, 'PUT', item.kind === 'files' ? { allowRead: rule === 'allow' } : undefined));
  }
  return <Modal title="Доступ к материалу" close={close}>
    <p>{item.name}</p>
    <Feedback message={courses.error || access.error || action.error} />
    {courses.loading && <Loading />}
    {!courses.loading && courses.data?.items.length === 0 && <p>Сначала создайте курс.</p>}
    {access.data && access.data.length > 0 && <ul className="edu-access-list">
      {access.data.map(rule => {
        const id = typeof rule === 'string' ? rule : rule.courseId;
        return <li key={id}>{courses.data?.items.find(course => course.id === id)?.title || id}
          <small>{typeof rule === 'string' || rule.allowRead ? 'Доступ открыт' : 'Доступ закрыт'}</small></li>;
      })}
    </ul>}
    <form onSubmit={submit}>
      {courses.data?.items.length ? <>
        <Field label="Курс"><select required name="course"><option value="">Выберите курс</option>
          {courses.data.items.map(course => <option key={course.id} value={course.id}>{course.title}</option>)}</select></Field>
        <Field label="Правило"><select name="rule">
          <option value="allow">Разрешить чтение</option>
          {item.kind === 'files' && <option value="deny">Запретить чтение</option>}
          <option value="inherit">{item.kind === 'files' ? 'Унаследовать правило папки' : 'Снять прямой доступ'}</option>
        </select></Field>
      </> : null}
      {saved && <p className="edu-success" role="status">Правило сохранено.</p>}
      <Actions><button type="button" className="edu-button" onClick={close}>Закрыть</button>
        <button className="edu-primary" disabled={action.busy || !courses.data?.items.length}>Сохранить</button></Actions>
    </form>
  </Modal>;
}
