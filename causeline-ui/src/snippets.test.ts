// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import type { SpanRow } from './api';
import { editorUrl } from './editor';
import { capturedRequest, toCurl, toMockMvcTest, toWebTestClientTest, traceGuard } from './snippets';

const span = (attributes: Record<string, string>, kind: SpanRow['kind'] = 'REQUEST'): SpanRow => ({
  spanId: 'a',
  parentSpanId: null,
  kind,
  name: 'POST /api/orders',
  source: 'checkout-demo',
  status: 'OK',
  depth: 0,
  offsetNanos: 0,
  durationNanos: 1,
  selfNanos: 1,
  clockSkew: false,
  attributes,
});

const order = span({
  'http.request.method': 'POST',
  'url.path': '/api/orders',
  'url.query': 'draft=true',
  'http.request.header.content-type': 'application/json',
  'http.request.header.authorization': 'Bearer SECRET',
  'http.request.header.host': 'localhost:8080',
  'http.request.header.traceparent': '00-x',
  'http.request.body': `{"item":"O'Brien's book","quantity":1}`,
  'http.response.status_code': '201',
});

describe('captured requests', () => {
  it('reads method, target, headers, body and status from a request span', () => {
    expect(capturedRequest(order)).toMatchObject({
      method: 'POST',
      target: '/api/orders?draft=true',
      headers: [
        ['content-type', 'application/json'],
        ['authorization', 'Bearer SECRET'],
      ],
      status: 201,
    });
    expect(capturedRequest(span({}, 'SERVICE'))).toBeUndefined();
    expect(capturedRequest(span({ 'http.request.method': 'POST', 'http.request.body': '[2048 bytes, image/png]' }))?.body)
      .toBeUndefined();
  });

  it('builds a cURL command, quoting for the shell', () => {
    const curl = toCurl(capturedRequest(order)!, 'http://localhost:8080');
    expect(curl).toContain("curl -X POST 'http://localhost:8080/api/orders?draft=true'");
    expect(curl).toContain("-H 'authorization: Bearer SECRET'");
    expect(curl).toContain(`--data-raw '{"item":"O'\\''Brien'\\''s book","quantity":1}'`);
    expect(curl).not.toContain('traceparent');
  });

  it('leaves out of a test the headers a browser adds by itself, and keeps the ones the request chose', () => {
    const fromBrowser = span({
      ...order.attributes,
      'http.request.header.user-agent': 'Mozilla/5.0',
      'http.request.header.sec-fetch-mode': 'cors',
      'http.request.header.referer': 'http://localhost:5173/',
      'http.request.header.origin': 'http://localhost:5173',
      'http.request.header.accept': '*/*',
      'http.request.header.x-tenant': 'acme',
    });

    for (const test of [toMockMvcTest(capturedRequest(fromBrowser)!), toWebTestClientTest(capturedRequest(fromBrowser)!)]) {
      expect(test).toContain('.header("x-tenant", "acme")');
      expect(test.match(/\.header\(/g)).toHaveLength(1);
    }
    expect(toCurl(capturedRequest(fromBrowser)!, 'http://localhost:8080')).toContain("-H 'user-agent: Mozilla/5.0'");
  });

  it('builds a MockMvc test that leaves credentials out and expects the original status', () => {
    const test = toMockMvcTest(capturedRequest(order)!);
    expect(test).toContain('class PostApiOrdersReplaysTest');
    expect(test).toContain('mockMvc.perform(request(HttpMethod.POST, "/api/orders?draft=true")');
    expect(test).toContain('.content("{\\"item\\":\\"O\'Brien\'s book\\",\\"quantity\\":1}"))');
    expect(test).toContain('.andExpect(status().is(201));');
    expect(test).not.toContain('SECRET');
  });

  it('adds what the trace did to the test: failures, queries, N+1 and the methods it went through', () => {
    const row = (spanId: string, parent: string, kind: SpanRow['kind'], name: string, depth: number, offset = 0, duration = 1) =>
      ({ ...span({}, kind), spanId, parentSpanId: parent, name, depth, offsetNanos: offset, durationNanos: duration });
    const request = { ...order, durationNanos: 100 };
    const spans = [
      request,
      row('c', 'a', 'CONTROLLER', 'OrderController.createOrder', 1, 0, 100),
      row('s', 'c', 'SERVICE', 'OrderService.createOrder', 2, 0, 90),
      row('r', 's', 'REPOSITORY', 'OrderRepository.save', 3, 0, 10),
      row('q1', 'r', 'DATABASE', 'INSERT orders', 4),
      row('r2', 's', 'REPOSITORY', 'OrderRepository.save', 3, 20, 10),
      row('q2', 'r2', 'DATABASE', 'UPDATE orders', 4, 20),
      // @Async: still running after the service returned, so a test must not wait for it.
      row('m', 's', 'SERVICE', 'ConfirmationMailer.sendConfirmation', 3, 80, 200),
      row('other', 'x', 'REQUEST', 'GET /api/prices', 0),
      row('q3', 'other', 'DATABASE', 'SELECT prices', 1),
    ];

    const guard = traceGuard(request, { spans, insights: [] });
    expect(guard).toEqual({
      request: 'POST /api/orders',
      exceptions: [],
      failed: false,
      queries: 2,
      repeatedQueries: false,
      spans: ['OrderController.createOrder', 'OrderService.createOrder', 'OrderRepository.save'],
    });

    const test = toMockMvcTest(capturedRequest(request)!, guard);
    expect(test).toContain('import dev.causeline.test.RecordedTraces;');
    expect(test).toContain('@AutoConfigureMockMvc\n@CauselineTest\nclass PostApiOrdersReplaysTest');
    expect(test).toContain('void postApiOrdersReplays(RecordedTraces causeline) throws Exception {');
    expect(test).toContain(
      [
        '        causeline.trace("POST /api/orders")',
        '                .hasNoFailedSpans()',
        '                .hasQueryCountAtMost(2)',
        '                .hasNoRepeatedQueries()',
        '                .hasSpans("OrderController.createOrder", "OrderService.createOrder", "OrderRepository.save");',
      ].join('\n'),
    );
    expect(toWebTestClientTest(capturedRequest(request)!, guard)).toContain('void postApiOrdersReplays(RecordedTraces causeline) {');
  });

  it('expects the failures a captured trace had, instead of demanding there are none', () => {
    const request = { ...order, status: 'ERROR' as const };
    const exception = { ...span({}, 'EXCEPTION'), spanId: 'e', parentSpanId: 'a', name: 'PaymentTimeoutException', depth: 1 };
    const query = { ...span({}, 'DATABASE'), spanId: 'q', parentSpanId: 'a', name: 'SELECT order_lines', depth: 1 };
    const insights = [{ rule: 'REPEATED_QUERY' as const, spanId: 'q', label: 'N+1 query', sharePercent: 0 }];

    const test = toMockMvcTest(capturedRequest(request)!, traceGuard(request, { spans: [request, exception, query], insights }));

    expect(test).toContain('.hasException("PaymentTimeoutException")');
    expect(test).not.toContain('hasNoFailedSpans');
    expect(test).not.toContain('hasNoRepeatedQueries');
  });

  it('builds a WebTestClient test for WebFlux applications', () => {
    const test = toWebTestClientTest(capturedRequest(order)!);
    expect(test).toContain('client.method(HttpMethod.POST).uri("/api/orders?draft=true")');
    expect(test).toContain('.expectStatus().isEqualTo(201);');
    expect(test).not.toContain('SECRET');
  });
});

describe('editor links', () => {
  it('opens files in VS Code, Cursor and IntelliJ', () => {
    expect(editorUrl('vscode', 'D:\\shop\\src\\main\\java\\OrderService.java', 42)).toBe(
      'vscode://file/D:/shop/src/main/java/OrderService.java:42',
    );
    expect(editorUrl('cursor', '/home/me/shop/OrderService.java', 7)).toBe('cursor://file/home/me/shop/OrderService.java:7');
    expect(editorUrl('idea', '/home/me/shop/OrderService.java', 7)).toBe(
      'http://localhost:63342/api/file//home/me/shop/OrderService.java:7',
    );
  });
});
