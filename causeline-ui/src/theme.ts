// SPDX-License-Identifier: Apache-2.0
import { useCallback, useState } from 'react';

export type Theme = 'dark' | 'light';

const KEY = 'causeline-ui-theme';

/** Dark unless the user picked light before. Storage can be unavailable (private mode, previews). */
export function savedTheme(): Theme {
  try {
    return localStorage.getItem(KEY) === 'light' ? 'light' : 'dark';
  } catch {
    return 'dark';
  }
}

export function applyTheme(theme: Theme): void {
  if (typeof document === 'undefined') {
    return;
  }
  if (theme === 'light') {
    document.documentElement.setAttribute('data-theme', 'light');
  } else {
    document.documentElement.removeAttribute('data-theme');
  }
}

export function useTheme(): [Theme, () => void] {
  const [theme, setTheme] = useState<Theme>(() => (typeof window === 'undefined' ? 'dark' : savedTheme()));
  const toggle = useCallback(() => {
    setTheme((current) => {
      const next: Theme = current === 'light' ? 'dark' : 'light';
      applyTheme(next);
      try {
        localStorage.setItem(KEY, next);
      } catch {
        // Not remembered; the switch still applies to this page.
      }
      return next;
    });
  }, []);
  return [theme, toggle];
}
