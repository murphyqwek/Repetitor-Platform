import Link from 'next/link';

export function PolicyPlaceholder({ title }: { title: string }) {
  return <main className="policy-page">
    <Link className="brand" href="/login">репетитор<span className="brand-dot" /></Link>
    <p className="eyebrow">Временная страница</p>
    <h1>{title}</h1>
    <p>Документ ещё готовится. Здесь будет опубликован полный текст перед запуском платформы.</p>
    <p>Эта заглушка используется для проверки регистрации и не заменяет условия или согласие на обработку данных.</p>
    <Link className="text-link" href="/register">К регистрации</Link>
  </main>;
}
