import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { themeScript, themeStorageKey } from '../themes';
import { ThemePicker } from './ThemePicker';

beforeEach(() => {
  const storage = new Map<string, string>();
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, value),
  });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  delete document.documentElement.dataset.theme;
});

it('changes the whole document and restores the theme on the next page load', () => {
  render(<ThemePicker />);
  const select = screen.getByRole('combobox', { name: 'Тема' });
  expect(select).toHaveValue('pink');
  expect(screen.getAllByRole('option')).toHaveLength(5);

  fireEvent.change(select, { target: { value: 'green' } });
  expect(document.documentElement).toHaveAttribute('data-theme', 'green');
  expect(window.localStorage.getItem(themeStorageKey)).toBe('green');

  cleanup();
  document.documentElement.dataset.theme = 'pink';
  Function(themeScript)();
  render(<ThemePicker />);
  expect(screen.getByRole('combobox', { name: 'Тема' })).toHaveValue('green');
});

it('keeps the default theme if the saved value is unknown', () => {
  window.localStorage.setItem(themeStorageKey, 'constructor');
  Function(themeScript)();
  expect(document.documentElement).not.toHaveAttribute('data-theme');
});
