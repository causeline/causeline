// SPDX-License-Identifier: Apache-2.0
import type { SpanRow, TraceView } from './api';
import type { Editable } from './SecurityGuide';

/**
 * Active probes (PRD section 7): a probe changes one thing in a captured request, the developer
 * replays it, and the replay's trace says what the change revealed. Each probe is one request, sent
 * by hand; nothing here ever runs by itself or in a batch.
 */
export type ProbeId = 'idor' | 'mass-assignment' | 'open-redirect' | 'auth-boundary';

export interface ProbeField {
  key: string;
  label: string;
  placeholder: string;
  required: boolean;
  defaultValue?: string;
}

export interface ProbeDefinition {
  id: ProbeId;
  label: string;
  owasp: string;
  /** One line shown in the replay form. */
  summary: string;
  fields: ProbeField[];
  /** The probe edits the JSON body, so it needs one. */
  needsBody?: boolean;
}

/** Marker values are obvious in logs and databases, and use a reserved `.invalid` host for redirects. */
export const MARKER = 'causeline-probe';
export const MARKER_URL = 'https://causeline-probe.invalid/landing';

export const PROBES: Record<ProbeId, ProbeDefinition> = {
  idor: {
    id: 'idor',
    label: 'Another ID (IDOR)',
    owasp: 'A01',
    summary: 'Asks for a different ID. If it answers with another record, nothing checks who owns it.',
    fields: [
      { key: 'value', label: 'Other ID', placeholder: 'an ID you know belongs to someone else', required: true },
      {
        key: 'name',
        label: 'Where',
        placeholder: 'path variable, query parameter or body field (blank: the first path variable)',
        required: false,
      },
    ],
  },
  'mass-assignment': {
    id: 'mass-assignment',
    label: 'Extra field (mass assignment)',
    owasp: 'A01',
    summary: 'Adds a field you name to the JSON body. If it reaches the database, the client controls a field it should not.',
    fields: [
      { key: 'field', label: 'Field', placeholder: 'role, isAdmin, price…', required: true },
      { key: 'value', label: 'Value', placeholder: MARKER, required: true, defaultValue: MARKER },
    ],
    needsBody: true,
  },
  'open-redirect': {
    id: 'open-redirect',
    label: 'Redirect elsewhere (open redirect)',
    owasp: 'A01',
    summary: 'Points a redirect parameter at another site. If the response redirects there, the app can send users away.',
    fields: [
      { key: 'param', label: 'Parameter', placeholder: 'next, returnUrl, redirect…', required: true },
      { key: 'value', label: 'Send to', placeholder: MARKER_URL, required: true, defaultValue: MARKER_URL },
    ],
  },
  'auth-boundary': {
    id: 'auth-boundary',
    label: 'Without a header (auth boundary)',
    owasp: 'A01',
    summary: 'Leaves out a tenant, role or scope header. If the answer is the same, the server ignores it.',
    fields: [{ key: 'header', label: 'Header', placeholder: 'X-Tenant-Id, X-Role…', required: true }],
  },
};

export const PROBE_IDS = Object.keys(PROBES) as ProbeId[];
export const isProbe = (id: string): id is ProbeId => id in PROBES;

export type ProbeParams = Record<string, string>;

export function defaultParams(id: ProbeId): ProbeParams {
  return Object.fromEntries(PROBES[id].fields.map((f) => [f.key, f.defaultValue ?? '']));
}

const clean = (value: string | undefined) => (value ?? '').trim();
const MAX_VALUE = 300;

