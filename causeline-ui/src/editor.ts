// SPDX-License-Identifier: Apache-2.0
import { useCallback, useState } from 'react';

export type Editor = 'vscode' | 'cursor' | 'idea';

export const EDITORS: { id: Editor; label: string }[] = [
  { id: 'vscode', label: 'VS Code' },
  { id: 'idea', label: 'IntelliJ IDEA' },
  { id: 'cursor', label: 'Cursor' },
];

const KEY = 'causeline-ui-editor';

/** The editor "Open in editor" uses, remembered per browser. */
export function useEditor(): [Editor, (editor: Editor) => void] {
  const [editor, setEditor] = useState<Editor>(() => {
    try {
      const saved = localStorage.getItem(KEY);
      return saved === 'idea' || saved === 'cursor' ? saved : 'vscode';
    } catch {
      return 'vscode';
    }
  });
  const choose = useCallback((next: Editor) => {
    setEditor(next);
    try {
      localStorage.setItem(KEY, next);
    } catch {
      // Not remembered; it still applies now.
    }
  }, []);
  return [editor, choose];
}

/**
 * A link that opens the file at the line. VS Code and Cursor register URL schemes; IntelliJ
 * listens on its built-in server (port 63342) and asks once before opening files for a page.
 */
export function editorUrl(editor: Editor, path: string, line: number): string {
  const forward = path.replace(/\\/g, '/');
  switch (editor) {
    case 'idea':
      return `http://localhost:63342/api/file/${encodeURI(forward)}:${line}`;
    case 'cursor':
      return `cursor://file/${encodeURI(forward.replace(/^\//, ''))}:${line}`;
    default:
      return `vscode://file/${encodeURI(forward.replace(/^\//, ''))}:${line}`;
  }
}

/** Opens the file without leaving the page. */
export function openInEditor(editor: Editor, path: string, line: number): void {
  const url = editorUrl(editor, path, line);
  if (editor === 'idea') {
    // A plain request is enough; the IDE comes to the front.
    void fetch(url, { mode: 'no-cors' }).catch(() => window.open(url, '_blank', 'noopener'));
  } else {
    window.location.href = url;
  }
}
