import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import '@fontsource/manrope/400.css';
import '@fontsource/manrope/500.css';
import '@fontsource/manrope/600.css';
import './globals.css';

export const metadata: Metadata = {
  title: { default: 'Репетитор · Вход в кабинет', template: '%s · Репетитор' },
  description: 'Платформа занятий с репетитором. Вход для учеников, родителей и преподавателей.',
  robots: { index: false, follow: false },
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return <html lang="ru"><body>{children}</body></html>;
}
