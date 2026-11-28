'use client';

import { ThemePicker } from './ThemePicker';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { ArrowLeft, ArrowRight, BookOpen, Check, Eye, EyeOff, Info, LoaderCircle, LogOut, LockKeyhole } from 'lucide-react';
import { api, ApiError, errorMessage, type Profile } from '../api';
import { validateCredentials } from '../validation';

type Mode = 'login' | 'parent' | 'teacher' | 'profile' | 'account';
type Errors = Record<string, string>;
const policyVersion = process.env.NEXT_PUBLIC_POLICY_VERSION || 'draft';
const termsUrl = process.env.NEXT_PUBLIC_TERMS_URL || '/terms';
const privacyUrl = process.env.NEXT_PUBLIC_PRIVACY_URL || '/privacy';

function Field({ name, label, hint, error, children }: {
  name: string; label: string; hint?: string; error?: string; children: ReactNode;
}) {
  return <div className="field">
    <label htmlFor={name}>{label}</label>
    {children}
    {(error || hint) && <p id={`${name}-hint`} className={error ? 'field-error' : 'field-hint'}>{error || hint}</p>}
  </div>;
}

function Password({ name = 'password', label = 'Пароль', registration = false, error }: {
  name?: string; label?: string; registration?: boolean; error?: string;
}) {
  const [visible, setVisible] = useState(false);
  const [capsLock, setCapsLock] = useState(false);
  return <Field name={name} label={label} error={error}
    hint={capsLock ? 'Включён Caps Lock' : registration && name === 'password' ? 'От 8 до 25 символов' : undefined}>
    <div className="password-field">
      <input id={name} name={name} type={visible ? 'text' : 'password'} required maxLength={registration ? 25 : undefined}
        autoComplete={registration ? 'new-password' : 'current-password'} aria-invalid={!!error}
        aria-describedby={error || capsLock || (registration && name === 'password') ? `${name}-hint` : undefined} placeholder={name === 'confirmation' ? 'Ещё раз тот же пароль' : registration ? 'Придумайте пароль' : 'Введите пароль'}
        onKeyUp={event => setCapsLock(event.getModifierState('CapsLock'))} onBlur={() => setCapsLock(false)} />
      <button type="button" className="password-toggle" aria-label={`${visible ? 'Скрыть' : 'Показать'} ${name === 'confirmation' ? 'повтор пароля' : 'пароль'}`}
        aria-pressed={visible} onClick={() => setVisible(!visible)}>{visible ? <EyeOff /> : <Eye />}</button>
    </div>
  </Field>;
}

function StudyPanel() {
  return <aside className="study-panel" aria-label="О платформе">
    <Link href="/login" className="brand"><span className="brand-symbol"><BookOpen /></span>репетитор<span className="brand-dot" /></Link>
    <div className="study-story">
      <p className="eyebrow">Ваше пространство для занятий</p>
      <h2>Уроки, задания<br />и всё между ними</h2>
      <p className="story-description">Учитесь с преподавателем, возвращайтесь<br className="desktop-break" /> к материалам и следите за своим прогрессом</p>
      <div className="notebook-scene" aria-hidden="true">
        <div className="notebook-back" />
        <div className="notebook">
          <div className="notebook-spine" />
          <span className="notebook-caption">Математика</span>
          <p>Больше<br />понимания</p>
          <svg className="notebook-graph" viewBox="0 0 260 148" fill="none">
            <path d="M16 126H244M40 142V8" stroke="currentColor" opacity=".4" />
            <path d="M22 98C64 138 94 128 122 83S173 7 231 30" stroke="currentColor" strokeWidth="2.5" />
            <circle cx="123" cy="82" r="5" fill="currentColor" />
            <path d="M123 88V125M45 82H117" stroke="currentColor" strokeDasharray="3 5" opacity=".5" />
          </svg>
          <span className="notebook-footer">От вопроса к решению</span>
        </div>
        <div className="bookmark" />
      </div>
    </div>
    <p className="study-footer">Для учеников, родителей и преподавателей</p>
  </aside>;
}

