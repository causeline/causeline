// SPDX-License-Identifier: Apache-2.0
import { CauselineProfiler, CauselineProvider } from '@causeline/react';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { Checkout } from './Checkout';

const root = document.getElementById('root');
if (!root) {
  throw new Error('react-demo: #root element missing');
}

createRoot(root).render(
  <StrictMode>
    <CauselineProvider endpoint="/causeline/api/spans" ignore={['/api/demo/']}>
      <CauselineProfiler id="Checkout page">
        <Checkout />
      </CauselineProfiler>
    </CauselineProvider>
  </StrictMode>,
);
