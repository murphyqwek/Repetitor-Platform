'use client';

import { useEffect, useId, useRef, type ReactNode } from 'react';
import { CircleAlert, LoaderCircle, X } from 'lucide-react';

export function Feedback({ message }: { message?: string }) {
  return message ? <div className="edu-feedback" role="alert"><CircleAlert size={17} />{message}</div> : null;
}

export function Loading() {
  return <div className="edu-loading" role="status"><LoaderCircle size={20} /> Загрузка…</div>;
}

export function Empty({ icon, title, children }: { icon: ReactNode; title: string; children?: ReactNode }) {
  return <div className="edu-empty"><span className="edu-symbol">{icon}</span><h3>{title}</h3>{children}</div>;
}

export function Modal({ title, close, children }: { title: string; close: () => void; children: ReactNode }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const id = useId();
  useEffect(() => { dialog.current?.showModal(); }, []);
  return <dialog ref={dialog} className="edu-dialog" aria-labelledby={id} onClose={close}>
    <header><h2 id={id}>{title}</h2><button type="button" className="edu-icon" aria-label="Закрыть" onClick={close}><X /></button></header>
    {children}
  </dialog>;
}

export function Actions({ children }: { children: ReactNode }) { return <div className="edu-actions">{children}</div>; }

export function Field({ label, children }: { label: string; children: ReactNode }) {
  return <label className="edu-field"><span>{label}</span>{children}</label>;
}
