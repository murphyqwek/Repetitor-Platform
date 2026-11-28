import { beforeEach, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AuthScreen } from './AuthScreen';
import { api, ApiError, type Profile } from '../api';

const navigation = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock('next/navigation', () => ({ useRouter: () => navigation }));
const profile: Profile = { id: '1', login: 'parent1', firstName: null, surname: null, patronymic: null, roles: ['PARENT'], nameRequested: false, blocked: false };

beforeEach(() => {
  navigation.replace.mockReset();
  vi.spyOn(api, 'session').mockRejectedValue(new ApiError(401, 'unauthorized'));
});

it('logs in then redirects to required profile completion, without storing tokens', async () => {
  const storageWrite = vi.fn();
  vi.stubGlobal('localStorage', { setItem: storageWrite });
  const login = vi.spyOn(api, 'login').mockResolvedValue();
  vi.spyOn(api, 'me').mockResolvedValue(profile);
  render(<AuthScreen mode="login" />);
  await userEvent.type(screen.getByLabelText('Логин'), 'parent1');
  await userEvent.type(screen.getByLabelText('Пароль', { exact: true }), 'password123');
  await userEvent.click(screen.getByRole('button', { name: 'Войти' }));
  await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith('/profile/complete'));
  expect(login).toHaveBeenCalledWith('parent1', 'password123');
  expect(storageWrite).not.toHaveBeenCalled();
});

it('toggles password visibility and retains useful login errors', async () => {
  vi.spyOn(api, 'login').mockRejectedValue(new ApiError(401, 'Неверный логин или пароль'));
  render(<AuthScreen mode="login" />);
  await userEvent.type(screen.getByLabelText('Логин'), 'parent1');
  const password = screen.getByLabelText('Пароль', { exact: true });
  await userEvent.type(password, 'password123');
  await userEvent.click(screen.getByRole('button', { name: 'Показать пароль' }));
  expect(password).toHaveAttribute('type', 'text');
  await userEvent.click(screen.getByRole('button', { name: 'Войти' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Неверный логин или пароль');
  expect(screen.getByLabelText('Логин')).toHaveValue('parent1');
});

it('blocks mismatched registration before calling backend', async () => {
  const parent = vi.spyOn(api, 'parent');
  render(<AuthScreen mode="parent" />);
  await userEvent.type(screen.getByLabelText('Логин'), 'parent1');
  await userEvent.type(screen.getByLabelText('Пароль', { exact: true }), 'password123');
  await userEvent.type(screen.getByLabelText('Повторите пароль', { exact: true }), 'different');
  await userEvent.type(screen.getByLabelText('Код приглашения'), 'ABCD1234EF56');
  await userEvent.click(screen.getByRole('checkbox'));
  await userEvent.click(screen.getByRole('button', { name: 'Создать аккаунт' }));
  expect(await screen.findByText('Пароли не совпадают')).toBeVisible();
  expect(parent).not.toHaveBeenCalled();
});

it('registers a parent without submitting password confirmation', async () => {
  const parent = vi.spyOn(api, 'parent').mockResolvedValue();
  vi.spyOn(api, 'me').mockResolvedValue(profile);
  render(<AuthScreen mode="parent" />);
  await userEvent.type(screen.getByLabelText('Логин'), 'parent1');
  await userEvent.type(screen.getByLabelText('Пароль', { exact: true }), 'password123');
  await userEvent.type(screen.getByLabelText('Повторите пароль', { exact: true }), 'password123');
  await userEvent.type(screen.getByLabelText('Код приглашения'), 'abcd1234ef56');
  await userEvent.click(screen.getByRole('checkbox'));
  await userEvent.click(screen.getByRole('button', { name: 'Создать аккаунт' }));
  await waitFor(() => expect(parent).toHaveBeenCalledWith({ login: 'parent1', password: 'password123', invitationCode: 'ABCD1234EF56', policyVersion: 'draft' }));
});

it('does not show teacher form until invitation is validated and handles expired links', async () => {
  vi.spyOn(api, 'invitation').mockRejectedValue(new ApiError(400, 'expired'));
  render(<AuthScreen mode="teacher" invitationCode="expired" />);
  expect(screen.queryByLabelText('Логин')).not.toBeInTheDocument();
  expect(await screen.findByRole('alert')).toHaveTextContent('Попросите администратора');
  expect(screen.queryByLabelText('Логин')).not.toBeInTheDocument();
});

it('uses teacher invitation from route rather than parent invite field', async () => {
  vi.spyOn(api, 'invitation').mockResolvedValue();
  const teacher = vi.spyOn(api, 'teacher').mockResolvedValue();
  vi.spyOn(api, 'me').mockResolvedValue({ ...profile, roles: ['TEACHER'] });
  render(<AuthScreen mode="teacher" invitationCode="teacher-token" />);
  await userEvent.type(await screen.findByLabelText('Логин'), 'teacher1');
  await userEvent.type(screen.getByLabelText('Пароль', { exact: true }), 'password123');
  await userEvent.type(screen.getByLabelText('Повторите пароль', { exact: true }), 'password123');
  expect(screen.queryByLabelText('Код приглашения')).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('checkbox'));
  await userEvent.click(screen.getByRole('button', { name: 'Создать аккаунт' }));
  await waitFor(() => expect(teacher).toHaveBeenCalledWith('teacher-token', { login: 'teacher1', password: 'password123', policyVersion: 'draft' }));
});

it('completes name before allowing account view', async () => {
  vi.spyOn(api, 'session').mockResolvedValue(profile);
  const save = vi.spyOn(api, 'profile').mockResolvedValue({ ...profile, nameRequested: true });
  render(<AuthScreen mode="profile" />);
  await userEvent.type(await screen.findByLabelText('Фамилия'), 'Иванова');
  await userEvent.type(screen.getByLabelText('Имя', { exact: true }), 'Мария');
  await userEvent.click(screen.getByRole('button', { name: 'Сохранить и продолжить' }));
  await waitFor(() => expect(save).toHaveBeenCalledWith({ firstName: 'Мария', surname: 'Иванова', patronymic: '' }));
  expect(navigation.replace).toHaveBeenCalledWith('/account');
});
