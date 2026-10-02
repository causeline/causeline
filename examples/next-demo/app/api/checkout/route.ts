// SPDX-License-Identifier: Apache-2.0

const spring = process.env.SPRING_URL ?? 'http://localhost:8080';

/** A backend-for-frontend route: it forwards the order to the Spring Boot checkout API. */
export async function POST(request: Request) {
  const order = (await request.json()) as { item: string; quantity: number };
  const response = await fetch(`${spring}/api/orders`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(order),
    cache: 'no-store',
  });
  return new Response(await response.text(), {
    status: response.status,
    headers: { 'Content-Type': 'application/json' },
  });
}
