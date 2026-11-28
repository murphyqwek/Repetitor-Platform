'use client';

import { useEffect, useState } from 'react';
import { isTheme, type Theme, themeIds, themes, themeStorageKey } from '../themes';

export function ThemePicker() {
  const [theme, setTheme] = useState<Theme>('pink');

  useEffect(() => {
    const current = document.documentElement.dataset.theme;
    setTheme(isTheme(current) ? current : 'pink');
    function syncTheme(event: StorageEvent) {
      if (event.key !== themeStorageKey && event.key !== null) return;
      const next = isTheme(event.newValue) ? event.newValue : 'pink';
      document.documentElement.dataset.theme = next;
      setTheme(next);
    }
    window.addEventListener('storage', syncTheme);
    return () => window.removeEventListener('storage', syncTheme);
  }, []);

  function selectTheme(value: string) {
    if (!isTheme(value)) return;
    document.documentElement.dataset.theme = value;
    setTheme(value);
    try {
      window.localStorage.setItem(themeStorageKey, value);
    } catch {
      // The selection still applies when browser storage is blocked.
    }
  }

  return <label className="theme-picker">
    <span>Тема</span>
    <select value={theme} onChange={event => selectTheme(event.target.value)}>
      {themeIds.map(id => <option key={id} value={id}>{themes[id]}</option>)}
    </select>
  </label>;
}
