export const themes = {
  pink: 'Розовая',
  blue: 'Голубая',
  orange: 'Рыжая',
  red: 'Красная',
  green: 'Зелёная',
} as const;

export type Theme = keyof typeof themes;

export const themeIds = Object.keys(themes) as Theme[];
export const themeStorageKey = 'repetitor-theme';

export function isTheme(value: unknown): value is Theme {
  return typeof value === 'string' && Object.hasOwn(themes, value);
}

export const themeScript = `try {
  const theme = window.localStorage.getItem(${JSON.stringify(themeStorageKey)});
  if (${JSON.stringify(themeIds)}.includes(theme)) document.documentElement.dataset.theme = theme;
} catch {}`;
