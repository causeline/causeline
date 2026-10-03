// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';
import type { SpanRow } from './api';
import { editorUrl } from './editor';
import { capturedRequest, toCurl, toMockMvcTest, toWebTestClientTest } from './snippets';

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

  it('builds a MockMvc test that leaves credentials out and expects the original status', () => {
    const test = toMockMvcTest(capturedRequest(order)!);
    expect(test).toContain('class PostApiOrdersReplaysTest');
    expect(test).toContain('mockMvc.perform(request(HttpMethod.POST, "/api/orders?draft=true")');
    expect(test).toContain('.content("{\\"item\\":\\"O\'Brien\'s book\\",\\"quantity\\":1}"))');
    expect(test).toContain('.andExpect(status().is(201));');
    expect(test).not.toContain('SECRET');
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
