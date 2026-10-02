// SPDX-License-Identifier: Apache-2.0
import type { ReactNode } from 'react';

export const metadata = { title: 'Causeline Next.js demo' };

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body style={{ fontFamily: 'system-ui, sans-serif', maxWidth: 560, margin: '48px auto', padding: '0 16px' }}>
        {children}
      </body>
    </html>
  );
}
