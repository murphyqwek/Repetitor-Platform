import type { Metadata } from 'next';
import { Workspace } from '../../education/Workspace';
import './workspace.css';

export const metadata: Metadata = { title: 'Учебный кабинет' };

export default function LearnPage() { return <Workspace />; }
