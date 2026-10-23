// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import type { CapturedRequest } from './snippets';
import { rampLevels, toK6 } from './snippets';

const order: CapturedRequest = {
  method: 'POST',
  target: '/api/orders?draft=true',
  headers: [
    ['content-type', 'application/json'],
    ['authorization', 'Bearer SECRET-TOKEN'],
    ['cookie', 'SESSION=SECRET-SESSION'],
    ['x-api-key', 'SECRET-KEY'],
    ['user-agent', 'Mozilla/5.0'],
    ['x-tenant', 'acme'],
  ],
  body: `{"item":"O'Brien's book","quantity":1}`,
  status: 201,
};
const stock: CapturedRequest = { method: 'GET', target: '/api/stock', headers: [], status: 200 };

interface Call {
  method: string;
  url: string;
  body: unknown;
  headers: Record<string, string>;
}

/**
 * Loads the script as k6 would, with its two imports stubbed, and runs one iteration. Getting this
 * far means the module parses; the calls say what it would send.
 */
function run(script: string, env: Record<string, string> = {}) {
  const calls: Call[] = [];
  const sleeps: number[] = [];
  const checks: string[] = [];
  const http = {
    request: (method: string, url: string, body: unknown, params: { headers: Record<string, string> }) => {
      calls.push({ method, url, body, headers: params.headers });
      return { status: method === 'POST' ? 201 : 200 };
    },
  };
  const check = (res: unknown, rules: Record<string, (r: unknown) => boolean>) => {
    Object.entries(rules).forEach(([name, rule]) => rule(res) && checks.push(name));
  };
  const sleep = (seconds: number) => sleeps.push(seconds);
  const body = script
    .replace("import http from 'k6/http';", '')
    .replace("import { check, sleep } from 'k6';", '')
    .replace('export const options =', 'const options =')
    .replace('export default function ()', 'function iteration()');
  expect(body).not.toMatch(/^\s*(import|export)\b/m);
  const module = new Function('http', 'check', 'sleep', '__ENV', `${body}\nreturn { options, iteration };`) as (
    ...args: unknown[]
  ) => { options: { scenarios: { replay: Record<string, unknown> } }; iteration: () => void };
  const loaded = module(http, check, sleep, env);
  loaded.iteration();
  return { options: loaded.options.scenarios.replay, calls, sleeps, checks };
}

describe('toK6', () => {
  it('sends the flow in order with its pauses, and checks the original statuses', () => {
    const script = toK6(
      [
        { request: order, thinkMillis: 0 },
        { request: stock, thinkMillis: 1250 },
      ],
      { shape: 'CONCURRENCY', hits: 40, concurrency: 20 },
      'http://localhost:8080/',
    );

    const { options, calls, sleeps, checks } = run(script);

    expect(options).toEqual({ executor: 'shared-iterations', vus: 20, iterations: 40 });
    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual([
      'POST http://localhost:8080/api/orders?draft=true',
      'GET http://localhost:8080/api/stock',
    ]);
    expect(calls[0]?.body).toBe(`{"item":"O'Brien's book","quantity":1}`);
    expect(calls[1]?.body).toBeNull();
    expect(sleeps).toEqual([1.25]);
    expect(checks).toEqual(['POST /api/orders is 201', 'GET /api/stock is 200']);
  });

  it('leaves captured credentials and browser headers out, and reads Authorization from the environment', () => {
    const script = toK6([{ request: order, thinkMillis: 0 }], { shape: 'CONCURRENCY', hits: 1, concurrency: 1 }, 'http://localhost:8080');

    expect(script).not.toContain('SECRET');
    expect(script).not.toContain('Mozilla');
    expect(run(script).calls[0]?.headers).toEqual({ 'content-type': 'application/json', 'x-tenant': 'acme' });
    const withAuth = run(script, { CAUSELINE_AUTH: 'Bearer qa', BASE_URL: 'https://qa.example' });
    expect(withAuth.calls[0]?.headers.Authorization).toBe('Bearer qa');
    expect(withAuth.calls[0]?.url).toBe('https://qa.example/api/orders?draft=true');
  });

  it('runs a fixed rate as an arrival-rate scenario', () => {
    const script = toK6([{ request: stock, thinkMillis: 0 }], { shape: 'RATE', rate: 15, durationSeconds: 30, concurrency: 10 }, 'http://x');
    expect(run(script).options).toEqual({
      executor: 'constant-arrival-rate',
      rate: 15,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 10,
      maxVUs: 10,
    });
  });

  it('ramps through the same levels as the load replay', () => {
    expect(rampLevels(20)).toEqual([1, 2, 4, 8, 16, 20]);
    expect(rampLevels(1)).toEqual([1]);
    const script = toK6([{ request: stock, thinkMillis: 0 }], { shape: 'CONCURRENCY', hits: 60, concurrency: 8, ramp: true }, 'http://x');
    const { options } = run(script);
    expect(options.executor).toBe('ramping-vus');
    expect((options.stages as { target: number }[]).map((s) => s.target)).toEqual([1, 2, 4, 8]);
  });
});
