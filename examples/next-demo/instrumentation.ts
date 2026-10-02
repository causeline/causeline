// SPDX-License-Identifier: Apache-2.0

// Next.js calls register() once when the server starts. Causeline then receives the server's
// spans (requests, route handlers, rendering, fetch calls) and adds traceparent to calls to Spring.
export async function register() {
  if (process.env.NEXT_RUNTIME === 'nodejs') {
    const { registerCauseline } = await import('@causeline/next');
    registerCauseline({
      serviceName: 'storefront',
      endpoint: `${process.env.SPRING_URL ?? 'http://localhost:8080'}/causeline`,
      // token: defaults to process.env.CAUSELINE_TOKEN (the Spring app's causeline.access-token)
    });
  }
}
