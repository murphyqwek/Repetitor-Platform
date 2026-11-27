import { AuthScreen } from '../../components/AuthScreen';
import type { Metadata } from 'next';

export const metadata: Metadata = { title: 'Регистрация родителя' };
export default function RegisterPage() { return <AuthScreen mode="parent" />; }