function safeDecode(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

/** Why a probe can't be applied yet, or undefined when its fields are filled in. */
export function paramsError(id: ProbeId, params: ProbeParams): string | undefined {
  for (const field of PROBES[id].fields) {
    const value = clean(params[field.key]);
    if (field.required && value === '') {
      return `${field.label} is required.`;
    }
    if (value.length > MAX_VALUE) {
      return `${field.label} is too long.`;
    }
  }
  return undefined;
}

type Applied = Editable | { error: string };

/** Changes the request as the probe describes. The developer still reviews it and sends it. */
export function applyProbe(id: ProbeId, rawParams: ProbeParams, current: Editable): Applied {
  const problem = paramsError(id, rawParams);
  if (problem) {
    return { error: problem };
  }
  const params = Object.fromEntries(Object.entries(rawParams).map(([k, v]) => [k, clean(v)]));
  switch (id) {
    case 'idor':
      return swapIdentifier(current, params.name ?? '', params.value ?? '');
    case 'mass-assignment':
      return addBodyField(current, params.field ?? '', params.value ?? '');
    case 'open-redirect':
      return {
        draft: { ...current.draft, query: setQueryParam(current.draft.query, params.param ?? '', params.value ?? '', false) ?? '' },
        body: current.body,
      };
    case 'auth-boundary':
      return dropHeader(current, params.header ?? '');
  }
}

/** Sets a query parameter (encoding the new value). With `onlyIfExists`, returns undefined when it isn't there. */
function setQueryParam(query: string, name: string, value: string, onlyIfExists: boolean): string | undefined {
  const pairs = query.split('&').filter((pair) => pair !== '');
  const index = pairs.findIndex((pair) => safeDecode(pair.split('=')[0] ?? '') === name);
  const replacement = `${encodeURIComponent(name)}=${encodeURIComponent(value)}`;
  if (index >= 0) {
    pairs[index] = replacement;
  } else if (onlyIfExists) {
    return undefined;
  } else {
    pairs.push(replacement);
  }
  return pairs.join('&');
}

function parseObject(body: string): Record<string, unknown> | undefined {
  try {
    const parsed: unknown = JSON.parse(body);
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : undefined;
  } catch {
    return undefined;
  }
}

function swapIdentifier(current: Editable, name: string, value: string): Applied {
  const { draft, body } = current;
  const routeSegments = (draft.route ?? '').split('/');
  const variables = routeSegments.filter((s) => /^\{[^}]+\}$/.test(s)).map((s) => s.slice(1, -1));
  const target = name !== '' ? name : variables[0];
  if (!target) {
    return { error: 'This request has no path variable. Type the query parameter or body field that holds the ID.' };
  }
  if (variables.includes(target)) {
    if (draft.path === undefined) {
      return { error: "The raw path wasn't captured (causeline.capture.path-values), so it can't be edited." };
    }
    const actual = draft.path.split('/');
    if (actual.length !== routeSegments.length) {
      return { error: "The path doesn't match the route template, so the ID can't be located." };
    }
    actual[routeSegments.indexOf(`{${target}}`)] = encodeURIComponent(value);
    return { draft: { ...draft, path: actual.join('/') }, body };
  }
  const query = setQueryParam(draft.query, target, value, true);
  if (query !== undefined) {
    return { draft: { ...draft, query }, body };
  }
  const object = parseObject(body);
  if (object && Object.hasOwn(object, target)) {
    // Keep a number a number, so the server doesn't reject the type before checking the owner.
    const numeric = typeof object[target] === 'number' && /^-?\d+(\.\d+)?$/.test(value);
    return { draft, body: JSON.stringify({ ...object, [target]: numeric ? Number(value) : value }) };
  }
  return { error: `"${target}" is not a path variable, query parameter or body field of this request.` };
}

function addBodyField(current: Editable, field: string, value: string): Applied {
  const object = parseObject(current.body);
  if (!object) {
    return { error: 'Mass assignment needs a JSON object body to add the field to.' };
  }
  return { draft: current.draft, body: JSON.stringify({ ...object, [field]: value }) };
}

function dropHeader(current: Editable, header: string): Applied {
  const lines = current.draft.headers.split('\n');
  const kept = lines.filter((line) => line.slice(0, Math.max(0, line.indexOf(':'))).trim().toLowerCase() !== header.toLowerCase());
  if (kept.length === lines.length) {
    return { error: `This request has no "${header}" header to leave out.` };
  }
  return { draft: { ...current.draft, headers: kept.join('\n') }, body: current.body };
}

/** What the original request answered, from its server span. */
export interface OriginalResponse {
  status?: number;
  body?: string;
}

export function originalOf(span: SpanRow | undefined): OriginalResponse {
  const status = Number(span?.attributes['http.response.status_code']);
  return { status: Number.isFinite(status) && status > 0 ? status : undefined, body: span?.attributes['http.response.body'] };
}

/** What the replay's trace shows: its response and the SQL it ran. */
export interface ReplaySummary {
  body?: string;
  location?: string;
  sql: { name: string; text: string }[];
}

export function summarizeReplay(view: TraceView): ReplaySummary {
  const server = view.spans.find((s) => s.kind === 'REQUEST' && s.source !== 'browser');
  return {
    body: server?.attributes['http.response.body'],
    location: server?.attributes['http.response.header.location'],
    sql: view.spans
      .filter((s) => s.kind === 'DATABASE')
      // The bound values are in db.query.parameters; db.query.text has them filled in.
      .map((s) => ({ name: s.name, text: `${s.attributes['db.query.parameters'] ?? ''} ${s.attributes['db.query.text'] ?? ''}` })),
  };
}

