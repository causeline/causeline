// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import type { SpanRow, TraceView } from './api';
import {
  MARKER,
  MARKER_URL,
  applyProbe,
  defaultParams,
  originalOf,
  paramsError,
  probeVerdict,
  sameBody,
  summarizeReplay,
} from './probes';

const request = {
  draft: {
    path: '/api/customers/1001',
    query: 'view=full',
    headers: 'authorization: Bearer user-token\nx-tenant-id: acme\ncontent-type: application/json',
    route: '/api/customers/{id}',
  },
  body: '{"name":"Ada","age":36}',
};

const ok = (result: ReturnType<typeof applyProbe>) => {
  expect(result).not.toHaveProperty('error');
  return result as typeof request;
};

describe('applyProbe: another ID', () => {
  it('swaps the first path variable, encoding the new value', () => {
    const next = ok(applyProbe('idor', { value: '1002' }, request));

    expect(next.draft.path).toBe('/api/customers/1002');
    expect(next.draft.query).toBe('view=full');
    expect(ok(applyProbe('idor', { value: 'a b/c' }, request)).draft.path).toBe('/api/customers/a%20b%2Fc');
  });

  it('swaps a named query parameter or body field instead', () => {
    expect(ok(applyProbe('idor', { value: '7', name: 'view' }, request)).draft.query).toBe('view=7');
    expect(ok(applyProbe('idor', { value: '40', name: 'age' }, request)).body).toBe('{"name":"Ada","age":40}');
    expect(ok(applyProbe('idor', { value: 'Grace', name: 'name' }, request)).body).toBe('{"name":"Grace","age":36}');
  });

  it('says where it looked when it finds nothing to swap', () => {
    expect(applyProbe('idor', { value: '1', name: 'owner' }, request)).toEqual({
      error: '"owner" is not a path variable, query parameter or body field of this request.',
    });
    expect(applyProbe('idor', { value: '1' }, { ...request, draft: { ...request.draft, route: '/api/customers' } })).toEqual({
      error: 'This request has no path variable. Type the query parameter or body field that holds the ID.',
    });
    expect(applyProbe('idor', { value: '1' }, { ...request, draft: { ...request.draft, path: undefined } })).toEqual({
      error: "The raw path wasn't captured (causeline.capture.path-values), so it can't be edited.",
    });
  });
});

describe('applyProbe: extra field', () => {
  it('adds the field to the JSON body, whatever its name', () => {
    expect(ok(applyProbe('mass-assignment', { field: 'role', value: 'admin' }, request)).body).toBe(
      '{"name":"Ada","age":36,"role":"admin"}',
    );
    expect(ok(applyProbe('mass-assignment', { field: 'isAdmin', value: MARKER }, request)).body).toContain(`"isAdmin":"${MARKER}"`);
  });

  it('needs a JSON object body', () => {
    expect(applyProbe('mass-assignment', { field: 'role', value: 'x' }, { ...request, body: 'name=Ada' })).toEqual({
      error: 'Mass assignment needs a JSON object body to add the field to.',
    });
    expect(applyProbe('mass-assignment', { field: 'role', value: 'x' }, { ...request, body: '[1]' })).toHaveProperty('error');
  });
});

describe('applyProbe: redirect and header', () => {
  it('points the redirect parameter at the test address, replacing or adding it', () => {
    const added = ok(applyProbe('open-redirect', { param: 'next', value: MARKER_URL }, request));
    expect(added.draft.query).toBe(`view=full&next=${encodeURIComponent(MARKER_URL)}`);

    const replaced = ok(
      applyProbe('open-redirect', { param: 'next', value: MARKER_URL }, { ...request, draft: { ...request.draft, query: 'next=%2Fhome&x=1' } }),
    );
    expect(replaced.draft.query).toBe(`next=${encodeURIComponent(MARKER_URL)}&x=1`);
  });

  it('leaves out a header whatever its case, and says when there is none', () => {
    expect(ok(applyProbe('auth-boundary', { header: 'X-Tenant-ID' }, request)).draft.headers).toBe(
      'authorization: Bearer user-token\ncontent-type: application/json',
    );
    expect(applyProbe('auth-boundary', { header: 'X-Role' }, request)).toEqual({
      error: 'This request has no "X-Role" header to leave out.',
    });
  });

  it('asks for the fields it needs', () => {
    expect(paramsError('idor', { value: ' ' })).toBe('Other ID is required.');
    expect(paramsError('mass-assignment', { field: 'role', value: 'x' })).toBeUndefined();
    expect(applyProbe('auth-boundary', { header: '' }, request)).toEqual({ error: 'Header is required.' });
    expect(defaultParams('mass-assignment')).toEqual({ field: '', value: MARKER });
    expect(defaultParams('open-redirect').value).toBe(MARKER_URL);
  });
});