export function AuthScreen({ mode, invitationCode = '' }: { mode: Mode; invitationCode?: string }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [checking, setChecking] = useState(true);
  const [invitationValid, setInvitationValid] = useState(false);
  const [profile, setProfile] = useState<Profile | null>(null);
  const [errors, setErrors] = useState<Errors>({});
  const [failure, setFailure] = useState('');
  const [retry, setRetry] = useState(0);
  const alertRef = useRef<HTMLDivElement>(null);
  const formRef = useRef<HTMLFormElement>(null);
  const registration = mode === 'parent' || mode === 'teacher';

  useEffect(() => {
    let active = true;
    setChecking(true);
    setFailure('');
    async function initialize() {
      try {
        const user = await api.session();
        if (!active) return;
        setProfile(user);
        if (mode === 'profile' && user.nameRequested) router.replace('/account');
        else if (mode !== 'profile' && mode !== 'account') router.replace(user.nameRequested ? '/account' : '/profile/complete');
        else if (mode === 'account' && !user.nameRequested) router.replace('/profile/complete');
      } catch (error) {
        if (!active) return;
        if (mode === 'profile' || mode === 'account') {
          if (error instanceof ApiError && error.status === 401) router.replace('/login');
          else setFailure(errorMessage(error));
        } else if (mode === 'teacher') {
          try {
            await api.invitation(invitationCode);
            if (active) setInvitationValid(true);
          } catch (invitationError) {
            if (active) setFailure(invitationError instanceof ApiError && invitationError.status === 400
              ? 'Ссылка недействительна или срок её действия истёк. Попросите администратора прислать новую.'
              : errorMessage(invitationError));
          }
        }
      } finally { if (active) setChecking(false); }
    }
    void initialize();
    return () => { active = false; };
  }, [mode, invitationCode, router, retry]);

  useEffect(() => { if (failure) alertRef.current?.focus(); }, [failure]);
  useEffect(() => {
    if (Object.keys(errors).length) formRef.current?.querySelector<HTMLInputElement>('[aria-invalid="true"]')?.focus();
  }, [errors]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    const data = new FormData(event.currentTarget);
    const value = (name: string) => String(data.get(name) || '');
    setFailure('');
    const validation = mode === 'profile'
      ? Object.fromEntries(['firstName', 'surname'].filter(name => !value(name).trim()).map(name => [name, 'Заполните это поле']))
      : validateCredentials({ login: value('login'), password: value('password'), confirmation: value('confirmation'),
        invitationCode: value('invitationCode'), consent: data.get('consent') === 'on' }, mode);
    setErrors(validation);
    if (Object.keys(validation).length) return;
    setBusy(true);
    try {
      if (mode === 'profile') {
        await api.profile({ firstName: value('firstName').trim(), surname: value('surname').trim(), patronymic: value('patronymic').trim() });
        router.replace('/account');
      } else {
        const credentials = { login: value('login').trim(), password: value('password') };
        if (mode === 'parent') await api.parent({ ...credentials, invitationCode: value('invitationCode').trim().toUpperCase(), policyVersion });
        else if (mode === 'teacher') await api.teacher(invitationCode, { ...credentials, policyVersion });
        else await api.login(credentials.login, credentials.password);
        const user = await api.me();
        router.replace(user.nameRequested ? '/account' : '/profile/complete');
      }
    } catch (error) { setFailure(errorMessage(error)); }
    finally { setBusy(false); }
  }

  async function logout() {
    setBusy(true);
    setFailure('');
    try { await api.logout(); router.replace('/login'); }
    catch (error) { setFailure(errorMessage(error)); }
    finally { setBusy(false); }
  }

  const title = mode === 'parent' ? 'Начнём знакомство' : mode === 'teacher' ? 'Кабинет преподавателя'
    : mode === 'profile' ? 'Как к вам обращаться?' : mode === 'account' ? 'Вы в своём кабинете' : 'Вход в кабинет';
  const description = mode === 'parent' ? 'Создайте аккаунт родителя по коду от преподавателя'
    : mode === 'teacher' ? 'Создайте аккаунт по приглашению администратора'
    : mode === 'profile' ? 'Укажите имя и фамилию, чтобы продолжить. Отчество необязательно.'
    : mode === 'account' ? 'Вход выполнен. Здесь появятся разделы вашей платформы.' : 'Продолжайте занятия там, где остановились';

  return <div className="auth-layout">
    <a className="skip-link" href="#auth-form">Перейти к форме</a>
    <StudyPanel />
    <main className="auth-main">
      <header className="auth-navigation">
        <Link className="mobile-brand brand" href="/login">репетитор<span className="brand-dot" /></Link>
        {mode === 'login' ? <p>Первый раз здесь? <Link href="/register">Создать аккаунт <ArrowRight /></Link></p>
          : registration ? <p>Уже есть аккаунт? <Link href="/login">Войти <ArrowRight /></Link></p>
          : <button className="text-button" disabled={busy} onClick={logout}>Выйти <LogOut /></button>}
        <ThemePicker />
      </header>
      <section className={`form-section ${registration ? 'registration-section' : ''}`} id="auth-form" aria-labelledby="auth-title">
        {mode === 'parent' && <Link className="back-link" href="/login"><ArrowLeft /> К входу</Link>}
        <div className="form-heading">
          <span className="form-mark">{mode === 'account' ? <Check /> : <LockKeyhole />}</span>
          <h1 id="auth-title">{title}</h1>
          <p>{description}</p>
        </div>
        {failure && <div className="error-notice" role="alert" tabIndex={-1} ref={alertRef}><Info /><span>{failure}</span></div>}
        {checking && (mode === 'teacher' || mode === 'profile' || mode === 'account')
          ? <div className="loading-state" role="status"><LoaderCircle className="spinner" /> {mode === 'teacher' ? 'Проверяем приглашение' : 'Открываем профиль'}</div>
          : (mode === 'teacher' && !invitationValid) || ((mode === 'profile' || mode === 'account') && !profile)
            ? <div className="unavailable"><button className="secondary-button" onClick={() => setRetry(retry + 1)}>Проверить ещё раз</button><Link href="/login">Вернуться ко входу</Link></div>
            : mode === 'account' ? <div className="account-summary">
              <div className="profile-avatar">{(profile?.firstName || profile?.login || '?')[0].toUpperCase()}</div>
              <h2>{[profile?.firstName, profile?.surname].filter(Boolean).join(' ') || profile?.login}</h2>
              <p>{profile?.roles.map(role => ({ PARENT: 'Родитель', TEACHER: 'Преподаватель', STUDENT: 'Ученик', ADMIN: 'Администратор' })[role]).join(', ')}</p>
              <p className="account-login">Логин: {profile?.login}</p>
              {profile?.roles.some(role => role === 'TEACHER' || role === 'STUDENT' || role === 'ADMIN') &&
                <Link className="primary-button" href="/learn">Открыть учебный кабинет <ArrowRight /></Link>}
              <button className="secondary-button" disabled={busy} onClick={logout}>Выйти из аккаунта</button>
            </div> : <form ref={formRef} onSubmit={submit} noValidate aria-busy={busy}>
              <fieldset disabled={busy}>
                {mode === 'profile' ? <>
                  <Field name="surname" label="Фамилия" error={errors.surname}><input id="surname" name="surname" autoComplete="family-name" maxLength={255} required defaultValue={profile?.surname || ''} aria-invalid={!!errors.surname} aria-describedby="surname-hint" /></Field>
                  <Field name="firstName" label="Имя" error={errors.firstName}><input id="firstName" name="firstName" autoComplete="given-name" maxLength={255} required defaultValue={profile?.firstName || ''} aria-invalid={!!errors.firstName} aria-describedby="firstName-hint" /></Field>
                  <Field name="patronymic" label="Отчество" hint="Если есть"><input id="patronymic" name="patronymic" autoComplete="additional-name" maxLength={255} defaultValue={profile?.patronymic || ''} aria-describedby="patronymic-hint" /></Field>
                </> : <>
                  <Field name="login" label="Логин" hint={registration ? 'От 5 до 25 символов' : undefined} error={errors.login}>
                    <input id="login" name="login" type="text" autoComplete="username" autoCapitalize="none" spellCheck={false}
                      required maxLength={registration ? 25 : undefined} placeholder={registration ? 'Придумайте логин' : 'Введите логин'}
                      aria-invalid={!!errors.login} aria-describedby={registration || errors.login ? 'login-hint' : undefined} />
                  </Field>
                  <Password registration={registration} error={errors.password} />
                  {registration && <Password name="confirmation" label="Повторите пароль" registration error={errors.confirmation} />}
                  {mode === 'parent' && <Field name="invitationCode" label="Код приглашения" hint="12 букв и цифр от преподавателя" error={errors.invitationCode}>
                    <input className="invite-input" id="invitationCode" name="invitationCode" placeholder="Например, ABCD1234EF56" maxLength={12}
                      autoComplete="off" autoCapitalize="characters" spellCheck={false} aria-invalid={!!errors.invitationCode} aria-describedby="invitationCode-hint" required />
                  </Field>}
                  {registration && <div className="consent-group">
                    <label className="consent"><input id="consent" name="consent" type="checkbox" required aria-invalid={!!errors.consent} aria-describedby="consent-hint" />
                      <span>Принимаю <a href={termsUrl} target="_blank" rel="noopener noreferrer">условия использования</a> и даю <a href={privacyUrl} target="_blank" rel="noopener noreferrer">согласие на обработку персональных данных</a></span>
                    </label>
                    <p id="consent-hint" className={errors.consent ? 'field-error' : 'field-hint'}>{errors.consent || (policyVersion === 'draft' ? 'Документы пока представлены заглушками' : '')}</p>
                  </div>}
                </>}
                <button className="primary-button" type="submit">{busy ? <><LoaderCircle className="spinner" /> {mode === 'login' ? 'Входим…' : 'Сохраняем…'}</>
                  : <>{mode === 'login' ? 'Войти' : mode === 'profile' ? 'Сохранить и продолжить' : 'Создать аккаунт'}<ArrowRight /></>}</button>
              </fieldset>
            </form>}
        {mode === 'login' && <details className="help-details"><summary>Не получается войти?</summary><p>Ученику логин и пароль выдаёт родитель. Если вы родитель или преподаватель и забыли данные, обратитесь к администратору платформы.</p></details>}
        {mode === 'parent' && <div className="registration-note"><Info /><p>Аккаунт ученика создаётся после заявки родителя. Преподаватель регистрируется по ссылке администратора.</p></div>}
      </section>
      <footer className="auth-footer"><span>Занятия с репетитором</span><span className="footer-rule" /><a href={privacyUrl} target="_blank" rel="noopener noreferrer">Конфиденциальность</a></footer>
    </main>
  </div>;
}
