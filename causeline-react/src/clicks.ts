// SPDX-License-Identifier: Apache-2.0

/** How long after a click an otherwise unlinked request is attributed to it. */
export const INFERENCE_WINDOW_MS = 1000;

const MAX_LABEL = 40;

let lastClick: { label: string; at: number } | undefined;

/** The label of a click within the inference window, if any. */
export function recentClick(now: number): string | undefined {
  return lastClick && now - lastClick.at <= INFERENCE_WINDOW_MS ? lastClick.label : undefined;
}

function labelOf(element: Element): string {
  const explicit = element.getAttribute('aria-label') ?? element.getAttribute('name') ?? element.id;
  const text = explicit || element.textContent?.replace(/\s+/g, ' ').trim() || element.tagName.toLowerCase();
  return text.length > MAX_LABEL ? `${text.slice(0, MAX_LABEL)}…` : text;
}

/**
 * Remembers the last click on a button or link, and the last form submit. Nothing is recorded
 * as a span: the click only annotates requests that follow it, marked as an inferred cause.
 */
export function watchClicks(now: () => number): () => void {
  const onClick = (event: Event) => {
    const target = event.target instanceof Element
      ? event.target.closest('button, a, [role="button"], input[type="submit"], input[type="button"]')
      : null;
    if (target) {
      lastClick = { label: `Click "${labelOf(target)}"`, at: now() };
    }
  };
  const onSubmit = (event: Event) => {
    if (event.target instanceof HTMLFormElement) {
      lastClick = { label: `Submit "${labelOf(event.target)}"`, at: now() };
    }
  };
  document.addEventListener('click', onClick, true);
  document.addEventListener('submit', onSubmit, true);
  return () => {
    document.removeEventListener('click', onClick, true);
    document.removeEventListener('submit', onSubmit, true);
    lastClick = undefined;
  };
}
