import { AuthScreen } from '../../../../components/AuthScreen';
import type { Metadata } from 'next';

export const metadata: Metadata = { title: 'Регистрация преподавателя' };
export default async function TeacherPage({ params }: { params: Promise<{ code: string }> }) {
  const { code } = await params;
  return <AuthScreen mode="teacher" invitationCode={code} />;
}
