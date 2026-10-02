// SPDX-License-Identifier: Apache-2.0

const STORAGE_KEY = 'causeline.token';

/**
 * The access token arrives once, in the link printed by the app (`/causeline/?token=…`).
 * It is kept for this browser tab only and removed from the address bar, so it does not end up
 * in history or screenshots.
 */
export function readToken(): string | undefined {
  const url = new URL(window.location.href);
  const fromUrl = url.searchParams.get('token');
  if (fromUrl) {
    try {
      sessionStorage.setItem(STORAGE_KEY, fromUrl);
    } catch {
      // Storage can be unavailable (private mode); the token still works for this page load.
    }
    url.searchParams.delete('token');
    window.history.replaceState(null, '', url.pathname + url.search + url.hash);
    return fromUrl;
  }
  try {
    return sessionStorage.getItem(STORAGE_KEY) ?? undefined;
  } catch {
    return undefined;
  }
}
