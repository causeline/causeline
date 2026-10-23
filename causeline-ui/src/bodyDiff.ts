// SPDX-License-Identifier: Apache-2.0
import type { SpanRow, TraceView } from './api';

/** One field that differs between two JSON documents. */
export interface FieldChange {
  /** Where the field is, e.g. `items[0].price`; empty for the document itself */
  path: string;
  kind: 'added' | 'removed' | 'changed';
  /** JSON text of the value on each side; undefined on the side that doesn't have the field */
  before?: string;
  after?: string;
}

const MAX_CHANGES = 50;
const MAX_VALUE_CHARS = 120;

const isObject = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

function show(value: unknown): string {
  const text = JSON.stringify(value) ?? 'undefined';
  return text.length > MAX_VALUE_CHARS ? `${text.slice(0, MAX_VALUE_CHARS)}…` : text;
}

/**
 * The fields that differ between two parsed JSON values. Objects are compared by key and arrays
 * by position; a value whose type changed is one change, not one per field underneath it.
 */
export function jsonDiff(before: unknown, after: unknown): FieldChange[] {
  const changes: FieldChange[] = [];
  walk(before, after, '', changes);
  return changes;
}

function walk(before: unknown, after: unknown, path: string, changes: FieldChange[]): void {
  if (changes.length >= MAX_CHANGES) {
    return;
  }
  if (isObject(before) && isObject(after)) {
    for (const key of new Set([...Object.keys(before), ...Object.keys(after)])) {
      const at = path ? `${path}.${key}` : key;
      if (!(key in after)) {
        changes.push({ path: at, kind: 'removed', before: show(before[key]) });
      } else if (!(key in before)) {
        changes.push({ path: at, kind: 'added', after: show(after[key]) });
      } else {
        walk(before[key], after[key], at, changes);
      }
    }
  } else if (Array.isArray(before) && Array.isArray(after)) {
    for (let i = 0; i < Math.max(before.length, after.length); i++) {
      const at = `${path}[${i}]`;
      if (i >= after.length) {
        changes.push({ path: at, kind: 'removed', before: show(before[i]) });
      } else if (i >= before.length) {
        changes.push({ path: at, kind: 'added', after: show(after[i]) });
      } else {
        walk(before[i], after[i], at, changes);
      }
    }
  } else if (JSON.stringify(before) !== JSON.stringify(after)) {
    changes.push({ path, kind: 'changed', before: show(before), after: show(after) });
  }
}

/** How one request's response body differs between two runs. */
export interface ResponseDiff {
  /** The request span's name, e.g. POST /api/orders */
  request: string;
  /** Field by field when both bodies are JSON; empty when they are the same */
  changes: FieldChange[];
  /** Set instead of `changes` when the bodies differ but can't be compared field by field */
  note?: string;
}

function parse(body: string): { value: unknown } | undefined {
  try {
    return { value: JSON.parse(body) };
  } catch {
    // Not JSON, or cut off at the capture limit.
    return undefined;
  }
}

/** Requests with a recorded response body: the server's own spans when there are any, else the browser's. */
function responses(trace: TraceView): SpanRow[] {
  const all = trace.spans.filter((s) => s.kind === 'REQUEST' && s.attributes['http.response.body'] !== undefined);
  const server = all.filter((s) => s.source !== 'browser');
  return server.length > 0 ? server : all;
}

/**
 * Response bodies of the same requests in two runs, compared. Requests are paired by name, in
 * order of appearance; a request that only one run made is left to the span comparison.
 */
export function responseDiffs(before: TraceView, after: TraceView): ResponseDiff[] {
  const unmatched = responses(after);
  const diffs: ResponseDiff[] = [];
  for (const left of responses(before)) {
    const index = unmatched.findIndex((s) => s.name === left.name);
    if (index < 0) {
      continue;
    }
    const [right] = unmatched.splice(index, 1);
    const a = left.attributes['http.response.body']!;
    const b = right!.attributes['http.response.body']!;
    const parsedA = parse(a);
    const parsedB = parse(b);
    if (parsedA && parsedB) {
      diffs.push({ request: left.name, changes: jsonDiff(parsedA.value, parsedB.value) });
    } else {
      diffs.push({
        request: left.name,
        changes: [],
        note: a === b ? undefined : 'The bodies differ, but at least one is not complete JSON, so they can’t be compared field by field.',
      });
    }
  }
  return diffs;
}