export interface ProbeVerdict {
  tone: 'bad' | 'good' | 'info';
  text: string;
}

const is2xx = (status: number) => status >= 200 && status < 300;
const isDenied = (status: number) => status === 401 || status === 403;

/** Two bodies are the same when they parse to the same JSON, or are the same text. */
export function sameBody(a: string, b: string): boolean {
  const normalize = (text: string) => {
    try {
      return JSON.stringify(JSON.parse(text));
    } catch {
      return text.trim();
    }
  };
  return normalize(a) === normalize(b);
}

/** What a probe's replay means, in plain words. The evidence is the replay's status, response and SQL. */
export function probeVerdict(
  id: ProbeId,
  rawParams: ProbeParams,
  status: number,
  original: OriginalResponse,
  replay: ReplaySummary,
): ProbeVerdict {
  const params = Object.fromEntries(Object.entries(rawParams).map(([k, v]) => [k, clean(v)]));
  switch (id) {
    case 'idor': {
      if (isDenied(status)) {
        return { tone: 'good', text: `Rejected with ${status} for another ID: the endpoint checks access to each object.` };
      }
      if (status === 404) {
        return { tone: 'info', text: 'Answered 404 for that ID. Use an ID that exists and belongs to someone else, to be sure.' };
      }
      if (is2xx(status) && original.body !== undefined && replay.body !== undefined) {
        return sameBody(original.body, replay.body)
          ? { tone: 'info', text: `Answered ${status} with the same body: the ID may be ignored, or both belong to one owner. Try an ID you know is someone else's.` }
          : { tone: 'bad', text: `Answered ${status} with a different record for another ID. If this caller shouldn't see it, nothing checks who owns each object (broken access control, A01).` };
      }
      return { tone: 'info', text: `Answered ${status}. The response bodies weren't captured, so compare the two traces to see whether the record differs.` };
    }
    case 'mass-assignment': {
      const value = params.value ?? '';
      const reached = value.length >= 3 ? replay.sql.find((q) => q.text.includes(value)) : undefined;
      if (reached) {
        return { tone: 'bad', text: `"${params.field}" from your request reached the database in ${reached.name}. The server accepted a field the client shouldn't control (A01). Bind requests to a DTO that lists only the allowed fields.` };
      }
      if (status >= 400 && status < 500) {
        return { tone: 'good', text: `Rejected with ${status}: the server refused the extra field.` };
      }
      if (replay.sql.length === 0) {
        return { tone: 'info', text: `Answered ${status}, but no SQL ran in the replay, so Causeline couldn't see where the field went.` };
      }
      return is2xx(status)
        ? { tone: 'good', text: `Answered ${status}, and "${params.field}" did not reach any SQL: the field was ignored (needs causeline.capture.sql=full to be sure).` }
        : { tone: 'info', text: `The server answered ${status}. The extra field may have broken something; try a value of the right type.` };
    }
    case 'open-redirect': {
      const needle = hostOf(params.value ?? '') ?? params.value ?? '';
      if (status >= 300 && status < 400 && replay.location !== undefined) {
        return needle !== '' && replay.location.includes(needle)
          ? { tone: 'bad', text: `Redirected (${status}) to the address you supplied. The app can send users to any site (A01). Allow only relative paths, or check the target against an allowlist of hosts.` }
          : { tone: 'good', text: `Redirected (${status}), but not to the address you supplied.` };
      }
      return { tone: 'good', text: `Answered ${status} without redirecting to the address you supplied.` };
    }
    case 'auth-boundary': {
      const header = params.header ?? 'that header';
      if (isDenied(status)) {
        return { tone: 'good', text: `Rejected with ${status} without ${header}: the header is enforced.` };
      }
      if (is2xx(status) && original.body !== undefined && replay.body !== undefined) {
        return sameBody(original.body, replay.body)
          ? { tone: 'bad', text: `Answered ${status} with the same response without ${header}: the server doesn't depend on it (A01). Derive tenant and role from the authenticated identity instead.` }
          : { tone: 'info', text: `Answered ${status}, but differently: ${header} changes what is returned. Check that a caller can't simply leave it out to see more.` };
      }
      return { tone: 'info', text: `Answered ${status}. Compare the two traces to see whether leaving out ${header} changed anything.` };
    }
  }
}

function hostOf(value: string): string | undefined {
  try {
    return new URL(value).host;
  } catch {
    return undefined;
  }
}