describe('probeVerdict', () => {
  const noReplay = { sql: [] };
  const original = { status: 200, body: '{"id":1001,"name":"Ada"}' };

  it('IDOR: a different record for another ID is the finding', () => {
    const other = { sql: [], body: '{"id":1002,"name":"Grace"}' };

    expect(probeVerdict('idor', { value: '1002' }, 200, original, other).tone).toBe('bad');
    expect(probeVerdict('idor', { value: '1002' }, 200, original, { sql: [], body: original.body }).tone).toBe('info');
    expect(probeVerdict('idor', { value: '1002' }, 403, original, noReplay).tone).toBe('good');
    expect(probeVerdict('idor', { value: '1002' }, 404, original, noReplay).tone).toBe('info');
    expect(probeVerdict('idor', { value: '1002' }, 200, {}, noReplay).tone).toBe('info');
  });

  it('mass assignment: the value reaching a SQL statement is the finding, and names the statement', () => {
    const reached = { sql: [{ name: 'UPDATE customers', text: `(${MARKER}, 1001) update customers set role = '${MARKER}'` }] };
    const verdict = probeVerdict('mass-assignment', { field: 'role', value: MARKER }, 200, original, reached);

    expect(verdict.tone).toBe('bad');
    expect(verdict.text).toContain('"role"');
    expect(verdict.text).toContain('UPDATE customers');
    expect(verdict.text).not.toContain(MARKER);
    expect(probeVerdict('mass-assignment', { field: 'role', value: MARKER }, 400, original, noReplay).tone).toBe('good');
    expect(
      probeVerdict('mass-assignment', { field: 'role', value: MARKER }, 200, original, { sql: [{ name: 'SELECT customers', text: '(1001)' }] })
        .tone,
    ).toBe('good');
    expect(probeVerdict('mass-assignment', { field: 'role', value: MARKER }, 200, original, noReplay).tone).toBe('info');
  });

  it('open redirect: a Location on the supplied host is the finding', () => {
    const toMarker = { sql: [], location: MARKER_URL };

    expect(probeVerdict('open-redirect', { param: 'next', value: MARKER_URL }, 302, original, toMarker).tone).toBe('bad');
    expect(probeVerdict('open-redirect', { param: 'next', value: MARKER_URL }, 302, original, { sql: [], location: '/home' }).tone).toBe(
      'good',
    );
    expect(probeVerdict('open-redirect', { param: 'next', value: MARKER_URL }, 200, original, noReplay).tone).toBe('good');
  });

  it('auth boundary: the same answer without the header is the finding', () => {
    const same = { sql: [], body: original.body };

    expect(probeVerdict('auth-boundary', { header: 'X-Tenant-Id' }, 200, original, same).tone).toBe('bad');
    expect(probeVerdict('auth-boundary', { header: 'X-Tenant-Id' }, 200, original, { sql: [], body: '{"rows":[]}' }).tone).toBe('info');
    expect(probeVerdict('auth-boundary', { header: 'X-Tenant-Id' }, 401, original, noReplay).tone).toBe('good');
  });
});

describe('reading the traces', () => {
  const row = (over: Partial<SpanRow>): SpanRow => ({
    spanId: 'a'.repeat(16),
    parentSpanId: null,
    kind: 'REQUEST',
    name: 'POST /api/x',
    source: 'checkout',
    status: 'OK',
    depth: 0,
    offsetNanos: 0,
    durationNanos: 1,
    selfNanos: 1,
    clockSkew: false,
    attributes: {},
    ...over,
  });

  it('takes the response and the SQL from the replay trace, ignoring the browser', () => {
    const view = {
      spans: [
        row({ source: 'browser', attributes: { 'http.response.body': 'browser' } }),
        row({ attributes: { 'http.response.body': '{"ok":true}', 'http.response.header.location': '/home' } }),
        row({ kind: 'DATABASE', name: 'UPDATE t', attributes: { 'db.query.parameters': '(x)', 'db.query.text': "update t set a = 'x'" } }),
      ],
    } as unknown as TraceView;

    expect(summarizeReplay(view)).toEqual({
      body: '{"ok":true}',
      location: '/home',
      sql: [{ name: 'UPDATE t', text: "(x) update t set a = 'x'" }],
    });
    expect(originalOf(row({ attributes: { 'http.response.status_code': '201', 'http.response.body': '{}' } }))).toEqual({
      status: 201,
      body: '{}',
    });
    expect(originalOf(undefined)).toEqual({ status: undefined, body: undefined });
  });

  it('compares bodies as JSON where it can', () => {
    expect(sameBody('{"a": 1, "b": 2}', '{"a":1,"b":2}')).toBe(true);
    expect(sameBody('{"a":1}', '{"a":2}')).toBe(false);
    expect(sameBody(' text ', 'text')).toBe(true);
  });
});
